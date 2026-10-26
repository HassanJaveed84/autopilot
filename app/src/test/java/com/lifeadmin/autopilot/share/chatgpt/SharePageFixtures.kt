package com.lifeadmin.autopilot.share.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic chatgpt.com/share pages in the observed shape (structure only; every word here is
 * invented). [turboEncode] mirrors turbo-stream's flat table: strings and keys are deduplicated,
 * objects map `"_<key index>"` to value indices, `null` is the -5 sentinel.
 */
object SharePageFixtures {
    const val ID = "0f0e0d0c-0b0a-4909-8807-060504030201"
    const val URL = "https://chatgpt.com/share/$ID"

    fun turboEncode(root: JsonElement): String {
        val table = ArrayList<JsonElement>()
        val strings = HashMap<String, Int>()
        fun str(s: String): Int = strings.getOrPut(s) { table.add(JsonPrimitive(s)); table.size - 1 }
        fun enc(e: JsonElement): Int = when (e) {
            JsonNull -> -5
            is JsonPrimitive -> if (e.isString) str(e.content) else { table.add(e); table.size - 1 }
            is JsonArray -> { val at = table.size; table.add(JsonNull); table[at] = JsonArray(e.map { JsonPrimitive(enc(it)) }); at }
            is JsonObject -> {
                val at = table.size; table.add(JsonNull)
                table[at] = JsonObject(e.entries.associate { (k, v) -> "_${str(k)}" to JsonPrimitive(enc(v)) })
                at
            }
        }
        enc(root)
        return JsonArray(table).toString()
    }

    /** The page around a turbo-stream payload, with metadata that must never count as content. */
    fun page(turbo: String, extraScripts: String = ""): String {
        val literal = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(turbo + "\n"))
        return """<!DOCTYPE html><html><head><title>Shared chat</title>
            |<meta property="og:title" content="Synthetic title"><meta property="og:description" content="Synthetic description">
            |</head><body><div id="root"></div>
            |<script>window.__reactRouterContext = {"basename":"/"};window.__reactRouterContext.stream = new ReadableStream({start(c){window.__reactRouterContext.streamController = c;}});</script>
            |<script>window.__reactRouterContext.streamController.enqueue($literal);</script>
            |$extraScripts<script>window.__reactRouterContext.streamController.close();</script></body></html>""".trimMargin()
    }

    data class Msg(
        val role: String,
        val text: String,
        val id: String,
        val contentType: String = "text",
        val recipient: String? = "all",
        val channel: String? = null,
        val hidden: Boolean = false,
    )

    fun user(id: String, text: String) = Msg("user", text, id)
    fun assistant(id: String, text: String) = Msg("assistant", text, id, channel = "final")

    fun node(m: Msg?, id: String, parent: String?, child: String?): JsonObject = buildJsonObject {
        put("id", id)
        if (m == null) put("message", JsonNull) else putJsonObject("message") {
            put("id", m.id)
            putJsonObject("author") { put("role", m.role); put("name", JsonNull); putJsonObject("metadata") {} }
            put("create_time", 1_760_000_000.5)
            putJsonObject("content") { put("content_type", m.contentType); putJsonArray("parts") { add(JsonPrimitive(m.text)) } }
            put("status", "finished_successfully")
            put("end_turn", m.role == "assistant")
            put("weight", 1.0)
            putJsonObject("metadata") { if (m.hidden) put("is_visually_hidden_from_conversation", true) }
            put("recipient", m.recipient?.let { JsonPrimitive(it) } ?: JsonNull)
            m.channel?.let { put("channel", it) }
        }
        put("parent", parent?.let { JsonPrimitive(it) } ?: JsonNull)
        putJsonArray("children") { child?.let { add(JsonPrimitive(it)) } }
    }

    /** A share route payload: root system node, then [messages] in order. */
    fun shareData(
        messages: List<Msg>,
        highlighted: String? = messages.lastOrNull()?.id,
        title: String? = "Synthetic decision",
        includeLinear: Boolean = true,
    ): JsonObject {
        val ids = listOf("root") + messages.map { it.id }
        val nodes = listOf(node(null, "root", null, ids.getOrNull(1))) +
            messages.mapIndexed { i, m -> node(m, m.id, ids[i], ids.getOrNull(i + 2)) }
        return buildJsonObject {
            put("title", title?.let { JsonPrimitive(it) } ?: JsonNull)
            put("create_time", 1_760_000_000.0)
            put("conversation_id", ID)
            putJsonObject("mapping") { nodes.forEach { put(it["id"]!!.let { id -> (id as JsonPrimitive).content }, it) } }
            put("current_node", ids.last())
            put("is_public", true)
            if (includeLinear) put("linear_conversation", JsonArray(nodes))
            put("highlighted_message_id", highlighted?.let { JsonPrimitive(it) } ?: JsonNull)
            put("continue_conversation_url", "$URL/continue")
        }
    }

    fun loader(serverResponse: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("loaderData") {
            putJsonObject("root") { put("disableSSR", false) }
            putJsonObject("routes/share.\$shareId.(\$action)") {
                put("sharedConversationId", ID)
                put("serverResponse", serverResponse)
                put("moderationMode", false)
                putJsonObject("meta") { put("pageTitle", "Synthetic"); put("ogTitle", "Synthetic") }
            }
        }
        put("actionData", JsonNull)
        put("errors", JsonNull)
    }

    fun dataPage(messages: List<Msg>, highlighted: String? = messages.lastOrNull()?.id, includeLinear: Boolean = true): String =
        page(turboEncode(loader(buildJsonObject { put("type", "data"); put("data", shareData(messages, highlighted, includeLinear = includeLinear)) })))

    /** What chatgpt.com served for a missing or malformed share id: HTTP 200 with an error response. */
    fun errorPage(): String = page(turboEncode(loader(buildJsonObject { put("type", "error") })))

    val sampleConversation = listOf(
        Msg("system", "", "m-sys", hidden = true),
        user("m-u1", "Help me pick a synthetic widget."),
        Msg("assistant", "{\"search_query\":[\"widgets\"]}", "m-call", recipient = "web.run"),
        Msg("tool", "Synthetic search results", "m-tool"),
        Msg("assistant", "thinking…", "m-think", contentType = "thoughts"),
        assistant("m-a1", "Widget A is the better pick citeturn0search0."),
        user("m-u2", "Prepare it for Autopilot."),
        Msg("assistant", "Hidden memory note", "m-hidden", channel = "final", hidden = true),
        assistant("m-a2", "Here is your handoff."),
    )

    fun handoffBlock(body: String, info: String = "autopilot-handoff") = "```$info\n$body\n```"

    val validHandoffJson = """
        {
          "schema": "autopilot.handoff.v1",
          "target": { "task_ref": "ap:task:synthetic-1", "title": "Choose a widget" },
          "summary": "Widget A fits the budget.",
          "decisions": [ { "text": "Buy Widget A", "rationale": "Cheaper and quieter" } ],
          "notes": [ "Compare warranty terms" ],
          "steps": [ { "text": "Order Widget A", "due": "2026-11-03" }, { "text": "Recycle the old widget" } ],
          "deadline": { "date": "2026-11-15", "time": "17:00", "note": "Return window closes" },
          "reminders": [ { "date": "2026-11-02", "time": "09:00", "note": "Check the price" } ],
          "open_questions": [ "Does it fit the shelf?" ],
          "future_field": { "ignored": true }
        }
    """.trimIndent()
}
