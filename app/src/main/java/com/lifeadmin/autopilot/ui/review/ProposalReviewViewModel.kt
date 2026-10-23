package com.lifeadmin.autopilot.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.proposal.ApplyChoice
import com.lifeadmin.autopilot.domain.proposal.ApplyResult
import com.lifeadmin.autopilot.domain.proposal.CheckOutcome
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
import com.lifeadmin.autopilot.domain.proposal.ItemCheck
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalReconciler
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** One row of the review. [selected] is the user's explicit choice; nothing starts selected (OD-5). */
data class ReviewRow(
    val item: ProposalItem,
    val check: ItemCheck?,
    val selected: Boolean,
    /** The user's edit, if any; applied in place of the proposed value. Editing never selects. */
    val edited: ItemValue?,
    val resolution: ConflictResolution,
    val label: String,
    val currentText: String?,
    val proposedText: String,
) {
    val value: ItemValue get() = edited ?: item.value
    val outcome: CheckOutcome? get() = check?.outcome
    val blocked: Boolean get() = outcome == CheckOutcome.BLOCKED
    val conflict: Boolean get() = outcome == CheckOutcome.CONFLICT
    /** Chosen and applicable as the user has resolved it. */
    val willApply: Boolean get() = selected && !blocked && (!conflict || resolution == ConflictResolution.APPLY_ANYWAY)
}

data class ReviewUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val set: ProposalSet? = null,
    val details: TaskDetails? = null,
    val snapshot: TargetSnapshot? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val rows: List<ReviewRow> = emptyList(),
    val applying: Boolean = false,
    val applied: ApplyResult.Applied? = null,
    val undone: Boolean = false,
    val undoRefused: Boolean = false,
    val message: String? = null,
    val explainOpen: Boolean = false,
) {
    val pending: List<ReviewRow> get() = rows.filter { it.item.status == ItemStatus.PENDING }
    /** Plain changes: bulk-selectable through "Select recommended". */
    val plain: List<ReviewRow> get() = pending.filter { !it.item.material }
    /** Material changes need their own visible acceptance and are never bulk-selected (PLAN-072). */
    val material: List<ReviewRow> get() = pending.filter { it.item.material }
    val settled: List<ReviewRow> get() = rows.filter { it.item.status != ItemStatus.PENDING }
    val selectedCount: Int get() = pending.count { it.selected }
    val applyCount: Int get() = pending.count { it.willApply }
    /** Chosen items whose conflict the user has not decided yet block the apply. */
    val undecided: List<ReviewRow> get() = pending.filter { it.selected && (it.blocked || (it.conflict && it.resolution == ConflictResolution.NONE)) }
    val stale: List<ReviewRow> get() = pending.filter { it.outcome != null && it.outcome != CheckOutcome.OK }
    val canUndo: Boolean get() {
        val la = set?.lastApply ?: return false
        val t = details?.task ?: return false
        return !undone && t.rev == la.revAfter && !t.status.isTerminal
    }
}

/**
 * The one review for planning and research proposals (handoff §7, INT-042). Reconciles against the
 * live task on every change, keeps the user's selection, edits and conflict decisions across
 * rotation and process death, and writes only through [ProposalApplier].
 */
class ProposalReviewViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    val setId: String,
    private val sets: ProposalSetRepository,
    private val tasks: TaskRepository,
    private val applier: ProposalApplier,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private data class Local(
        val selected: Set<String>,
        val edits: Map<String, ItemValue>,
        val resolutions: Map<String, ConflictResolution>,
        val applying: Boolean = false,
        val applied: ApplyResult.Applied? = null,
        val undone: Boolean = false,
        val undoRefused: Boolean = false,
        val message: String? = null,
        val explainOpen: Boolean = false,
    )

    private val local = MutableStateFlow(
        Local(
            selected = (saved.get<Array<String>>(KEY_SELECTED) ?: emptyArray()).toSet(),
            edits = decodeEdits(saved.get<Array<String>>(KEY_EDITS)),
            resolutions = (saved.get<Array<String>>(KEY_RESOLUTIONS) ?: emptyArray()).mapNotNull { raw ->
                val parts = raw.split("\u0001"); if (parts.size == 2) parts[0] to (ConflictResolution.entries.firstOrNull { it.name == parts[1]} ?: return@mapNotNull null) else null
            }.toMap(),
            explainOpen = saved[KEY_EXPLAIN] ?: false,
        ),
    )

    val state: StateFlow<ReviewUi> = combine(sets.observe(setId), tasks.observeDetails(taskId), local) { set, details, l ->
        val zone = zoneProvider.current()
        if (set == null) return@combine ReviewUi(loading = false, missing = true, zone = zone)
        val snapshot = details?.let { TargetSnapshot.of(it.task, it.steps, it.policy, clock.now().toEpochMilli()) }
        val checks = snapshot?.let { ProposalReconciler.reconcile(set, it) }?.associateBy { it.itemId }.orEmpty()
        val rows = set.items.map { item ->
            val edited = l.edits[item.id]
            ReviewRow(
                item = item, check = checks[item.id], selected = item.id in l.selected, edited = edited,
                resolution = l.resolutions[item.id] ?: ConflictResolution.NONE, label = ReviewPresenter.kindLabel(item),
                currentText = snapshot?.let { ReviewPresenter.currentText(item, it, zone) },
                proposedText = ReviewPresenter.proposedText(item, edited ?: item.value, zone),
            )
        }
        ReviewUi(
            loading = false, missing = details == null, set = set, details = details, snapshot = snapshot, zone = zone, rows = rows,
            applying = l.applying, applied = l.applied, undone = l.undone, undoRefused = l.undoRefused, message = l.message, explainOpen = l.explainOpen,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUi())

    // ------------------------------------------------------------------ selection

    fun toggle(id: String) = local.update { l -> l.copy(selected = l.selected.toMutableSet().also { if (!it.add(id)) it.remove(id) }, message = null) }.also { persist() }

    fun setSelected(id: String, on: Boolean) = local.update { l -> l.copy(selected = if (on) l.selected + id else l.selected - id, message = null) }.also { persist() }

    /** Only recommended, non-material, non-conflicting items (handoff §7 rule 2). Never touches material rows. */
    fun selectRecommended() {
        val s = state.value
        val set = s.set ?: return
        val picks = ProposalReconciler.recommendedSelection(set, s.rows.mapNotNull { it.check })
        local.update { l -> l.copy(selected = l.selected + picks, message = if (picks.isEmpty()) "Nothing recommended is left to select" else null) }
        persist()
    }

    fun clearSelection() = local.update { it.copy(selected = emptySet()) }.also { persist() }

    fun editText(id: String, text: String) {
        val item = state.value.set?.item(id) ?: return
        local.update { l -> l.copy(edits = l.edits + (id to ReviewPresenter.withText(l.edits[id] ?: item.value, text))) }
        persist()
    }

    fun editDate(id: String, date: LocalDate) {
        val item = state.value.set?.item(id) ?: return
        local.update { l -> l.copy(edits = l.edits + (id to ReviewPresenter.withDate(l.edits[id] ?: item.value, date))) }
        persist()
    }

    fun resetEdit(id: String) = local.update { l -> l.copy(edits = l.edits - id) }.also { persist() }

    fun resolve(id: String, resolution: ConflictResolution) {
        if (resolution == ConflictResolution.KEEP_YOURS) {
            // Keep yours declines the item outright (handoff §5); no apply needed.
            viewModelScope.launch { applier.decline(setId, listOf(id), "Kept yours") }
            local.update { l -> l.copy(selected = l.selected - id, resolutions = l.resolutions - id) }
        } else {
            local.update { l -> l.copy(resolutions = l.resolutions + (id to resolution), selected = l.selected + id) }
        }
        persist()
    }

    fun toggleExplain() = local.update { it.copy(explainOpen = !it.explainOpen) }.also { saved[KEY_EXPLAIN] = local.value.explainOpen }

    // ------------------------------------------------------------------ writes (only through the applier)

    fun reject(id: String) = viewModelScope.launch {
        applier.decline(setId, listOf(id))
        local.update { l -> l.copy(selected = l.selected - id, edits = l.edits - id, resolutions = l.resolutions - id) }
        persist()
    }

    fun rejectAll() = viewModelScope.launch {
        val ids = state.value.pending.map { it.item.id }
        applier.decline(setId, ids)
        local.update { it.copy(selected = emptySet(), edits = emptyMap(), resolutions = emptyMap()) }
        persist()
    }

    fun apply() {
        val s = state.value
        if (s.applying || s.applied != null) return
        if (s.undecided.isNotEmpty()) {
            local.update { it.copy(message = "Decide on the flagged change${if (s.undecided.size == 1) "" else "s"} first: apply anyway or keep yours.") }
            return
        }
        val choices = s.pending.filter { it.selected }.map { ApplyChoice(it.item.id, it.edited, it.resolution) }
        if (choices.isEmpty()) { local.update { it.copy(message = "Select at least one change to apply") }; return }
        local.update { it.copy(applying = true, message = null) }
        viewModelScope.launch {
            when (val r = applier.apply(setId, choices)) {
                is ApplyResult.Applied -> local.update { it.copy(applying = false, applied = r, selected = emptySet(), edits = emptyMap(), resolutions = emptyMap()) }.also { persist() }
                is ApplyResult.NeedsReview -> local.update { it.copy(applying = false, message = "The task changed while you were reviewing. Check the flagged changes and decide, then apply again. Nothing was changed.") }
                is ApplyResult.Rejected -> local.update { it.copy(applying = false, message = r.message) }
            }
        }
    }

    fun undo() = viewModelScope.launch {
        val ok = applier.undo(setId)
        local.update { it.copy(undone = ok, undoRefused = !ok, message = if (ok) null else "Undo isn't safe any more: the task changed after the apply.") }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // ------------------------------------------------------------------ persistence (rotation / process death)

    private fun persist() {
        val l = local.value
        saved[KEY_SELECTED] = l.selected.toTypedArray()
        saved[KEY_EDITS] = l.edits.map { (id, v) -> id + "\u0001" + com.lifeadmin.autopilot.domain.proposal.ProposalJson.json.encodeToString(ItemValue.serializer(), v) }.toTypedArray()
        saved[KEY_RESOLUTIONS] = l.resolutions.map { (id, r) -> id + "\u0001" + r.name }.toTypedArray()
    }

    private fun decodeEdits(raw: Array<String>?): Map<String, ItemValue> = (raw ?: emptyArray()).mapNotNull { line ->
        val i = line.indexOf('\u0001'); if (i <= 0) return@mapNotNull null
        val v = runCatching { com.lifeadmin.autopilot.domain.proposal.ProposalJson.json.decodeFromString(ItemValue.serializer(), line.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
        line.substring(0, i) to v
    }.toMap()

    companion object {
        const val KEY_SELECTED = "review_selected"
        const val KEY_EDITS = "review_edits"
        const val KEY_RESOLUTIONS = "review_resolutions"
        const val KEY_EXPLAIN = "review_explain"
    }
}
