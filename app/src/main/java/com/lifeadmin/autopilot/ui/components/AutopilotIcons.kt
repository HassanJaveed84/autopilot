package com.lifeadmin.autopilot.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icon set ported from the prototype's path data: 24-unit grid, 1.9 stroke, round caps and
 * joins, no fills. Tinted through [androidx.compose.material3.Icon] like any Material icon.
 */
object AutopilotIcons {
    private fun stroke(name: String, d: String): ImageVector =
        ImageVector.Builder(name = "autopilot_$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    /** Globe inside a magnifier: web research, and steps that came from it. */
    val Research by lazy { stroke("research", "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13Z M20 20l-4.8-4.8 M4 10.5h13 M10.5 4c-2.2 2-2.2 11 0 13 M10.5 4c2.2 2 2.2 11 0 13") }
    val Globe by lazy { stroke("globe", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M3 12h18 M12 3c-3 3-3 15 0 18 M12 3c3 3 3 15 0 18") }
    val OpenExternal by lazy { stroke("open_external", "M14 4h6v6 M20 4l-9 9 M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5") }
    val Chat by lazy { stroke("chat", "M4 5h16v11H10l-6 4V5Z M8 9h8 M8 12.5h5") }
    val Copy by lazy { stroke("copy", "M9 9h10v11H9z M15 9V5H5v10h4") }
    val Stop by lazy { stroke("stop", "M7 7h10v10H7z") }
    val Bell by lazy { stroke("bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15L6 16Z M10 20.5a2 2 0 0 0 4 0") }
    val BellOff by lazy { stroke("bell_off", "M6 16v-5a6 6 0 0 1 9.5-4.9 M18 11v5l1.5 2H8 M10 20.5a2 2 0 0 0 4 0 M4 4l16 16") }
    val Check by lazy { stroke("check", "M5 12.5l4.5 4.5L19 7.5") }
    val Plus by lazy { stroke("plus", "M12 5v14 M5 12h14") }
    val Minus by lazy { stroke("minus", "M5 12h14") }
    val ChevronRight by lazy { stroke("chevron_right", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { stroke("chevron_down", "M6 9l6 6 6-6") }
    val Back by lazy { stroke("back", "M19 12H5 M11 6l-6 6 6 6") }
    val Close by lazy { stroke("close", "M6 6l12 12 M18 6L6 18") }
    val Sliders by lazy { stroke("sliders", "M4 7h10 M18 7h2 M16 5v4 M4 17h4 M12 17h8 M10 15v4") }
    val Search by lazy { stroke("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z M20 20l-4-4") }
    val Tray by lazy { stroke("tray", "M4 13l2.5-7h11L20 13v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-5Z M4 13h4.5l1 2h5l1-2H20") }
    val List by lazy { stroke("list", "M9 6h11 M9 12h11 M9 18h11 M4.5 6h.01 M4.5 12h.01 M4.5 18h.01") }
    val Sun by lazy { stroke("sun", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z M12 2.5v2 M12 19.5v2 M2.5 12h2 M19.5 12h2 M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M5.3 18.7l1.4-1.4 M17.3 6.7l1.4-1.4") }
    val Hourglass by lazy { stroke("hourglass", "M7 3h10 M7 21h10 M8 3c0 5 8 5 8 9s-8 4-8 9 M16 3c0 5-8 5-8 9s8 4 8 9") }
    val Pause by lazy { stroke("pause", "M9 6v12 M15 6v12") }
    val Play by lazy { stroke("play", "M8 5l11 7-11 7V5Z") }
    val Moon by lazy { stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z") }
    val Calendar by lazy { stroke("calendar", "M5 6h14a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z M4 10h16 M8 4v4 M16 4v4") }
    val Route by lazy { stroke("route", "M6 20.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z M18 8.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z M8.5 18H15a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h6.5") }
    val Pencil by lazy { stroke("pencil", "M4 20h4L19 9l-4-4L4 16v4Z M13.5 6.5l4 4") }
    val More by lazy { stroke("more", "M12 5h.01 M12 12h.01 M12 19h.01") }
    val Trash by lazy { stroke("trash", "M4 7h16 M10 7V4h4v3 M6 7l1 13h10l1-13") }
    val Shield by lazy { stroke("shield", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3Z M9 12l2 2 4-4") }
    val Warn by lazy { stroke("warn", "M12 4l9 16H3L12 4Z M12 10v4 M12 17h.01") }
    val Info by lazy { stroke("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M12 11v5 M12 8h.01") }
    val Lock by lazy { stroke("lock", "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 7 0v3") }
    val Export by lazy { stroke("export", "M12 15V4 M8 8l4-4 4 4 M5 14v5h14v-5") }
    val Import by lazy { stroke("import", "M12 4v11 M8 11l4 4 4-4 M5 14v5h14v-5") }
    val Sound by lazy { stroke("sound", "M4 10v4h3l5 4V6L7 10H4Z M16 9a4 4 0 0 1 0 6 M18.5 6.5a7.5 7.5 0 0 1 0 11") }
    val Vibrate by lazy { stroke("vibrate", "M8 4h8v16H8z M4 9v6 M20 9v6") }
    val Alarm by lazy { stroke("alarm", "M12 20a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z M12 9.5V13l2 2 M4 5l3-2 M20 5l-3-2") }
    val Battery by lazy { stroke("battery", "M3 8h15v8H3z M21 11v2 M6 11h6") }
    val Dnd by lazy { stroke("dnd", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M8 12h8") }
    val Flag by lazy { stroke("flag", "M5 21V4 M5 4h11l-2 4 2 4H5") }
    val Sort by lazy { stroke("sort", "M7 4v16 M4 17l3 3 3-3 M17 20V4 M14 7l3-3 3 3") }
    val Refresh by lazy { stroke("refresh", "M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7") }
    val Key by lazy { stroke("key", "M9 14a4 4 0 1 1 2.8-1.2L20 21 M16.5 17.5l2-2") }
    val WifiOff by lazy { stroke("wifi_off", "M3 3l18 18 M8.5 16.5a5 5 0 0 1 7 0 M5 12.5a10 10 0 0 1 5-2.7 M14 9.8a10 10 0 0 1 5 2.7 M12 20h.01") }
    val Clock by lazy { stroke("clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M12 7v5l3 2") }
    val Clip by lazy { stroke("clip", "M9 4h6v3H9z M8 5.5H6v15h12v-15h-2") }
    val Archive by lazy { stroke("archive", "M4 5h16v4H4z M6 9v10h12V9 M10 13h4") }
    val Dot by lazy { stroke("dot", "M12 12h.01") }
}
