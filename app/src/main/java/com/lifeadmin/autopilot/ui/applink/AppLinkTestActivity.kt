package com.lifeadmin.autopilot.ui.applink

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
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.appContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.share.applink.TestLinkParser
import com.lifeadmin.autopilot.ui.components.PaperBackground
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import com.lifeadmin.autopilot.ui.theme.resolveDarkTheme

/**
 * Target of the ChatGPT → App Link spike's one verified route. Separate from
 * [com.lifeadmin.autopilot.MainActivity] and in its own task (manifest `taskAffinity=""`), so a link
 * never disturbs an open Today screen and Close or Back returns to the app that opened the link.
 * An intent is read on a fresh create and on [onNewIntent] only; recreation restores the view model.
 */
class AppLinkTestActivity : ComponentActivity() {
    private val viewModel: AppLinkTestViewModel by viewModels {
        viewModelFactory { initializer { AppLinkTestViewModel(createSavedStateHandle(), TestLinkParser(BuildConfig.APP_LINK_HOST)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent, Delivery.NEW_SCREEN)
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
            val diagnostics = remember(state.received) { readAppLinkDiagnostics(this, BuildConfig.APP_LINK_HOST) }
            LifeAdminTheme(darkTheme = dark) {
                PaperBackground { AppLinkTestScreen(state, diagnostics, BuildConfig.APP_LINK_HOST, onClose = ::finishAndRemoveTask) }
            }
        }
    }

    /** Public so tests can deliver a repeat link to the running instance, as singleTop does. */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent, Delivery.OPEN_SCREEN)
    }

    private fun handle(intent: Intent?, delivery: Delivery) {
        val data = intent?.data
        val view = intent?.action == Intent.ACTION_VIEW && data?.scheme.equals("https", ignoreCase = true)
        val openedBy = referrer?.takeIf { it.scheme == "android-app" }?.host
        viewModel.onLink(if (intent?.action == Intent.ACTION_VIEW) data?.toString() else null, view, openedBy, delivery)
    }
}
