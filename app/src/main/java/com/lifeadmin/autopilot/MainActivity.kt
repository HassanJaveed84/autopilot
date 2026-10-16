package com.lifeadmin.autopilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lifeadmin.autopilot.ui.LifeAdminRoot
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LifeAdminTheme {
                LifeAdminRoot(container = appContainer)
            }
        }
    }
}
