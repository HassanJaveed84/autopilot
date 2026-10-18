package com.lifeadmin.autopilot.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.ui.components.EmptyState
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.SectionHeader
import com.lifeadmin.autopilot.ui.components.TaskRow
import kotlinx.coroutines.launch

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    snackbar: SnackbarHostState,
    onOpenTask: (String) -> Unit,
    onOpenInbox: () -> Unit,
    healthBanner: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun done(task: TaskSummary) = viewModel.complete(task.task.id) {
        scope.launch {
            val r = snackbar.showSnackbar("Marked “${task.task.title.take(40)}” done", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) viewModel.undoComplete(task.task.id)
        }
    }

    fun snooze(task: TaskSummary) = viewModel.snoozeDefault(task.task.id) { until ->
        scope.launch { snackbar.showSnackbar("Snoozed until ${Format.relative(until, state.now, state.zone)}") }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Today") }) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("today_list"),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { healthBanner() }
            if (state.showOnboarding) {
                item {
                    OnboardingCard(
                        onExample = { viewModel.createExample(onOpenTask) },
                        onDismiss = viewModel::dismissOnboarding,
                    )
                }
            }
            item { SummaryRow(state) }
            if (state.inboxCount > 0) {
                item {
                    TextButton(onClick = onOpenInbox, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("${state.inboxCount} item${if (state.inboxCount == 1) "" else "s"} in Inbox to review")
                    }
                }
            }
            if (state.overdue.isNotEmpty()) {
                item { SectionHeader("Overdue") }
                items(state.overdue, key = { "o" + it.task.id }) { s ->
                    TaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, onDone = { done(s) }, onSnooze = { snooze(s) })
                }
            }
            item { SectionHeader("Needs action") }
            if (state.needsAction.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        if (state.hasAnyTask) "Nothing needs action right now" else "No tasks yet",
                        if (state.hasAnyTask) "Snoozed and waiting items will come back on schedule." else "Tap + to capture something you need to follow through on.",
                    )
                }
            }
            items(state.needsAction.take(8), key = { "n" + it.task.id }) { s ->
                TaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, onDone = { done(s) }, onSnooze = { snooze(s) })
            }
            if (state.waiting.isNotEmpty()) {
                item { SectionHeader("Waiting") }
                items(state.waiting, key = { "w" + it.task.id }) { s ->
                    TaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, onDone = { done(s) }, onSnooze = null)
                }
            }
            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader("Upcoming reminders") }
                items(state.upcoming, key = { "u" + it.task.id }) { s ->
                    TaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, onDone = null, onSnooze = null)
                }
            }
            if (state.snoozed.isNotEmpty()) {
                item { SectionHeader("Snoozed") }
                items(state.snoozed, key = { "s" + it.task.id }) { s ->
                    TaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, onDone = { done(s) }, onSnooze = null)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(state: TodayUiState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryTile("Needs action", state.needsAction.size + state.overdue.size, Modifier.weight(1f))
        SummaryTile("Waiting", state.waiting.size, Modifier.weight(1f))
        SummaryTile("Next 24h", state.upcomingCount24h, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryTile(label: String, count: Int, modifier: Modifier) {
    Card(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $count" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun OnboardingCard(onExample: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Welcome", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Everything stays on this phone — no account, no cloud. AI planning is optional and off unless you set it up. Capture a task, choose when to be reminded, " +
                    "and the app keeps following up until you mark it done. Dismissing a notification never completes a task.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExample) { Text("Try an example") }
                OutlinedButton(onClick = onDismiss) { Text("Got it") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "The example creates “Book train ride” with reminders at 09:00 and 18:00 daily.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
