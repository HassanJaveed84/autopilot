package com.lifeadmin.autopilot.ui.investigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.investigation.TurnRunState
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.GroupRow
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId

/** Research history: every Investigation Thread, standalone or task-linked, newest first. Opens offline. */
@Composable
fun InvestigationHomeScreen(viewModel: InvestigationHomeViewModel, zone: ZoneId, title: String, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    Scaffold(containerColor = c.paper, contentWindowInsets = WindowInsets(0), topBar = { BackHeader(title, onBack, Modifier.statusBarsPadding().padding(start = 8.dp, end = 12.dp)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("home_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    PrimaryButton("New research", onClick = onNew, icon = AutopilotIcons.Research, enabled = s.run !is TurnRunState.Running, modifier = Modifier.fillMaxWidth().testTag("home_new"))
                    Text("Each research keeps its turns, sources and costs. Nothing here runs on its own.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
            }
            if (!s.loading && s.investigations.isEmpty()) item { Text("No research yet.", style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 16.dp).testTag("home_empty")) }
            itemsIndexed(s.investigations, key = { _, i -> i.id }) { i, inv ->
                val latest = inv.latestTurn
                val state = when (latest?.state) {
                    TurnState.RUNNING -> "Running now"
                    TurnState.STOPPED -> "Stopped"
                    TurnState.FAILED -> "Didn't finish"
                    TurnState.INTERRUPTED -> "Interrupted"
                    TurnState.CLARIFYING, TurnState.AUTHORIZING -> "Not started"
                    TurnState.DECLINED -> "Not started"
                    else -> inv.latestResult?.state?.label ?: "Saved"
                }
                val task = inv.taskId?.let { s.taskTitles[it] }
                SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 8.dp)) {
                    GroupRow(
                        inv.title, onClick = { onOpen(inv.id) }, modifier = Modifier.testTag("home_item_$i"),
                        subtitle = listOfNotNull(task?.let { "For: $it" } ?: "Not linked to a task", state, "${inv.turns.size} ${if (inv.turns.size == 1) "turn" else "turns"}", Format.relative(Instant.ofEpochMilli(inv.updatedAtMs), s.now, zone)).joinToString(" · "),
                        icon = AutopilotIcons.Research,
                    )
                }
            }
        }
    }
}
