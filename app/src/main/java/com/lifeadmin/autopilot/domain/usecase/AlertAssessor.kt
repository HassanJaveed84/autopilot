package com.lifeadmin.autopilot.domain.usecase

import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.InterruptionFilter
import com.lifeadmin.autopilot.domain.model.RingerMode

/** What a reminder posted right now would most likely do, as far as public APIs reveal. */
enum class AlertLevel {
    /** Will not appear at all (permission / app / channel blocked or in-app switch off). */
    BLOCKED,
    /** Appears in the shade but makes no sound and no vibration. */
    SILENT,
    /** Vibration only (phone in Vibrate mode, channel vibrates). */
    VIBRATE_ONLY,
    /** Sound (and vibration if the channel vibrates). */
    AUDIBLE,
    /** Depends on Do Not Disturb exceptions or settings we cannot read. */
    UNCERTAIN,
}

enum class AlertIssue {
    PERMISSION_DENIED, APP_NOTIFICATIONS_OFF, CHANNEL_BLOCKED, IN_APP_SWITCH_OFF,
    CHANNEL_SILENT_IMPORTANCE, CHANNEL_NO_SOUND, CHANNEL_NO_VIBRATION,
    PHONE_VIBRATE_MODE, PHONE_SILENT_MODE, NOTIFICATION_VOLUME_ZERO,
    DND_ON, NO_POPUP,
}

data class AlertAssessment(
    val level: AlertLevel,
    val popUp: Boolean,
    val issues: List<AlertIssue>,
    val summary: String,
)

data class AlertInputs(
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val inAppEnabled: Boolean,
    val environment: DeliveryEnvironment,
)

/**
 * Pure rules from documented Android behaviour:
 * - Channel importance ≤ LOW (2) never makes sound/vibration; HIGH (4) and above can pop up.
 * - Vibrate ringer mode mutes notification sounds; Silent mutes sound and vibration.
 * - Do Not Disturb (any filter but ALL) silences notifications unless the channel may bypass it or
 *   the user's DND exceptions allow it (exceptions are not readable without extra permission).
 *
 * In Vibrate mode AOSP may convert a notification sound into the default vibration even if the
 * channel's vibration is off; OEMs differ, so that case is reported as likely silent with a fix
 * (turn on the channel's vibration) rather than as certain.
 */
object AlertAssessor {
    private const val IMPORTANCE_NONE = 0
    private const val IMPORTANCE_LOW = 2
    private const val IMPORTANCE_HIGH = 4

    fun assess(i: AlertInputs): AlertAssessment {
        val env = i.environment
        val issues = mutableListOf<AlertIssue>()
        if (!i.permissionGranted) issues += AlertIssue.PERMISSION_DENIED
        if (!i.appNotificationsEnabled) issues += AlertIssue.APP_NOTIFICATIONS_OFF
        if (env.channelExists && env.channelImportance == IMPORTANCE_NONE) issues += AlertIssue.CHANNEL_BLOCKED
        if (!i.inAppEnabled) issues += AlertIssue.IN_APP_SWITCH_OFF
        if (issues.isNotEmpty()) {
            return AlertAssessment(AlertLevel.BLOCKED, false, issues, "Reminders will not be shown at all.")
        }

        val importance = env.channelImportance ?: 3
        val popUp = importance >= IMPORTANCE_HIGH
        if (!popUp) issues += AlertIssue.NO_POPUP
        if (importance <= IMPORTANCE_LOW) {
            issues += AlertIssue.CHANNEL_SILENT_IMPORTANCE
            return AlertAssessment(AlertLevel.SILENT, false, issues, "The reminder channel is set to Silent: reminders appear in the shade without sound or vibration.")
        }
        val sound = env.channelHasSound ?: true
        val vibrate = env.channelVibrates ?: false
        if (!sound) issues += AlertIssue.CHANNEL_NO_SOUND
        if (!vibrate) issues += AlertIssue.CHANNEL_NO_VIBRATION
        val volumeZero = env.notificationVolume == 0

        val base: Pair<AlertLevel, String> = when (env.ringerMode) {
            RingerMode.SILENT -> {
                issues += AlertIssue.PHONE_SILENT_MODE
                AlertLevel.SILENT to "The phone is in Silent mode, so reminders make no sound or vibration."
            }
            RingerMode.VIBRATE -> {
                issues += AlertIssue.PHONE_VIBRATE_MODE
                if (vibrate) AlertLevel.VIBRATE_ONLY to "The phone is in Vibrate mode: reminders vibrate but make no sound."
                else AlertLevel.SILENT to "The phone is in Vibrate mode and this app's reminder channel has vibration turned off, so reminders will most likely be silent."
            }
            RingerMode.NORMAL, RingerMode.UNKNOWN -> {
                if (volumeZero) issues += AlertIssue.NOTIFICATION_VOLUME_ZERO
                when {
                    sound && !volumeZero -> AlertLevel.AUDIBLE to (if (vibrate) "Reminders play a sound and vibrate." else "Reminders play a sound (no vibration).")
                    vibrate -> AlertLevel.VIBRATE_ONLY to "Reminders vibrate but make no sound (no channel sound or notification volume is 0)."
                    else -> AlertLevel.SILENT to "Reminders have no sound and no vibration configured."
                }
            }
        }
        // "Alarms only" and "Total silence" silence ordinary notifications; a channel's DND bypass
        // only applies to Priority mode.
        val strictDnd = env.interruptionFilter == InterruptionFilter.ALARMS || env.interruptionFilter == InterruptionFilter.NONE
        if (strictDnd) {
            issues += AlertIssue.DND_ON
            if (base.first == AlertLevel.AUDIBLE || base.first == AlertLevel.VIBRATE_ONLY) {
                return AlertAssessment(AlertLevel.SILENT, popUp, issues, "Do Not Disturb is set to alarms only or total silence: reminders make no sound or vibration.")
            }
        }
        val dnd = env.interruptionFilter == InterruptionFilter.PRIORITY
        if (dnd && env.channelBypassesDnd != true) {
            issues += AlertIssue.DND_ON
            if (base.first == AlertLevel.AUDIBLE || base.first == AlertLevel.VIBRATE_ONLY) {
                return AlertAssessment(AlertLevel.UNCERTAIN, popUp, issues, "Do Not Disturb is on: reminders may be silenced unless your DND exceptions allow this app.")
            }
        }
        val level = if (env.ringerMode == RingerMode.UNKNOWN && base.first == AlertLevel.AUDIBLE) AlertLevel.UNCERTAIN else base.first
        return AlertAssessment(level, popUp, issues, base.second)
    }
}
