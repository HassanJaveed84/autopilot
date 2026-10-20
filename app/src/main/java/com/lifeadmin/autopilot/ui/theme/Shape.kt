package com.lifeadmin.autopilot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale from the design system: 7dp step checkboxes, 12–14dp fields and time chips,
 * 18–20dp cards and rows, 24dp hero/inbox cards, 28dp sheet tops, full pills for buttons.
 */
val AutopilotShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
)

object AutopilotRadius {
    val field = 14.dp
    val card = 20.dp
    val hero = 24.dp
    val tile = 18.dp
    val chip = 16.dp
}
