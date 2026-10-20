package com.lifeadmin.autopilot.ui.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.research.ApplyRow
import com.lifeadmin.autopilot.ai.research.MoveKind
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * Add to your task: the only place research can change a task. Shows exactly what will be applied
 * (and what is skipped), applies once through the transactional path, and offers Undo only while
 * a conflict-safe inverse exists.
 */
@Composable
fun ApplyResearchScreen(viewModel: ApplyResearchViewModel, onBack: () -> Unit, onDoneToTask: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back to brief", onClick = onBack)
                Text("Add to your task", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    HorizontalDivider(color = c.line)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        when (val st = s) {
                            is ApplyUi.Review -> {
                                val n = st.plan.changeCount
                                ButtonPair(
                                    secondary = { m -> SecondaryButton("Not now", onClick = onBack, modifier = m.testTag("apply_cancel")) },
                                    primary = { m -> PrimaryButton(if (n == 0) "Nothing to apply" else "Apply $n ${if (n == 1) "change" else "changes"}", onClick = viewModel::apply, enabled = n > 0, modifier = m.testTag("apply_confirm")) },
                                )
                                Text("Changes go into this task only. You can undo right after.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                            }
                            is ApplyUi.Applied -> {
                                ButtonPair(
                                    secondary = { m -> SecondaryButton(if (st.undone) "Undone" else "Undo", onClick = viewModel::undo, enabled = st.undo != null && !st.undone && !st.undoRefused, modifier = m.testTag("apply_undo")) },
                                    primary = { m -> PrimaryButton("Back to task", onClick = onDoneToTask, modifier = m.testTag("apply_done")) },
                                )
                            }
                            else -> PrimaryButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("apply_done"))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("apply_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            when (val st = s) {
                ApplyUi.Loading -> Unit
                is ApplyUi.Failed -> item {
                    Text("Couldn’t apply", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Text(st.message, style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("apply_failed"))
                }
                is ApplyUi.Applied -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconCircle(AutopilotIcons.Check, tint = c.onDoneSoft, background = c.doneSoft, size = 48.dp)
                        Text(if (st.undone) "Changes removed" else "Added ${st.count} ${if (st.count == 1) "change" else "changes"}", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("apply_result"))
                        Text(
                            when {
                                st.undone -> "The steps and note from this research were removed again. Your task is as it was."
                                st.undoRefused -> "Undo isn’t safe any more: the task changed after the apply (a step was ticked or edited). Remove anything you don’t want by hand."
                                st.undo == null -> "Your task has the new steps and notes. Reminders, status and your other tasks are unchanged."
                                else -> "Your task has the new steps and notes. Reminders, status and your other tasks are unchanged. Undo is available until you edit the task."
                            },
                            style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("apply_result_body"),
                        )
                    }
                }
                is ApplyUi.Review -> {
                    item {
                        Text("Changes for ${st.details.task.title} from your research brief. Nothing changes until you tap Apply.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 12.dp))
                        st.conflict?.let { ResearchBanner("This task changed", it, warn = true, icon = AutopilotIcons.Warn, modifier = Modifier.padding(bottom = 12.dp), tag = "apply_conflict") }
                    }
                    item {
                        SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("apply_rows")) {
                            val rows = st.plan.rows.filter { it !is ApplyRow.Skip }
                            if (rows.isEmpty()) Text("Everything you picked is already on the task.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(16.dp))
                            rows.forEachIndexed { i, row ->
                                if (i > 0) HorizontalDivider(color = c.line)
                                val (kind, text) = when (row) {
                                    is ApplyRow.AddStep -> (if (row.move.kind == MoveKind.CHECK) "Add step · check" else "Add step") to row.title
                                    is ApplyRow.AppendNote -> "Add to notes" to row.line.removePrefix("• ")
                                    is ApplyRow.Skip -> "Skip" to row.move.text
                                }
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("apply_row_${row.move.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.ink2)
                                    Text(text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                    Text(
                                        if (row.move.refs.isNotEmpty()) "From your research · source ${row.move.refs.joinToString(", ")}" else "Claude’s suggestion — not from a source",
                                        style = MaterialTheme.typography.bodySmall, color = if (row.move.refs.isNotEmpty()) c.ink2 else c.route,
                                    )
                                }
                            }
                        }
                        if (st.plan.skipped.isNotEmpty()) {
                            Text(
                                "Skipped — " + st.plan.skipped.joinToString("; ") { "${it.reason.lowercase()}: “${it.move.text}”" },
                                style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp).testTag("apply_skipped"),
                            )
                        }
                        Text("Reminders, deadline, status and your other tasks stay exactly as they are.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}
