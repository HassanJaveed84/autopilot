package com.lifeadmin.autopilot.ui.research

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.data.repository.BriefStatus
import com.lifeadmin.autopilot.data.repository.ResearchBrief
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.KindTag
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId

/** Research history for one task: come back without paying again. Newest first. */
@Composable
fun ResearchHistoryScreen(
    viewModel: ResearchHistoryViewModel,
    zone: ZoneId,
    onBack: () -> Unit,
    onOpenBrief: (briefId: String) -> Unit,
    onNewResearch: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack)
                Text("Research", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    HorizontalDivider(color = c.line)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        PrimaryButton("New research", onClick = onNewResearch, icon = AutopilotIcons.Research, enabled = !s.running, modifier = Modifier.fillMaxWidth().testTag("history_new"))
                        if (s.briefs.isNotEmpty()) SecondaryButton("Delete research for this task", onClick = { confirmDelete = true }, minHeight = 44.dp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("history_delete_all"))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("history_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text(s.taskTitle, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                Text("Saved on this phone with the task. Opens offline and never re-runs on its own.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            }
            if (!s.loading && s.briefs.isEmpty()) {
                item {
                    SurfaceCard(radius = 20.dp, modifier = Modifier.testTag("history_empty")) {
                        IconCircle(AutopilotIcons.Research, tint = c.ink2, background = c.sunken)
                        Text("No research yet", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 10.dp))
                        Text("When you research this task, the brief is kept here so you can come back without paying for it again.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            items(s.briefs, key = { it.id }) { b ->
                HistoryRow(b, s.now, zone, onClick = { onOpenBrief(b.id) }, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete research for this task?") },
            text = {
                Text(
                    "Removes ${s.briefs.size} saved ${if (s.briefs.size == 1) "brief" else "briefs"} from this phone. Steps and notes you already added stay on the task; they just lose their link to the sources." +
                        if (s.running) " The research running for this task is stopped and not saved; searches that already ran may still be billed." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAll() }, modifier = Modifier.testTag("history_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun HistoryRow(b: ResearchBrief, now: Instant, zone: ZoneId, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = modifier.testTag("history_${b.id}")) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ResearchStamp("${Format.relative(b.createdAt, now, zone)} · ${b.searchCount} ${if (b.searchCount == 1) "search" else "searches"} · ${b.sourceCount} ${if (b.sourceCount == 1) "source" else "sources"}")
            Text(b.question, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.content.quickAnswer.firstOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (b.applied.isNotEmpty()) KindTag("${b.applied.size} applied", c.onDoneSoft)
                when (b.status) {
                    BriefStatus.UNVERIFIED -> KindTag("Unverified", c.route)
                    BriefStatus.TRUNCATED -> KindTag("Cut short", c.ink2)
                    BriefStatus.VERIFIED -> Unit
                }
                KindTag(if (b.isStale(now)) "May be out of date" else "Fresh", if (b.isStale(now)) c.onSignalSoft else c.ink2)
            }
        }
    }
}
