package com.lifeadmin.autopilot.ui.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import com.lifeadmin.autopilot.notifications.NotificationHealth
import com.lifeadmin.autopilot.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class HealthState(
    val notifications: NotificationHealth,
    val canScheduleExact: Boolean,
    val ignoringBatteryOptimizations: Boolean?,
    val activeReminders: Int,
    val nextTitle: String?,
    val nextAt: Instant?,
    val nextExact: Boolean,
)

private suspend fun loadHealth(container: AppContainer): HealthState = withContext(Dispatchers.IO) {
    val next = container.reminderEngine.nextScheduled()
    val title = next?.let { container.database.taskDao().get(it.taskId)?.title }
    val power = container.context.getSystemService(PowerManager::class.java)
    HealthState(
        notifications = container.notificationPublisher.health(),
        canScheduleExact = container.alarmDriver.canScheduleExact(),
        ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(container.context.packageName),
        activeReminders = container.database.occurrenceDao().getWithStatus(listOf(com.lifeadmin.autopilot.domain.model.OccurrenceStatus.SCHEDULED, com.lifeadmin.autopilot.domain.model.OccurrenceStatus.PLANNED)).size,
        nextTitle = title,
        nextAt = next?.plannedAt,
        nextExact = next?.exact ?: false,
    )
}

/** Recomputes on resume (permissions may have changed in system settings) and on settings changes. */
@Composable
private fun rememberHealth(container: AppContainer, settings: AppSettings): HealthState? {
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<HealthState?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh, settings) {
        val loaded = runCatching { loadHealth(container) }.getOrNull()
        withContext(Dispatchers.Main.immediate) { state = loaded }
    }
    return state
}

/** Today-screen warning shown only when reminders exist but cannot be displayed. */
@Composable
fun HealthBanner(container: AppContainer, onFix: () -> Unit) {
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
    val h = rememberHealth(container, settings) ?: return
    val blocked = !h.notifications.canPostReminders || !settings.allowReminderNotifications
    if (!blocked || h.activeReminders == 0) return
    Card(
        Modifier.fillMaxWidth().padding(12.dp).testTag("health_banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Reminders can't be shown", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                when {
                    !settings.allowReminderNotifications -> "Reminder notifications are turned off in Settings. Your tasks and schedules are kept."
                    !h.notifications.permissionGranted -> "Notification permission is off. Your tasks and schedules are kept; reminders are logged as not shown."
                    else -> "Notifications for this app or its reminder channel are blocked in Android settings."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onFix) { Text("Review") }
        }
    }
}

@Composable
fun HealthSection(container: AppContainer, requestPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
    val h = rememberHealth(container, settings)
    var explainExact by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("health_section")) {
        Text("Reminder health", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        if (h == null) {
            Text("Checking…")
            return@Column
        }
        StatusLine("Notification permission", if (h.notifications.permissionGranted) "Allowed" else "Not allowed", ok = h.notifications.permissionGranted)
        if (!h.notifications.permissionGranted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionRequested) {
                    OutlinedButton(onClick = requestPermission) { Text("Allow notifications") }
                }
                OutlinedButton(onClick = { context.openAppNotificationSettings() }) { Text("Open notification settings") }
            }
        }
        StatusLine("App notifications", if (h.notifications.appNotificationsEnabled) "On" else "Off", ok = h.notifications.appNotificationsEnabled)
        StatusLine("“Task reminders” channel", if (h.notifications.reminderChannelEnabled) "On" else "Blocked", ok = h.notifications.reminderChannelEnabled)
        OutlinedButton(onClick = { context.openChannelSettings(AndroidNotificationPublisher.CHANNEL_REMINDERS) }) { Text("Sound & importance…") }

        Row(
            Modifier.fillMaxWidth().toggleable(settings.allowReminderNotifications, role = Role.Switch) { on ->
                scope.launch { container.settingsRepository.update { it.copy(allowReminderNotifications = on) } }
            }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Reminder notifications", style = MaterialTheme.typography.bodyLarge)
                Text("Off keeps schedules but shows nothing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.allowReminderNotifications, onCheckedChange = null)
        }

        Text("Delivery timing", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (settings.precisionMode && h.canScheduleExact) "Precise: exact alarms are used and should arrive on time, though Android can still delay them in rare cases."
            else "Best-effort: Android may delay reminders by several minutes, more in Battery Saver or deep sleep (Doze).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth().toggleable(settings.precisionMode, role = Role.Switch) { on ->
                if (on && !h.canScheduleExact) explainExact = true
                scope.launch {
                    container.settingsRepository.update { it.copy(precisionMode = on) }
                    container.reconcileAll("precision_mode")
                }
            }.padding(vertical = 8.dp).testTag("precision_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Precise timing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Exact-alarm access: " + if (h.canScheduleExact) "granted" else "not granted (falls back to best-effort)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.precisionMode, onCheckedChange = null)
        }
        if (settings.precisionMode && !h.canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(onClick = { context.openExactAlarmSettings() }) { Text("Grant exact-alarm access…") }
        }

        Text("Battery", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            when (h.ignoringBatteryOptimizations) {
                true -> "Battery usage is unrestricted for this app."
                else -> "Battery optimisation is on (Android default). Some phones — e.g. Samsung “Sleeping apps” — can delay or block reminders. If reminders arrive late, set this app's battery usage to Unrestricted."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { context.openAppDetails() }) { Text("App settings…") }

        Text("Schedule", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        val zone = container.zoneProvider.current()
        val now = container.clock.now()
        Text(
            h.nextAt?.let { "Next reminder: ${h.nextTitle ?: "task"} · ${Format.relative(it, now, zone)} (${if (h.nextExact) "exact" else "best-effort"})" } ?: "No reminders scheduled",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("health_next"),
        )
        Text(
            settings.lastReconcileAt?.let { "Last check: ${Format.relative(it, now, zone)} — ${settings.lastReconcileSummary.orEmpty()}" } ?: "Not checked yet",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { container.reconcileAll("manual") } }, modifier = Modifier.testTag("reconcile_now")) { Text("Re-check schedule") }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val text = container.diagnostics.report()
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostics", text))
                    copied = true
                }
            },
            modifier = Modifier.testTag("copy_diagnostics"),
        ) { Text(if (copied) "Diagnostics copied" else "Copy diagnostics (no task contents)") }
        Text(
            "If you force-stop the app, Android cancels its alarms until you open it again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (explainExact) {
        AlertDialog(
            onDismissRequest = { explainExact = false },
            title = { Text("Allow exact alarms?") },
            text = {
                Text(
                    "Precise timing needs Android's “Alarms & reminders” special access so reminders like 09:00 aren't shifted by battery optimisations. " +
                        "It uses slightly more battery. Without it, reminders stay best-effort.",
                )
            },
            confirmButton = { TextButton(onClick = { explainExact = false; context.openExactAlarmSettings() }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { explainExact = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}

private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun Context.openAppNotificationSettings() =
    safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

private fun Context.openChannelSettings(channel: String) =
    safeStart(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel))

private fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        safeStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
    }
}

private fun Context.openAppDetails() =
    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
