package com.lifeadmin.autopilot.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.Appearance
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.GroupRow
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.PageTitle
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RowGroup
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.SwitchRow
import com.lifeadmin.autopilot.ui.components.TimeSelectDialog
import com.lifeadmin.autopilot.ui.components.showToast
import com.lifeadmin.autopilot.ui.health.HealthSummaryCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Settings: reminder health first, then Follow-up defaults (preset, snooze button, quiet hours),
 * Privacy, AI planning, Appearance, Your data and About. Every control from the previous
 * Settings tab is still here; the layout follows the Flight Plan groups.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit = {},
    showHealth: Boolean = false,
    container: AppContainer? = null,
    onOpenHealth: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val context = LocalContext.current
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var presetSheet by remember { mutableStateOf(false) }
    var snoozeOpen by rememberSaveable { mutableStateOf(false) }
    var quietOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showToast(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export { context.contentResolver.openOutputStream(uri, "wt") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import { context.contentResolver.openInputStream(uri) }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
        item { BackHeader(null, onBack, Modifier.statusBarsPadding().offset(x = (-8).dp)) }
        item { PageTitle("Settings", Modifier.padding(top = 6.dp, bottom = 18.dp)) }
        if (showHealth && container != null) item { HealthSummaryCard(container, onOpen = onOpenHealth) }

        item { SectionTitle("Follow-up", Modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp)) }
        item {
            RowGroup {
                GroupRow(
                    title = "“Save & nudge” uses",
                    subtitle = ReminderPresets.label(s.defaultPreset),
                    subtitleColor = c.signal,
                    onClick = { presetSheet = true },
                    minHeight = 68.dp,
                    modifier = Modifier.testTag("settings_default_preset"),
                )
                GroupRow(
                    title = "Snooze button on notifications",
                    subtitle = snoozeLabel(s.defaultSnooze),
                    onClick = { snoozeOpen = !snoozeOpen },
                    divider = true, minHeight = 68.dp,
                    trailing = { Icon(if (snoozeOpen) AutopilotIcons.ChevronDown else AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp)) },
                    chevron = false,
                )
                if (snoozeOpen) {
                    Column(Modifier.selectableGroup().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                        listOf(SnoozePreset.ONE_HOUR, SnoozePreset.THREE_HOURS, SnoozePreset.TOMORROW_MORNING).forEach { p ->
                            RadioRow(snoozeLabel(p), s.defaultSnooze == p) { viewModel.update(reschedule = false) { it.copy(defaultSnooze = p) } }
                        }
                    }
                }
                SwitchRow(
                    title = "Quiet hours",
                    subtitle = if (s.quietHours.enabled) "Nothing rings ${s.quietHours.start.format(hhmm)}–${s.quietHours.end.format(hhmm)}. " +
                        (if (s.quietHoursBehavior == QuietHoursBehavior.SKIP) "Nudges in that window are skipped." else "Nudges wait for morning.")
                    else "Off — nudges can ring at any time",
                    checked = s.quietHours.enabled,
                    onCheckedChange = { on -> viewModel.update { it.copy(quietHours = it.quietHours.copy(enabled = on)) } },
                    divider = true,
                    switchTestTag = "settings_quiet_switch",
                )
                if (s.quietHours.enabled) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("From ${s.quietHours.start.format(hhmm)}", selected = false, onClick = { pickStart = true })
                            ChoiceChip("Until ${s.quietHours.end.format(hhmm)}", selected = false, onClick = { pickEnd = true })
                        }
                        Row(
                            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { quietOpen = !quietOpen }
                                .semantics { contentDescription = if (quietOpen) "Hide quiet-hours options" else "Show quiet-hours options" },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("During quiet hours", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                            Icon(if (quietOpen) AutopilotIcons.ChevronDown else AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp))
                        }
                        if (quietOpen) Column(Modifier.selectableGroup()) {
                            RadioRow("Hold nudges until quiet hours end and combine them into one", s.quietHoursBehavior == QuietHoursBehavior.DEFER_AND_COALESCE) {
                                viewModel.update { it.copy(quietHoursBehavior = QuietHoursBehavior.DEFER_AND_COALESCE) }
                            }
                            RadioRow("Skip nudges that fall in quiet hours", s.quietHoursBehavior == QuietHoursBehavior.SKIP) {
                                viewModel.update { it.copy(quietHoursBehavior = QuietHoursBehavior.SKIP) }
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Privacy", Modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp)) }
        item {
            RowGroup {
                SwitchRow(
                    title = "Hide task titles on the lock screen",
                    subtitle = "Notifications show “Task reminder” until the phone is unlocked",
                    checked = s.lockScreenPrivacy,
                    onCheckedChange = { on -> viewModel.update(reschedule = false) { it.copy(lockScreenPrivacy = on) } },
                    icon = AutopilotIcons.Lock,
                )
            }
        }

        item { SectionTitle("AI planning · optional", Modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp)) }
        item {
            val modelLabel = com.lifeadmin.autopilot.ai.AiConfig.label(com.lifeadmin.autopilot.ai.AiConfig.modelOrDefault(s.aiModel))
            RowGroup {
                GroupRow(
                    title = "Plan with AI",
                    subtitle = if (s.aiEnabled) "On · $modelLabel · uses your own Anthropic API key" else "Off. Turn a sentence into a reviewed plan, using your own Anthropic API key.",
                    icon = AutopilotIcons.Route, iconTint = c.route, iconBackground = c.routeSoft,
                    onClick = onOpenAiSettings,
                    minHeight = 76.dp,
                    chevron = false,
                    trailing = {
                        Text(
                            if (s.aiEnabled) "On" else "Off", style = MaterialTheme.typography.labelMedium,
                            color = if (s.aiEnabled) c.onRouteSoft else c.ink2,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (s.aiEnabled) c.routeSoft else c.sunken).padding(horizontal = 10.dp, vertical = 5.dp).testTag("ai_status"),
                        )
                    },
                    modifier = Modifier.testTag("open_ai_settings"),
                )
            }
        }

        item { SectionTitle("Appearance", Modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp)) }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.sunken).padding(4.dp).selectableGroup().semantics { contentDescription = "Appearance" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(Appearance.SYSTEM to "System", Appearance.LIGHT to "Light", Appearance.DARK to "Dark").forEach { (a, label) ->
                    val sel = s.appearance == a
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (sel) c.surface else Color.Transparent)
                            .selectable(selected = sel, role = Role.RadioButton, onClick = { viewModel.update(reschedule = false) { it.copy(appearance = a) } })
                            .testTag("appearance_${a.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink) }
                }
            }
        }

        item { SectionTitle("Your data", Modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp)) }
        item {
            SurfaceCard {
                Text(
                    "Stored only on this phone and excluded from Android cloud backup. Export a copy now and then — without one, a lost phone means lost tasks.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink2,
                )
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Export", onClick = { confirmExport = true }, icon = AutopilotIcons.Export, minHeight = 48.dp, modifier = Modifier.weight(1f).testTag("export_button"))
                    SecondaryButton("Import", onClick = { confirmImport = true }, icon = AutopilotIcons.Import, minHeight = 48.dp, modifier = Modifier.weight(1f).testTag("import_button"))
                }
            }
        }

        item {
            Column(Modifier.padding(top = 28.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AutopilotIcons.Route, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                    Text("Life Admin Autopilot", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
                }
                Text("Works fully offline. No account, analytics or ads. The network is used only when you ask AI planning for something.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = c.ink3)
                TextButton(onClick = onOpenLicenses, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("open_licenses")) {
                    Text("Open-source licences (fonts)", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                }
            }
        }
    }

    if (presetSheet) {
        AutopilotSheet(title = "Save & nudge uses", onDismiss = { presetSheet = false }) {
            Text("The default follow-up for a quick capture. Every task's schedule can still be changed afterwards.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderPreset.entries.forEach { p ->
                    val sel = s.defaultPreset == p
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) c.paper else c.surface)
                            .border(2.dp, if (sel) c.ink else c.line, RoundedCornerShape(18.dp))
                            .selectable(selected = sel, role = Role.RadioButton, onClick = { viewModel.update(reschedule = false) { it.copy(defaultPreset = p) }; presetSheet = false })
                            .testTag("preset_${p.name.lowercase()}")
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(ReminderPresets.shortLabel(p), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink)
                            Text(ReminderPresets.label(p).substringAfter("· "), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                        }
                        if (sel) IconCircle(AutopilotIcons.Check, tint = c.onInverse, background = c.inverse, size = 22.dp)
                    }
                }
            }
            PrimaryButton("Done", onClick = { presetSheet = false }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
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

private fun snoozeLabel(p: SnoozePreset) = when (p) {
    SnoozePreset.ONE_HOUR -> "1 hour"
    SnoozePreset.THREE_HOURS -> "3 hours"
    SnoozePreset.TOMORROW_MORNING -> "Tomorrow morning (09:00)"
}


@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) c.ink else c.lineStrong, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.ink))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}


/** Keeps the previously public helpers available for other screens. */
@Composable
fun Header(text: String) {
    SectionTitle(text, Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp))
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(title = title, subtitle = subtitle, checked = checked, onCheckedChange = onChange)
}
