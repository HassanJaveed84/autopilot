package com.lifeadmin.autopilot.ui.share

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lifeadmin.autopilot.appContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.ui.components.PaperBackground
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import com.lifeadmin.autopilot.ui.theme.resolveDarkTheme

/**
 * Share target for `text/plain` (proof of concept), and the "Send to Autopilot" text-selection
 * action (`PROCESS_TEXT` spike). Separate from [com.lifeadmin.autopilot.MainActivity]
 * so ordinary launches, notification taps and the task UI are untouched. An intent is handled on a
 * fresh create and on [onNewIntent] only; rotation and process recreation restore the view model.
 */
class ShareImportActivity : ComponentActivity() {
    private val viewModel: ShareImportViewModel by viewModels {
        viewModelFactory { initializer { ShareImportViewModel(createSavedStateHandle(), appContainer.shareImporter) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val settingsRepository = appContainer.settingsRepository
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = resolveDarkTheme(settings.appearance)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LifeAdminTheme(darkTheme = dark) {
                PaperBackground { ShareImportScreen(state, onFetch = viewModel::fetch, onClose = ::finish) }
            }
        }
    }

    /** Public so tests can deliver a second share to the running instance, as singleTop does. */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * `ACTION_SEND` → shared-link import. `ACTION_PROCESS_TEXT` → show the selected text only; no
     * result is ever set, so the sender's text is never replaced, read-only or not. Anything else
     * is treated as an empty share.
     */
    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            // Extras from another app are untrusted: a malformed bundle reads as no text.
            val (text, readOnly) = runCatching {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) to intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            }.getOrDefault(null to true)
            viewModel.onSelectedText(text, readOnly)
            return
        }
        val text = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        viewModel.onShared(text)
    }
}
