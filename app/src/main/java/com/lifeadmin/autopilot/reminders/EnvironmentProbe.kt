package com.lifeadmin.autopilot.reminders

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.InterruptionFilter
import com.lifeadmin.autopilot.domain.model.RingerMode
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher

/** Reads the device state that decides whether a posted reminder is noticeable. */
fun interface EnvironmentProbe {
    fun snapshot(): DeliveryEnvironment

    companion object {
        val UNKNOWN = EnvironmentProbe { DeliveryEnvironment() }
    }
}

/**
 * Uses only public APIs that need no extra permission. Each read is guarded: some OEM builds
 * throw for unusual states, and diagnostics must never crash delivery.
 */
class AndroidEnvironmentProbe(private val context: Context, private val alarms: AlarmDriver) : EnvironmentProbe {
    override fun snapshot(): DeliveryEnvironment {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val channel = runCatching { nm?.getNotificationChannel(AndroidNotificationPublisher.CHANNEL_REMINDERS) }.getOrNull()
        return DeliveryEnvironment(
            ringerMode = runCatching {
                when (audio?.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
                    AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                    AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
                    else -> RingerMode.UNKNOWN
                }
            }.getOrDefault(RingerMode.UNKNOWN),
            interruptionFilter = runCatching {
                when (nm?.currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_ALL -> InterruptionFilter.ALL
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> InterruptionFilter.PRIORITY
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> InterruptionFilter.ALARMS
                    NotificationManager.INTERRUPTION_FILTER_NONE -> InterruptionFilter.NONE
                    else -> InterruptionFilter.UNKNOWN
                }
            }.getOrDefault(InterruptionFilter.UNKNOWN),
            notificationVolume = runCatching { audio?.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull(),
            notificationVolumeMax = runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull(),
            channelExists = channel != null,
            channelImportance = channel?.importance,
            channelHasSound = channel?.let { it.sound != null },
            channelVibrates = channel?.shouldVibrate(),
            channelBypassesDnd = channel?.canBypassDnd(),
            deviceIdle = runCatching { power?.isDeviceIdleMode }.getOrNull(),
            powerSave = runCatching { power?.isPowerSaveMode }.getOrNull(),
            ignoringBatteryOptimizations = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted }.getOrNull()
            } else null,
            standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }.getOrNull()
            } else null,
            canScheduleExact = runCatching { alarms.canScheduleExact() }.getOrDefault(false),
        )
    }
}
