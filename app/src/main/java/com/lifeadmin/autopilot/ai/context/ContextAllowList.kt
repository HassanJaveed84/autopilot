package com.lifeadmin.autopilot.ai.context

import com.lifeadmin.autopilot.ai.PlanningContext
import com.lifeadmin.autopilot.ai.TaskSnapshot
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.data.repository.label
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/*
 * Allow-listed context serialization (PLAN-080/081, INT-012, INT-020–022, INT-064, RES-160/161,
 * handoff §13 "Privacy-safe context serialization"). Every paid operation builds its payload here,
 * from an explicit list of included items, and the same frozen JSON is what the authorization
 * surface shows ("See exactly what's sent") and what the adapter sends. Nothing about other tasks,
 * other drafts, other investigations, excluded items or the API key can appear, because none of
 * them is an input.
 */

/** The exact bytes an operation will transmit, with a fingerprint the ledger and the turn record keep. */
data class FrozenPayload(val json: String, val sha256: String, val lines: List<String>) {
    companion object {
        fun of(json: String, lines: List<String>): FrozenPayload = FrozenPayload(json, sha256(json), lines)
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A current finding the user ticked for THIS planning request (INT-022). Never persisted as a selection. */
@Serializable
data class SelectedFinding(val investigationId: String, val turnId: String, val findingId: String, val text: String, val status: String, val asOf: String? = null)

@Serializable
data class TimingPreferences(val nowLocal: String, val zoneId: String, val utcOffset: String, val quietHours: List<String> = emptyList(), val defaultReminderTimes: List<String> = emptyList())

@Serializable
data class TaskPayload(
    val title: String,
    val notes: String? = null,
    val status: String,
    /** Read-only: priority is never proposed (PLAN-070). */
    val priority: String,
    val steps: List<StepPayload> = emptyList(),
    val deadline: String? = null,
    val eventDate: String? = null,
    val eventLabel: String? = null,
    val intendedDate: String? = null,
    val planningTarget: String? = null,
    val reminders: String,
    val waitingFor: String? = null,
)

@Serializable
data class StepPayload(val text: String, val done: Boolean)

/** Activity signals (PLAN-075): present only when the user leaves the switch on. */
@Serializable
data class ActivitySignals(val ageDays: Long, val snoozesLast14Days: Int)

@Serializable
data class PlanningPayload(
    val operation: String,
    val text: String,
    val timing: TimingPreferences,
    val task: TaskPayload? = null,
    val signals: ActivitySignals? = null,
    val findings: List<SelectedFinding> = emptyList(),
)

object PlanningContextBuilder {
    private val json = Json { encodeDefaults = false; prettyPrint = true }

    fun timing(c: PlanningContext) = TimingPreferences(
        nowLocal = c.nowLocal.toString(), zoneId = c.zoneId, utcOffset = c.utcOffset,
        quietHours = c.quietHours?.let { listOf(it.first.toString(), it.second.toString()) } ?: emptyList(),
        defaultReminderTimes = c.defaultReminderTimes.map { it.toString() },
    )

    /** New-draft planning sends the input text and timing preferences, nothing else (INT-023). */
    fun newDraft(text: String, context: PlanningContext): FrozenPayload {
        val body = PlanningPayload(operation = "plan_new_draft", text = text, timing = timing(context))
        return FrozenPayload.of(json.encodeToString(PlanningPayload.serializer(), body), listOf("Your text", "Date, time zone and reminder preferences"))
    }

    /** The one task plus, when switched on, its activity signals, plus only the findings ticked for this request. */
    fun task(details: TaskDetails, text: String, context: PlanningContext, now: Instant, zone: ZoneId, includeSignals: Boolean, findings: List<SelectedFinding>): FrozenPayload {
        val t = details.task
        val task = TaskPayload(
            title = t.title, notes = t.notes?.takeIf { it.isNotBlank() }, status = t.status.label(), priority = t.priority.name,
            steps = details.steps.map { StepPayload(it.title, it.isDone) },
            deadline = deadlineText(details, zone), eventDate = t.eventLocalDate?.toString(), eventLabel = t.eventLabel,
            intendedDate = t.intendedLocalDate?.toString(), planningTarget = t.targetLocalDate?.let { d -> d.toString() + (t.targetBasis?.let { " ($it)" } ?: "") },
            reminders = PolicyDescriber.describe(details.policy, zone), waitingFor = t.waitingReason,
        )
        val body = PlanningPayload(
            operation = "plan_existing_task", text = text, timing = timing(context), task = task,
            signals = if (includeSignals) signals(details, now) else null, findings = findings,
        )
        val lines = buildList {
            add("This task: title, notes, status, steps, dates and reminders (priority read-only)")
            if (text.isNotBlank()) add("Your note to the planner")
            if (includeSignals) add("Activity signals: age and recent snoozes") else add("Activity signals: not sent")
            if (findings.isNotEmpty()) add("${findings.size} research finding(s) you ticked for this request") else add("No research findings")
            add("Date, time zone and reminder preferences")
        }
        return FrozenPayload.of(json.encodeToString(PlanningPayload.serializer(), body), lines)
    }

    /** The provider-facing snapshot, built from the same allow-list so the prompt can never see more than the payload shows. */
    fun snapshot(details: TaskDetails, now: Instant, zone: ZoneId, includeSignals: Boolean = true): TaskSnapshot {
        val sig = if (includeSignals) signals(details, now) else null
        return TaskSnapshot(
            title = details.task.title,
            notes = details.task.notes,
            status = details.task.status.label(),
            steps = details.steps.map { it.title to it.isDone },
            deadline = deadlineText(details, zone),
            reminders = PolicyDescriber.describe(details.policy, zone),
            ageDays = sig?.ageDays ?: -1,
            snoozesLast14Days = sig?.snoozesLast14Days ?: -1,
            waitingFor = details.task.waitingReason,
        )
    }

    fun signals(details: TaskDetails, now: Instant): ActivitySignals {
        val since = now.minus(Duration.ofDays(14))
        return ActivitySignals(
            ageDays = Duration.between(details.task.createdAt, now).toDays(),
            snoozesLast14Days = details.events.count { it.type == EventType.SNOOZED && it.at.isAfter(since) },
        )
    }

    private fun deadlineText(d: TaskDetails, zone: ZoneId): String? = when (d.task.deadlineType) {
        DeadlineType.NONE -> null
        DeadlineType.DATE_ONLY -> d.task.deadlineLocalDate?.toString()
        DeadlineType.EXACT -> d.task.deadlineInstant?.atZone(zone)?.toLocalDateTime()?.toString()
    }
}

// ------------------------------------------------------------------ research turns

/** One context item on the authorization card; only included items reach the payload (RES-161, OD-4). */
data class ContextItem(val id: String, val label: String, val value: String, val included: Boolean = true, val requestOnly: Boolean = false)

enum class FindingDisposition {
    /** Needed by the new objective; reused as found, not re-checked. */
    SENT,

    /** Unticked by the user before authorization; never reinserted (OD-4). */
    EXCLUDED,

    /** Irrelevant to the new objective. */
    NOT_SENT,

    /** Answered by the new input. */
    REPLACED,

    /** Volatile: checked fresh, never reused. */
    FRESH,
}

data class InheritedFinding(val id: String, val text: String, val status: String, val asOf: String? = null, val disposition: FindingDisposition, val reason: String = "")

@Serializable
data class TurnConfig(val model: String, val effort: String? = null, val depth: String, val searchBudget: Int, val spendLimitCents: Int? = null, val auto: Boolean = false)

@Serializable
data class TurnContextPayload(val label: String, val value: String, val requestOnly: Boolean = false)

@Serializable
data class SentFinding(val id: String, val text: String, val status: String, val asOf: String? = null, val reused: Boolean = true)

@Serializable
data class TurnPayload(
    val objective: String,
    val constraints: List<String> = emptyList(),
    val context: List<TurnContextPayload> = emptyList(),
    /** Sent findings only. */
    val inherited_findings: List<SentFinding> = emptyList(),
    val fresh_checks: List<String> = emptyList(),
    /** Counts only: no content for anything that is not sent (handoff §8). */
    val earlier_findings_not_sent: Int = 0,
    val excluded_by_you: Int = 0,
    val config: TurnConfig,
)

data class TurnInput(
    val objective: String,
    val constraints: List<String> = emptyList(),
    val context: List<ContextItem> = emptyList(),
    val inherited: List<InheritedFinding> = emptyList(),
    val freshChecks: List<String> = emptyList(),
    val config: TurnConfig,
)

object InvestigationTurnBuilder {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    fun build(input: TurnInput): FrozenPayload {
        val body = TurnPayload(
            objective = input.objective.trim(),
            constraints = input.constraints.map { it.trim() }.filter { it.isNotEmpty() },
            context = input.context.filter { it.included }.map { TurnContextPayload(it.label, it.value, it.requestOnly) },
            inherited_findings = input.inherited.filter { it.disposition == FindingDisposition.SENT }.map { SentFinding(it.id, it.text, it.status, it.asOf) },
            fresh_checks = input.freshChecks + input.inherited.filter { it.disposition == FindingDisposition.FRESH }.map { it.text },
            earlier_findings_not_sent = input.inherited.count { it.disposition == FindingDisposition.NOT_SENT || it.disposition == FindingDisposition.REPLACED },
            excluded_by_you = input.inherited.count { it.disposition == FindingDisposition.EXCLUDED },
            config = input.config,
        )
        val lines = buildList {
            add("Your objective" + if (body.constraints.isNotEmpty()) " and ${body.constraints.size} constraint(s)" else "")
            body.context.forEach { add(it.label + if (it.requestOnly) " (this request only)" else "") }
            input.context.filter { !it.included }.forEach { add("Not sent: ${it.label}") }
            if (body.inherited_findings.isNotEmpty()) add("${body.inherited_findings.size} earlier finding(s), reused as found")
            if (body.excluded_by_you > 0) add("${body.excluded_by_you} earlier finding(s) excluded by you")
            if (body.earlier_findings_not_sent > 0) add("${body.earlier_findings_not_sent} earlier finding(s) not needed")
            if (body.fresh_checks.isNotEmpty()) add("${body.fresh_checks.size} item(s) checked fresh")
            add("Configuration: ${body.config.model}, ${body.config.depth}, up to ${body.config.searchBudget} searches")
        }
        return FrozenPayload.of(json.encodeToString(TurnPayload.serializer(), body), lines)
    }

    /** A hand-edited objective that names an excluded item or finding is a disclosure problem the card must show (handoff §6). */
    fun warnings(input: TurnInput): List<String> {
        val text = input.objective.lowercase()
        val out = mutableListOf<String>()
        input.context.filter { !it.included }.forEach { c ->
            if (mentions(text, c.value)) out += "Your objective mentions \"${c.value}\", which you excluded. It will not be sent as context, but the wording itself goes out."
        }
        input.inherited.filter { it.disposition == FindingDisposition.EXCLUDED }.forEach { f ->
            if (mentions(text, f.text)) out += "Your objective refers to an earlier finding you excluded. The finding stays excluded; only your wording goes out."
        }
        return out
    }

    /** A paraphrase counts: most of the item's significant words (at least two) appear in the objective. */
    private fun mentions(objective: String, value: String): Boolean {
        val words = value.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.distinct()
        if (words.isEmpty()) return objective.contains(value.lowercase().trim()) && value.isNotBlank()
        val matched = words.count { objective.contains(it) }
        return matched == words.size || matched >= maxOf(2, (words.size * 0.6 + 0.5).toInt())
    }
}
