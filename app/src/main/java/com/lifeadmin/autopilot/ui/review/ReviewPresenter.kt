package com.lifeadmin.autopilot.ui.review

import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure text for the review rows: what the task says now against what is proposed. */
object ReviewPresenter {
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.ENGLISH)

    fun kindLabel(item: ProposalItem): String = when (item.kind) {
        ItemKind.ADD_STEP -> if ((item.value as? ItemValue.Step)?.verify == true) "New step to verify" else "New step"
        ItemKind.EDIT_STEP -> "Step wording"
        ItemKind.REMOVE_STEP -> "Remove step"
        ItemKind.APPEND_NOTE -> "Add to notes"
        ItemKind.SET_NOTES -> "Notes"
        ItemKind.SET_TITLE -> "Title"
        ItemKind.SET_DEADLINE -> "Deadline"
        ItemKind.SET_EVENT_DATE -> "Event date"
        ItemKind.SET_TARGET -> "Planning target"
        ItemKind.SET_REMINDER -> "Reminders"
        ItemKind.SET_STATUS -> "Status"
    }

    /** The task as it is now, for the field the item touches; null when there is nothing to compare against. */
    fun currentText(item: ProposalItem, current: TargetSnapshot, zone: ZoneId): String? = when (item.kind) {
        ItemKind.ADD_STEP -> null
        ItemKind.EDIT_STEP, ItemKind.REMOVE_STEP -> current.step(item.anchorStepId)?.title ?: "(step no longer exists)"
        ItemKind.APPEND_NOTE -> null
        ItemKind.SET_NOTES -> current.notes?.takeIf { it.isNotBlank() } ?: "(no notes)"
        ItemKind.SET_TITLE -> current.title
        ItemKind.SET_DEADLINE -> when {
            current.deadlineAtMs != null -> Instant.ofEpochMilli(current.deadlineAtMs).atZone(zone).toLocalDateTime().format(dateTimeFmt)
            current.deadlineDate != null -> LocalDate.parse(current.deadlineDate).format(dateFmt)
            else -> "No deadline"
        }
        ItemKind.SET_EVENT_DATE -> current.eventDate?.let { LocalDate.parse(it).format(dateFmt) + (current.eventLabel?.let { l -> " · $l" } ?: "") } ?: "No event date"
        ItemKind.SET_TARGET -> current.targetDate?.let { LocalDate.parse(it).format(dateFmt) + (current.targetBasis?.let { b -> " · $b" } ?: "") } ?: "No planning target"
        ItemKind.SET_REMINDER -> current.reminder?.takeIf { it.enabled }?.let { r ->
            when (r.kind) {
                ReminderKind.ONCE.name -> "Once" + (r.onceAtMs?.let { " · " + Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().format(dateTimeFmt) } ?: "")
                ReminderKind.EVERY_N_DAYS.name -> "Every ${r.dayInterval} days at ${r.times.joinToString(", ")}"
                else -> "Daily at ${r.times.joinToString(", ")}"
            }
        } ?: "No reminders"
        ItemKind.SET_STATUS -> statusLabel(current.status) + (current.waitingReason?.let { " · $it" } ?: "")
    }

    fun proposedText(item: ProposalItem, value: ItemValue, zone: ZoneId): String = when (value) {
        is ItemValue.Step -> value.text
        is ItemValue.Text -> value.text
        is ItemValue.Date -> {
            val date = runCatching { LocalDate.parse(value.date) }.getOrNull()
            val time = value.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val when_ = when {
                date == null -> value.date
                time != null -> LocalDateTime.of(date, time).format(dateTimeFmt)
                else -> date.format(dateFmt)
            }
            listOfNotNull(value.label, when_).joinToString(" · ") + (value.basis.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        }
        is ItemValue.Reminder -> when (value.kind) {
            ReminderKind.ONCE.name -> "Once" + (value.onceAt?.let { " · " + runCatching { LocalDateTime.parse(it).format(dateTimeFmt) }.getOrDefault(it) } ?: "")
            ReminderKind.EVERY_N_DAYS.name -> "Every ${value.dayInterval} days at ${value.times.joinToString(", ")}"
            else -> "Daily at ${value.times.joinToString(", ")}"
        } + (value.basis.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        is ItemValue.Status -> statusLabel(value.status) + (value.reason?.let { " · $it" } ?: "") + (value.checkInAt?.let { " · check in " + runCatching { LocalDateTime.parse(it).format(dateTimeFmt) }.getOrDefault(it) } ?: "")
        ItemValue.None -> if (item.kind == ItemKind.REMOVE_STEP) "Removed" else ""
    }

    /** Text kinds the review lets the user edit inline before accepting (PLAN-071). */
    fun isTextEditable(item: ProposalItem): Boolean = item.kind == ItemKind.ADD_STEP || item.kind == ItemKind.EDIT_STEP || item.kind == ItemKind.APPEND_NOTE || item.kind == ItemKind.SET_NOTES || item.kind == ItemKind.SET_TITLE
    fun isDateEditable(item: ProposalItem): Boolean = item.value is ItemValue.Date

    fun withText(value: ItemValue, text: String): ItemValue = when (value) {
        is ItemValue.Step -> value.copy(text = text)
        is ItemValue.Text -> value.copy(text = text)
        else -> value
    }

    fun withDate(value: ItemValue, date: LocalDate): ItemValue = when (value) {
        is ItemValue.Date -> value.copy(date = date.toString())
        else -> value
    }

    fun editableText(value: ItemValue): String? = when (value) {
        is ItemValue.Step -> value.text
        is ItemValue.Text -> value.text
        else -> null
    }

    fun statusLabel(name: String): String = TaskStatus.entries.firstOrNull { it.name == name }?.let {
        when (it) {
            TaskStatus.INBOX -> "Inbox"; TaskStatus.ACTIONABLE -> "Actionable"; TaskStatus.WAITING -> "Waiting"
            TaskStatus.PAUSED -> "Paused"; TaskStatus.DONE -> "Done"; TaskStatus.CANCELED -> "Canceled"
        }
    } ?: name
}
