package com.lifeadmin.autopilot.domain.draft

import com.lifeadmin.autopilot.domain.model.ContentOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/*
 * A draft (PLAN-010/013/014, handoff §4.1): the user's input and everything built on it before a
 * task exists. It is never a task, never reminds, and is deleted only by the user (M3 shows the
 * policy on the draft surface). Content is JSON so later milestones can add fields without a
 * migration; unknown keys are ignored on read.
 */

enum class DraftStatus { DRAFT, SAVED, DISCARDED }

@Serializable
data class DraftStep(
    val id: String,
    val text: String,
    val origin: ContentOrigin = ContentOrigin.USER,
    val include: Boolean = true,
    val isDone: Boolean = false,
    val provenanceId: String? = null,
)

/** An embedded decision or question the plan surfaced (PLAN-024); [choice] is what the user picked. */
@Serializable
data class DraftDecision(val id: String, val text: String, val options: List<String> = emptyList(), val choice: String? = null)

/** A detail the user chose to keep on the draft via "Add to draft" (OD-10). Request-only details never land here. */
@Serializable
data class RetainedDetail(val key: String, val value: String)

/** The four timing roles, kept apart (PLAN-040/041). ISO dates; [deadlineTime] HH:mm. */
@Serializable
data class DraftTiming(
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val eventDate: String? = null,
    val eventLabel: String? = null,
    val intendedDate: String? = null,
    val targetDate: String? = null,
    val targetBasis: String? = null,
)

@Serializable
data class DraftHistoryEntry(val atMs: Long, val summary: String)

@Serializable
data class DraftContent(
    /** The input, verbatim (PLAN-010). */
    val text: String,
    val title: String? = null,
    val notes: String? = null,
    val priority: String? = null,
    val steps: List<DraftStep> = emptyList(),
    val decisions: List<DraftDecision> = emptyList(),
    val retained: List<RetainedDetail> = emptyList(),
    val timing: DraftTiming = DraftTiming(),
    /** Opaque, encoded planning result owned by the planning layer; kept so recovery never re-runs planning (PLAN-014). */
    val planningResult: String? = null,
    val planningModel: String? = null,
    val planningAtMs: Long? = null,
    val history: List<DraftHistoryEntry> = emptyList(),
    val contentVersion: Int = 1,
)

data class Draft(
    val id: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val status: DraftStatus,
    val savedTaskId: String? = null,
    val content: DraftContent,
) {
    val text: String get() = content.text
}

object DraftJson {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(c: DraftContent): String = json.encodeToString(DraftContent.serializer(), c)
    fun decode(raw: String): DraftContent? = runCatching { json.decodeFromString(DraftContent.serializer(), raw) }.getOrNull()
}
