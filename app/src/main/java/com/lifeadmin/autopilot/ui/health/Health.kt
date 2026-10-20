package com.lifeadmin.autopilot.ui.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSnackbar
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.GroupDivider
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RowGroup
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.SwitchRow
import com.lifeadmin.autopilot.ui.components.showToast
import com.lifeadmin.autopilot.ui.theme.AutopilotColors
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
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

/** True when reminders exist and would be blocked or probably silent: drives the settings warning dot. */
@Composable
fun rememberHealthIssue(container: AppContainer): Boolean {
    val q = rememberQuickAssessment(container) ?: return false
    if (q.activeReminders == 0) return false
    return q.assessment.level == AlertLevel.BLOCKED || q.assessment.level == AlertLevel.SILENT || q.recentLateApproximate != null
}

private fun levelHeadline(level: AlertLevel): String = when (level) {
    AlertLevel.BLOCKED -> "Reminders can't be shown"
    AlertLevel.SILENT -> "Reminders will probably be silent"
    AlertLevel.VIBRATE_ONLY -> "Reminders vibrate (no sound)"
    AlertLevel.AUDIBLE -> "Reminders should make a sound"
    AlertLevel.UNCERTAIN -> "Reminders may be silenced"
}

private fun shortHeadline(level: AlertLevel): String = when (level) {
    AlertLevel.BLOCKED -> "Reminders are blocked"
    AlertLevel.SILENT -> "Reminders will be silent"
    AlertLevel.VIBRATE_ONLY -> "Reminders vibrate only"
    AlertLevel.AUDIBLE -> "Reminders are working"
    AlertLevel.UNCERTAIN -> "Reminders may be silenced"
}

/** Background, foreground and icon tint for a health level (soft done / signal / danger). */
private fun levelColors(c: AutopilotColors, level: AlertLevel): Triple<Color, Color, Color> = when (level) {
    AlertLevel.BLOCKED -> Triple(c.dangerSoft, c.onDangerSoft, c.danger)
    AlertLevel.SILENT, AlertLevel.UNCERTAIN, AlertLevel.VIBRATE_ONLY -> Triple(c.signalSoft, c.onSignalSoft, c.signal)
    AlertLevel.AUDIBLE -> Triple(c.doneSoft, c.onDoneSoft, c.done)
}

private fun levelIcon(level: AlertLevel): ImageVector = when (level) {
    AlertLevel.AUDIBLE -> AutopilotIcons.Sound
    AlertLevel.VIBRATE_ONLY -> AutopilotIcons.Vibrate
    else -> AutopilotIcons.Warn
}

/**
 * Today-screen warning: when reminders exist and would be blocked or (probably) silent, or when
 * the latest reminder measurably arrived late on approximate timing.
 */
@Composable
fun HealthBanner(container: AppContainer, onFix: () -> Unit) {
    val c = AutopilotTheme.colors
    val q = rememberQuickAssessment(container) ?: return
    val a = q.assessment
    if (q.activeReminders == 0) return
    if (a.level != AlertLevel.BLOCKED && a.level != AlertLevel.SILENT) {
        val late = q.recentLateApproximate ?: return
        BannerCard(
            AutopilotIcons.Clock, c.signalSoft, c.onSignalSoft, c.signal,
            "Your last reminder arrived ${humanDuration(late)} late",
            "Reminders use approximate timing, so Android may delay them to save battery. Precise timing (with “Alarms & reminders” access) makes them arrive on time.",
            "Review timing", onFix, Modifier.testTag("late_banner"),
        )
        return
    }
    val (bg, fg, tint) = levelColors(c, a.level)
    BannerCard(AutopilotIcons.Warn, bg, fg, tint, levelHeadline(a.level), a.summary + " Your tasks and schedules are kept.", "Review reminder health", onFix, Modifier.testTag("health_banner"))
}

@Composable
private fun BannerCard(icon: ImageVector, bg: Color, fg: Color, tint: Color, title: String, body: String, action: String, onFix: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable(role = Role.Button, onClick = onFix).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
                Text(body, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = fg)
                Text(action, style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Compact entry point shown at the top of Settings. */
@Composable
fun HealthSummaryCard(container: AppContainer, onOpen: () -> Unit) {
    val c = AutopilotTheme.colors
    val q = rememberQuickAssessment(container)
    val level = q?.assessment?.level ?: AlertLevel.UNCERTAIN
    val (bg, fg, tint) = levelColors(c, level)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Reminder health. " + (if (q == null) "Checking" else "${levelHeadline(level)}. ${q.assessment.summary}") + ". Opens reminder health and tests." }
            .testTag("open_health").padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconCircle(if (q == null) AutopilotIcons.Refresh else if (level == AlertLevel.AUDIBLE) AutopilotIcons.Shield else levelIcon(level), tint = tint, background = c.surface, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (q == null) "Checking reminder health…" else shortHeadline(level), style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.semantics { heading() })
            Text(if (q == null) "Reading what Android reports" else q.assessment.summary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = fg, maxLines = 3)
        }
        Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
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
    val c = AutopilotTheme.colors
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var explainExact by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showToast(it) } }
    // Refresh while a scheduled test is pending so its result appears without leaving the screen.
    LaunchedEffect(recent.firstOrNull()?.id) { viewModel.refresh() }

    Scaffold(containerColor = c.paper, contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) { data -> AutopilotSnackbar(data) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("health_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
            item { BackHeader("Reminder health", onBack, Modifier.statusBarsPadding().offset(x = (-8).dp), backLabel = "Back") }
            item { Hero(state.assessment, state.loading) }

            item {
                SurfaceCard(modifier = Modifier.padding(top = 14.dp), radius = 22.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)) {
                    Text("Test a nudge", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(
                        "Uses the same notification channel and alarm timing as real nudges. Android can only say it delivered one — you know if you noticed.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(top = 4.dp),
                    )
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Send a test now", onClick = viewModel::sendTestNow, minHeight = 52.dp, modifier = Modifier.fillMaxWidth().testTag("test_now"))
                        SecondaryButton("Test with phone locked · in 2 min", onClick = viewModel::scheduleTest, minHeight = 52.dp, modifier = Modifier.fillMaxWidth().testTag("test_in_2_min"))
                    }
                    state.pendingTestAt?.let {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.sunken).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AutopilotIcons.Clock, contentDescription = null, tint = c.ink2, modifier = Modifier.size(20.dp))
                            Text("Test reminder due ${Format.time(it, zone)} — lock the phone and wait.", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                        }
                    }
                    if (state.lastTest.isNotEmpty()) TestResult(state.lastTest, zone)
                }
            }

            item { SectionTitle("What Android reports", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                AlertReport(
                    state,
                    onOpenChannel = { context.openChannelSettings() }, onOpenApp = { context.openAppNotificationSettings() },
                    onOpenDnd = { context.openDndSettings() }, onOpenSound = { context.openSoundSettings() },
                    onOpenAppDetails = { context.openAppDetails() }, onGrantExact = { context.openExactAlarmSettings() },
                    requestPermission = requestPermission,
                )
            }
            item {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Reminder channel settings", onClick = { context.openChannelSettings() }, minHeight = 44.dp, modifier = Modifier.testTag("open_channel_settings"))
                    SecondaryButton("App notification settings", onClick = { context.openAppNotificationSettings() }, minHeight = 44.dp)
                    SecondaryButton("Do Not Disturb settings", onClick = { context.openDndSettings() }, minHeight = 44.dp)
                }
                Text(
                    "In channel settings: choose “Alert”, turn on “Vibrate” if your phone is usually in Vibrate mode, and pick a sound. " +
                        "“Show as pop-up” is optional — turn it on if you want reminders to appear over other apps. Android keeps these " +
                        "choices; the app never changes them for you.",
                    style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
                LinkButton(if (showModes) "Hide: how Vibrate, Silent and Do Not Disturb affect reminders" else "How Vibrate, Silent and Do Not Disturb affect reminders", onClick = { showModes = !showModes })
                if (showModes) ModesExplanation()
            }

            item { SectionTitle("Timing", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item { TimingDetails(state, zone, now(), onPrecision = { on -> if (on && !state.environment.canScheduleExact) explainExact = true; viewModel.setPrecision(on) }, onGrant = { context.openExactAlarmSettings() }) }
            item { BatteryDetails(state.environment, onOpenApp = { context.openAppDetails() }) }

            item { SectionTitle("App switches", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                RowGroup {
                    SwitchRow(
                        title = "Reminder notifications", subtitle = "Off keeps schedules but shows nothing",
                        checked = state.settings.allowReminderNotifications, onCheckedChange = viewModel::setNotificationsEnabled, icon = AutopilotIcons.Bell,
                    )
                    GroupDivider()
                    Column(Modifier.padding(16.dp)) {
                        SecondaryButton("Re-check schedule", onClick = viewModel::recheck, icon = AutopilotIcons.Refresh, minHeight = 44.dp, modifier = Modifier.testTag("reconcile_now"))
                        Text(
                            state.settings.lastReconcileAt?.let { "Last check: ${Format.relative(it, now(), zone)} — ${state.settings.lastReconcileSummary.orEmpty()}" } ?: "Not checked yet",
                            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item { SectionTitle("Recent delivery log", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                Text("Actual times on this phone. Contains no task titles or notes.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(start = 4.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Copy diagnostics", onClick = {
                        scope.launch {
                            val text = viewModel.diagnosticsText()
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Reminder diagnostics", text))
                            snackbar.showToast("Diagnostics copied")
                        }
                    }, icon = AutopilotIcons.Clip, minHeight = 44.dp, modifier = Modifier.testTag("copy_diagnostics"))
                    SecondaryButton("Share…", onClick = {
                        scope.launch {
                            val text = viewModel.diagnosticsText()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Life Admin reminder diagnostics").putExtra(Intent.EXTRA_TEXT, text)
                            runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }, icon = AutopilotIcons.Export, minHeight = 44.dp)
                }
            }
            if (recent.isEmpty()) {
                item { Text("Nothing recorded yet.", modifier = Modifier.padding(top = 12.dp, start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = c.ink2) }
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
private fun Hero(a: AlertAssessment?, loading: Boolean) {
    val c = AutopilotTheme.colors
    val level = a?.level ?: AlertLevel.UNCERTAIN
    val (bg, fg, tint) = levelColors(c, level)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(24.dp)).background(bg).padding(horizontal = 20.dp, vertical = 22.dp).testTag("health_status")) {
        IconCircle(if (loading || a == null) AutopilotIcons.Refresh else levelIcon(level), tint = tint, background = c.surface, size = 56.dp)
        Text(
            if (loading || a == null) "Checking…" else levelHeadline(level),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 31.sp), color = fg,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() }.testTag("health_headline"),
        )
        a?.let {
            Text(it.summary, style = MaterialTheme.typography.bodyMedium, color = fg)
            it.issues.mapNotNull(::issueAdvice).forEach { f -> Text("• $f", style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.padding(top = 4.dp)) }
        }
        Text(
            "Based on settings Android reports. It can't confirm you actually heard or felt a reminder — use the test below.",
            style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.padding(top = 8.dp),
        )
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

private data class ReportRow(val icon: ImageVector, val label: String, val value: String, val ok: Boolean, val help: String? = null, val fix: (() -> Unit)? = null, val tag: String? = null, val informational: Boolean = false)

@Composable
private fun AlertReport(
    state: HealthUiState,
    onOpenChannel: () -> Unit, onOpenApp: () -> Unit, onOpenDnd: () -> Unit, onOpenSound: () -> Unit, onOpenAppDetails: () -> Unit, onGrantExact: () -> Unit,
    requestPermission: () -> Unit,
) {
    val env = state.environment
    val canAsk = !state.health.permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.settings.notificationPermissionRequested
    val rows = buildList {
        add(ReportRow(AutopilotIcons.Bell, "Notification permission", if (state.health.permissionGranted) "Allowed" else "Not allowed", state.health.permissionGranted, "Without it, no reminder can be shown.", if (canAsk) requestPermission else onOpenApp))
        add(ReportRow(AutopilotIcons.Bell, "App notifications", if (state.health.appNotificationsEnabled) "On" else "Off", state.health.appNotificationsEnabled, "Turned off for the whole app in Android settings.", onOpenApp))
        add(ReportRow(AutopilotIcons.Bell, "“Task reminders” channel", importanceLabel(env), env.channelImportance != 0, "The reminder category is blocked in Android.", onOpenChannel))
        add(ReportRow(AutopilotIcons.Sound, "Nudge sound", when (env.channelHasSound) { true -> "On"; false -> "None"; null -> "Unknown" }, env.channelHasSound != false, "Pick a sound in channel settings if you want one.", onOpenChannel))
        add(ReportRow(AutopilotIcons.Vibrate, "Vibration", when (env.channelVibrates) { true -> "On"; false -> "Off"; null -> "Unknown" }, env.channelVibrates == true, "Turn on “Vibrate” in channel settings to feel reminders in Vibrate mode.", onOpenChannel, tag = "channel_vibration"))
        add(ReportRow(AutopilotIcons.Info, "Pop-up on screen", if ((env.channelImportance ?: 0) >= 4) "On" else "Off (optional)", true, informational = true))
        add(ReportRow(AutopilotIcons.Sound, "Phone sound mode", when (env.ringerMode) { RingerMode.NORMAL -> "Sound"; RingerMode.VIBRATE -> "Vibrate"; RingerMode.SILENT -> "Silent"; RingerMode.UNKNOWN -> "Unknown" }, env.ringerMode == RingerMode.NORMAL, "In Vibrate or Silent mode, reminders don't play a sound.", onOpenSound, tag = "ringer_mode"))
        add(ReportRow(AutopilotIcons.Dnd, "Do Not Disturb", when (env.interruptionFilter) { InterruptionFilter.ALL -> "Off"; InterruptionFilter.PRIORITY -> "On (priority only)"; InterruptionFilter.ALARMS -> "On (alarms only)"; InterruptionFilter.NONE -> "On (total silence)"; InterruptionFilter.UNKNOWN -> "Unknown" }, env.interruptionFilter == InterruptionFilter.ALL, "Add this app as an exception if reminders should break through.", onOpenDnd))
        env.notificationVolume?.let { add(ReportRow(AutopilotIcons.Sound, "Notification volume", "$it of ${env.notificationVolumeMax ?: "?"}", it > 0, "Turn up notification volume, not just media.", onOpenSound)) }
        add(ReportRow(AutopilotIcons.Alarm, "Exact alarms", if (env.canScheduleExact) "Allowed" else "Not granted", env.canScheduleExact || !state.settings.precisionMode, "Precise timing needs “Alarms & reminders” access.", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) onGrantExact else null))
        add(ReportRow(AutopilotIcons.Battery, "Battery", when (env.ignoringBatteryOptimizations) { true -> "Unrestricted"; false -> "Optimized"; null -> "Unknown" }, env.ignoringBatteryOptimizations == true, "Android may hold nudges while the phone sleeps. Choose “Unrestricted” for this app.", onOpenAppDetails))
    }
    RowGroup { rows.forEachIndexed { i, r -> ReportRowView(r, divider = i > 0) } }
}

@Composable
private fun ReportRowView(r: ReportRow, divider: Boolean) {
    val c = AutopilotTheme.colors
    if (divider) GroupDivider()
    val problem = !r.ok && !r.informational
    Column(
        Modifier.fillMaxWidth().background(if (problem) c.signalSoft else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp)
            .let { if (r.tag != null) it.testTag(r.tag) else it }
            .semantics(mergeDescendants = true) { contentDescription = "${r.label}: ${r.value}" + (if (problem) ". Needs attention" else "") },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(r.icon, contentDescription = null, tint = if (problem) c.onSignalSoft else c.ink2, modifier = Modifier.size(20.dp))
            Text(r.label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(r.value, style = MaterialTheme.typography.labelLarge, color = if (problem) c.onSignalSoft else c.ink2)
            if (!r.informational) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (r.ok) c.doneSoft else c.signal), contentAlignment = Alignment.Center) {
                    Icon(if (r.ok) AutopilotIcons.Check else AutopilotIcons.Warn, contentDescription = null, tint = if (r.ok) c.onDoneSoft else c.onSignal, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (problem && r.help != null) {
            Row(Modifier.padding(start = 32.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.help, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.weight(1f))
                if (r.fix != null) PrimaryButton("Fix", onClick = r.fix, minHeight = 40.dp)
            }
        }
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
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = AutopilotTheme.colors.ink2, modifier = Modifier.padding(horizontal = 4.dp).testTag("modes_explanation"),
    )
}

@Composable
private fun TimingDetails(state: HealthUiState, zone: ZoneId, now: Instant, onPrecision: (Boolean) -> Unit, onGrant: () -> Unit) {
    val c = AutopilotTheme.colors
    val env = state.environment
    val precise = state.settings.precisionMode && env.canScheduleExact
    RowGroup {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (precise) "Precise timing is active: reminders use exact alarms."
                else "Approximate timing: Android may deliver reminders many minutes late (it batches alarms to save battery, more in deep sleep or with battery restrictions).",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("timing_mode"), color = if (precise) c.done else c.ink,
            )
        }
        SwitchRow(
            title = "Precise timing",
            subtitle = "Exact-alarm access (“Alarms & reminders”): " + if (env.canScheduleExact) "granted" else "not granted",
            checked = state.settings.precisionMode, onCheckedChange = onPrecision, icon = AutopilotIcons.Alarm, divider = true,
            switchTestTag = "precision_toggle",
        )
        if (state.settings.precisionMode && !env.canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SecondaryButton("Grant “Alarms & reminders” access…", onClick = onGrant, minHeight = 44.dp)
                Text("Until granted, reminders stay approximate.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val ls = state.lateStats
        Column(Modifier.padding(16.dp)) {
            if (ls.deliveries > 0) {
                Text(
                    "Last ${ls.deliveries} deliveries: ${ls.lateOver5Min} alarms arrived more than 5 min late" +
                        (ls.worst?.let { " (worst ${RoomDiagnosticsLog.formatLateness(it)})" } ?: "") + "." +
                        (if (ls.caughtUp > 0) " ${ls.caughtUp} were caught up later (alarm not received, phone off or app force-stopped)." else ""),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink, modifier = Modifier.testTag("late_stats"),
                )
                if (ls.lateOver5Min > 0 && !precise) Text("Turning on Precise timing is the most effective fix for late reminders.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.signal, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                state.nextAt?.let { "Next reminder: ${state.nextTitle ?: "task"} · ${Format.relative(it, now, zone)} (${if (state.nextExact) "precise" else "approximate"})" } ?: "No reminders scheduled",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(top = if (ls.deliveries > 0) 6.dp else 0.dp).testTag("health_next"),
            )
        }
    }
}

@Composable
private fun BatteryDetails(env: DeliveryEnvironment, onOpenApp: () -> Unit) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)) {
        Text("Battery", style = MaterialTheme.typography.labelLarge, color = c.ink2)
        Status("Battery optimisation", when (env.ignoringBatteryOptimizations) { true -> "Unrestricted"; false -> "Optimised (default)"; null -> "Unknown" }, env.ignoringBatteryOptimizations == true)
        env.backgroundRestricted?.let { Status("Background restriction", if (it) "Restricted" else "Not restricted", !it) }
        env.standbyBucket?.let { Status("App standby", bucketName(it), it <= 20) }
        if (env.powerSave == true) Status("Battery Saver", "On", false)
        Text(
            "Samsung: Settings › Apps › Life Admin Autopilot › Battery › choose “Unrestricted”, and make sure the app is not under " +
                "Settings › Battery › Background usage limits › “Sleeping apps” or “Deep sleeping apps” (optionally add it to “Never sleeping apps”). " +
                "The app never requests a battery exemption itself.",
            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp),
        )
        SecondaryButton("Open app settings…", onClick = onOpenApp, minHeight = 44.dp, modifier = Modifier.padding(top = 8.dp))
        Text("Force stop cancels all reminders until you open the app again.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
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
    val c = AutopilotTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).let { if (tag != null) it.testTag(tag) else it }) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (ok) c.done else c.signal)
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
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 12.dp).testTag("test_result")) {
        Text("Last test", style = MaterialTheme.typography.labelLarge, color = c.ink2)
        entries.forEach { e ->
            val time = e.at.atZone(zone).format(hms)
            val text = when (e.type) {
                DiagnosticType.TEST_SCHEDULED -> "$time scheduled for ${e.triggerAt?.atZone(zone)?.format(hms)} (${if (e.exact == true) "precise" else "approximate"})"
                DiagnosticType.TEST_ALARM_RECEIVED -> "$time alarm arrived ${e.lateness?.let(RoomDiagnosticsLog::formatLateness) ?: ""}"
                DiagnosticType.TEST_NOTIFICATION -> "$time notification posted (${e.outcome?.replace('_', ' ')})"
                else -> "$time ${e.type.name.lowercase().replace('_', ' ')} ${e.outcome.orEmpty()}"
            }
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink)
        }
        Text("Did you hear and feel it? Android can only confirm it was posted.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LedgerRow(e: DiagnosticEntry, zone: ZoneId) {
    val c = AutopilotTheme.colors
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp)) {
        Text("${e.at.atZone(zone).format(dayHms)}  $label", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink)
        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = c.ink2)
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

fun Context.openSoundSettings() = safeStart(Intent(Settings.ACTION_SOUND_SETTINGS))

fun Context.openAppDetails() =
    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
