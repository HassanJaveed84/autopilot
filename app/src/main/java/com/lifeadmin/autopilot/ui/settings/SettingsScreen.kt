package com.lifeadmin.autopilot.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.ui.components.TimeSelectDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    snackbar: SnackbarHostState,
    healthSection: @Composable () -> Unit = {},
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export { context.contentResolver.openOutputStream(uri, "wt") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import { context.contentResolver.openInputStream(uri) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings_list"), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { healthSection() }

            item { Header("Reminder defaults") }
            item {
                Text("“Save & remind” preset", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                Column(Modifier.selectableGroup()) {
                    ReminderPreset.entries.forEach { p ->
                        Choice(ReminderPresets.label(p), s.defaultPreset == p) { viewModel.update(reschedule = false) { it.copy(defaultPreset = p) } }
                    }
                }
            }
            item {
                Text("Notification “Snooze” button", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                Column(Modifier.selectableGroup()) {
                    listOf(SnoozePreset.ONE_HOUR to "1 hour", SnoozePreset.THREE_HOURS to "3 hours", SnoozePreset.TOMORROW_MORNING to "Tomorrow morning (09:00)").forEach { (p, label) ->
                        Choice(label, s.defaultSnooze == p) { viewModel.update(reschedule = false) { it.copy(defaultSnooze = p) } }
                    }
                }
            }

            item { Header("Quiet hours") }
            item {
                ToggleRow("Quiet hours", "No reminder notifications between ${s.quietHours.start.format(hhmm)} and ${s.quietHours.end.format(hhmm)}", s.quietHours.enabled) { on ->
                    viewModel.update { it.copy(quietHours = it.quietHours.copy(enabled = on)) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { pickStart = true }, enabled = s.quietHours.enabled) { Text("From ${s.quietHours.start.format(hhmm)}") }
                    OutlinedButton(onClick = { pickEnd = true }, enabled = s.quietHours.enabled) { Text("Until ${s.quietHours.end.format(hhmm)}") }
                }
            }
            item {
                Column(Modifier.selectableGroup()) {
                    Choice("Hold reminders until quiet hours end and combine them into one", s.quietHoursBehavior == QuietHoursBehavior.DEFER_AND_COALESCE) {
                        viewModel.update { it.copy(quietHoursBehavior = QuietHoursBehavior.DEFER_AND_COALESCE) }
                    }
                    Choice("Skip reminders that fall in quiet hours", s.quietHoursBehavior == QuietHoursBehavior.SKIP) {
                        viewModel.update { it.copy(quietHoursBehavior = QuietHoursBehavior.SKIP) }
                    }
                }
            }

            item { Header("Privacy") }
            item {
                ToggleRow("Hide task titles on lock screen", "Notifications show “Task reminder” until the phone is unlocked", s.lockScreenPrivacy) { on ->
                    viewModel.update(reschedule = false) { it.copy(lockScreenPrivacy = on) }
                }
            }

            item { Header("Backup") }
            item {
                Text(
                    "Tasks live only on this phone and are excluded from Android cloud backup. Export a JSON file to keep a copy; " +
                        "without one there is no recovery if the phone is lost.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
                )
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirmExport = true }, modifier = Modifier.testTag("export_button")) { Text("Export…") }
                    OutlinedButton(onClick = { confirmImport = true }, modifier = Modifier.testTag("import_button")) { Text("Import…") }
                }
            }

            item { Header("About") }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("AI assistance: Off — not included in this version", style = MaterialTheme.typography.bodyMedium)
                    Text("Works fully offline. No account, analytics, ads or network access.", style = MaterialTheme.typography.bodyMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (pickStart) TimeSelectDialog(s.quietHours.start, "Quiet hours start", onDismiss = { pickStart = false }) { t ->
        pickStart = false; viewModel.update { it.copy(quietHours = it.quietHours.copy(start = t)) }
    }
    if (pickEnd) TimeSelectDialog(s.quietHours.end, "Quiet hours end", onDismiss = { pickEnd = false }) { t ->
        pickEnd = false; viewModel.update { it.copy(quietHours = it.quietHours.copy(end = t)) }
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export plaintext backup?") },
            text = { Text("The file contains your task titles, notes, checklists and history in readable form. Store it somewhere private.") },
            confirmButton = { TextButton(onClick = { confirmExport = false; exportLauncher.launch("life-admin-backup-${LocalDate.now()}.json") }) { Text("Export") } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
        )
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import backup?") },
            text = { Text("Tasks are merged by ID: new ones are added and newer versions replace older ones. Invalid files are rejected without changing anything. Reminders are rescheduled afterwards.") },
            confirmButton = { TextButton(onClick = { confirmImport = false; importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Header(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
