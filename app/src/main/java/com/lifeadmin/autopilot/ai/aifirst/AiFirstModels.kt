package com.lifeadmin.autopilot.ai.aifirst

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * EXPERIMENT (architecture A/B, not wired into the app). The data an AI-first research thread keeps.
 * Everything here is either what the person approved, what the provider returned, or what the app
 * measured; nothing is a model's description of itself.
 */

/** What one turn may spend. The search budget goes to the server as `max_uses`; the spend limit is enforced here. */
@Serializable
data class AfConfig(
    val model: String,
    val searchBudget: Int,
    val spendLimitCents: Int,
    val maxOutputTokens: Long = 16_000,
    /** Provider reasoning effort; null = provider default. */
    val effort: String? = null,
)

/** The record the person approves before anything is sent. The payload is built from this and nothing else. */
@Serializable
data class AfAuthorization(
    val question: String,
    /** Context lines the person chose to share (date, task title, …), verbatim. */
    val context: List<String>,
    val config: AfConfig,
    /** Earlier turns of this same thread travel with the question (shown on the card). */
    val includesHistory: Boolean,
    val estimateHighMicros: Long?,
    val authorizedAtMs: Long,
)

/** A page the provider cited, with the provider's own cited text. Numbered per turn. */
@Serializable
data class AfSource(val n: Int, val url: String, val title: String?, val quotes: List<String>, val pageAge: String? = null)

@Serializable
data class AfSearch(val query: String, val results: Int, val error: String? = null)

@Serializable
enum class AfStatus { RUNNING, COMPLETE, TRUNCATED, SEARCH_BUDGET_REACHED, SPEND_LIMIT_REACHED, REFUSED, FAILED, STOPPED }

@Serializable
data class AfTurn(
    val id: String,
    val authorization: AfAuthorization,
    val status: AfStatus,
    /** The model's answer with `[n]` after every passage the provider attached a citation to. */
    val answer: String = "",
    val sources: List<AfSource> = emptyList(),
    val searches: List<AfSearch> = emptyList(),
    /** Links the model wrote itself that no search result supplied: shown as unverified, never as citations. */
    val unsuppliedLinks: List<String> = emptyList(),
    /** Plain-language stop or failure reason; null when it simply finished. */
    val note: String? = null,
    val calls: Int = 0,
    val webSearches: Int = 0,
    val costMicros: Long = 0,
    /** Some usage was not reported (a failure or stop), so [costMicros] is a floor. */
    val costIsFloor: Boolean = false,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
) {
    val question: String get() = authorization.question
}

@Serializable
data class AfThread(val id: String, val turns: List<AfTurn> = emptyList()) {
    fun upsert(turn: AfTurn): AfThread = copy(turns = turns.filterNot { it.id == turn.id } + turn)
}

/** Persistence seam. The app would back this with Room; the harness uses [FileAfThreadStore]. */
interface AfThreadStore {
    suspend fun get(id: String): AfThread?
    suspend fun save(thread: AfThread)
}

class InMemoryAfThreadStore : AfThreadStore {
    private val threads = java.util.concurrent.ConcurrentHashMap<String, AfThread>()
    override suspend fun get(id: String) = threads[id]
    override suspend fun save(thread: AfThread) { threads[thread.id] = thread }
}

/** One JSON file per thread, written atomically (temp file + rename) so a crash never leaves half a thread. */
class FileAfThreadStore(private val dir: File) : AfThreadStore {
    private fun file(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")
    override suspend fun get(id: String): AfThread? = file(id).takeIf { it.exists() }?.let { AfJson.json.decodeFromString(AfThread.serializer(), it.readText()) }
    override suspend fun save(thread: AfThread) {
        dir.mkdirs()
        val tmp = File(dir, "${thread.id}.tmp")
        tmp.writeText(AfJson.json.encodeToString(AfThread.serializer(), thread))
        check(tmp.renameTo(file(thread.id))) { "could not save the thread" }
    }
}

object AfJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
}
