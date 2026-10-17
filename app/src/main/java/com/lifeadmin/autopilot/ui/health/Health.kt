package com.lifeadmin.autopilot.ui.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.domain.model.InterruptionFilter
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.RingerMode
import com.lifeadmin.autopilot.domain.usecase.AlertAssessment
import com.lifeadmin.autopilot.domain.usecase.AlertAssessor
import com.lifeadmin.autopilot.domain.usecase.AlertInputs
import com.lifeadmin.autopilot.domain.usecase.AlertIssue
import com.lifeadmin.autopilot.domain.usecase.AlertLevel
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- shared helpers

data class QuickHealth(val assessment: AlertAssessment, val activeReminders: Int, val recentLateApproximate: java.time.Duration?)

private suspend fun quickAssessment(container: AppContainer, settings: AppSettings): QuickHealth = withContext(Dispatchers.IO) {
    val health = container.notificationPublisher.health()
    val env = container.environmentProbe.snapshot()
    val active = container.database.occurrenceDao().getWithStatus(listOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.PLANNED)).size
    QuickHealth(
        AlertAssessor.assess(AlertInputs(health.permissionGranted, health.appNotificationsEnabled, settings.allowReminderNotifications, env)),
        active,
        recentLateApproximateDelivery(container, settings, env.canScheduleExact),
    )
}

/**
 * Lateness of the most recent alarm-delivered reminder if it was > 10 min late on approximate
 * timing within the last 48 h and precise timing isn't active now — the evidence-based case for
 * Precise timing. Catch-ups (boot, app start, periodic check) are excluded: their delay comes from
 * a missing alarm or a phone that was off, which Precise timing would not fix.
 */
suspend fun recentLateApproximateDelivery(container: AppContainer, settings: AppSettings, canExact: Boolean): java.time.Duration? {
    if (settings.precisionMode && canExact) return null
    val last = container.diagnosticsLog.recentOfTypes(setOf(DiagnosticType.POST_REQUESTED), 20)
        .firstOrNull { it.source == "alarm" } ?: return null
    val recent = java.time.Duration.between(last.at, container.clock.now()) <= java.time.Duration.ofHours(48)
    val late = last.lateness ?: return null
    return if (recent && last.exact == false && late > java.time.Duration.ofMinutes(10)) late else null
}

@Composable
private fun rememberQuickAssessment(container: AppContainer): QuickHealth? {
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<QuickHealth?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh, settings) {
        val r = runCatching { quickAssessment(container, settings) }.getOrNull()
        withContext(Dispatchers.Main.immediate) { result = r }
    }
    return result
}

private fun levelHeadline(level: AlertLevel): String = when (level) {
    AlertLevel.BLOCKED -> "Reminders can't be shown"
    AlertLevel.SILENT -> "Reminders will probably be silent"
    AlertLevel.VIBRATE_ONLY -> "Reminders vibrate (no sound)"
    AlertLevel.AUDIBLE -> "Reminders should make a sound"
    AlertLevel.UNCERTAIN -> "Reminders may be silenced"
}

@Composable
private fun levelColors(level: AlertLevel): Pair<Color, Color> = when (level) {
    AlertLevel.BLOCKED, AlertLevel.SILENT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    AlertLevel.UNCERTAIN, AlertLevel.VIBRATE_ONLY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    AlertLevel.AUDIBLE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
}

/**
 * Today-screen warning: when reminders exist and would be blocked or (probably) silent, or when
 * the latest reminder measurably arrived late on approximate timing.
 */
@Composable
fun HealthBanner(container: AppContainer, onFix: () -> Unit) {
    val q = rememberQuickAssessment(container) ?: return
    val a = q.assessment
    val active = q.activeReminders
    if (active == 0) return
    if (a.level != AlertLevel.BLOCKED && a.level != AlertLevel.SILENT) {
        val late = q.recentLateApproximate ?: return
        Card(
            Modifier.fillMaxWidth().padding(12.dp).testTag("late_banner"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Your last reminder arrived ${humanDuration(late)} late", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    "Reminders use approximate timing, so Android may delay them to save battery. Precise timing (with “Alarms & reminders” access) makes them arrive on time.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                TextButton(onClick = onFix) { Text("Review timing") }
            }
        }
        return
    }
    val (bg, fg) = levelColors(a.level)
    Card(Modifier.fillMaxWidth().padding(12.dp).testTag("health_banner"), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(16.dp)) {
            Text(levelHeadline(a.level), style = MaterialTheme.typography.titleMedium, color = fg)
            Text(a.summary + " Your tasks and schedules are kept.", style = MaterialTheme.typography.bodyMedium, color = fg)
            TextButton(onClick = onFix) { Text("Review reminder health") }
        }
    }
}

/** Compact entry point shown at the top of Settings. */
@Composable
fun HealthSummaryCard(container: AppContainer, onOpen: () -> Unit) {
    val q = rememberQuickAssessment(container)
    val level = q?.assessment?.level ?: AlertLevel.UNCERTAIN
    val (bg, fg) = levelColors(level)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("health_summary"),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Reminder health", style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.semantics { heading() })
            Text(if (q == null) "Checking…" else "${levelHeadline(level)}. ${q.assessment.summary}", style = MaterialTheme.typography.bodyMedium, color = fg)
            Button(onClick = onOpen, modifier = Modifier.padding(top = 8.dp).testTag("open_health")) { Text("Open reminder health & tests") }
        }
    }
}

// ---------------------------------------------------------------- full screen

@Composable
fun ReminderHealthScreen(
    viewModel: ReminderHealthViewModel,
    zone: ZoneId,
    now: () -> Instant,
    onBack: () -> Unit,
    requestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var explainExact by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it) } }
    // Refresh while a scheduled test is pending so its result appears without leaving the screen.
    LaunchedEffect(recent.firstOrNull()?.id) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminder health") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("health_list"), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { StatusCard(state.assessment, state.loading) }

            item { Section("Test delivery") }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "Tests use the same notification channel and alarm timing as real reminders. Android accepting a notification " +
                            "doesn't prove you noticed it — tell from the sound/vibration you got.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::sendTestNow, modifier = Modifier.testTag("test_now")) { Text("Send test notification now") }
                        OutlinedButton(onClick = viewModel::scheduleTest, modifier = Modifier.testTag("test_in_2_min")) { Text("Schedule test reminder in 2 minutes") }
                    }
                    state.pendingTestAt?.let {
                        Text("Test reminder due ${Format.time(it, zone)} — lock the phone and wait.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (state.lastTest.isNotEmpty()) TestResult(state.lastTest, zone)
                }
            }

            item { Section("How reminders alert you") }
            item { AlertDetails(state, onOpenChannel = { context.openChannelSettings() }, onOpenApp = { context.openAppNotificationSettings() }, onOpenDnd = { context.openDndSettings() }, requestPermission = requestPermission) }
            item {
                TextButton(onClick = { showModes = !showModes }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showModes) "Hide: how Vibrate, Silent and Do Not Disturb affect reminders" else "How Vibrate, Silent and Do Not Disturb affect reminders")
                }
                if (showModes) ModesExplanation()
            }

            item { Section("Timing") }
            item { TimingDetails(state, zone, now(), onPrecision = { on -> if (on && !state.environment.canScheduleExact) explainExact = true; viewModel.setPrecision(on) }, onGrant = { context.openExactAlarmSettings() }) }
            item { BatteryDetails(state.environment, onOpenApp = { context.openAppDetails() }) }

            item { Section("App switches") }
            item {
                Row(
                    Modifier.fillMaxWidth().toggleable(state.settings.allowReminderNotifications, role = Role.Switch, onValueChange = viewModel::setNotificationsEnabled).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reminder notifications", style = MaterialTheme.typography.bodyLarge)
                        Text("Off keeps schedules but shows nothing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.settings.allowReminderNotifications, onCheckedChange = null)
                }
                OutlinedButton(onClick = viewModel::recheck, modifier = Modifier.padding(horizontal = 20.dp).testTag("reconcile_now")) { Text("Re-check schedule") }
                Text(
                    state.settings.lastReconcileAt?.let { "Last check: ${Format.relative(it, now(), zone)} — ${state.settings.lastReconcileSummary.orEmpty()}" } ?: "Not checked yet",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            item { Section("Recent delivery log") }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Actual times on this phone. Contains no task titles or notes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val text = viewModel.diagnosticsText()
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Reminder diagnostics", text))
                                snackbar.showSnackbar("Diagnostics copied")
                            }
                        }, modifier = Modifier.testTag("copy_diagnostics")) { Text("Copy diagnostics") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val text = viewModel.diagnosticsText()
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Life Admin reminder diagnostics").putExtra(Intent.EXTRA_TEXT, text)
                                runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }) { Text("Share…") }
                    }
                }
            }
            if (recent.isEmpty()) {
                item { Text("Nothing recorded yet.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            items(recent, key = { it.id }) { LedgerRow(it, zone) }
        }
    }

    if (explainExact) {
        AlertDialog(
            onDismissRequest = { explainExact = false },
            title = { Text("Allow exact alarms?") },
            text = {
                Text(
                    "Precise timing asks Android for “Alarms & reminders” access so a 09:00 reminder isn't shifted to save battery. " +
                        "Without it, reminders stay approximate and can arrive many minutes late. Uses slightly more battery.",
                )
            },
            confirmButton = { TextButton(onClick = { explainExact = false; context.openExactAlarmSettings() }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { explainExact = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun Section(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
    }
}

@Composable
private fun StatusCard(a: AlertAssessment?, loading: Boolean) {
    val level = a?.level ?: AlertLevel.UNCERTAIN
    val (bg, fg) = levelColors(level)
    Card(Modifier.fillMaxWidth().padding(12.dp).testTag("health_status"), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (loading || a == null) "Checking…" else levelHeadline(level), style = MaterialTheme.typography.titleLarge, color = fg, modifier = Modifier.testTag("health_headline"))
            a?.let {
                Text(it.summary, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.padding(top = 4.dp))
                val fixes = it.issues.mapNotNull(::issueAdvice)
                fixes.forEach { f -> Text("• $f", style = MaterialTheme.typography.bodyMedium, color = fg) }
            }
            Text(
                "Based on settings Android reports. It can't confirm you actually heard or felt a reminder — use the tests below.",
                style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun issueAdvice(i: AlertIssue): String? = when (i) {
    AlertIssue.PERMISSION_DENIED -> "Allow notifications for this app."
    AlertIssue.APP_NOTIFICATIONS_OFF -> "Turn on notifications for this app in Android settings."
    AlertIssue.CHANNEL_BLOCKED -> "The “Task reminders” category is turned off — turn it on in channel settings."
    AlertIssue.IN_APP_SWITCH_OFF -> "Turn on “Reminder notifications” below."
    AlertIssue.CHANNEL_SILENT_IMPORTANCE -> "In channel settings choose “Alert” instead of “Silent”."
    AlertIssue.CHANNEL_NO_SOUND -> "Channel sound is “None” — pick a sound in channel settings if you want one."
    AlertIssue.CHANNEL_NO_VIBRATION -> "Channel vibration is off — turn on “Vibrate” in channel settings to feel reminders in Vibrate mode."
    AlertIssue.PHONE_VIBRATE_MODE -> "The phone is in Vibrate mode, so no sound plays."
    AlertIssue.PHONE_SILENT_MODE -> "The phone is in Silent mode (no sound or vibration)."
    AlertIssue.NOTIFICATION_VOLUME_ZERO -> "Notification volume is 0."
    AlertIssue.DND_ON -> "Do Not Disturb is on; add this app as an exception if reminders should break through."
    AlertIssue.NO_POPUP -> null // optional preference, explained in the details section
}

@Composable
private fun AlertDetails(state: HealthUiState, onOpenChannel: () -> Unit, onOpenApp: () -> Unit, onOpenDnd: () -> Unit, requestPermission: () -> Unit) {
    val env = state.environment
    Column(Modifier.padding(horizontal = 20.dp)) {
        Status("Notification permission", if (state.health.permissionGranted) "Allowed" else "Not allowed", state.health.permissionGranted)
        if (!state.health.permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.settings.notificationPermissionRequested) {
            OutlinedButton(onClick = requestPermission) { Text("Allow notifications") }
        }
        Status("App notifications", if (state.health.appNotificationsEnabled) "On" else "Off", state.health.appNotificationsEnabled)
        Status("“Task reminders” channel", importanceLabel(env), env.channelImportance != 0)
        Status("Channel sound", when (env.channelHasSound) { true -> "On"; false -> "None"; null -> "Unknown" }, env.channelHasSound != false)
        Status("Channel vibration", when (env.channelVibrates) { true -> "On"; false -> "Off"; null -> "Unknown" }, env.channelVibrates == true, tag = "channel_vibration")
        Status("Pop-up on screen", if ((env.channelImportance ?: 0) >= 4) "On" else "Off (optional)", true)
        Status("Phone sound mode", when (env.ringerMode) { RingerMode.NORMAL -> "Sound"; RingerMode.VIBRATE -> "Vibrate"; RingerMode.SILENT -> "Silent"; RingerMode.UNKNOWN -> "Unknown" }, env.ringerMode == RingerMode.NORMAL, tag = "ringer_mode")
        Status("Do Not Disturb", when (env.interruptionFilter) { InterruptionFilter.ALL -> "Off"; InterruptionFilter.PRIORITY -> "On (priority only)"; InterruptionFilter.ALARMS -> "On (alarms only)"; InterruptionFilter.NONE -> "On (total silence)"; InterruptionFilter.UNKNOWN -> "Unknown" }, env.interruptionFilter == InterruptionFilter.ALL)
        env.notificationVolume?.let { Status("Notification volume", "$it of ${env.notificationVolumeMax ?: "?"}", it > 0) }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenChannel, modifier = Modifier.testTag("open_channel_settings")) { Text("Open reminder channel settings") }
            OutlinedButton(onClick = onOpenApp) { Text("App notification settings") }
            OutlinedButton(onClick = onOpenDnd) { Text("Do Not Disturb settings") }
        }
        Text(
            "In channel settings: choose “Alert”, turn on “Vibrate” if your phone is usually in Vibrate mode, and pick a sound. " +
                "“Show as pop-up” is optional — turn it on if you want reminders to appear over other apps. Android keeps these " +
                "choices; the app never changes them for you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun importanceLabel(env: DeliveryEnvironment): String = when {
    !env.channelExists -> "Not created yet"
    env.channelImportance == 0 -> "Blocked"
    (env.channelImportance ?: 3) <= 2 -> "Silent"
    (env.channelImportance ?: 3) >= 4 -> "Alert + pop-up"
    else -> "Alert"
}

@Composable
private fun ModesExplanation() {
    Text(
        "• Sound mode: reminders play the channel sound (and vibrate if channel vibration is on).\n" +
            "• Vibrate mode: Android mutes notification sounds. You only feel a reminder if the channel's vibration is on " +
            "(some phones substitute a vibration for the sound, but Samsung behaviour varies).\n" +
            "• Silent mode: no sound and no vibration — reminders only appear in the notification shade.\n" +
            "• Do Not Disturb: silences reminders unless you allow this app (or its “Task reminders” category) as an exception.\n" +
            "• Pop-up (“Show as pop-up”) only changes whether the reminder appears on top of the screen; it doesn't add sound.",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp).testTag("modes_explanation"),
    )
}

@Composable
private fun TimingDetails(state: HealthUiState, zone: ZoneId, now: Instant, onPrecision: (Boolean) -> Unit, onGrant: () -> Unit) {
    val env = state.environment
    val precise = state.settings.precisionMode && env.canScheduleExact
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            if (precise) "Precise timing is active: reminders use exact alarms."
            else "Approximate timing: Android may deliver reminders many minutes late (it batches alarms to save battery, more in deep sleep or with battery restrictions).",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("timing_mode"),
            color = if (precise) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Row(
            Modifier.fillMaxWidth().toggleable(state.settings.precisionMode, role = Role.Switch, onValueChange = onPrecision).padding(vertical = 8.dp).testTag("precision_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Precise timing", style = MaterialTheme.typography.bodyLarge)
                Text("Exact-alarm access (“Alarms & reminders”): " + if (env.canScheduleExact) "granted" else "not granted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.settings.precisionMode, onCheckedChange = null)
        }
        if (state.settings.precisionMode && !env.canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(onClick = onGrant) { Text("Grant “Alarms & reminders” access…") }
            Text("Until granted, reminders stay approximate.", style = MaterialTheme.typography.bodySmall)
        }
        val ls = state.lateStats
        if (ls.deliveries > 0) {
            Text(
                "Last ${ls.deliveries} deliveries: ${ls.lateOver5Min} alarms arrived more than 5 min late" +
                    (ls.worst?.let { " (worst ${RoomDiagnosticsLog.formatLateness(it)})" } ?: "") + "." +
                    (if (ls.caughtUp > 0) " ${ls.caughtUp} were caught up later (alarm not received, phone off or app force-stopped)." else ""),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp).testTag("late_stats"),
            )
            if (ls.lateOver5Min > 0 && !precise) Text("Turning on Precise timing is the most effective fix for late reminders.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            state.nextAt?.let { "Next reminder: ${state.nextTitle ?: "task"} · ${Format.relative(it, now, zone)} (${if (state.nextExact) "precise" else "approximate"})" } ?: "No reminders scheduled",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp).testTag("health_next"),
        )
    }
}

@Composable
private fun BatteryDetails(env: DeliveryEnvironment, onOpenApp: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Battery", style = MaterialTheme.typography.labelLarge)
        Status("Battery optimisation", when (env.ignoringBatteryOptimizations) { true -> "Unrestricted"; false -> "Optimised (default)"; null -> "Unknown" }, env.ignoringBatteryOptimizations == true)
        env.backgroundRestricted?.let { Status("Background restriction", if (it) "Restricted" else "Not restricted", !it) }
        env.standbyBucket?.let { Status("App standby", bucketName(it), it <= 20) }
        if (env.powerSave == true) Status("Battery Saver", "On", false)
        Text(
            "Samsung: Settings › Apps › Life Admin Autopilot › Battery › choose “Unrestricted”, and make sure the app is not under " +
                "Settings › Battery › Background usage limits › “Sleeping apps” or “Deep sleeping apps” (optionally add it to “Never sleeping apps”). " +
                "The app never requests a battery exemption itself.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenApp, modifier = Modifier.padding(top = 4.dp)) { Text("Open app settings…") }
        Text("Force stop cancels all reminders until you open the app again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun bucketName(b: Int): String = when {
    b <= 5 -> "Exempt"
    b <= 10 -> "Active"
    b <= 20 -> "Working set"
    b <= 30 -> "Frequent"
    b <= 40 -> "Rare (alarms may be delayed)"
    else -> "Restricted (alarms heavily delayed)"
}

@Composable
private fun Status(label: String, value: String, ok: Boolean, tag: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).let { if (tag != null) it.testTag(tag) else it }) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}

/** "24 min", "2 h 05 min". */
fun humanDuration(d: java.time.Duration): String {
    val m = d.toMinutes()
    return if (m < 60) "$m min" else "${m / 60} h %02d min".format(m % 60)
}

private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dayHms = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")

@Composable
private fun TestResult(entries: List<DiagnosticEntry>, zone: ZoneId) {
    Column(Modifier.padding(top = 8.dp).testTag("test_result")) {
        Text("Last test", style = MaterialTheme.typography.labelLarge)
        entries.forEach { e ->
            val time = e.at.atZone(zone).format(hms)
            val text = when (e.type) {
                DiagnosticType.TEST_SCHEDULED -> "$time scheduled for ${e.triggerAt?.atZone(zone)?.format(hms)} (${if (e.exact == true) "precise" else "approximate"})"
                DiagnosticType.TEST_ALARM_RECEIVED -> "$time alarm arrived ${e.lateness?.let(RoomDiagnosticsLog::formatLateness) ?: ""}"
                DiagnosticType.TEST_NOTIFICATION -> "$time notification posted (${e.outcome?.replace('_', ' ')})"
                else -> "$time ${e.type.name.lowercase().replace('_', ' ')} ${e.outcome.orEmpty()}"
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LedgerRow(e: DiagnosticEntry, zone: ZoneId) {
    val label = when (e.type) {
        DiagnosticType.SCHEDULED -> "Scheduled"
        DiagnosticType.SCHEDULE_FAILED -> "Android refused alarm"
        DiagnosticType.ALARM_RECEIVED -> "Alarm received"
        DiagnosticType.ALARM_IGNORED -> "Alarm ignored"
        DiagnosticType.RECONCILE -> "Schedule check"
        DiagnosticType.POST_REQUESTED -> "Posting notification"
        DiagnosticType.POSTED -> "Notification posted"
        DiagnosticType.POST_FAILED -> "Post failed"
        DiagnosticType.SUPPRESSED -> "Not shown"
        DiagnosticType.MISSED -> "Missed"
        DiagnosticType.SNOOZED -> "Snoozed"
        DiagnosticType.UNSNOOZED -> "Snooze cleared"
        DiagnosticType.CANCELED -> "Canceled"
        DiagnosticType.TEST_NOTIFICATION -> "Test notification"
        DiagnosticType.TEST_SCHEDULED -> "Test scheduled"
        DiagnosticType.TEST_ALARM_RECEIVED -> "Test alarm received"
    }
    val details = buildList {
        e.plannedAt?.let { add("due ${it.atZone(zone).format(hms)}") }
        e.lateness?.let { add(RoomDiagnosticsLog.formatLateness(it)) }
        e.exact?.let { add(if (it) "precise" else "approximate") }
        e.source?.let { add(it.removePrefix("reconcile:")) }
        e.outcome?.let { add(it.replace('_', ' ')) }
    }.joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text("${e.at.atZone(zone).format(dayHms)}  $label", style = MaterialTheme.typography.bodyMedium)
        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- settings intents

private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

fun Context.openAppNotificationSettings() =
    safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

fun Context.openChannelSettings(channel: String = AndroidNotificationPublisher.CHANNEL_REMINDERS) =
    safeStart(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel))

fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        safeStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
    }
}

/** DND settings have no public per-app intent; open the system DND page, falling back to sound settings. */
fun Context.openDndSettings() {
    runCatching { startActivity(Intent("android.settings.ZEN_MODE_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { safeStart(Intent(Settings.ACTION_SOUND_SETTINGS)) }
}

fun Context.openAppDetails() =
    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
