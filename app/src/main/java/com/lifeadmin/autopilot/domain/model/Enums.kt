package com.lifeadmin.autopilot.domain.model

/** Lifecycle status of a task. Snooze is NOT a status; see [Task.snoozedUntil]. */
enum class TaskStatus {
    INBOX,
    ACTIONABLE,
    WAITING,
    PAUSED,
    DONE,
    CANCELED;

    val isTerminal: Boolean get() = this == DONE || this == CANCELED
}

enum class Priority { LOW, NORMAL, HIGH }

enum class DeadlineType { NONE, DATE_ONLY, EXACT }

enum class ReminderKind { ONCE, DAILY_TIMES, EVERY_N_DAYS }

enum class ZoneMode { FOLLOW_DEVICE, FIXED_ZONE }

enum class QuietHoursBehavior { DEFER_AND_COALESCE, SKIP }

/** Why an occurrence exists. All kinds share the task's single policy row and generation. */
enum class OccurrenceKind {
    /** Generated from the task's recurring/once reminder policy. */
    POLICY,

    /** Explicit check-in for a WAITING task. */
    CHECK_IN,

    /** One-time reminder when a snooze ends (user-visible choice). */
    SNOOZE_END,
}

enum class OccurrenceStatus {
    PLANNED,
    SCHEDULED,
    DELIVERED,
    SUPPRESSED,
    MISSED,
    CANCELED;

    /** Ledger entries that must never be delivered again for the same planned instant. */
    val isSettled: Boolean get() = this == DELIVERED || this == SUPPRESSED || this == MISSED
}

enum class EventType {
    CREATED,
    EDITED,
    STATUS_CHANGED,
    REOPENED,
    SNOOZED,
    SNOOZE_CLEARED,
    POLICY_EDITED,
    STEP_ADDED,
    STEP_COMPLETED,
    STEP_REOPENED,
    STEP_REMOVED,
    REMINDER_DELIVERED,
    REMINDER_SUPPRESSED,
    REMINDER_MISSED,
    ARCHIVED,
    UNARCHIVED,
    IMPORTED,
}

/** Default snooze applied by the notification "Snooze" action. */
enum class SnoozePreset { ONE_HOUR, THREE_HOURS, TOMORROW_MORNING }

/** Presets only pre-populate an editable policy; the user owns the resulting schedule. */
enum class ReminderPreset { GENTLE, DAILY, PERSISTENT }

/** Theme preference. SYSTEM follows the phone's dark-mode setting (the default). */
enum class Appearance { SYSTEM, LIGHT, DARK }
