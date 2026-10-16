package com.lifeadmin.autopilot

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.reminders.IntentContract
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity + application container (as a notification tap would). */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<LifeAdminApp>()

    @Test fun launcherStartShowsToday() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("today_list")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun notificationDeepLinkOpensTheTask() {
        val id = runBlocking { app.container.taskRepository.createTask(NewTask("Deep linked task", status = TaskStatus.ACTIONABLE)) }
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(IntentContract.taskUri(id, "open"))
            .putExtra(IntentContract.EXTRA_TASK_ID, id)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("detail_title") and hasText("Deep linked task")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun malformedDeepLinkIsIgnored() {
        val intent = Intent(app, MainActivity::class.java).putExtra(IntentContract.EXTRA_TASK_ID, "../../etc/passwd")
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("today_list")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
