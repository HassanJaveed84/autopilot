package com.lifeadmin.autopilot.ui.research2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.research2.ApplyOutcome2
import com.lifeadmin.autopilot.ai.research2.Proposal
import com.lifeadmin.autopilot.ai.research2.ProposalEdit
import com.lifeadmin.autopilot.ai.research2.ProposalKind
import com.lifeadmin.autopilot.ai.research2.ProposalPlan
import com.lifeadmin.autopilot.ai.research2.ProposalRow
import com.lifeadmin.autopilot.ai.research2.Research2Actions
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.ResearchSessionStore
import com.lifeadmin.autopilot.ai.research2.UndoToken2
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.research.CitationChip
import com.lifeadmin.autopilot.ui.research.ResearchBanner
import com.lifeadmin.autopilot.ui.research.dottedBorder
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ view model

/** The user's working copy of one proposal (SavedStateHandle-backed so edits survive rotation). */
data class EditState(val include: Boolean, val title: String, val note: String, val open: Boolean = false, val addAnyway: Boolean = false, val edited: Boolean = false)

sealed interface ProposalsUi {
    data object Loading : ProposalsUi
    data class Review(val session: ResearchSession, val details: TaskDetails, val edits: Map<String, EditState>, val plan: ProposalPlan, val conflict: String?) : ProposalsUi
    data class Applied(val count: Int, val undo: UndoToken2?, val undone: Boolean, val undoRefused: Boolean) : ProposalsUi
    data class Failed(val message: String) : ProposalsUi
}

class ProposalsViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    val sessionId: String,
    store: ResearchSessionStore,
    tasks: TaskRepository,
    private val actions: Research2Actions,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private val edits = MutableStateFlow<Map<String, EditState>>(restore())
    private val _result = MutableStateFlow<ProposalsUi?>(null)
    private var applying = false
    private var frozen = false
    private var lastVersion: java.time.Instant? = null
    private var conflict: String? = null

    val state: StateFlow<ProposalsUi> = combine(store.observe(sessionId), tasks.observeDetails(taskId), edits, _result) { s, d, e, r ->
        if (r != null) return@combine r
        if (frozen) return@combine ProposalsUi.Loading
        if (s == null || d == null) return@combine ProposalsUi.Failed("This research or task no longer exists.")
        val merged = seed(s, e)
        if (merged != e) edits.value = merged
        val plan = actions.plan(s, d, merged.map { (id, st) -> ProposalEdit(id, st.include, st.title, st.note.ifBlank { null }, st.addAnyway) }, zoneProvider.current())
        if (lastVersion != null && lastVersion != d.task.updatedAt) conflict = "This task changed since you opened the result. The list below is refreshed — check it again."
        lastVersion = d.task.updatedAt
        ProposalsUi.Review(s, d, merged, plan, conflict)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProposalsUi.Loading)

    /** Proposals not yet edited get their defaults; applied ones are excluded. */
    private fun seed(s: ResearchSession, e: Map<String, EditState>): Map<String, EditState> {
        val applied = s.applied.map { it.proposalId }.toSet()
        val out = LinkedHashMap<String, EditState>()
        for (p in s.content.proposals) {
            if (p.id in applied) continue
            out[p.id] = e[p.id] ?: EditState(include = p.selectedByDefault && (p.refs.isNotEmpty() || p.kind == ProposalKind.NOTE), title = p.title, note = p.note.orEmpty())
        }
        return out
    }

    fun toggle(id: String, on: Boolean) = change(id) { it.copy(include = on) }
    fun setTitle(id: String, t: String) = change(id) { it.copy(title = t.take(80), edited = true) }
    fun setNote(id: String, n: String) = change(id) { it.copy(note = n.take(300), edited = true) }
    fun toggleOpen(id: String) = change(id) { it.copy(open = !it.open) }
    fun addAnyway(id: String) = change(id) { it.copy(addAnyway = true, include = true) }
    fun reset(id: String, p: Proposal) = change(id) { EditState(it.include, p.title, p.note.orEmpty(), it.open) }

    private fun change(id: String, f: (EditState) -> EditState) {
        edits.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
        persist()
    }

    fun apply() = viewModelScope.launch {
        val review = state.value as? ProposalsUi.Review ?: return@launch
        if (applying) return@launch
        applying = true; frozen = true
        when (val outcome = actions.apply(review.plan)) {
            is ApplyOutcome2.Applied -> _result.value = ProposalsUi.Applied(outcome.applied.size, outcome.undo, undone = false, undoRefused = false)
            is ApplyOutcome2.Conflict -> { frozen = false; conflict = outcome.message; edits.update { it.toMap() } }
            is ApplyOutcome2.Rejected -> _result.value = ProposalsUi.Failed(outcome.message)
        }
        applying = false
    }

    fun undo() = viewModelScope.launch {
        val s = _result.value as? ProposalsUi.Applied ?: return@launch
        val token = s.undo ?: return@launch
        val ok = actions.undo(token)
        _result.value = s.copy(undone = ok, undoRefused = !ok)
    }

    private fun persist() { saved["edits"] = edits.value.entries.map { (id, e) -> listOf(id, if (e.include) "1" else "0", if (e.addAnyway) "1" else "0", if (e.edited) "1" else "0", e.title, e.note).joinToString("\u0001") }.toTypedArray() }
    private fun restore(): Map<String, EditState> = (saved.get<Array<String>>("edits") ?: emptyArray()).mapNotNull { raw ->
        val p = raw.split("\u0001", limit = 6); if (p.size < 6) null else p[0] to EditState(p[1] == "1", p[4], p[5], addAnyway = p[2] == "1", edited = p[3] == "1")
    }.toMap()
}

// ------------------------------------------------------------------ screen

/** Add to your task: editable proposals; the only place research changes a task. Apply once, Undo while safe. */
@Composable
fun ProposalsScreen(viewModel: ProposalsViewModel, onBack: () -> Unit, onDoneToTask: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    Scaffold(
        containerColor = c.paper, contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back to result", onClick = onBack)
                Text("Add to your task", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    HorizontalDivider(color = c.line)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        when (val st = s) {
                            is ProposalsUi.Review -> {
                                val n = st.plan.changeCount
                                ButtonPair(
                                    secondary = { m -> SecondaryButton("Not now", onClick = onBack, modifier = m.testTag("apply_cancel")) },
                                    primary = { m -> PrimaryButton(if (n == 0) "Nothing to apply" else "Apply $n ${if (n == 1) "change" else "changes"}", onClick = viewModel::apply, enabled = n > 0, modifier = m.testTag("apply_confirm")) },
                                )
                                Text("Reminders, status and deadline stay as they are. Prices are snapshots — each step keeps the date it was checked.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                            }
                            is ProposalsUi.Applied -> ButtonPair(
                                secondary = { m -> SecondaryButton(if (st.undone) "Undone" else "Undo", onClick = viewModel::undo, enabled = st.undo != null && !st.undone && !st.undoRefused, modifier = m.testTag("apply_undo")) },
                                primary = { m -> PrimaryButton("Back to task", onClick = onDoneToTask, modifier = m.testTag("apply_done")) },
                            )
                            else -> PrimaryButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("apply_done"))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("apply_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            when (val st = s) {
                ProposalsUi.Loading -> Unit
                is ProposalsUi.Failed -> item {
                    Text("Couldn't apply", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Text(st.message, style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("apply_failed"))
                }
                is ProposalsUi.Applied -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconCircle(AutopilotIcons.Check, tint = c.onDoneSoft, background = c.doneSoft, size = 48.dp)
                        Text(if (st.undone) "Changes removed" else "Added ${st.count} ${if (st.count == 1) "change" else "changes"}", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("apply_result"))
                        Text(
                            when {
                                st.undone -> "The steps and notes from this research were removed again. Your task is as it was."
                                st.undoRefused -> "Undo isn't safe any more: the task changed after the apply. Remove anything you don't want by hand."
                                st.undo == null -> "Your task has the new steps and notes. Reminders, status and your other tasks are unchanged."
                                else -> "Your task has the new steps and notes. Reminders, status and your other tasks are unchanged. Undo is available until you edit the task."
                            },
                            style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("apply_result_body"),
                        )
                    }
                }
                is ProposalsUi.Review -> {
                    item {
                        Text("For ${st.details.task.title}. Edit wording, untick anything — nothing changes until you tap Apply.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 12.dp))
                        st.conflict?.let { ResearchBanner("This task changed", it, warn = true, icon = AutopilotIcons.Warn, modifier = Modifier.padding(bottom = 12.dp), tag = "apply_conflict") }
                        if (st.edits.isEmpty()) Text("Everything from this research is already on the task.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
                    }
                    val rowsById = st.plan.rows.associateBy { it.proposal.id }
                    st.session.content.proposals.filter { it.id in st.edits }.forEach { p ->
                        val e = st.edits[p.id]!!
                        item(key = p.id) { ProposalRowView(p, e, rowsById[p.id], st.session, viewModel) }
                    }
                    item {
                        if (st.plan.skipped.any { !it.duplicate }) {
                            Text("Skipped — " + st.plan.skipped.filter { !it.duplicate }.joinToString("; ") { "${it.reason.lowercase()}: “${it.edit.title}”" }, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp).testTag("apply_skipped"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProposalRowView(p: Proposal, e: EditState, row: ProposalRow?, session: ResearchSession, vm: ProposalsViewModel) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val dup = row is ProposalRow.Skip && row.duplicate
    val kindLabel = when (p.kind) { ProposalKind.STEP -> "Add step"; ProposalKind.DECIDE -> "Add step · needs your decision"; ProposalKind.CHECK -> "Add step · check first"; ProposalKind.NOTE -> "Add to notes"; ProposalKind.RESEARCH -> "Add step · research later" }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(if (e.include && !dup) c.sunken else c.surface)
            .let { if (dup) it.dottedBorder(c.lineStrong, 18.dp) else if (p.refs.isEmpty()) it.dashedRoute(c.route, 18.dp) else it.border(1.dp, if (e.include) c.lineStrong else c.line, shape) }
            .padding(12.dp).testTag("proposal_${p.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (e.include && !dup) c.inverse else Color.Transparent).border(2.dp, if (dup) c.line else if (e.include) c.inverse else c.lineStrong, RoundedCornerShape(7.dp))
                    .toggleable(value = e.include, enabled = !dup, role = Role.Checkbox, onValueChange = { vm.toggle(p.id, it) }).semantics { stateDescription = if (dup) "Will be skipped" else if (e.include) "Selected" else "Not selected" }.testTag("proposal_check_${p.id}"),
                contentAlignment = Alignment.Center,
            ) { if (e.include && !dup) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(14.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(kindLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (p.refs.isEmpty()) c.route else c.ink2)
                    if (e.edited) Text("· EDITED BY YOU", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                    if (p.generated) Text("· ADDED BY THE APP", style = MaterialTheme.typography.labelSmall, color = c.ink3)
                }
                OutlinedTextField(value = e.title, onValueChange = { vm.setTitle(p.id, it) }, label = { Text("Step title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("proposal_title_${p.id}"), shape = RoundedCornerShape(12.dp), colors = fieldColors())
                if (dup) {
                    Text("Matches a step already on your task — will skip.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    LinkButton("Add anyway", onClick = { vm.addAnyway(p.id) }, modifier = Modifier.testTag("proposal_anyway_${p.id}"))
                }
                p.requirementId?.let { rid -> session.content.requirements.firstOrNull { it.id == rid }?.let { r -> Text("For: ${r.label} · ${session.content.coverageOf(rid).status.label}", style = MaterialTheme.typography.labelSmall, color = c.ink2) } }
                LinkButton(if (e.open) "Hide note" else if (e.note.isBlank()) "Add a note" else "Step note", onClick = { vm.toggleOpen(p.id) }, modifier = Modifier.testTag("proposal_note_toggle_${p.id}"))
                if (e.open) OutlinedTextField(value = e.note, onValueChange = { vm.setNote(p.id, it) }, label = { Text("Step note (saved with the step)") }, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("proposal_note_${p.id}"), shape = RoundedCornerShape(12.dp), colors = fieldColors())
                else if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (p.refs.isEmpty()) "No source — Claude's suggestion" else "Evidence kept with the step:", style = MaterialTheme.typography.labelSmall, color = if (p.refs.isEmpty()) c.route else c.ink2, modifier = Modifier.padding(top = 3.dp))
                    p.refs.take(4).forEach { n -> CitationChip(n, onClick = {}, title = session.content.sources.firstOrNull { it.n == n }?.title) }
                }
                if (e.edited) LinkButton("Reset wording", onClick = { vm.reset(p.id, p) })
            }
        }
    }
}
