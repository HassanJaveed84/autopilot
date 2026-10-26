package com.lifeadmin.autopilot.share.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

enum class Role { USER, ASSISTANT }

data class SharedMessage(val id: String, val role: Role, val text: String)

/** How the response handed to the handoff parser was chosen. */
enum class TargetChoice {
    /** The page's `highlighted_message_id`: the response the share was made from. */
    HIGHLIGHTED,

    /** No usable highlight: the last visible assistant response. */
    LATEST,
}

data class SharedConversation(
    val title: String?,
    /** Visible user and assistant text in order; tool calls, reasoning and system messages are left out. */
    val messages: List<SharedMessage>,
    val target: SharedMessage,
    val targetChoice: TargetChoice,
)

sealed interface Extraction {
    data class Success(val conversation: SharedConversation) : Extraction

    /** The page says there is no such share (deleted, revoked, never existed, or not public). */
    data object Unavailable : Extraction

    /**
     * The page did not match the known format, or held no assistant response. Page metadata
     * (title, description, preview image) is never treated as a conversation.
     */
    data class Unrecognized(val reason: String) : Extraction
}

/**
 * Reads the conversation from a chatgpt.com share page **without running any of its scripts**.
 *
 * Observed format (2026-10-26, undocumented and subject to change): the page is a React Router
 * app that inlines its loader data as a turbo-stream encoded string in
 * `window.__reactRouterContext.streamController.enqueue("…")`. Decoded, the first line is a flat
 * JSON table; objects map `"_<key index>"` to value indices and negative numbers are sentinels
 * (null/undefined). The share route's `serverResponse` is `{type: "data", data: {…}}` for a live
 * share or `{type: "error"}` for a missing one, and `data.linear_conversation` is the message
 * path in order. Server-rendered markup holds no message text.
 */
object ShareExtractor {
    private const val ENQUEUE = "streamController.enqueue(\""
    private const val MAX_MESSAGES = 2_000
    private val json = Json { isLenient = false }
    private val citationMarker = Regex("[^]{0,200}")
    private val privateUse = Regex("[-]")

    fun extract(html: String): Extraction {
        val literal = firstEnqueuedLiteral(html) ?: return Extraction.Unrecognized("no inline loader data")
        val payload = runCatching { json.parseToJsonElement(literal) as? JsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
            ?: return Extraction.Unrecognized("loader data is not a string literal")
        val table = runCatching { json.parseToJsonElement(payload.substringBefore('\n')) as? JsonArray }.getOrNull()
            ?: return Extraction.Unrecognized("loader data is not a turbo-stream table")
        return Reader(table).read()
    }

    /** The first `enqueue("…")` argument, scanned by hand (no regex backtracking over ~1 MB). */
    internal fun firstEnqueuedLiteral(html: String): String? {
        val start = html.indexOf(ENQUEUE).takeIf { it >= 0 }?.let { it + ENQUEUE.length - 1 } ?: return null
        var i = start + 1
        while (i < html.length) {
            when (html[i]) {
                '\\' -> i += 2
                '"' -> return html.substring(start, i + 1)
                else -> i++
            }
        }
        return null
    }

    private class Reader(private val t: JsonArray) {
        private fun el(i: Int?): JsonElement? = if (i == null || i < 0 || i >= t.size) null else t[i]

        private fun field(obj: Int?, key: String): Int? {
            val o = el(obj) as? JsonObject ?: return null
            for ((k, v) in o) {
                if (!k.startsWith("_")) continue
                val name = el(k.substring(1).toIntOrNull()) as? JsonPrimitive ?: continue
                if (name.isString && name.content == key) return (v as? JsonPrimitive)?.intOrNull
            }
            return null
        }

        private fun keys(obj: Int?): List<Pair<String, Int?>> = (el(obj) as? JsonObject)?.mapNotNull { (k, v) ->
            val name = el(k.removePrefix("_").toIntOrNull()) as? JsonPrimitive ?: return@mapNotNull null
            if (name.isString) name.content to (v as? JsonPrimitive)?.intOrNull else null
        }.orEmpty()

        private fun str(i: Int?): String? = (el(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun bool(i: Int?): Boolean? = (el(i) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        /** A plain array of indices; a type-tagged array (`["P", …]`, `["D", …]`) is not a list. */
        private fun list(i: Int?): List<Int>? {
            val a = el(i) as? JsonArray ?: return null
            if (a.firstOrNull().let { it is JsonPrimitive && it.isString }) return null
            return a.map { (it as? JsonPrimitive)?.intOrNull ?: return null }
        }

        fun read(): Extraction {
            val loader = field(0, "loaderData") ?: return Extraction.Unrecognized("no loaderData")
            val route = keys(loader).firstOrNull { it.first.startsWith("routes/share") }?.second
                ?: return Extraction.Unrecognized("no share route data")
            val response = field(route, "serverResponse") ?: return Extraction.Unrecognized("no serverResponse")
            when (str(field(response, "type"))) {
                "data" -> Unit
                "error" -> return Extraction.Unavailable
                else -> return Extraction.Unrecognized("unknown serverResponse type")
            }
            val data = field(response, "data") ?: return Extraction.Unrecognized("no conversation data")
            val nodes = list(field(data, "linear_conversation")) ?: pathFromMapping(data)
                ?: return Extraction.Unrecognized("no message list")
            val messages = nodes.asSequence().take(MAX_MESSAGES).mapNotNull { message(field(it, "message")) }.toList()
            val highlighted = str(field(data, "highlighted_message_id"))
            val hit = messages.lastOrNull { it.role == Role.ASSISTANT && it.id == highlighted }
            val target = hit ?: messages.lastOrNull { it.role == Role.ASSISTANT } ?: return Extraction.Unrecognized("no assistant response")
            return Extraction.Success(
                SharedConversation(str(field(data, "title"))?.takeIf { it.isNotBlank() }, messages, target, if (hit != null) TargetChoice.HIGHLIGHTED else TargetChoice.LATEST),
            )
        }

        /** Fallback when only `mapping` + `current_node` are present: walk parents back to the root. */
        private fun pathFromMapping(data: Int): List<Int>? {
            val mapping = field(data, "mapping") ?: return null
            var id = str(field(data, "current_node")) ?: return null
            val nodes = keys(mapping).toMap()
            val path = ArrayList<Int>()
            val seen = HashSet<String>()
            while (seen.add(id) && path.size < MAX_MESSAGES) {
                val node = nodes[id] ?: break
                path += node
                id = str(field(node, "parent")) ?: break
            }
            return path.reversed().takeIf { it.isNotEmpty() }
        }

        /** Visible text only: user/assistant, text content, addressed to everyone, final channel when present. */
        private fun message(m: Int?): SharedMessage? {
            if (m == null) return null
            val role = when (str(field(field(m, "author"), "role"))) {
                "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                else -> return null
            }
            val content = field(m, "content")
            if (str(field(content, "content_type")) != "text") return null
            str(field(m, "recipient"))?.let { if (it != "all") return null }
            str(field(m, "channel"))?.let { if (role == Role.ASSISTANT && it != "final") return null }
            if (bool(field(field(m, "metadata"), "is_visually_hidden_from_conversation")) == true) return null
            val text = list(field(content, "parts")).orEmpty().mapNotNull { str(it) }.joinToString("\n")
                .replace(citationMarker, "").replace(privateUse, "").trim()
            if (text.isEmpty()) return null
            return SharedMessage(str(field(m, "id")) ?: return null, role, text)
        }
    }
}
