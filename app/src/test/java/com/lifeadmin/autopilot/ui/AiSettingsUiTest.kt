package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.ai.AiSettingsScreen
import com.lifeadmin.autopilot.ui.ai.AiSettingsViewModel
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AiSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 14, 10, 0))
    private val settings = InMemorySettingsRepository(AppSettings())
    private val keys = InMemoryApiKeyStore()
    private val provider = FakePlanningProvider()

    @Before fun setUp() {
        val db = inMemoryDatabase()
        val ids = SequentialIds("set")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, FakeZoneProvider(CHICAGO), ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val repo = TaskRepository(db, clock, ids, { CHICAGO }, engine, log)
        val service = AiPlanningService(settings, keys, provider, repo, clock, FakeZoneProvider(CHICAGO), ids) { true }
        val vm = AiSettingsViewModel(settings, keys, service, clock)
        compose.setContent { LifeAdminTheme(dynamicColor = false) { AiSettingsScreen(vm, onBack = {}) } }
    }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(tag: String) = compose.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag(tag))

    @Test fun offByDefaultAndTurningOnRequiresThePrivacyDisclosure() {
        waitFor("ai_enabled_switch")
        compose.onNodeWithTag("ai_enabled_row").assertIsOff()
        compose.onNodeWithTag("ai_enabled_row").performClick()
        waitFor("ai_consent_text")
        assertFalse("not enabled before accepting", runBlocking { settings.current() }.aiEnabled)
        compose.onNodeWithTag("ai_consent_accept").performClick()
        compose.waitUntil(5_000) { runBlocking { settings.current() }.aiEnabled }
        assertNotNull(runBlocking { settings.current() }.aiConsentAt)
        compose.onNodeWithTag("ai_enabled_row").assertIsOn()
    }

    @Test fun billingAndPrivacyAreExplained() {
        waitFor("ai_billing")
        compose.onNodeWithText("does not cover this", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nothing else is sent", substring = true).assertIsDisplayed()
    }

    @Test fun keyCanBeSavedTestedReplacedAndDeleted() {
        waitFor("ai_key_input")
        scrollTo("ai_key_input")
        compose.onNodeWithTag("ai_key_input").performTextInput(FAKE_KEY)
        compose.onNodeWithTag("ai_key_save").performClick()
        compose.waitUntil(5_000) { keys.hasKey() }
        assertEquals(FAKE_KEY, keys.get())
        waitFor("ai_test_connection")
        compose.onNodeWithText(FAKE_KEY).assertDoesNotExist()
        provider.connectionError = PlanningError.Unauthorized
        compose.onNodeWithTag("ai_test_connection").performClick()
        waitFor("ai_test_result")
        compose.onNodeWithText(PlanningError.Unauthorized.message).assertIsDisplayed()
        compose.onNodeWithTag("ai_key_delete").performClick()
        compose.onNodeWithText("Delete key").performClick()
        compose.waitUntil(5_000) { !keys.hasKey() }
        assertNull(keys.get())
    }

    @Test fun invalidKeyIsRejected() {
        waitFor("ai_key_input")
        scrollTo("ai_key_input")
        compose.onNodeWithTag("ai_key_input").performTextInput("not a key")
        compose.onNodeWithTag("ai_key_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("doesn't look like", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(keys.hasKey())
    }

    @Test fun deleteAllAiSettings() {
        runBlocking { settings.update { it.copy(aiEnabled = true, aiConsentAt = clock.now(), aiModel = "claude-sonnet-5-5") } }
        keys.save(FAKE_KEY)
        waitFor("ai_enabled_switch")
        scrollTo("ai_delete_all") // the Remove section sits below the fold of the lazy list
        waitFor("ai_delete_all")
        compose.onNodeWithTag("ai_delete_all").performClick()
        compose.onNodeWithTag("ai_delete_all_confirm").performClick()
        compose.waitUntil(5_000) { !keys.hasKey() }
        val s = runBlocking { settings.current() }
        assertFalse(s.aiEnabled); assertNull(s.aiConsentAt); assertEquals("", s.aiModel)
        assertTrue(provider.requests.isEmpty())
    }
}
