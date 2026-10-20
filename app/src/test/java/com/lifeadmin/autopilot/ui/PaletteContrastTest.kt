package com.lifeadmin.autopilot.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lifeadmin.autopilot.ui.theme.AutopilotColors
import com.lifeadmin.autopilot.ui.theme.DarkAutopilot
import com.lifeadmin.autopilot.ui.theme.LightAutopilot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast for every text-on-surface pairing the screens use, in both palettes: 4.5:1 for
 * text, 3:1 for control boundaries (the stall card's buttons, which used to fall to 2.4:1).
 */
class PaletteContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(theme: String, c: AutopilotColors) {
        val text = listOf(
            "ink on paper" to (c.ink to c.paper), "ink on surface" to (c.ink to c.surface), "ink on sunken" to (c.ink to c.sunken),
            "ink2 on paper" to (c.ink2 to c.paper), "ink2 on surface" to (c.ink2 to c.surface), "ink3 on paper" to (c.ink3 to c.paper),
            "onInverse on inverse" to (c.onInverse to c.inverse), "onSignalSoft on signalSoft" to (c.onSignalSoft to c.signalSoft),
            "signal on paper" to (c.signal to c.paper), "signal on surface" to (c.signal to c.surface), "onSignal on signal" to (c.onSignal to c.signal),
            "onRouteSoft on routeSoft" to (c.onRouteSoft to c.routeSoft), "route on paper" to (c.route to c.paper),
            "done on paper" to (c.done to c.paper), "onDone on done" to (c.onDone to c.done), "onDoneSoft on doneSoft" to (c.onDoneSoft to c.doneSoft),
            "danger on paper" to (c.danger to c.paper), "onDangerSoft on dangerSoft" to (c.onDangerSoft to c.dangerSoft),
            "onToast on toast" to (c.onToast to c.toast), "toastAction on toast" to (c.toastAction to c.toast),
        )
        for ((name, pair) in text) {
            val r = ratio(pair.first, pair.second)
            assertTrue("$theme: $name is %.2f:1, text needs 4.5:1".format(r), r >= 4.5)
        }
        // The stall card's outlined buttons draw their border and label in onSignalSoft.
        val border = ratio(c.onSignalSoft, c.signalSoft)
        assertTrue("$theme: stall-card button border is %.2f:1, needs 3:1".format(border), border >= 3.0)
    }

    @Test fun lightPaletteMeetsContrast() = check("light", LightAutopilot)
    @Test fun darkPaletteMeetsContrast() = check("dark", DarkAutopilot)
}
