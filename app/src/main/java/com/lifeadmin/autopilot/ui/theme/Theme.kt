package com.lifeadmin.autopilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import com.lifeadmin.autopilot.domain.model.Appearance

/**
 * Flight Plan theme: calm ink on mineral paper. Material 3 components still do the heavy lifting
 * (sheets, switches, text fields, pickers); the brand roles are mapped onto the color scheme so
 * stock components inherit them, and the extended roles are available through [LocalAutopilot].
 *
 * Dynamic (wallpaper) color is intentionally not used: orange must always mean "time" and indigo
 * must always mean "suggested", regardless of the wallpaper.
 */
@Composable
fun LifeAdminTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Kept for source compatibility with existing call sites; dynamic color is always off. */
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = if (darkTheme) DarkAutopilot else LightAutopilot
    val scheme = materialScheme(c)
    CompositionLocalProvider(LocalAutopilot provides c) {
        MaterialTheme(colorScheme = scheme, typography = AutopilotTypography, shapes = AutopilotShapes, content = content)
    }
}

/** Resolves the persisted appearance preference against the system setting. */
@Composable
fun resolveDarkTheme(appearance: Appearance): Boolean = when (appearance) {
    Appearance.SYSTEM -> isSystemInDarkTheme()
    Appearance.LIGHT -> false
    Appearance.DARK -> true
}

private fun materialScheme(c: AutopilotColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.inverse,
        onPrimary = c.onInverse,
        primaryContainer = c.sunken,
        onPrimaryContainer = c.ink,
        secondary = c.ink2,
        onSecondary = c.paper,
        secondaryContainer = c.sunken,
        onSecondaryContainer = c.ink,
        tertiary = c.signal,
        onTertiary = c.onSignal,
        tertiaryContainer = c.signalSoft,
        onTertiaryContainer = c.onSignalSoft,
        background = c.paper,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.sunken,
        onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.sunken,
        surfaceContainerHigh = c.sunken,
        surfaceContainerHighest = c.sunken,
        surfaceDim = c.paper,
        surfaceBright = c.surface,
        inverseSurface = c.toast,
        inverseOnSurface = c.onToast,
        inversePrimary = c.toastAction,
        outline = c.lineStrong,
        outlineVariant = c.line,
        error = c.danger,
        onError = if (c.isDark) c.paper else c.surface,
        errorContainer = c.dangerSoft,
        onErrorContainer = c.onDangerSoft,
        scrim = c.scrim,
    )
}

/** Shorthand for the extended roles inside composables. */
object AutopilotTheme {
    val colors: AutopilotColors
        @Composable @ReadOnlyComposable get() = LocalAutopilot.current
}
