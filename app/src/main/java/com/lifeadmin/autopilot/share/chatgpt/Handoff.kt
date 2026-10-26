package com.lifeadmin.autopilot.share.chatgpt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalTime

/*
 * autopilot.handoff.v1 (spec: docs/CHATGPT_SHARE_HANDOFF_POC.md §4). A handoff is only ever read
 * from one fenced code block in the chosen assistant response; prose around it is ignored, so an
 * ordinary remark can never become a proposed change. Nothing here writes tasks.
 */

@Serializable
data class HandoffTarget(
    /** Opaque reference copied from Autopilot, when the conversation started from a task. */
    @SerialName("task_ref") val taskRef: String? = null,
    val title: String = "",
)

@Serializable
data class HandoffDecision(val text: String = "", val rationale: String? = null)

@Serializable
data class HandoffStep(val text: String = "", val due: String? = null)

/** A date stated in the conversation: ISO `yyyy-MM-dd`, optional `HH:mm`, both in the phone's zone. */
@Serializable
data class HandoffWhen(val date: String = "", val time: String? = null, val note: String? = null)

@Serializable
data class AutopilotHandoff(
    val schema: String = "",
    val target: HandoffTarget = HandoffTarget(),
    val summary: String? = null,
    val decisions: List<HandoffDecision> = emptyList(),
    val notes: List<String> = emptyList(),
    val steps: List<HandoffStep> = emptyList(),
    val deadline: HandoffWhen? = null,
    val reminders: List<HandoffWhen> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<String> = emptyList(),
)

sealed interface HandoffResult {
    data class Found(val handoff: AutopilotHandoff) : HandoffResult

    /** No handoff block: an ordinary response. Nothing is proposed. */
    data object NotFound : HandoffResult

    /** More than one block: refused rather than guessing which one is meant. */
    data class Ambiguous(val count: Int) : HandoffResult

    data class UnsupportedVersion(val schema: String) : HandoffResult

    /** A block was present but is not valid v1. [problems] name fields, never their content. */
    data class Malformed(val problems: List<String>) : HandoffResult
}

object HandoffParser {
    const val SCHEMA = "autopilot.handoff.v1"
    const val FENCE_INFO = "autopilot-handoff"
    private const val SCHEMA_PREFIX = "autopilot.handoff."
    private const val MAX_BLOCK = 32 * 1024
    private const val MAX_ITEMS = 30
    private const val MAX_TEXT = 2_000
    private const val MAX_TITLE = 200

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val fenceOpen = Regex("^ {0,3}(`{3,}|~{3,})[ \t]*([^`\\s]*)[^`]*$")

    private data class Block(val info: String, val body: String)

    fun parse(responseText: String): HandoffResult {
        val candidates = fencedBlocks(responseText).filter { isCandidate(it) }
        if (candidates.isEmpty()) return HandoffResult.NotFound
        if (candidates.size > 1) return HandoffResult.Ambiguous(candidates.size)
        return decode(candidates.single().body)
    }

    /** `autopilot-handoff` blocks always count; a `json`/unlabelled block counts only if it declares the schema. */
    private fun isCandidate(b: Block): Boolean = when (b.info.lowercase()) {
        FENCE_INFO -> true
        "json", "" -> schemaOf(b.body)?.startsWith(SCHEMA_PREFIX) == true
        else -> false
    }

    private fun schemaOf(body: String): String? = runCatching {
        ((json.parseToJsonElement(body) as? JsonObject)?.get("schema") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()

    private fun decode(body: String): HandoffResult {
        if (body.length > MAX_BLOCK) return HandoffResult.Malformed(listOf("block larger than ${MAX_BLOCK / 1024} KB"))
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return HandoffResult.Malformed(listOf("not a JSON object"))
        val schema = (root["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return HandoffResult.Malformed(listOf("schema: missing"))
        if (schema != SCHEMA) {
            return if (schema.startsWith(SCHEMA_PREFIX)) HandoffResult.UnsupportedVersion(schema) else HandoffResult.Malformed(listOf("schema: unknown"))
        }
        val h = runCatching { json.decodeFromJsonElement(AutopilotHandoff.serializer(), root) }.getOrElse {
            return HandoffResult.Malformed(listOf("fields have the wrong types"))
        }
        val problems = validate(h)
        return if (problems.isEmpty()) HandoffResult.Found(h.trimmed()) else HandoffResult.Malformed(problems)
    }

    private fun validate(h: AutopilotHandoff): List<String> = buildList {
        if (h.target.title.isBlank()) add("target.title: missing")
        if (h.target.title.length > MAX_TITLE) add("target.title: too long")
        h.target.taskRef?.let { if (it.length > 100) add("target.task_ref: too long") }
        fun text(path: String, s: String?, required: Boolean = true) {
            if (s == null) return
            if (required && s.isBlank()) add("$path: empty")
            if (s.length > MAX_TEXT) add("$path: too long")
        }
        fun count(path: String, n: Int) { if (n > MAX_ITEMS) add("$path: more than $MAX_ITEMS items") }
        fun date(path: String, s: String?, required: Boolean) {
            if (s == null) { if (required) add("$path: missing"); return }
            if (runCatching { LocalDate.parse(s.trim()) }.isFailure) add("$path: not yyyy-MM-dd")
        }
        fun time(path: String, s: String?) {
            if (s != null && (!Regex("\\d{2}:\\d{2}").matches(s.trim()) || runCatching { LocalTime.parse(s.trim()) }.isFailure)) add("$path: not HH:mm")
        }
        text("summary", h.summary, required = false)
        count("decisions", h.decisions.size)
        h.decisions.forEachIndexed { i, d -> text("decisions[$i].text", d.text); text("decisions[$i].rationale", d.rationale, required = false) }
        count("notes", h.notes.size)
        h.notes.forEachIndexed { i, n -> text("notes[$i]", n) }
        count("steps", h.steps.size)
        h.steps.forEachIndexed { i, s -> text("steps[$i].text", s.text); date("steps[$i].due", s.due, required = false) }
        h.deadline?.let { date("deadline.date", it.date, required = true); time("deadline.time", it.time); text("deadline.note", it.note, required = false) }
        count("reminders", h.reminders.size)
        h.reminders.forEachIndexed { i, r -> date("reminders[$i].date", r.date, required = true); time("reminders[$i].time", r.time); text("reminders[$i].note", r.note, required = false) }
        count("open_questions", h.openQuestions.size)
        h.openQuestions.forEachIndexed { i, q -> text("open_questions[$i]", q) }
    }

    private fun AutopilotHandoff.trimmed() = copy(
        target = target.copy(taskRef = target.taskRef?.trim()?.ifEmpty { null }, title = target.title.trim()),
        summary = summary?.trim()?.ifEmpty { null },
        decisions = decisions.map { HandoffDecision(it.text.trim(), it.rationale?.trim()?.ifEmpty { null }) },
        notes = notes.map { it.trim() },
        steps = steps.map { HandoffStep(it.text.trim(), it.due?.trim()) },
        deadline = deadline?.let { HandoffWhen(it.date.trim(), it.time?.trim(), it.note?.trim()?.ifEmpty { null }) },
        reminders = reminders.map { HandoffWhen(it.date.trim(), it.time?.trim(), it.note?.trim()?.ifEmpty { null }) },
        openQuestions = openQuestions.map { it.trim() },
    )

    /** CommonMark-style fences: same character, closing run at least as long as the opening one. */
    private fun fencedBlocks(text: String): List<Block> {
        val lines = text.lines()
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val m = fenceOpen.matchEntire(lines[i])
            if (m == null) { i++; continue }
            val fence = m.groupValues[1]
            val info = m.groupValues[2]
            val close = Regex("^ {0,3}${Regex.escape(fence.substring(0, 1))}{${fence.length},}[ \t]*$")
            val end = (i + 1 until lines.size).firstOrNull { close.matches(lines[it]) } ?: lines.size
            out += Block(info, lines.subList(i + 1, end).joinToString("\n"))
            i = end + 1
        }
        return out
    }
}
