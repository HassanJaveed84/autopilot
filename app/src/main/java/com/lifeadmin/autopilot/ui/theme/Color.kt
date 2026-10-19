package com.lifeadmin.autopilot.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Flight Plan brand roles. Material 3's [androidx.compose.material3.ColorScheme] has no slot for
 * "time" (signal) or "suggested by AI" (route), so they live here and are read through
 * [LocalAutopilot]. Values come from the Claude Design "Design system · light & dark" board.
 *
 * Rules (from the design system):
 * - Ink is the action color: primary buttons are ink-filled, never brand-tinted.
 * - Signal (orange) is for time and nudges only — never errors or decoration.
 * - Route (indigo) is for anything AI suggested, always paired with a dashed edge.
 * - Done (green) is for completed state. Danger is for delete only.
 */
@Immutable
data class AutopilotColors(
    val paper: Color,
    val surface: Color,
    val sunken: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** Inverse fill for primary actions (ink in light, light ink in dark). */
    val inverse: Color,
    val onInverse: Color,
    val signal: Color,
    val onSignal: Color,
    val signalSoft: Color,
    val onSignalSoft: Color,
    val route: Color,
    val routeSoft: Color,
    val onRouteSoft: Color,
    val done: Color,
    val onDone: Color,
    val doneSoft: Color,
    val onDoneSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
    val scrim: Color,
    val toast: Color,
    val onToast: Color,
    val toastAction: Color,
    val isDark: Boolean,
)

val LightAutopilot = AutopilotColors(
    paper = Color(0xFFEEF1EF),
    surface = Color(0xFFFBFCFB),
    sunken = Color(0xFFE3E8E5),
    line = Color(0xFFD2D9D5),
    lineStrong = Color(0xFF8E9A94),
    ink = Color(0xFF111916),
    ink2 = Color(0xFF45514C),
    ink3 = Color(0xFF5B6762),
    inverse = Color(0xFF111916),
    onInverse = Color(0xFFF3F6F4),
    signal = Color(0xFFB4460F),
    onSignal = Color(0xFFFFFFFF),
    signalSoft = Color(0xFFF8E5D9),
    onSignalSoft = Color(0xFF7E2E06),
    route = Color(0xFF4A4FC2),
    routeSoft = Color(0xFFE5E6F8),
    onRouteSoft = Color(0xFF2E3290),
    done = Color(0xFF2B7A52),
    onDone = Color(0xFFFFFFFF),
    doneSoft = Color(0xFFDCEEE3),
    onDoneSoft = Color(0xFF1C5638),
    danger = Color(0xFFB42318),
    dangerSoft = Color(0xFFFBE3E0),
    onDangerSoft = Color(0xFF7A1A12),
    scrim = Color(0x730A100D),
    toast = Color(0xFF1B2420),
    onToast = Color(0xFFF3F6F4),
    toastAction = Color(0xFFF5A97C),
    isDark = false,
)

val DarkAutopilot = AutopilotColors(
    paper = Color(0xFF0D1210),
    surface = Color(0xFF151C19),
    sunken = Color(0xFF1F2824),
    line = Color(0xFF2A3530),
    lineStrong = Color(0xFF64736B),
    ink = Color(0xFFE7EDEA),
    ink2 = Color(0xFFB0BCB6),
    ink3 = Color(0xFF909D97),
    inverse = Color(0xFFE7EDEA),
    onInverse = Color(0xFF0D1210),
    signal = Color(0xFFF0915A),
    onSignal = Color(0xFF2A1206),
    signalSoft = Color(0xFF3A2216),
    onSignalSoft = Color(0xFFFFC8A6),
    route = Color(0xFFA3A7FF),
    routeSoft = Color(0xFF23254A),
    onRouteSoft = Color(0xFFCDCFFF),
    done = Color(0xFF63C795),
    onDone = Color(0xFF08200F),
    doneSoft = Color(0xFF16301F),
    onDoneSoft = Color(0xFFA9E6C4),
    danger = Color(0xFFFF8A80),
    dangerSoft = Color(0xFF3B1A17),
    onDangerSoft = Color(0xFFFFC9C3),
    scrim = Color(0x9E000000),
    toast = Color(0xFFE7EDEA),
    onToast = Color(0xFF0D1210),
    toastAction = Color(0xFFA8420C),
    isDark = true,
)

val LocalAutopilot = staticCompositionLocalOf { LightAutopilot }
