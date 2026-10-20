package com.lifeadmin.autopilot.ui.theme

import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion explains where things went; it never performs. One emphasized easing for anything
 * entering. When the system "Remove animations" setting is on (animator duration scale 0),
 * every transition becomes a short crossfade.
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    const val ENTER_MS = 340
    const val EXIT_MS = 280
    const val CHECK_MS = 180
    const val REDUCED_MS = 100

    fun enter(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(REDUCED_MS)) else fadeIn(tween(ENTER_MS, easing = Emphasized)) + expandVertically(tween(ENTER_MS, easing = Emphasized))

    fun exit(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(REDUCED_MS)) else fadeOut(tween(EXIT_MS, easing = FastOutLinearInEasing)) + shrinkVertically(tween(EXIT_MS, easing = FastOutLinearInEasing))

    fun <S> crossfade(reduced: Boolean): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
        val ms = if (reduced) REDUCED_MS else ENTER_MS
        fadeIn(tween(ms, easing = Emphasized)) togetherWith fadeOut(tween(if (reduced) REDUCED_MS else EXIT_MS))
    }
}

/** True when the user asked Android to remove animations (Developer options / Accessibility). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
}
