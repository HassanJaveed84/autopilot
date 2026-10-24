package com.lifeadmin.autopilot.ui.investigation

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/** Outlined text-field colours shared by the research screens. */
@Composable
internal fun fieldColors() = AutopilotTheme.colors.let { c ->
    OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.lineStrong, cursorColor = c.ink, focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, focusedLabelColor = c.ink2, unfocusedLabelColor = c.ink2)
}
