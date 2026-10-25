package com.lifeadmin.autopilot.domain.usecase

import android.content.Context
import androidx.core.content.edit
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Gentle options offered when a task seems stuck. Each opens an existing, user-confirmed editor. */
enum class StallAction { BREAK_DOWN, MOVE_DATE, MARK_WAITING, PAUSE, CHECK_IN }

data class StallHint(
    val message: String,
    val actions: List<StallAction>,
    /** Identifies the signal level; a dismissed hint returns only when this grows. */
    val signature: String,
)

/**
 * Deterministic, local stall detection from the task's own activity log. No network, no AI,
 * no background work, and it never changes anything — it only suggests.
 */
object StallDetector {
    const val SNOOZE_THRESHOLD = 3
    const val REMINDERS_WITHOUT_PROGRESS = 6
    private val WINDOW: Duration = Duration.ofDays(14)
    private val OVERDUE_GRACE: Duration = Duration.ofDays(2)
    private val CHECK_IN_GRACE: Duration = Duration.ofDays(3)
    // "Done for tonight" on a routine is progress (that cycle was completed), never a snooze.
    private val progressEvents = setOf(EventType.STEP_COMPLETED, EventType.EDITED, EventType.POLICY_EDITED, EventType.STATUS_CHANGED, EventType.REOPENED, EventType.CREATED, EventType.OCCURRENCE_DONE)

    fun detect(d: TaskDetails, now: Instant, zone: ZoneId): StallHint? {
        val t = d.task
        if (t.status.isTerminal || t.status == TaskStatus.PAUSED || t.archivedAt != null) return null
        val since = now.minus(WINDOW)
        val hasDeadline = t.deadlineType != DeadlineType.NONE

        if (t.status == TaskStatus.WAITING) {
            val checkIn = t.waitingCheckInAt ?: return null
            if (now.isAfter(checkIn.plus(CHECK_IN_GRACE))) {
                val days = Duration.between(checkIn, now).toDays()
                return StallHint("You planned to check in $days days ago. Still waiting, or ready to continue?", listOf(StallAction.CHECK_IN, StallAction.PAUSE), "checkin:$days")
            }
            return null
        }

        // Progress (a completed step, an edit, a status change) resets both signals.
        val lastProgress = d.events.filter { it.type in progressEvents }.maxOfOrNull { it.at } ?: t.createdAt
        val snoozeFrom = if (lastProgress.isAfter(since)) lastProgress else since
        val snoozes = d.events.count { it.type == EventType.SNOOZED && it.at.isAfter(snoozeFrom) }
        if (snoozes >= SNOOZE_THRESHOLD) {
            return StallHint(
                "You've snoozed this $snoozes times in the last two weeks. A smaller first step, a different date, or pausing it might help.",
                listOfNotNull(StallAction.BREAK_DOWN, StallAction.MOVE_DATE.takeIf { hasDeadline }, StallAction.MARK_WAITING, StallAction.PAUSE),
                "snooze:$snoozes",
            )
        }

        val remindersSince = d.events.count { it.type == EventType.REMINDER_DELIVERED && it.at.isAfter(lastProgress) }
        if (remindersSince >= REMINDERS_WITHOUT_PROGRESS) {
            return StallHint(
                "You've been reminded $remindersSince times since the last change. Is something blocking this?",
                listOfNotNull(StallAction.BREAK_DOWN, StallAction.MARK_WAITING, StallAction.MOVE_DATE.takeIf { hasDeadline }, StallAction.PAUSE),
                "reminded:$remindersSince",
            )
        }

        val due = when (t.deadlineType) {
            DeadlineType.EXACT -> t.deadlineInstant
            DeadlineType.DATE_ONLY -> t.deadlineLocalDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
            DeadlineType.NONE -> null
        }
        if (due != null && now.isAfter(due.plus(OVERDUE_GRACE))) {
            val days = Duration.between(due, now).toDays()
            return StallHint("This is $days days past its deadline. Move the date, or break it into something smaller?", listOf(StallAction.MOVE_DATE, StallAction.BREAK_DOWN, StallAction.PAUSE), "overdue:$days")
        }
        return null
    }
}

/** Remembers dismissed hints (per task + signal level) so they don't nag. */
interface HintDismissals {
    fun isDismissed(taskId: String, signature: String): Boolean
    fun dismiss(taskId: String, signature: String)
}

class SharedPrefsHintDismissals(context: Context) : HintDismissals {
    private val prefs = context.getSharedPreferences("stall_hints", Context.MODE_PRIVATE)
    override fun isDismissed(taskId: String, signature: String) = prefs.getString(taskId, null) == signature
    override fun dismiss(taskId: String, signature: String) = prefs.edit { putString(taskId, signature) }
}

class InMemoryHintDismissals : HintDismissals {
    private val map = mutableMapOf<String, String>()
    override fun isDismissed(taskId: String, signature: String) = map[taskId] == signature
    override fun dismiss(taskId: String, signature: String) { map[taskId] = signature }
}
