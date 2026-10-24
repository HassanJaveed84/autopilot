package com.lifeadmin.autopilot.ai.aifirst

import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.ModelCall
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchCallProvider
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.CostCalculator
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.proposal.FindingRef
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemSrc
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.Premise
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two small no-search calls around research, both under general AI consent, both on Haiku and
 * both ledgered whatever happens: the clarification check before the authorization card, and the
 * task-change suggestions the person asks for after an answer. Neither has the web_search tool.
 */
internal class SmallCall(private val provider: ResearchCallProvider, private val ledger: UsageLedger, private val clock: AppClock, private val ids: IdGenerator) {
    data class Reply(val text: String?, val costMicros: Long, val floor: Boolean, val failure: String?)

    suspend fun send(feature: UsageFeature, stage: String, system: String, user: String, maxTokens: Long, apiKey: String, runId: String, sessionId: String?, taskId: String?): Reply {
        val record = UsageRecord(ids.newId(), feature, MODEL, clock.now(), runId, sessionId, taskId, stage)
        ledger.record(record)
        val call = ModelCall(MODEL, system, user, maxTokens, maxSearches = 0, timeout = Research2Config.MIN_CALL_TIMEOUT)
        val result = try {
            provider.call(call, apiKey)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { ledger.record(UsageRecord.priced(record, null, CallOutcome.STOPPED, null)) }
            throw e
        } catch (e: Exception) {
            ledger.record(UsageRecord.priced(record, null, CallOutcome.FAILED_UNKNOWN, null))
            return Reply(null, 0, true, "the check failed unexpectedly")
        }
        return when (result) {
            is CallResult.Failed -> {
                val notBilled = result.error.let { it == ResearchError.Unauthorized || it == ResearchError.Forbidden || it == ResearchError.OutOfCredit || it is ResearchError.BadRequest || it is ResearchError.RateLimited || it == ResearchError.Offline }
                val priced = UsageRecord.priced(record, result.usage.takeUnless { notBilled }, if (notBilled) CallOutcome.FAILED_NOT_BILLED else CallOutcome.FAILED_UNKNOWN, result.elapsedMs)
                ledger.record(priced)
                Reply(null, priced.estimatedMicros ?: 0, !notBilled, result.error.headline.lowercase())
            }
            is CallResult.Done -> {
                val priced = UsageRecord.priced(record, result.usage, if (result.stop == CallStop.MAX_TOKENS) CallOutcome.TRUNCATED else CallOutcome.COMPLETE, result.elapsedMs, result.providerMessageId)
                ledger.record(priced)
                val text = result.segments.joinToString("") { it.text }.trim()
                if (result.stop == CallStop.REFUSAL) Reply(null, priced.estimatedMicros ?: 0, priced.isFloor, "Claude declined") else Reply(text, priced.estimatedMicros ?: 0, priced.isFloor, null)
            }
        }
    }

    companion object {
        const val MODEL = "claude-haiku-5-5"

        /** Upper estimate of one small call (system + user + output budget), for the disclosure line. */
        fun estimateHighMicros(system: String, user: String, maxTokens: Long): Long =
            CostCalculator.estimateRun(MODEL, 0, maxTokens, baseInputTokens = (system.length + user.length) / 3L + 200)?.highMicros ?: 0L
    }
}

/**
 * Asks Claude (Haiku, no search) whether a genuinely material ambiguity prevents answering the
 * question, before the authorization card. Only the question and this thread's earlier questions are
 * sent. Fails open: when the check cannot run, the card says so and the person decides.
 */
class AfClarifier(provider: ResearchCallProvider, ledger: UsageLedger, clock: AppClock, ids: IdGenerator) {
    private val call = SmallCall(provider, ledger, clock, ids)

    suspend fun check(question: String, earlierQuestions: List<String>, apiKey: String, threadId: String, turnId: String, taskId: String?): AfClarification {
        val r = call.send(UsageFeature.OBJECTIVE_PREP, "CLARIFY", SYSTEM, user(question, earlierQuestions), MAX_TOKENS, apiKey, turnId, threadId, taskId)
        if (r.text == null) return AfClarification(checked = false, note = "Couldn't check the question first (${r.failure}). Nothing was searched.", model = SmallCall.MODEL, costMicros = r.costMicros, costIsFloor = r.floor)
        val (q, options) = parse(r.text)
        return AfClarification(checked = true, question = q, options = options, model = SmallCall.MODEL, costMicros = r.costMicros, costIsFloor = r.floor)
    }

    companion object {
        const val MAX_TOKENS = 300L

        val SYSTEM: String = """
            You check a question before a paid web search runs on it. Decide only whether a genuinely material ambiguity prevents answering it: the answer would be substantially different depending on which reading is meant, and nothing in the question or the earlier questions tells which (for example a place or station name that exists in several cities with no city given, or "the latest version" of an unnamed product).
            Do not ask about details a researcher can reasonably cover in the answer (budget, preferences, scope, units), do not ask for confirmation, and do not answer the question. Most questions are clear.
            Reply with exactly one of these forms and nothing else:
            CLEAR
            or
            ASK: <one short question to the person>
            OPTION: <a likely answer>
            (zero to four OPTION lines)
        """.trimIndent()

        fun user(question: String, earlier: List<String>): String = buildString {
            if (earlier.isNotEmpty()) {
                appendLine("Earlier questions in this conversation:")
                earlier.forEach { appendLine("- $it") }
                appendLine()
            }
            append("Question: ").append(question.trim())
        }

        /** Anything but a well-formed ASK reads as clear: the check is a convenience, never a gate. */
        fun parse(text: String): Pair<String?, List<String>> {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val ask = lines.firstOrNull { it.startsWith("ASK:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() } ?: return null to emptyList()
            val options = lines.filter { it.startsWith("OPTION:", ignoreCase = true) }.map { it.substringAfter(':').trim() }.filter { it.isNotEmpty() }.distinct().take(4)
            return ask.take(200) to options.map { it.take(80) }
        }
    }
}

sealed interface SuggestResult {
    data class Ready(val set: ProposalSet) : SuggestResult
    data class Failed(val message: String) : SuggestResult
}

/**
 * Task-change suggestions from one finished answer, asked for explicitly by the person. One Haiku
 * call without search turns the answer into at most four added steps or notes; they become a
 * canonical [ProposalSet] that changes nothing until the person applies items in the Proposal Review.
 */
class AfTaskSuggester(provider: ResearchCallProvider, ledger: UsageLedger, private val clock: AppClock, ids: IdGenerator) {
    private val call = SmallCall(provider, ledger, clock, ids)

    suspend fun suggest(threadId: String, turn: AfTurn, task: TargetSnapshot, taskId: String, apiKey: String): SuggestResult {
        val r = call.send(UsageFeature.ASSIST, "TASK_SUGGEST", SYSTEM, user(turn, task), MAX_TOKENS, apiKey, turn.id, threadId, taskId)
        val text = r.text ?: return SuggestResult.Failed("Couldn't get suggestions (${r.failure}). Your task is unchanged.")
        return SuggestResult.Ready(build(threadId, turn, task, taskId, parse(text), clock.now().toEpochMilli()))
    }

    companion object {
        const val MAX_TOKENS = 600L
        const val MAX_ITEMS = 4

        fun setId(threadId: String, turnId: String) = "afs-$threadId-$turnId"

        val SYSTEM: String = """
            You suggest changes to a person's task from research they ran. Suggest at most four concrete additions the research answer supports: next steps worth adding (STEP) or a short fact worth keeping on the task (NOTE). Do not repeat existing steps, do not invent facts the answer does not contain, and keep each line under 200 characters. If nothing is worth adding, reply NONE.
            Reply only with lines in these forms:
            STEP: <text>
            NOTE: <text>
            or the single word NONE.
        """.trimIndent()

        /** Exactly what the suggestion call sends; shown before the person confirms it. */
        fun user(turn: AfTurn, task: TargetSnapshot): String = buildString {
            appendLine("Task: ${task.title}")
            val open = task.steps.filter { !it.isDone }
            if (open.isNotEmpty()) appendLine("Current steps: " + open.joinToString("; ") { it.title })
            appendLine()
            appendLine("Research question: ${turn.authorization?.question ?: turn.question}")
            appendLine("Research answer:")
            append(turn.answer)
        }

        fun parse(text: String): List<Pair<ItemKind, String>> = text.lines().map { it.trim().removePrefix("-").trim() }.mapNotNull { l ->
            when {
                l.startsWith("STEP:", ignoreCase = true) -> ItemKind.ADD_STEP to l.substringAfter(':').trim()
                l.startsWith("NOTE:", ignoreCase = true) -> ItemKind.APPEND_NOTE to l.substringAfter(':').trim()
                else -> null
            }
        }.filter { it.second.isNotEmpty() }.distinctBy { it.second.lowercase() }.take(MAX_ITEMS)

        fun build(threadId: String, turn: AfTurn, task: TargetSnapshot, taskId: String, items: List<Pair<ItemKind, String>>, nowMs: Long): ProposalSet {
            val asOf = Instant.ofEpochMilli(turn.finishedAtMs ?: nowMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val status = "From a research answer; Claude's wording, not independently verified"
            val src = ItemSrc(
                premises = listOf(Premise((turn.authorization?.question ?: turn.question).take(300), turn.sources.map { "s${it.n}" })),
                findings = listOf(FindingRef(ref = turn.id, turnId = turn.id, findingId = turn.id, text = turn.answer.take(300), status = status, asOf = asOf, keys = turn.sources.map { it.url })),
                status = status, asOf = asOf,
            )
            val proposal = items.mapIndexed { i, (kind, text) ->
                ProposalItem(
                    id = "${turn.id}-s$i", kind = kind,
                    value = if (kind == ItemKind.ADD_STEP) ItemValue.Step(text.take(300), ContentOrigin.RESEARCH) else ItemValue.Text(text.take(600)),
                    why = "Suggested by Claude from this research answer. Check it against the sources before applying.",
                    recommended = false, src = src,
                )
            }
            return ProposalSet(
                id = setId(threadId, turn.id), source = ProposalSource.RESEARCH, targetKind = TargetKind.TASK, targetId = taskId,
                investigationId = threadId, turnId = turn.id, madeAtMs = nowMs, model = SmallCall.MODEL,
                status = if (proposal.isEmpty()) SetStatus.CLOSED else SetStatus.OPEN, baseOriginal = task, base = task, items = proposal,
                title = "From research: ${(turn.authorization?.question ?: turn.question).take(90)}",
                advice = if (proposal.isEmpty()) "Claude found nothing in this answer worth adding to the task." else "Suggestions from one research answer. Nothing changes until you apply items.",
            )
        }
    }
}
