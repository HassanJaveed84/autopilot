@file:OptIn(ExperimentalTextApi::class)

package com.lifeadmin.autopilot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.R

/*
 * Three bundled, SIL Open Font License families (texts in assets/licenses/):
 * - Bricolage Grotesque for moments with voice (headlines, sheet titles),
 * - Atkinson Hyperlegible Next for everything you read,
 * - Atkinson Hyperlegible Mono for times, so 09:00 and 18:00 line up like a timetable.
 * All three are variable fonts; weights are pinned per style with FontVariation (API 26+ = minSdk).
 * Bundling (instead of downloadable fonts) keeps the app working offline and without Play services.
 * If a font resource ever fails to load, Compose falls back to the system sans-serif.
 */

private fun bricolage(weight: FontWeight, opticalSize: Float) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.Setting("opsz", opticalSize)),
)

private fun atkinson(weight: FontWeight) = Font(
    resId = R.font.atkinson_hyperlegible_next,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun atkinsonMono(weight: FontWeight) = Font(
    resId = R.font.atkinson_hyperlegible_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Display/headline family, pinned to the large optical size. */
val Bricolage = FontFamily(bricolage(FontWeight.SemiBold, 36f), bricolage(FontWeight.Bold, 36f))

val Atkinson = FontFamily(
    atkinson(FontWeight.Normal),
    atkinson(FontWeight.Medium),
    atkinson(FontWeight.SemiBold),
    atkinson(FontWeight.Bold),
)

val AtkinsonMono = FontFamily(atkinsonMono(FontWeight.Medium), atkinsonMono(FontWeight.SemiBold))

val AutopilotTypography = Typography(
    // Headline sentence on Today ("Two things to move today.").
    displayMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    // Task title on the detail screen.
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.6).sp),
    // Health hero, setup sheet questions.
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
    // Sheet titles ("Capture", "Follow-up").
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    // Task card titles.
    titleMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    // Section headers ("Today's nudges", "Waiting on others").
    titleSmall = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    // Captions: nothing in the app is set below 13sp except the tiny uppercase kind tags (12sp).
    bodySmall = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/** Tabular mono style for clock times; size is chosen per use (13–18sp). */
val TimeTextStyle = TextStyle(fontFamily = AtkinsonMono, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", fontSize = 14.sp, lineHeight = 18.sp)
