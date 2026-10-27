package com.lifeadmin.autopilot.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.domain.proposal.CheckOutcome
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one Proposal Review (handoff §7): changes first, explanations collapsed; nothing selected by
 * default; "Select recommended" picks only recommended, non-material, non-conflicting items;
 * material changes have their own Accept; every item editable before acceptance; conflicts need an
 * explicit choice; Apply writes only the chosen items, once; undo while the task has not moved on.
 *
 * The ChatGPT import screen hosts the same composable: [exitLabel] and [onOpenTask] become
 * "Return to ChatGPT", [backIcon]/[backDescription] a close button, and [headerExtra] its
 * destination card. Behaviour is unchanged.
 */
@Composable
fun ProposalReviewScreen(
    viewModel: ProposalReviewViewModel,
    onBack: () -> Unit,
    onOpenTask: () -> Unit,
    onOpenInvestigation: ((String) -> Unit)? = null,
    exitLabel: String = "Back to task",
    backIcon: ImageVector = AutopilotIcons.Back,
    backDescription: String = "Back",
    headerExtra: (@Composable () -> Unit)? = null,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(backIcon, contentDescription = backDescription, onClick = onBack)
                Text(
                    if (s.applied != null) "Changes applied" else "Review changes",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }.testTag("review_heading"),
                )
            }
        },
        bottomBar = {
            Surface(color = c.paper) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    s.message?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("review_message")) }
                    when {
                        s.applied != null -> ButtonPair(
                            secondary = { m -> SecondaryButton(if (s.undone) "Undone" else "Undo", onClick = viewModel::undo, enabled = s.canUndo, modifier = m.testTag("review_undo")) },
                            primary = { m -> PrimaryButton(exitLabel, onClick = onOpenTask, modifier = m.testTag("review_done")) },
                        )
                        s.set != null && s.pending.isNotEmpty() -> ButtonPair(
                            secondary = { m -> SecondaryButton("Reject all", onClick = viewModel::rejectAll, enabled = !s.applying, modifier = m.testTag("review_reject_all")) },
                            primary = { m ->
                                val n = s.applyCount
                                PrimaryButton(
                                    if (n == 0) "Nothing selected" else "Apply $n ${if (n == 1) "change" else "changes"}",
                                    onClick = viewModel::apply, enabled = n > 0 && !s.applying && s.undecided.isEmpty(), modifier = m.testTag("review_apply"),
                                )
                            },
                        )
                        else -> PrimaryButton(exitLabel, onClick = onOpenTask, modifier = Modifier.fillMaxWidth().testTag("review_done"))
                    }
                }
            }
        },
    ) { padding ->
        val set = s.set
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("review_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                s.loading -> item { Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("review_loading")) }
                set == null || s.missing -> item { Text("This proposal is no longer available.", style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("review_missing")) }
                else -> {
                    item { Header(s) }
                    headerExtra?.let { extra -> item { extra() } }
                    s.applied?.let { a -> item { AppliedSummary(s, a.appliedItemIds.size, a.declinedItemIds.size) } }
                    if (s.applied == null && s.stale.isNotEmpty()) item { StaleBanner(s) }
                    if (s.applied == null && s.pending.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${s.selectedCount} of ${s.pending.size} selected", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.weight(1f).testTag("review_selected_count"))
                                // Everything ChatGPT sends is what the user asked to send, so its sets read "Select all" (every item is recommended).
                                LinkButton(if (set.source == ProposalSource.HANDOFF) "Select all" else "Select recommended", onClick = viewModel::selectRecommended, modifier = Modifier.testTag("review_select_recommended"))
                            }
                        }
                    }
                    if (s.applied == null && s.plain.isNotEmpty()) {
                        item { SectionLabel("Changes", "review_section_changes") }
                        s.plain.forEach { row -> item(key = row.item.id) { ReviewRowCard(row, s, viewModel, material = false) } }
                    }
                    if (s.applied == null && s.material.isNotEmpty()) {
                        item { SectionLabel("Needs your own OK", "review_section_material", caption = "Deadlines, reminders, status and your own wording are never part of a bulk selection.") }
                        s.material.forEach { row -> item(key = row.item.id) { ReviewRowCard(row, s, viewModel, material = true) } }
                    }
                    if (s.settled.isNotEmpty()) {
                        item { SectionLabel(if (s.applied != null) "What happened" else "Already settled", "review_section_settled") }
                        s.settled.forEach { row -> item(key = "settled-" + row.item.id) { SettledRow(row) } }
                    }
                    if (set.advice.isNotBlank() || set.assumptions.isNotEmpty() || set.questions.isNotEmpty()) {
                        item {
                            LinkButton(if (s.explainOpen) "Hide the planner's notes" else "Why these changes", onClick = viewModel::toggleExplain, modifier = Modifier.testTag("review_explain_toggle"))
                            if (s.explainOpen) {
                                SurfaceCard(modifier = Modifier.testTag("review_explain")) {
                                    if (set.advice.isNotBlank()) Text(set.advice, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                                    set.questions.forEach { q -> Text("Question: $q", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 8.dp)) }
                                    set.assumptions.forEach { a -> Text("Assumed: $a", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp)) }
                                }
                            }
                        }
                    }
                    if (set.investigationId != null && onOpenInvestigation != null) {
                        item { LinkButton("Open the research", onClick = { onOpenInvestigation(set.investigationId) }, modifier = Modifier.testTag("review_open_investigation")) }
                    }
                }
            }
        }
    }
}

private val madeFmt = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH)

@Composable
private fun Header(s: ReviewUi) {
    val c = AutopilotTheme.colors
    val set = s.set ?: return
    Column(Modifier.padding(top = 6.dp)) {
        Text(set.title.ifBlank { ReviewPresenter.defaultTitle(set.source) }, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("review_title"))
        val who = if (set.source == ProposalSource.HANDOFF) "Your ChatGPT conversation" else ReviewPresenter.sourceName(set.source)
        Text(
            "$who${set.model.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""} · ${Instant.ofEpochMilli(set.madeAtMs).atZone(s.zone).toLocalDateTime().format(madeFmt)}",
            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("review_meta"),
        )
        s.details?.task?.let { t -> Text("Task: ${t.title}", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("review_task")) }
        if (s.applied == null) Text("Nothing changes until you apply. Your task stays as it is while you review.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SectionLabel(text: String, tag: String, caption: String? = null) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 6.dp).testTag(tag)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun StaleBanner(s: ReviewUi) {
    val c = AutopilotTheme.colors
    val conflicts = s.stale.count { it.conflict || it.blocked }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.signalSoft).padding(14.dp).testTag("review_stale")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AutopilotIcons.Warn, contentDescription = null, tint = c.onSignalSoft, modifier = Modifier.size(18.dp))
            Text("Since this was proposed, the task changed", style = MaterialTheme.typography.titleSmall, color = c.onSignalSoft)
        }
        Text(
            if (conflicts > 0) "$conflicts ${if (conflicts == 1) "change needs" else "changes need"} your decision; the rest still apply as proposed. The original proposal is shown unchanged."
            else "Everything here still applies; the notes below say what moved.",
            style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AppliedSummary(s: ReviewUi, applied: Int, declined: Int) {
    val c = AutopilotTheme.colors
    val pendingLeft = s.pending.size
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (s.undone) c.sunken else c.doneSoft).padding(14.dp).testTag("review_result")) {
        Text(
            when {
                s.undone -> "Changes removed again"
                else -> "Applied $applied ${if (applied == 1) "change" else "changes"}"
            },
            style = MaterialTheme.typography.titleMedium, color = if (s.undone) c.ink else c.onDoneSoft, modifier = Modifier.semantics { heading() }.testTag("review_result_title"),
        )
        Text(
            buildString {
                if (s.undone) append("Your task is as it was before the apply.")
                else {
                    append("Your task has the changes you chose. ")
                    if (declined > 0) append("$declined kept as yours. ")
                    if (pendingLeft > 0) append("$pendingLeft still waiting for a decision. ")
                    append(if (s.canUndo) "Undo is available until you edit the task." else if (s.undoRefused) "Undo isn't safe any more: the task changed." else "")
                }
            }.trim(),
            style = MaterialTheme.typography.bodyMedium, color = if (s.undone) c.ink2 else c.onDoneSoft, modifier = Modifier.padding(top = 4.dp).testTag("review_result_body"),
        )
    }
}

@Composable
private fun ReviewRowCard(row: ReviewRow, s: ReviewUi, vm: ProposalReviewViewModel, material: Boolean) {
    val c = AutopilotTheme.colors
    val id = row.item.id
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val tone = when {
        row.blocked -> c.dangerSoft
        row.conflict -> c.signalSoft
        else -> c.surface
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tone).border(1.dp, if (material) c.lineStrong else c.line, RoundedCornerShape(16.dp)).padding(12.dp).testTag("review_item_$id"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!row.blocked) {
                SelectBox(
                    checked = row.selected, material = material,
                    label = (if (material) "Accept: " else "Select: ") + row.label,
                    onToggle = { vm.setSelected(id, it) }, modifier = Modifier.testTag(if (material) "review_accept_$id" else "review_check_$id"),
                )
            } else {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(AutopilotIcons.Lock, contentDescription = "Can't be applied", tint = c.ink3, modifier = Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.label, style = MaterialTheme.typography.labelLarge, color = c.ink2)
                    if (row.item.recommended && !material && s.set?.source != ProposalSource.HANDOFF) Text("Recommended", style = MaterialTheme.typography.labelSmall, color = c.route)
                    if (row.edited != null) Text("Edited by you", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                }
                row.currentText?.let { cur ->
                    Text("Now: $cur", style = MaterialTheme.typography.bodyMedium, color = c.ink2, textDecoration = if (row.item.kind.name == "REMOVE_STEP") TextDecoration.LineThrough else null, modifier = Modifier.padding(top = 4.dp).testTag("review_current_$id"))
                }
                if (editing && ReviewPresenter.isTextEditable(row.item)) {
                    OutlinedTextField(
                        value = ReviewPresenter.editableText(row.value).orEmpty(), onValueChange = { vm.editText(id, it) }, label = { Text("Proposed") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("review_edit_$id"), shape = RoundedCornerShape(12.dp), maxLines = 4,
                    )
                } else {
                    Text(
                        (if (row.currentText != null && row.item.kind.name != "REMOVE_STEP") "Proposed: " else "") + row.proposedText,
                        style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 4.dp).testTag("review_proposed_$id"),
                    )
                }
                row.item.value.let { v ->
                    val basis = (v as? com.lifeadmin.autopilot.domain.proposal.ItemValue.Date)?.basis
                    if (basis != null && basis.isNotBlank() && row.item.kind.name == "SET_TARGET") Text("Not a deadline: your deadline is kept.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
                row.check?.reason?.let { why ->
                    Text(
                        when (row.outcome) { CheckOutcome.NOTE -> "Still applies · $why"; CheckOutcome.CONFLICT -> "Needs your decision · $why"; CheckOutcome.BLOCKED -> "Can't be applied · $why"; else -> why },
                        style = MaterialTheme.typography.bodySmall, color = if (row.blocked) c.danger else if (row.conflict) c.onSignalSoft else c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("review_check_note_$id"),
                    )
                }
                if (row.conflict) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("Apply anyway", selected = row.resolution == ConflictResolution.APPLY_ANYWAY, onClick = { vm.resolve(id, ConflictResolution.APPLY_ANYWAY) }, modifier = Modifier.testTag("review_apply_anyway_$id"))
                        ChoiceChip("Keep yours", selected = false, onClick = { vm.resolve(id, ConflictResolution.KEEP_YOURS) }, modifier = Modifier.testTag("review_keep_yours_$id"))
                    }
                }
                if (row.item.why.isNotBlank() && !row.conflict) {
                    var whyOpen by rememberSaveable("why-$id") { mutableStateOf(false) }
                    LinkButton(if (whyOpen) "Hide why" else "Why", onClick = { whyOpen = !whyOpen }, modifier = Modifier.testTag("review_why_$id"))
                    if (whyOpen) Text(row.item.why, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("review_why_text_$id"))
                }
                row.item.src?.let { src ->
                    if (src.findings.isNotEmpty()) Text("${src.findings.size} supporting ${if (src.findings.size == 1) "finding" else "findings"} · ${src.status}${src.asOf?.let { " · as of $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("review_src_$id"))
                }
                if (!row.blocked) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (ReviewPresenter.isTextEditable(row.item)) LinkButton(if (editing) "Done editing" else "Edit", onClick = { editing = !editing }, modifier = Modifier.testTag("review_edit_toggle_$id"))
                        if (ReviewPresenter.isDateEditable(row.item)) LinkButton("Change date", onClick = { pickDate = true }, modifier = Modifier.testTag("review_date_$id"))
                        if (row.edited != null) LinkButton("Reset", onClick = { vm.resetEdit(id) }, modifier = Modifier.testTag("review_reset_$id"))
                        LinkButton("Reject", onClick = { vm.reject(id) }, modifier = Modifier.testTag("review_reject_$id"))
                    }
                }
            }
        }
    }
    if (pickDate) {
        val initial = (row.value as? com.lifeadmin.autopilot.domain.proposal.ItemValue.Date)?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        DateSelectDialog(initial = initial, onDismiss = { pickDate = false }, onConfirm = { d -> vm.editDate(id, d); pickDate = false })
    }
}

@Composable
private fun SettledRow(row: ReviewRow) {
    val c = AutopilotTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("review_settled_${row.item.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val (icon, tint) = when (row.item.status) {
            ItemStatus.APPLIED -> AutopilotIcons.Check to c.done
            ItemStatus.DECLINED -> AutopilotIcons.Close to c.ink3
            else -> AutopilotIcons.Minus to c.ink3
        }
        Icon(icon, contentDescription = row.item.status.name.lowercase().replaceFirstChar { it.titlecase() }, tint = tint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(row.proposedText, style = MaterialTheme.typography.bodyMedium, color = if (row.item.status == ItemStatus.APPLIED) c.ink else c.ink3, textDecoration = if (row.item.status == ItemStatus.APPLIED) null else TextDecoration.LineThrough)
            Text(
                when (row.item.status) { ItemStatus.APPLIED -> "Applied"; ItemStatus.DECLINED -> row.item.statusReason ?: "Declined"; ItemStatus.OBSOLETE -> "No longer applicable" + (row.item.statusReason?.let { " · $it" } ?: ""); else -> "" } + " · ${row.label}",
                style = MaterialTheme.typography.bodySmall, color = c.ink2,
            )
        }
    }
}

/** 48 dp toggle; material rows use a distinct outlined "Accept" box so the two kinds read differently without colour alone. */
@Composable
private fun SelectBox(checked: Boolean, material: Boolean, label: String, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Box(
        modifier.size(48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle).semantics { contentDescription = label; stateDescription = if (checked) (if (material) "Accepted" else "Selected") else (if (material) "Not accepted" else "Not selected") },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (material) 26.dp else 24.dp).clip(RoundedCornerShape(if (material) 13.dp else 7.dp)).background(if (checked) c.inverse else Color.Transparent).border(2.dp, if (checked) c.inverse else c.lineStrong, RoundedCornerShape(if (material) 13.dp else 7.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(14.dp)) }
    }
}
