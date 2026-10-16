package com.lifeadmin.autopilot.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.ui.components.EmptyState
import com.lifeadmin.autopilot.ui.components.TaskRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun doneWithUndo(scope: CoroutineScope, snackbar: SnackbarHostState, vm: TaskListViewModel, s: TaskSummary) {
    vm.complete(s.task.id) {
        scope.launch {
            val r = snackbar.showSnackbar("Marked “${s.task.title.take(40)}” done", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.reopen(s.task.id)
        }
    }
}

@Composable
fun TasksScreen(viewModel: TaskListViewModel, snackbar: SnackbarHostState, onOpenTask: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Tasks") }) }, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("tasks_list"), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("search_field"),
                    placeholder = { Text("Search title and notes") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskFilter.entries.forEach { f ->
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { viewModel.setFilter(f) },
                            label = { Text("${f.label} ${state.counts[f] ?: 0}") },
                        )
                    }
                }
            }
            if (state.tasks.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        if (state.query.isBlank()) "Nothing here" else "No matches",
                        if (state.query.isBlank()) "Tasks in this category will appear here." else "Try a different search.",
                    )
                }
            }
            items(state.tasks, key = { it.task.id }) { s ->
                TaskRow(
                    s, state.now, state.zone,
                    onOpen = { onOpenTask(s.task.id) },
                    onDone = if (s.task.status.isTerminal) null else ({ doneWithUndo(scope, snackbar, viewModel, s) }),
                    onSnooze = null,
                    showStatus = state.filter == TaskFilter.ALL || state.filter == TaskFilter.ARCHIVED,
                )
            }
        }
    }
}

@Composable
fun InboxScreen(viewModel: TaskListViewModel, snackbar: SnackbarHostState, onOpenTask: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Inbox") }) }, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("inbox_list"), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (state.tasks.isEmpty() && !state.loading) {
                item { EmptyState("Inbox zero", "Quick captures land here until you schedule or classify them.") }
            }
            items(state.tasks, key = { it.task.id }) { s ->
                Row {
                    TaskRow(
                        s, state.now, state.zone,
                        onOpen = { onOpenTask(s.task.id) },
                        onDone = { doneWithUndo(scope, snackbar, viewModel, s) },
                        onSnooze = null,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.makeActionable(s.task.id) }) { Text("Make actionable") }
                    TextButton(onClick = { onOpenTask(s.task.id) }) { Text("Schedule…") }
                }
            }
        }
    }
}
