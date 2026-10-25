package com.lifeadmin.autopilot.ui.today

import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.scheduling.ReminderProjection
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.TaskPresentation
import com.lifeadmin.autopilot.ui.components.isOverdue
import com.lifeadmin.autopilot.ui.components.isSnoozed
import java.time.Instant
import java.time.ZoneId

/** One row on the Today rail. */
sealed interface RailEntry {
    /** The "Now · 08:42" marker. */
    data class Now(val time: String) : RailEntry

    /**
     * Something that needs attention right now without a scheduled nudge: an overdue deadline,
     * a deadline due today, a waiting check-in that has passed, or a nudge Android has not
     * delivered yet (late within the grace window). Shown as a full card with a label in place
     * of a clock time.
     */
    data class Attention(val summary: TaskSummary, val label: String) : RailEntry

    /** First appearance of a task today: a full card at the time of its next nudge. */
    data class Card(val summary: TaskSummary, val at: Instant, val time: String, val kind: OccurrenceKind) : RailEntry

    /** A later repeat of a task already on the rail: a slim dashed row ("again, if it's still open"). */
    data class Ghost(val summary: TaskSummary, val at: Instant, val time: String) : RailEntry

    /** A waiting task's check-in due today: a slim row (the task itself stays in "Waiting on others"). */
    data class CheckIn(val summary: TaskSummary, val at: Instant, val time: String) : RailEntry
}

data class TodayPlan(
    val rail: List<RailEntry>,
    /**
     * Tasks that need a move from you today: those with a card or an attention row. This is the
     * number in the headline. Waiting tasks whose check-in is still ahead are NOT counted — their
     * check-in sits on the rail as a slim row and is mentioned in [sub] — because nothing is
     * asked of you until the check-in time; once it passes, the task becomes an attention row
     * and counts.
     */
    val tasksToday: Int,
    /** Waiting tasks with a check-in later today (slim rail rows, not counted in [tasksToday]). */
    val checkInsToday: Int,
    val headline: String,
    val sub: String,
    val waiting: List<TaskSummary>,
    val later: List<TaskSummary>,
    val doneToday: List<TaskSummary>,
    val inbox: List<TaskSummary>,
)

/**
 * Pure planner for the Today screen. Rail times come from [ReminderProjection], i.e. the same
 * rules the engine schedules with; attention rows add what has no nudge but needs you now.
 * No task appears twice as a full card.
 */
object TodayPlanner {
    private const val MAX_TODAY_PER_TASK = 8

    fun plan(all: List<TaskSummary>, settings: AppSettings, now: Instant, zone: ZoneId): TodayPlan {
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant()
        val open = all.filter { !it.task.status.isTerminal && it.task.archivedAt == null }
        val inbox = open.filter { it.task.status == TaskStatus.INBOX && it.policy?.enabled != true }.sortedByDescending { it.task.createdAt }

        val attention = mutableListOf<RailEntry.Attention>()
        val timed = mutableListOf<RailEntry>()
        val onRail = linkedSetOf<String>()
        var anyRecurring = false

        for (s in open) {
            val t = s.task
            if (t.status == TaskStatus.PAUSED) continue
            if (t.status == TaskStatus.INBOX && s.policy?.enabled != true) continue

            // Deadlines and passed check-ins: needed now, nudge or not.
            val overdue = t.isOverdue(now, zone)
            val dueToday = when (t.deadlineType) {
                DeadlineType.DATE_ONLY -> t.deadlineLocalDate == today
                DeadlineType.EXACT -> t.deadlineInstant?.let { it > now && it < endOfDay } ?: false
                DeadlineType.NONE -> false
            }
            val checkInDue = t.status == TaskStatus.WAITING && t.waitingCheckInAt?.let { it <= now } == true

            if (t.status == TaskStatus.WAITING) {
                if (checkInDue) { attention += RailEntry.Attention(s, "Check-in due"); onRail += t.id }
                else if (overdue) { attention += RailEntry.Attention(s, "Overdue"); onRail += t.id }
                else t.waitingCheckInAt?.takeIf { it > now && it < endOfDay }?.let { timed += RailEntry.CheckIn(s, it, Format.time(it, zone)) }
                continue
            }

            // Nudges today, from the engine's own planner (first = what Android holds).
            val next = s.nextReminder
            val lateNudge = next != null && next.plannedAt <= now
            val todays = if (t.isSnoozed(now) && t.snoozedUntil!! >= endOfDay) emptyList()
            else ReminderProjection.upcoming(t, s.policy, settings, now, zone, MAX_TODAY_PER_TASK, horizon = endOfDay.minusNanos(1))
            if (todays.any { it.kind == OccurrenceKind.POLICY } && s.policy?.kind != ReminderKind.ONCE) anyRecurring = true

            when {
                lateNudge -> { attention += RailEntry.Attention(s, "Nudge due"); onRail += t.id }
                overdue -> { attention += RailEntry.Attention(s, "Overdue"); onRail += t.id }
                todays.isEmpty() && dueToday -> { attention += RailEntry.Attention(s, if (t.deadlineType == DeadlineType.EXACT) "Due ${Format.time(t.deadlineInstant!!, zone)}" else "Due today"); onRail += t.id }
            }
            todays.forEach { p ->
                val time = Format.time(p.plannedAt, zone)
                if (t.id in onRail) timed += RailEntry.Ghost(s, p.plannedAt, time)
                else { timed += RailEntry.Card(s, p.plannedAt, time, p.kind); onRail += t.id }
            }
        }

        val sortedTimed = timed.sortedBy { entryInstant(it) }
        val rail = buildList {
            if (attention.isNotEmpty() || sortedTimed.isNotEmpty()) add(RailEntry.Now(Format.time(now, zone)))
            addAll(attention.sortedWith(compareBy({ attentionRank(it.label) }, { it.summary.task.title })))
            addAll(sortedTimed)
        }
        val tasksToday = onRail.size
        val checkIns = sortedTimed.filterIsInstance<RailEntry.CheckIn>()

        val waiting = open.filter { it.task.status == TaskStatus.WAITING }.sortedBy { it.task.waitingCheckInAt ?: Instant.MAX }
        val later = open.filter { it.task.status != TaskStatus.WAITING && it.task.id !in onRail && !(it.task.status == TaskStatus.INBOX && it.policy?.enabled != true) && !TaskPresentation.isCycleDone(it, now) }
            .sortedWith(compareBy<TaskSummary> { it.task.status == TaskStatus.PAUSED }.thenBy { it.nextReminder?.plannedAt ?: it.task.snoozedUntil ?: Instant.MAX })
        val doneToday = all.filter { it.task.status == TaskStatus.DONE && it.task.archivedAt == null && it.task.completedAt?.atZone(zone)?.toLocalDate() == today }
            .sortedByDescending { it.task.completedAt } +
            // Routines answered "Done for tonight" are done for this cycle and stay open.
            open.filter { TaskPresentation.isCycleDone(it, now) }

        val headline: String
        val sub: String
        val checkInText = when (checkIns.size) {
            0 -> ""
            1 -> "Check-in on ${checkIns.single().summary.task.title} at ${checkIns.single().time}."
            else -> "${checkIns.size} check-ins, at ${joinList(checkIns.map { it.time }.distinct())}."
        }
        if (tasksToday == 0) {
            headline = "Nothing nudges you today."
            val best = open.filter { it.nextReminder != null }.minByOrNull { it.nextReminder!!.plannedAt }
            sub = checkInText.ifEmpty { best?.let { "Next: ${it.task.title}, ${Format.relativeLower(it.nextReminder!!.plannedAt, now, zone)}." } ?: "Enjoy the quiet." }
        } else {
            headline = "${countWord(tasksToday)} to move today."
            val times = sortedTimed.filter { it !is RailEntry.CheckIn }.map { entryTime(it) }.distinct()
            sub = buildString {
                if (attention.isNotEmpty()) append(if (attention.size == 1) "One needs you now. " else "${attention.size} need you now. ")
                if (times.isNotEmpty()) {
                    append("Nudges at ").append(joinList(times)).append(". ")
                    append(if (anyRecurring) "Each one repeats until it's done. " else "")
                }
                append(checkInText)
            }.trim()
        }
        return TodayPlan(rail, tasksToday, checkIns.size, headline, sub, waiting, later, doneToday, inbox)
    }

    private fun entryInstant(e: RailEntry): Instant = when (e) {
        is RailEntry.Card -> e.at
        is RailEntry.Ghost -> e.at
        is RailEntry.CheckIn -> e.at
        else -> Instant.MIN
    }

    private fun entryTime(e: RailEntry): String = when (e) {
        is RailEntry.Card -> e.time
        is RailEntry.Ghost -> e.time
        is RailEntry.CheckIn -> e.time
        else -> ""
    }

    private fun attentionRank(label: String) = when {
        label.startsWith("Overdue") -> 0
        label.startsWith("Nudge due") -> 1
        label.startsWith("Check-in") -> 2
        else -> 3
    }

    private val words = listOf("Nothing", "One thing", "Two things", "Three things", "Four things", "Five things", "Six things", "Seven things", "Eight things", "Nine things")
    private fun countWord(n: Int) = words.getOrNull(n) ?: "$n things"
    private fun joinList(a: List<String>) = if (a.size <= 1) a.joinToString("") else a.dropLast(1).joinToString(", ") + " and " + a.last()
}
