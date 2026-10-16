package com.lifeadmin.autopilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lifeadmin.autopilot.reminders.IntentContract
import com.lifeadmin.autopilot.ui.LifeAdminRoot
import com.lifeadmin.autopilot.ui.health.HealthBanner
import com.lifeadmin.autopilot.ui.health.HealthSection
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openTaskRequests = MutableStateFlow<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Result is reflected by the health panel; no repeated prompting.
        appContainer.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val container = appContainer
        setContent {
            LifeAdminTheme {
                LifeAdminRoot(
                    container = container,
                    openTaskRequests = openTaskRequests,
                    onOpenTaskHandled = { openTaskRequests.value = null },
                    onReminderEnabled = ::maybeRequestNotificationPermission,
                    healthBanner = { openSettings -> HealthBanner(container, onFix = openSettings) },
                    healthSection = { HealthSection(container, requestPermission = ::requestNotificationPermission) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Only a syntactically valid id is forwarded; the screen then loads it from Room. */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(IntentContract.EXTRA_TASK_ID) ?: IntentContract.taskIdFrom(intent?.data)
        if (IntentContract.isValidId(id)) openTaskRequests.value = id
    }

    /** Contextual request: the first time the user turns on a reminder (Android 13+). */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            val settings = appContainer.settingsRepository
            if (settings.current().notificationPermissionRequested) return@launch
            settings.update { it.copy(notificationPermissionRequested = true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Explicit request from the health panel. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch { appContainer.settingsRepository.update { it.copy(notificationPermissionRequested = true) } }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
