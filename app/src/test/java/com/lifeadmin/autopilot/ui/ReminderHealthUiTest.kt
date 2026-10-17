package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.RingerMode
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.ui.health.ReminderHealthScreen
import com.lifeadmin.autopilot.ui.health.ReminderHealthViewModel
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReminderHealthUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val env = FakeEnvironmentProbe(
        DeliveryEnvironment(ringerMode = RingerMode.VIBRATE, channelExists = true, channelImportance = 3, channelHasSound = true, channelVibrates = false),
    )

    @Before fun setUp() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = FakeClock(at(CHICAGO, 2026, 10, 17, 12, 0)),
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds(),
            database = fileDatabase("health-ui.db"),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
            alarmDriver = alarms,
            notificationPublisher = notifications,
            environmentProbe = env,
        )
        runBlocking { container.taskRepository.createTask(NewTask("Book train ride", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) }
        val vm = ReminderHealthViewModel(container)
        compose.setContent {
            LifeAdminTheme(dynamicColor = false) {
                ReminderHealthScreen(vm, zone = CHICAGO, now = container.clock::now, onBack = {}, requestPermission = {})
            }
        }
    }

    // The DB is not closed here: the screen's ViewModel may still be loading on an IO thread when the
    // test ends (the app itself never closes its DB). fileDatabase() deletes the file on next setUp.
    @After fun tearDown() = compose.waitForIdle()

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(tag: String) = compose.onNodeWithTag("health_list").performScrollToNode(hasTestTag(tag))

    @Test fun vibrateModeWithChannelVibrationOffIsFlaggedAsProbablySilent() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Reminders will probably be silent"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("health_headline").assertTextContains("Reminders will probably be silent")
        scrollTo("channel_vibration")
        compose.onNodeWithTag("channel_vibration", useUnmergedTree = true).assertIsDisplayed()
        scrollTo("ringer_mode")
        compose.onNodeWithTag("ringer_mode", useUnmergedTree = true).assertIsDisplayed()
        scrollTo("open_channel_settings")
        compose.onNodeWithTag("open_channel_settings").assertIsDisplayed()
    }

    @Test fun approximateTimingIsClearlyIndicated() {
        scrollTo("timing_mode")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Approximate timing", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        scrollTo("health_next")
        compose.onNodeWithTag("health_next", useUnmergedTree = true).assertTextContains("(approximate)", substring = true)
    }

    @Test fun sendTestNotificationNowPostsOnce() {
        waitFor("test_now")
        compose.onNodeWithTag("test_now").performClick()
        compose.waitUntil(10_000) { notifications.tests.size == 1 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("test_result"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun scheduleTestReminderInTwoMinutesUsesTheAlarmDriver() {
        waitFor("test_in_2_min")
        compose.onNodeWithTag("test_in_2_min").performClick()
        compose.waitUntil(10_000) { alarms.tests.size == 1 }
        val (triggerAt, exact) = alarms.tests.values.single()
        assertEquals(container.clock.now().plusSeconds(120), triggerAt)
        assertEquals(false, exact)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test reminder due", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
