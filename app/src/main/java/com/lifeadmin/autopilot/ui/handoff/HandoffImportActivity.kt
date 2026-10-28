package com.lifeadmin.autopilot.ui.handoff

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.MainActivity
import com.lifeadmin.autopilot.appContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.reminders.IntentContract
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.chatgpt.ChatGptShare
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.PaperBackground
import com.lifeadmin.autopilot.ui.review.ProposalReviewScreen
import com.lifeadmin.autopilot.ui.review.ProposalReviewViewModel
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import com.lifeadmin.autopilot.ui.theme.resolveDarkTheme

/**
 * Target of the ChatGPT round trip's "Open in Autopilot" link (`/autopilot/import`). Like the App
 * Link test screen it is separate from [MainActivity] and in its own task (manifest
 * `taskAffinity=""`), so a link never opens Today and every exit returns to ChatGPT. It hosts the
 * canonical [ProposalReviewScreen] and its view model rather than a copy of them.
 * An intent is read on a fresh create and on [onNewIntent] only; recreation restores the view models.
 */
class HandoffImportActivity : ComponentActivity() {
    private val viewModel: HandoffImportViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = appContainer
                HandoffImportViewModel(createSavedStateHandle(), ImportLinkParser(BuildConfig.APP_LINK_HOST), container.handoffImporter, container.taskRepository.observeSummaries())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val container = appContainer
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = resolveDarkTheme(settings.appearance)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val exitLabel = exitLabel(state.openedBy)
            BackHandler { if (state.changing && state.stage == ImportStage.CHOOSE) viewModel.cancelChange() else close() }
            LifeAdminTheme(darkTheme = dark) {
                PaperBackground {
                    val taskId = state.taskId
                    val setId = state.setId
                    if (state.stage == ImportStage.REVIEW && taskId != null && setId != null) {
                        Review(state, taskId, setId, exitLabel)
                    } else {
                        val candidates by viewModel.candidates.collectAsStateWithLifecycle()
                        val query by viewModel.query.collectAsStateWithLifecycle()
                        HandoffImportScreen(
                            state, candidates, query, exitLabel,
                            onQuery = viewModel::setQuery, onChoose = viewModel::choose, onCreateTask = viewModel::createTask,
                            onChange = viewModel::changeDestination, onCancelChange = viewModel::cancelChange, onExit = ::close,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Review(state: ImportUi, taskId: String, setId: String, exitLabel: String) {
        val container = appContainer
        val review: ProposalReviewViewModel = viewModel(
            key = "review-$setId",
            factory = viewModelFactory {
                initializer { ProposalReviewViewModel(createSavedStateHandle(), taskId, setId, container.proposalSets, container.taskRepository, container.proposalApplier, container.clock, container.zoneProvider) }
            },
        )
        val rs by review.state.collectAsStateWithLifecycle()
        val settled = rs.applied != null || rs.set?.let { set -> set.lastApply != null || set.items.any { it.status != ItemStatus.PENDING } } == true
        ProposalReviewScreen(
            review,
            onBack = ::close,
            onOpenTask = ::close,
            exitLabel = exitLabel,
            backIcon = AutopilotIcons.Close,
            backDescription = exitLabel,
            headerExtra = {
                DestinationCard(
                    taskTitle = rs.details?.task?.title, handoff = state.handoff, titleDiffers = state.titleDiffers, existed = state.existed,
                    settled = settled, onChange = viewModel::changeDestination, onOpenTask = { openInAutopilot(taskId) },
                )
            },
        )
    }

    /** Public so tests can deliver a repeat link to the running instance, as singleTop does. */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        viewModel.onLink(data?.toString(), referrer?.takeIf { it.scheme == "android-app" }?.host)
    }

    /**
     * Back to whatever opened the link. Started in its own task (the usual case from ChatGPT), the
     * task is removed so Recents keeps no stale import screen; if Android placed this screen on top of
     * the caller's task instead, only this screen closes, so the caller is never closed with it.
     */
    private fun close() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    /** Leaves the ChatGPT window for the task in Autopilot's own window (its main task, not this one). */
    private fun openInAutopilot(taskId: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(IntentContract.EXTRA_TASK_ID, taskId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        close()
    }

    companion object {
        /** "Return to ChatGPT" unless Android says another app opened the link. */
        fun exitLabel(openedBy: String?): String = if (openedBy == null || openedBy == ChatGptShare.PACKAGE) "Return to ChatGPT" else "Close"
    }
}
