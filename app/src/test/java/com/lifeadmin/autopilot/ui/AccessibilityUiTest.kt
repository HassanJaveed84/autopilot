package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Large text, touch targets, headings and labels (SPEC M1C accessibility checks). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2.0f)
class AccessibilityUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer

    @Before fun setUp() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = FakeClock(at(CHICAGO, 2026, 10, 16, 7, 30)),
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds(),
            database = fileDatabase("a11y-test.db"),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        runBlocking {
            container.taskRepository.createTask(
                NewTask("Book a train ride for the whole family before prices go up", priority = Priority.HIGH, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT),
            )
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("done", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() = container.database.close()

    /** Asserts the *touch* bounds (incl. Material's minimum interactive size) are ≥ [min] in both axes. */
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(min: androidx.compose.ui.unit.Dp): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val density = node.layoutInfo.density
        val bounds = node.touchBoundsInRoot
        val w = with(density) { bounds.width.toDp() }
        val h = with(density) { bounds.height.toDp() }
        assertTrue("touch target ${w}x$h smaller than $min", w >= min - 0.5.dp && h >= min - 0.5.dp)
        return this
    }

    @Test fun todayRendersAtDoubleFontScaleWithLargeTouchTargets() {
        val done = compose.onAllNodes(hasContentDescription("Mark Book a train ride", substring = true))[0]
        done.assertIsDisplayed()
        // Touch bounds include Material's minimum interactive size (visual icon buttons are 40 dp).
        done.assertTouchTargetAtLeast(48.dp)
        compose.onNodeWithTag("fab_capture").assertTouchTargetAtLeast(48.dp)
        // At 2x text the list scrolls; content must remain reachable.
        compose.onNodeWithTag("today_list").performScrollToNode(hasText("Today's nudges"))
        compose.onNodeWithText("Today's nudges").assertIsDisplayed()
    }

    @Test fun sectionTitlesAreHeadings() {
        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Today has heading semantics for sections", headings.isNotEmpty())
    }

    @Test fun taskRowAnnouncesStatusAndScheduleInOneNode() {
        val rows = compose.onAllNodes(hasContentDescription("High priority", substring = true)).fetchSemanticsNodes()
        assertTrue("row content description includes chips (priority/reminder) for TalkBack", rows.isNotEmpty())
        compose.onAllNodes(hasText("Book a train ride", substring = true))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Edit task")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").assertTouchTargetAtLeast(48.dp)
        compose.onNodeWithTag("action_done").assertTouchTargetAtLeast(48.dp)
    }
}
