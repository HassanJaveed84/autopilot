package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research2.AppliedProposal
import com.lifeadmin.autopilot.ai.research2.DeliverableTemplate
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.RunKind
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.ai.research2.SessionOutcome
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Carries M3A.2 research sessions into the Investigation Thread model without rewriting them
 * (RES-092): one investigation per session, one turn per recorded run, the session's parsed
 * content kept byte-for-byte on the turn that holds the result. The same conversion serves the
 * Room 6→7 migration, legacy backup import and the read-only legacy viewer.
 */
object LegacyInvestigations {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Converted(val meta: InvestigationMeta, val turns: List<Turn>, val title: String, val state: TurnState)

    /** From the raw stored row. Works even when the payload no longer decodes as [SessionContent]: the bytes are kept and shown as-is later. */
    fun fromStored(id: String, request: String?, title: String, payload: String, applied: String, createdAtMs: Long, updatedAtMs: Long): Converted {
        val content = runCatching { json.decodeFromString(SessionContent.serializer(), payload) }.getOrNull()
        val runs = content?.runs ?: runsFromJson(payload)
        val question = content?.request ?: request ?: title
        val objective = Objective(question = question, text = content?.title?.ifBlank { null } ?: title.ifBlank { question }, askKind = askKindOf(content?.template), established = true)
        val outcome = content?.outcome?.name ?: outcomeFromJson(payload) ?: "COMPLETE"
        // Turn ids follow the run position so a session saved again after a continuation maps onto the same rows.
        val turns = if (runs.isEmpty()) {
            listOf(legacyTurn("$id-run0", 0, TurnKind.INITIAL, stateOf(outcome), createdAtMs, objective, LegacyTurn(payload, applied, runId = id, outcome = outcome)))
        } else {
            runs.mapIndexed { i, r ->
                val last = i == runs.lastIndex
                legacyTurn(
                    "$id-run${i + 1}", i, if (r.kind == RunKind.CONTINUATION) TurnKind.CONTINUATION else TurnKind.INITIAL, stateOf(r.outcome.name), r.startedAtEpochMs,
                    objective, LegacyTurn(if (last) payload else "", if (last) applied else "[]", runId = r.id, outcome = r.outcome.name),
                    usage = TurnUsage(r.usage.inputTokens, r.usage.outputTokens, r.usage.cacheWriteTokens, r.usage.cacheReadTokens, r.usage.webSearches, r.usage.elapsedMs, floor = !r.usage.complete, modelsUsed = listOf(r.model)),
                )
            }
        }
        val meta = InvestigationMeta(title = title.ifBlank { objective.text }, firstQuestion = question, legacy = true)
        return Converted(meta, turns, meta.title, turns.last().state)
    }

    fun fromSession(s: ResearchSession): Converted =
        fromStored(s.id, s.content.request, s.content.title, json.encodeToString(SessionContent.serializer(), s.content), json.encodeToString(ListSerializer(AppliedProposal.serializer()), s.applied), s.createdAt.toEpochMilli(), s.updatedAt.toEpochMilli())

    /** The legacy session a converted investigation still represents, or null when it is not a legacy investigation or its content no longer decodes. */
    fun toSession(inv: Investigation): ResearchSession? {
        if (!inv.meta.legacy) return null
        val holder = inv.turns.sortedByDescending { it.index }.firstOrNull { it.legacy?.sessionContentJson?.isNotBlank() == true } ?: return null
        val legacy = holder.legacy!!
        val content = runCatching { json.decodeFromString(SessionContent.serializer(), legacy.sessionContentJson) }.getOrNull() ?: return null
        val applied = runCatching { json.decodeFromString(ListSerializer(AppliedProposal.serializer()), legacy.appliedJson) }.getOrDefault(emptyList())
        return ResearchSession(inv.id, inv.taskId ?: return null, Instant.ofEpochMilli(inv.createdAtMs), Instant.ofEpochMilli(inv.updatedAtMs), content, applied)
    }

    /** The legacy content of a turn, for the read-only viewer. */
    fun content(turn: Turn): SessionContent? = turn.legacy?.sessionContentJson?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(SessionContent.serializer(), it) }.getOrNull() }
    fun applied(turn: Turn): List<AppliedProposal> = turn.legacy?.appliedJson?.let { runCatching { json.decodeFromString(ListSerializer(AppliedProposal.serializer()), it) }.getOrNull() }.orEmpty()
    fun encodeApplied(applied: List<AppliedProposal>): String = json.encodeToString(ListSerializer(AppliedProposal.serializer()), applied)

    fun askKindOf(template: DeliverableTemplate?): AskKind = when (template) {
        DeliverableTemplate.SHORTLIST -> AskKind.COMPARE
        DeliverableTemplate.PICK_PER_ITEM -> AskKind.RECOMMEND
        DeliverableTemplate.HOW_TO, DeliverableTemplate.CHECKLIST -> AskKind.PROCEDURE
        else -> AskKind.FACT
    }

    fun stateOf(outcome: String): TurnState = when (outcome) {
        SessionOutcome.STOPPED.name -> TurnState.STOPPED
        SessionOutcome.INTERRUPTED.name -> TurnState.INTERRUPTED
        SessionOutcome.FAILED.name -> TurnState.FAILED
        else -> TurnState.DONE
    }

    private fun legacyTurn(id: String, index: Int, kind: TurnKind, state: TurnState, createdAtMs: Long, objective: Objective, legacy: LegacyTurn, usage: TurnUsage = TurnUsage()) =
        Turn(id = id, index = index, kind = kind, state = state, createdAtMs = createdAtMs, objective = objective, usage = usage, legacy = legacy, endingNote = SessionOutcome.entries.firstOrNull { it.name == legacy.outcome }?.label)

    private fun runsFromJson(payload: String): List<com.lifeadmin.autopilot.ai.research2.RunRecord> = runCatching {
        lenient.parseToJsonElement(payload).jsonObject["runs"]!!.jsonArray.map { lenient.decodeFromJsonElement(com.lifeadmin.autopilot.ai.research2.RunRecord.serializer(), it) }
    }.getOrDefault(emptyList())

    private fun outcomeFromJson(payload: String): String? = runCatching { lenient.parseToJsonElement(payload).jsonObject["outcome"]!!.jsonPrimitive.content }.getOrNull()
}
