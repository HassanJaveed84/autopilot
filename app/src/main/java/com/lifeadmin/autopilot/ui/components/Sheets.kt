package com.lifeadmin.autopilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.ui.theme.AutopilotShapes
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * Bottom sheet host used by Capture, Follow-up, Snooze, Waiting and AI setup: 28dp top corners,
 * a 36×4 drag handle, a Bricolage title with a 48dp close button, keyboard-aware content.
 *
 * [footer] (the sheet's primary actions) is pinned below the scrolling [content] and above the
 * keyboard, so Save stays reachable however tall the content or the keyboard is.
 */
/**
 * [body] above [footer], wrapping their combined height: the footer is measured first and the
 * body gets whatever height is left, so a scrolling body shrinks before the footer ever moves off
 * screen. (A weighted column would instead stretch the sheet to the full available height.)
 */
@Composable
private fun BodyWithFooter(modifier: Modifier, body: @Composable () -> Unit, footer: @Composable () -> Unit) {
    Layout(contents = listOf(body, footer), modifier = modifier) { (bodyMeasurables, footerMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val footers = footerMeasurables.map { it.measure(loose) }
        val footerHeight = footers.sumOf { it.height }
        val bodyMax = if (constraints.hasBoundedHeight) (constraints.maxHeight - footerHeight).coerceAtLeast(0) else Constraints.Infinity
        val bodies = bodyMeasurables.map { it.measure(loose.copy(maxHeight = bodyMax)) }
        val bodyHeight = bodies.sumOf { it.height }
        val width = (bodies + footers).maxOfOrNull { it.width }?.coerceIn(constraints.minWidth, constraints.maxWidth) ?: constraints.minWidth
        layout(width, (bodyHeight + footerHeight).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            bodies.forEach { it.placeRelative(0, y); y += it.height }
            footers.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

@Composable
fun AutopilotSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    closeLabel: String = "Close",
    scrollable: Boolean = true,
    titleLeading: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AutopilotTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = AutopilotShapes.extraLarge,
        containerColor = c.surface,
        contentColor = c.ink,
        scrimColor = c.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 2.dp).width(36.dp).height(4.dp).clip(CircleShape).background(c.lineStrong)) },
    ) {
        val body: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                    .padding(horizontal = 20.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (titleLeading != null) titleLeading()
                    if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    else Box(Modifier.weight(1f))
                    RoundIconButton(AutopilotIcons.Close, contentDescription = closeLabel, onClick = onDismiss, modifier = Modifier.offset(x = 8.dp))
                }
                content()
            }
        }
        val insets = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 22.dp)
        if (footer == null) {
            Box(insets) { body() }
        } else {
            BodyWithFooter(
                modifier = insets,
                body = body,
                footer = { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("sheet_footer")) { footer() } },
            )
        }
    }
}
