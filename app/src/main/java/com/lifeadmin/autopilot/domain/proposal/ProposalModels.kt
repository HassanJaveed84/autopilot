package com.lifeadmin.autopilot.domain.proposal

import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * One proposal model for every source (planning, research or a ChatGPT handoff) and every target (draft or task),
 * per handoff §4.4. Nothing here writes anything: a proposal becomes a change only through
 * TaskRepository.applyProposalSet after an explicit accept (PLAN-071, INT-050).
 */

enum class ProposalSource {
    PLANNING,
    RESEARCH,

    /** Decisions, steps and notes the user sent back from a ChatGPT conversation (an `ap=v1` App Link). */
    HANDOFF,
}

enum class TargetKind { DRAFT, TASK }

enum class SetStatus {
    /** Items may still be reviewed and applied. Research sets stay open while items are pending (OD-1). */
    OPEN,

    /** Planning sets close on apply; research sets close when nothing is pending. */
    CLOSED,

    /** The target is gone or the premises were withdrawn; kept and shown struck through. */
    OBSOLETE,
}

enum class ItemStatus { PENDING, APPLIED, DECLINED, OBSOLETE }

/** What a single proposed change does to its target. Priority is never proposed (PLAN-070, handoff §7). */
enum class ItemKind {
    ADD_STEP,
    EDIT_STEP,
    REMOVE_STEP,
    APPEND_NOTE,
    SET_NOTES,
    SET_TITLE,
    /** Allowed only when the target has no deadline: user-stated deadlines are never altered (OD-6). */
    SET_DEADLINE,
    SET_EVENT_DATE,
    /** A planning target with its basis; stored apart from the deadline (PLAN-041). */
    SET_TARGET,
    SET_REMINDER,
    SET_STATUS,
    ;

    val touchesSteps: Boolean get() = this == ADD_STEP || this == EDIT_STEP || this == REMOVE_STEP
}

@Serializable
sealed class ItemValue {
    /** A step's wording; [afterStepId] anchors an insertion (a ticked anchor is a note, not a conflict). */
    @Serializable
    @SerialName("step")
    data class Step(
        val text: String,
        val origin: ContentOrigin = ContentOrigin.PLANNING,
        /** A "verify this" item from an indicative or contested finding (INT-052). */
        val verify: Boolean = false,
        /** The edited step is an assumption the finding superseded (INT-052). */
        val supersedes: Boolean = false,
        val afterStepId: String? = null,
    ) : ItemValue()

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ItemValue()

    /** ISO date (+ optional HH:mm). [basis] states the derivation, shown next to the value (PLAN-041/044). */
    @Serializable
    @SerialName("date")
    data class Date(val date: String, val time: String? = null, val basis: String = "", val label: String? = null) : ItemValue()

    /** Mirrors the policy editor; [kind] is a ReminderKind name. */
    @Serializable
    @SerialName("reminder")
    data class Reminder(
        val kind: String,
        val times: List<String> = emptyList(),
        val dayInterval: Int = 1,
        val startDate: String? = null,
        /** ISO local date-time for a one-time reminder. */
        val onceAt: String? = null,
        val basis: String = "",
    ) : ItemValue()

    /** [status] is a TaskStatus name; [checkInAt] an ISO local date-time. */
    @Serializable
    @SerialName("status")
    data class Status(val status: String, val reason: String? = null, val checkInAt: String? = null) : ItemValue()

    @Serializable
    @SerialName("none")
    data object None : ItemValue()
}

/** A premise the item relies on and the finding refs that support it (handoff §8). */
@Serializable
data class Premise(val text: String, val refs: List<String> = emptyList())

/** One supporting finding with its status and as-of date at the time of the proposal (INT-033). */
@Serializable
data class FindingRef(
    val ref: String,
    val turnId: String,
    val findingId: String,
    val text: String,
    val status: String,
    val asOf: String? = null,
    /** Evidence (source) keys the finding resolves to (RES-091). */
    val keys: List<String> = emptyList(),
    /** Reused from an earlier turn without a re-check. */
    val reused: Boolean = false,
)

/** `src` on a research item: claim → findings → evidence keys. Copied unchanged into provenance. */
@Serializable
data class ItemSrc(
    val premises: List<Premise> = emptyList(),
    val findings: List<FindingRef> = emptyList(),
    /** The weakest supporting status. */
    val status: String = "",
    val asOf: String? = null,
)

@Serializable
data class ProposalItem(
    val id: String,
    val kind: ItemKind,
    /** The existing step an EDIT/REMOVE targets. */
    val anchorStepId: String? = null,
    val value: ItemValue,
    /** Judgment, never cited (RES-085). */
    val why: String = "",
    /** Picked by "Select recommended" unless material or conflicting (handoff §7). */
    val recommended: Boolean = false,
    /** Needs its own accept and is never bulk-selected (PLAN-072). */
    val material: Boolean = false,
    val status: ItemStatus = ItemStatus.PENDING,
    val statusReason: String? = null,
    val src: ItemSrc? = null,
)

@Serializable
data class StepSnapshot(val id: String, val sortOrder: Int, val title: String, val isDone: Boolean, val origin: String = "USER", val provenanceId: String? = null)

@Serializable
data class ReminderSnapshot(
    val enabled: Boolean,
    val kind: String,
    val times: List<String>,
    val dayInterval: Int,
    val startDate: String? = null,
    val onceAtMs: Long? = null,
    val lastEditedAtMs: Long,
)

/** Everything a proposal can conflict with, as the target was when the set was made or last rebased. */
@Serializable
data class TargetSnapshot(
    val rev: Int,
    val title: String,
    val notes: String? = null,
    val status: String,
    val deadlineType: String = "NONE",
    val deadlineDate: String? = null,
    val deadlineAtMs: Long? = null,
    val eventDate: String? = null,
    val eventLabel: String? = null,
    val intendedDate: String? = null,
    val targetDate: String? = null,
    val targetBasis: String? = null,
    val snoozedUntilMs: Long? = null,
    val waitingReason: String? = null,
    val waitingCheckInAtMs: Long? = null,
    val steps: List<StepSnapshot> = emptyList(),
    val reminder: ReminderSnapshot? = null,
    val takenAtMs: Long,
) {
    val hasDeadline: Boolean get() = deadlineType != DeadlineType.NONE.name
    fun step(id: String?): StepSnapshot? = id?.let { s -> steps.firstOrNull { it.id == s } }

    companion object {
        fun of(task: Task, steps: List<TaskStep>, policy: ReminderPolicy?, nowMs: Long): TargetSnapshot = TargetSnapshot(
            rev = task.rev, title = task.title, notes = task.notes, status = task.status.name,
            deadlineType = task.deadlineType.name, deadlineDate = task.deadlineLocalDate?.toString(), deadlineAtMs = task.deadlineInstant?.toEpochMilli(),
            eventDate = task.eventLocalDate?.toString(), eventLabel = task.eventLabel, intendedDate = task.intendedLocalDate?.toString(),
            targetDate = task.targetLocalDate?.toString(), targetBasis = task.targetBasis, snoozedUntilMs = task.snoozedUntil?.toEpochMilli(),
            waitingReason = task.waitingReason, waitingCheckInAtMs = task.waitingCheckInAt?.toEpochMilli(),
            steps = steps.sortedBy { it.sortOrder }.map { StepSnapshot(it.id, it.sortOrder, it.title, it.isDone, it.origin.name, it.provenanceId) },
            reminder = policy?.let {
                ReminderSnapshot(it.enabled, it.kind.name, it.times.map { t -> t.toString() }, it.dayInterval, it.startLocalDate?.toString(), it.atInstant?.toEpochMilli(), it.lastEditedAt.toEpochMilli())
            },
            takenAtMs = nowMs,
        )
    }
}

/** Enough of a reminder policy to put it back on undo. */
@Serializable
data class PolicyRestore(
    val id: String,
    val enabled: Boolean,
    val kind: String,
    val times: List<String>,
    val dayInterval: Int,
    val startDate: String?,
    val zoneMode: String,
    val zoneId: String?,
    val quietHoursBehavior: String?,
    val snoozeDefaultMinutes: Int?,
    val atInstantMs: Long?,
) {
    companion object {
        fun of(p: ReminderPolicy) = PolicyRestore(
            p.id, p.enabled, p.kind.name, p.times.map { it.toString() }, p.dayInterval, p.startLocalDate?.toString(), p.zoneMode.name, p.zoneId,
            p.quietHoursBehavior?.name, p.snoozeDefaultMinutes, p.atInstant?.toEpochMilli(),
        )
    }
}

/** What one apply did, kept on the set so it can be undone while the task has not moved on. */
@Serializable
data class ApplyRecord(
    val atMs: Long,
    val revBefore: Int,
    val revAfter: Int,
    val appliedItemIds: List<String>,
    val declinedItemIds: List<String> = emptyList(),
    val addedStepIds: List<String> = emptyList(),
    val provenanceIds: List<String> = emptyList(),
    /** The target exactly before this apply (also the history snapshot, INT-054). */
    val previous: TargetSnapshot,
    val previousPolicy: PolicyRestore? = null,
)

@Serializable
data class ProposalSet(
    val id: String,
    val source: ProposalSource,
    val targetKind: TargetKind,
    val targetId: String,
    val investigationId: String? = null,
    val turnId: String? = null,
    val madeAtMs: Long,
    /** Model that produced it (PLAN-094 / RES-123); blank for app-generated items. */
    val model: String = "",
    val status: SetStatus = SetStatus.OPEN,
    /** The snapshot the proposal was made against. Never changes (OD-2). */
    val baseOriginal: TargetSnapshot,
    /** Rebased after each partial apply so remaining items reconcile against what the user saw (OD-2). */
    val base: TargetSnapshot,
    val items: List<ProposalItem>,
    val lastApply: ApplyRecord? = null,
    /** Short heading for the review ("Plan next step", "From research: Thorne & Pure picks"). */
    val title: String = "",
    /** Explanations stay collapsed in review: changes first (handoff §7 rule 1). */
    val advice: String = "",
    val assumptions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
) {
    val pending: List<ProposalItem> get() = items.filter { it.status == ItemStatus.PENDING }
    fun item(id: String): ProposalItem? = items.firstOrNull { it.id == id }
}

enum class ElementKind { STEP, NOTES, TITLE, DEADLINE, EVENT_DATE, TARGET, REMINDER, STATUS }

/** Provenance of an applied element (INT-030–033, handoff §4.5). Survives edits and the investigation's deletion. */
@Serializable
data class ProvenanceRecord(
    val id: String,
    val taskId: String? = null,
    val draftId: String? = null,
    val elementKind: ElementKind,
    /** Step id for steps; null for task-level fields. */
    val elementId: String? = null,
    val source: ProposalSource,
    val proposalSetId: String,
    val itemId: String,
    val investigationId: String? = null,
    val turnId: String? = null,
    val appliedAtMs: Long,
    /** The user edited the element after it was applied; the origin stays (INT-030). */
    val editedByUser: Boolean = false,
    val editedAtMs: Long? = null,
    /** Copied unchanged from the item (handoff §4.5). */
    val src: ItemSrc? = null,
    /** The applied value, so the record stands alone after the investigation is gone. */
    val appliedText: String = "",
    val model: String = "",
)

object ProposalJson {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    fun encodeSet(set: ProposalSet): String = json.encodeToString(ProposalSet.serializer(), set)
    fun decodeSet(raw: String): ProposalSet? = runCatching { json.decodeFromString(ProposalSet.serializer(), raw) }.getOrNull()
    fun encodeRecord(r: ProvenanceRecord): String = json.encodeToString(ProvenanceRecord.serializer(), r)
    fun decodeRecord(raw: String): ProvenanceRecord? = runCatching { json.decodeFromString(ProvenanceRecord.serializer(), raw) }.getOrNull()
}
