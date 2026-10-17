package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.InterruptionFilter
import com.lifeadmin.autopilot.domain.model.RingerMode
import com.lifeadmin.autopilot.domain.usecase.AlertAssessor
import com.lifeadmin.autopilot.domain.usecase.AlertInputs
import com.lifeadmin.autopilot.domain.usecase.AlertIssue
import com.lifeadmin.autopilot.domain.usecase.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertAssessorTest {
    private fun env(
        ringer: RingerMode = RingerMode.NORMAL, importance: Int = 3, sound: Boolean = true, vibrate: Boolean = true,
        dnd: InterruptionFilter = InterruptionFilter.ALL, bypass: Boolean = false, volume: Int? = 5,
    ) = DeliveryEnvironment(
        ringerMode = ringer, interruptionFilter = dnd, notificationVolume = volume, notificationVolumeMax = 7,
        channelExists = true, channelImportance = importance, channelHasSound = sound, channelVibrates = vibrate, channelBypassesDnd = bypass,
    )

    private fun assess(e: DeliveryEnvironment, permission: Boolean = true, app: Boolean = true, inApp: Boolean = true) =
        AlertAssessor.assess(AlertInputs(permission, app, inApp, e))

    @Test fun reportedS24CaseVibrateModeWithChannelVibrationOffIsSilent() {
        val a = assess(env(ringer = RingerMode.VIBRATE, vibrate = false))
        assertEquals(AlertLevel.SILENT, a.level)
        assertTrue(AlertIssue.PHONE_VIBRATE_MODE in a.issues)
        assertTrue(AlertIssue.CHANNEL_NO_VIBRATION in a.issues)
        assertTrue(AlertIssue.NO_POPUP in a.issues)
        assertFalse(a.popUp)
    }

    @Test fun vibrateModeWithChannelVibrationVibrates() {
        assertEquals(AlertLevel.VIBRATE_ONLY, assess(env(ringer = RingerMode.VIBRATE, vibrate = true)).level)
    }

    @Test fun normalModeWithSoundIsAudible() {
        val a = assess(env())
        assertEquals(AlertLevel.AUDIBLE, a.level)
        assertEquals("Reminders play a sound and vibrate.", a.summary)
    }

    @Test fun silentModeIsSilent() {
        assertEquals(AlertLevel.SILENT, assess(env(ringer = RingerMode.SILENT)).level)
    }

    @Test fun zeroVolumeWithoutVibrationIsSilent() {
        val a = assess(env(volume = 0, vibrate = false))
        assertEquals(AlertLevel.SILENT, a.level)
        assertTrue(AlertIssue.NOTIFICATION_VOLUME_ZERO in a.issues)
    }

    @Test fun channelWithoutSoundButVibrationVibrates() {
        assertEquals(AlertLevel.VIBRATE_ONLY, assess(env(sound = false)).level)
    }

    @Test fun lowImportanceChannelIsSilent() {
        val a = assess(env(importance = 2))
        assertEquals(AlertLevel.SILENT, a.level)
        assertTrue(AlertIssue.CHANNEL_SILENT_IMPORTANCE in a.issues)
    }

    @Test fun highImportanceMeansPopUp() {
        assertTrue(assess(env(importance = 4)).popUp)
    }

    @Test fun doNotDisturbMakesAudibleUncertainUnlessChannelBypasses() {
        assertEquals(AlertLevel.UNCERTAIN, assess(env(dnd = InterruptionFilter.PRIORITY)).level)
        assertEquals(AlertLevel.AUDIBLE, assess(env(dnd = InterruptionFilter.PRIORITY, bypass = true)).level)
    }

    @Test fun alarmsOnlyAndTotalSilenceDndAreSilentEvenWithChannelBypass() {
        for (filter in listOf(InterruptionFilter.ALARMS, InterruptionFilter.NONE)) {
            val a = assess(env(dnd = filter, bypass = true))
            assertEquals(AlertLevel.SILENT, a.level)
            assertTrue(AlertIssue.DND_ON in a.issues)
        }
    }

    @Test fun blockedStatesWin() {
        assertEquals(AlertLevel.BLOCKED, assess(env(), permission = false).level)
        assertEquals(AlertLevel.BLOCKED, assess(env(), app = false).level)
        assertEquals(AlertLevel.BLOCKED, assess(env(importance = 0)).level)
        assertEquals(AlertLevel.BLOCKED, assess(env(), inApp = false).level)
    }

    @Test fun unknownRingerModeIsNotClaimedAudible() {
        assertEquals(AlertLevel.UNCERTAIN, assess(env(ringer = RingerMode.UNKNOWN)).level)
    }

    @Test fun compactSnapshotIsStable() {
        val c = env(ringer = RingerMode.VIBRATE, vibrate = false).copy(standbyBucket = 10, canScheduleExact = false).compact()
        assertTrue(c.contains("ringer=vibrate"))
        assertTrue(c.contains("channel=imp3,sound=y,vibrate=n,bypassDnd=n"))
        assertTrue(c.contains("bucket=10"))
        assertTrue(c.contains("exactAllowed=n"))
    }
}
