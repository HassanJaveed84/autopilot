package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout at larger system text: the Today rail's time labels stay on one line, the follow-up
 * sheet's kind selector wraps instead of truncating, and the stall card's actions keep 44dp
 * targets. Native graphics so text is measured with real glyph widths.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class LargeTextLayoutBase {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 19, 8, 42))

    @Before fun setUp() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = clock,
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds(),
            database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        runBlocking {
            container.taskRepository.createTask(NewTask("Book train ride", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
            val gym = container.taskRepository.createTask(NewTask("Cancel the gym membership", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY))
            // Three snoozes on separate days, like the real stall: the hint counts snoozes after the last progress.
            repeat(3) { clock.instant = clock.instant.plusSeconds(86_400); container.taskRepository.snooze(gym, clock.now().plusSeconds(3600)) }
            clock.instant = at(CHICAGO, 2026, 10, 19, 8, 42).plusSeconds(4 * 86_400)
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("today_headline")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() = container.database.close()

    @Test fun railTimeLabelsStayOnOneLine() {
        compose.onNodeWithTag("today_list").performScrollToNode(hasTestTag("rail_time"))
        val times = compose.onAllNodes(hasTestTag("rail_time"), useUnmergedTree = true)
        val n = times.fetchSemanticsNodes().size
        assertTrue("at least one ghost row with a time", n >= 1)
        for (i in 0 until n) {
            val layout = times[i].textLayout()
            assertEquals("\"${layout.layoutInput.text}\" wrapped", 1, layout.lineCount)
            // softWrap=false measures against an unbounded width, so check the glyphs against the column instead.
            val maxWidth = layout.layoutInput.constraints.maxWidth
            assertTrue("\"${layout.layoutInput.text}\" is ${layout.getLineRight(0)}px wide in a ${maxWidth}px column", layout.getLineRight(0) <= maxWidth + 0.5f)
        }
    }

    @Test fun followUpKindSelectorWrapsInsteadOfTruncating() {
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("tasks_list")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasText("Book train ride", substring = true))
        compose.onAllNodes(hasText("Book train ride", substring = true))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("card_action_reminders")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("card_action_reminders"))
        compose.onNodeWithTag("card_action_reminders").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("policy_kind_every_n_days")).fetchSemanticsNodes().isNotEmpty() }
        val label = compose.onNode(hasText("Every N days") and !hasTestTag("policy_kind_every_n_days"), useUnmergedTree = true)
        val layout = label.textLayout()
        assertTrue("kind label fits in two lines, got ${layout.lineCount}", layout.lineCount <= 2)
        assertFalse("kind label must not be ellipsised", layout.isLineEllipsized(layout.lineCount - 1))
        compose.onNodeWithTag("policy_kind_every_n_days").assertTouchTargetAtLeast(48.dp)
    }

    @Test fun stallCardActionsAreLargeEnough() {
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("tasks_list")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasText("Cancel the gym", substring = true))
        compose.onAllNodes(hasText("Cancel the gym", substring = true))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("stall_hint")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("stall_break_down"))
        compose.onNodeWithTag("stall_break_down").assertTouchTargetAtLeast(44.dp)
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("stall_dismiss"))
        compose.onNodeWithTag("stall_dismiss").assertTouchTargetAtLeast(44.dp)
    }
}

@Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.3f)
class LargeTextLayout130UiTest : LargeTextLayoutBase()

@Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2.0f)
class LargeTextLayout200UiTest : LargeTextLayoutBase()
