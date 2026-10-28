package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.LinkList
import com.lifeadmin.autopilot.ui.components.MainLinkButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * The step detail sheet (Rich Steps, hybrid A+B): its own done box, the main link as the big
 * target, the description, the other links, where it came from, and Edit. A done, cancelled or
 * archived task shows it read-only; links still open, because reading them changes nothing.
 */
@Composable
fun StepDetailSheet(
    step: TaskStep,
    locked: Boolean,
    provenance: ProvenanceRecord?,
    onToggle: (Boolean) -> Unit,
    onOpenLink: (StepLink) -> Unit,
    onEdit: () -> Unit,
    onShowProvenance: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AutopilotTheme.colors
    val rich = step.description != null || step.links.isNotEmpty()
    AutopilotSheet(
        title = step.title,
        onDismiss = onDismiss,
        modifier = Modifier.testTag("step_sheet"),
        footer = if (locked) null else {
            {
                SecondaryButton(
                    if (rich) "Edit step" else "Add a description or link", onClick = onEdit, icon = AutopilotIcons.Pencil,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("step_sheet_edit"),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Its own done box, so the sheet never needs the list behind it.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
                    .toggleable(value = step.isDone, enabled = !locked, role = Role.Checkbox, onValueChange = onToggle)
                    .testTag("step_sheet_done"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (step.isDone) c.done else Color.Transparent)
                            .border(2.dp, if (step.isDone) c.done else c.lineStrong, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (step.isDone) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onDone, modifier = Modifier.size(14.dp)) }
                }
                Text(if (step.isDone) "Done" else "Mark this step done", style = MaterialTheme.typography.bodyLarge, color = if (locked) c.ink3 else c.ink)
            }
            step.links.firstOrNull()?.let { main -> MainLinkButton(main, onOpen = { onOpenLink(main) }, modifier = Modifier.testTag("step_sheet_main_link")) }
            step.description?.let { d ->
                SelectionContainer { Text(d, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.fillMaxWidth().testTag("step_sheet_description")) }
            }
            if (step.links.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MORE LINKS", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.semantics { heading() })
                    LinkList(step.links.drop(1), onOpen = onOpenLink, modifier = Modifier.testTag("step_sheet_links"))
                }
            }
            if (!rich) Text(
                if (locked) "No description or links." else "No description or links yet. Add them if they'd help you do this step.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink2,
            )
            provenance?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Route (indigo) with a dashed edge means "came from AI" across the app.
                    Icon(AutopilotIcons.Route, contentDescription = null, tint = c.route, modifier = Modifier.size(18.dp))
                    LinkButton(
                        when (p.source) {
                            ProposalSource.HANDOFF -> "From ChatGPT · where this came from"
                            ProposalSource.RESEARCH -> "From research · where this came from"
                            ProposalSource.PLANNING -> "From Plan with AI · where this came from"
                        },
                        onClick = onShowProvenance, modifier = Modifier.testTag("step_sheet_provenance"),
                    )
                }
            }
        }
    }
}
