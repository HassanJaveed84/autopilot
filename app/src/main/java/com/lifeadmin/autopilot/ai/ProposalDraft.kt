package com.lifeadmin.autopilot.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Where a value in a proposal came from. Drives labels and what is pre-selected. */
@Serializable
enum class Provenance {
    /** The user's own words state it. Pre-selected. */
    EXPLICIT,

    /** Derived from vague wording. Shown as a suggestion, not pre-selected. */
    INFERRED,

    /** The model's own idea (e.g. reminders the user didn't ask for). Not pre-selected. */
    SUGGESTED,

    /** No usable value. */
    UNKNOWN,
}

@Serializable
enum class DraftReminderKind { OFF, ONCE, DAILY, EVERY_N_DAYS }

@Serializable
data class DraftStep(val text: String, val include: Boolean = true)

@Serializable
data class DraftDeadline(
    val provenance: Provenance = Provenance.UNKNOWN,
    /** ISO yyyy-MM-dd */
    val date: String? = null,
    /** HH:mm, null = date-only deadline */
    val time: String? = null,
    val sourcePhrase: String = "",
    val apply: Boolean = false,
) {
    val localDate: LocalDate? get() = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val localTime: LocalTime? get() = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
}

/** A schedule the engine can't express, with the closest supported alternative. */
@Serializable
data class UnsupportedSchedule(
    val description: String,
    val alternativeKind: DraftReminderKind,
    val alternativeTimes: List<String>,
    val alternativeInterval: Int = 1,
    val alternativeLabel: String,
)

@Serializable
data class DraftReminder(
    val kind: DraftReminderKind = DraftReminderKind.OFF,
    /** HH:mm, sorted, distinct */
    val times: List<String> = emptyList(),
    val dayInterval: Int = 1,
    /** ISO date of the first recurring day; null = today. */
    val startDate: String? = null,
    /** ISO local date-time for ONCE. */
    val onceAt: String? = null,
    val provenance: Provenance = Provenance.UNKNOWN,
    val requestedPhrase: String = "",
    val apply: Boolean = false,
    val unsupported: UnsupportedSchedule? = null,
) {
    val localTimes: List<LocalTime> get() = times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.distinct().sorted()
    val onceLocal: LocalDateTime? get() = onceAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val startLocalDate: LocalDate? get() = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

@Serializable
data class DraftWaiting(
    val apply: Boolean = false,
    val waitingFor: String = "",
    /** ISO local date-time of an explicit check-in, if the user gave one. */
    val checkIn: String? = null,
) {
    val checkInLocal: LocalDateTime? get() = checkIn?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
}

@Serializable
enum class NoticeLevel { INFO, WARNING }

@Serializable
data class Notice(val level: NoticeLevel, val text: String)

/**
 * The editable, reviewable form of an AI proposal. Nothing here touches the database; it is
 * turned into a write only by [ProposalMapper] after the user approves.
 */
@Serializable
data class ProposalDraft(
    /** Also the new task's id, which makes approval idempotent. */
    val proposalId: String,
    val mode: PlanningMode,
    val originalText: String,
    val title: String,
    val notes: String = "",
    val steps: List<DraftStep> = emptyList(),
    val nextStep: String = "",
    val addNextStep: Boolean = false,
    val deadline: DraftDeadline = DraftDeadline(),
    val reminder: DraftReminder = DraftReminder(),
    val waiting: DraftWaiting = DraftWaiting(),
    /** Priority enum name, null = no suggestion. */
    val priority: String? = null,
    val priorityReason: String = "",
    val applyPriority: Boolean = false,
    val assumptions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val ignoredInstructions: List<String> = emptyList(),
    val notices: List<Notice> = emptyList(),
    val advice: String = "",
    val model: String = "",
    /** FOLLOW_THROUGH only: the task this proposal edits and its updatedAt when proposed. */
    val targetTaskId: String? = null,
    val targetUpdatedAtMillis: Long? = null,
    /** FOLLOW_THROUGH only: whether to replace the title / notes. */
    val applyTitle: Boolean = false,
    val applyNotes: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(raw: String): ProposalDraft? = runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}
