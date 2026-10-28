package com.lifeadmin.autopilot.data.repository

import androidx.room.withTransaction
import com.lifeadmin.autopilot.ai.aifirst.AfJson
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfThread
import com.lifeadmin.autopilot.ai.aifirst.AfThreadMeta
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.InvestigationEntity
import com.lifeadmin.autopilot.data.local.InvestigationTurnEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/** One research thread as lists show it, whichever engine wrote it. Never needs the network. */
data class ResearchThreadSummary(
    val id: String,
    val title: String,
    val taskId: String?,
    val updatedAtMs: Long,
    val turnCount: Int,
    val status: String,
    val searches: Int,
    val sources: Int,
    /** First line of the latest answer (AI-first) or the stored status line (legacy). */
    val preview: String?,
    /** Saved by the earlier research engine: read-only. */
    val legacy: Boolean,
    val running: Boolean,
)

/**
 * Room store for AI-first research threads, in the same `investigations` / `investigation_turns`
 * tables as the M4 threads (no schema change). A thread is AI-first when its meta JSON carries
 * `engine`; its turns are stored with kind [AfJson.TURN_KIND] and [AfTurn] JSON. Legacy threads are
 * never rewritten here: they stay readable through [InvestigationRepository]. Unreadable rows are
 * skipped, never deleted. Built-in research is retired: the app only reads, settles and deletes
 * threads now; [create] and [saveTurn] keep the stored format writable for backups and tests.
 */
open class AfThreadRepository(private val db: AppDatabase) {
    private val dao get() = db.investigationDao()
    private val turns get() = db.investigationTurnDao()
    private val legacy = InvestigationRepository(db)

    fun observe(id: String): Flow<AfThread?> = combine(dao.observe(id), turns.observeForInvestigation(id)) { e, t -> e?.let { decode(it, t) } }

    suspend fun get(id: String): AfThread? = dao.get(id)?.let { decode(it, turns.forInvestigation(id)) }

    /** True when the thread exists and was written by the earlier engine. */
    suspend fun isLegacy(id: String): Boolean = dao.get(id)?.let { AfJson.decodeMeta(it.meta) == null } ?: false

    suspend fun create(id: String, taskId: String?, title: String, firstQuestion: String, first: AfTurn, at: Instant) = db.withTransaction {
        dao.upsert(
            InvestigationEntity(
                id = id, origin = if (taskId != null) "TASK" else "STANDALONE", taskId = taskId, draftId = null, createdAt = at, updatedAt = at,
                title = title.take(InvestigationRepository.MAX_TITLE), state = first.status.name, turnCount = 1, meta = AfJson.encodeMeta(AfThreadMeta(title.take(InvestigationRepository.MAX_TITLE), firstQuestion)),
            ),
        )
        turns.upsert(turnEntity(id, first))
    }

    suspend fun saveTurn(threadId: String, turn: AfTurn) = db.withTransaction {
        val e = dao.get(threadId) ?: return@withTransaction
        check(AfJson.decodeMeta(e.meta) != null) { "legacy research is read-only" }
        turns.upsert(turnEntity(threadId, turn))
        val all = turns.forInvestigation(threadId)
        val latest = all.maxByOrNull { it.turnIndex }
        dao.upsert(e.copy(updatedAt = Instant.ofEpochMilli(maxOf(e.updatedAt.toEpochMilli(), turn.finishedAtMs ?: turn.startedAtMs ?: turn.createdAtMs)), state = latest?.state ?: e.state, turnCount = all.size))
    }

    /** AI-first threads with a turn still marked running: after a process death they can never finish. */
    suspend fun running(): List<AfThread> = dao.running().mapNotNull { decode(it, turns.forInvestigation(it.id)) }

    /**
     * App start: a turn still marked running belongs to a process that died (built-in research is
     * retired, so nothing runs any more). It becomes Interrupted with everything its last checkpoint
     * held; the cost of a call that was in flight is unknown and shown as such. Nothing is re-sent.
     */
    suspend fun settleInterrupted(at: Instant): Int {
        var n = 0
        for (t in running()) for (turn in t.turns.filter { it.status == AfStatus.RUNNING }) {
            saveTurn(t.id, turn.copy(status = AfStatus.INTERRUPTED, costIsFloor = true, finishedAtMs = at.toEpochMilli(),
                note = "Interrupted: the app closed while this was running. " + (if (turn.hasAnswer) "What arrived before is kept. " else "Nothing had arrived yet. ") + "A call may have been in flight; its cost is unknown."))
            n++
        }
        return n + legacy.settleRunning(at)
    }

    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)
    suspend fun deleteAll() = dao.deleteAll()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSummaries(taskId: String? = null): Flow<List<ResearchThreadSummary>> =
        (if (taskId != null) dao.observeForTask(taskId) else dao.observeAll()).flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { e -> turns.observeForInvestigation(e.id).map { t -> summary(e, t) } }) { arr -> arr.filterNotNull() }
        }

    suspend fun summaries(): List<ResearchThreadSummary> = dao.getAll().mapNotNull { summary(it, turns.forInvestigation(it.id)) }

    fun summary(e: InvestigationEntity, rows: List<InvestigationTurnEntity>): ResearchThreadSummary? {
        if (AfJson.decodeMeta(e.meta) != null) {
            val t = decode(e, rows) ?: return null
            val latest = t.latest
            val answered = t.answered.lastOrNull()
            return ResearchThreadSummary(
                t.id, t.title, t.taskId, t.updatedAtMs, t.turns.size, latest?.status?.let(::label) ?: "Saved",
                t.turns.sumOf { it.webSearches }, answered?.sources?.size ?: 0, answered?.let { firstLine(it.answer) }, legacy = false, running = t.running,
            )
        }
        val inv = legacy.decode(e, rows) ?: return null
        val latest = inv.latestTurn
        val status = when (latest?.state) {
            TurnState.RUNNING -> "Running now"
            TurnState.STOPPED -> "Stopped"
            TurnState.FAILED -> "Didn't finish"
            TurnState.INTERRUPTED -> "Interrupted"
            TurnState.CLARIFYING, TurnState.AUTHORIZING, TurnState.DECLINED, TurnState.PREPARING -> "Not started"
            else -> "Saved"
        }
        val result = inv.latestResult
        return ResearchThreadSummary(
            inv.id, inv.title, inv.taskId, inv.updatedAtMs, inv.turns.size, "$status · earlier format", inv.turns.sumOf { it.usage.webSearches },
            result?.sources?.count { it.citedCount > 0 } ?: 0, result?.answer?.stateLine, legacy = true, running = inv.running,
        )
    }

    fun turnEntity(threadId: String, t: AfTurn) = InvestigationTurnEntity(t.id, threadId, t.index, AfJson.TURN_KIND, t.status.name, Instant.ofEpochMilli(t.createdAtMs), AfJson.encodeTurn(t))

    fun decode(e: InvestigationEntity, rows: List<InvestigationTurnEntity>): AfThread? {
        val meta = AfJson.decodeMeta(e.meta) ?: return null
        val list = rows.filter { it.kind == AfJson.TURN_KIND }.mapNotNull { AfJson.decodeTurn(it.payload) }.sortedBy { it.index }
        return AfThread(e.id, e.taskId, e.createdAt.toEpochMilli(), e.updatedAt.toEpochMilli(), meta, list)
    }

    companion object {
        fun label(s: AfStatus): String = when (s) {
            AfStatus.CLARIFYING, AfStatus.READY, AfStatus.DECLINED -> "Not started"
            AfStatus.RUNNING -> "Running now"
            AfStatus.COMPLETE -> "Answered"
            AfStatus.TRUNCATED -> "Answer cut short"
            AfStatus.SEARCH_BUDGET_REACHED -> "Search budget reached"
            AfStatus.SPEND_LIMIT_REACHED -> "Spending limit reached"
            AfStatus.REFUSED -> "Claude declined"
            AfStatus.FAILED -> "Didn't finish"
            AfStatus.STOPPED -> "Stopped"
            AfStatus.INTERRUPTED -> "Interrupted"
        }

        /** The first readable line of an answer, without Markdown markers or citation numbers. */
        fun firstLine(answer: String): String? = answer.lines().map { it.trim().trimStart('#', '*', '-', '>', ' ').replace("**", "").replace(Regex("""\s?\[\d+]"""), "").trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("|") }?.take(160)
    }
}
