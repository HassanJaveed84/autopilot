package com.lifeadmin.autopilot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import com.lifeadmin.autopilot.ui.theme.LocalAutopilot
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle

/*
 * Flight Plan building blocks shared by every screen. Everything tappable has a 48dp target;
 * color never carries meaning alone (icons and words always accompany it).
 */

// ---------------------------------------------------------------- buttons

/** Ink-filled pill: the one thing to do on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minHeight: Dp = 56.dp,
) {
    val c = AutopilotTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.inverse, contentColor = c.onInverse,
            disabledContainerColor = c.sunken, disabledContentColor = c.ink3,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Outlined pill in ink for secondary actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minHeight: Dp = 56.dp,
    /** Route (indigo) styling for AI actions. */
    route: Boolean = false,
) {
    val c = AutopilotTheme.colors
    val fg = if (!enabled) c.ink3 else if (route) c.route else c.ink
    val bd = if (!enabled) c.line else if (route) c.route else c.lineStrong
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, bd),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = fg, disabledContentColor = c.ink3),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Underlined inline action ("Change", "Add") inside a row. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AutopilotTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, textDecoration = TextDecoration.Underline),
        color = if (enabled) c.ink else c.ink3,
        modifier = modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp, minWidth = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** 48dp round icon button with a required label. */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint, modifier = Modifier.size(24.dp))
    }
}

/** Ink-filled when selected, outlined otherwise (selection is never indigo). */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    enabled: Boolean = true,
) {
    val c = AutopilotTheme.colors
    val bg = if (selected) c.inverse else c.surface
    val fg = if (!enabled) c.ink3 else if (selected) c.onInverse else c.ink
    val bd = if (selected) c.inverse else c.line
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, bd, CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        if (count != null) Text(count, style = TimeTextStyle.copy(fontSize = 13.sp), color = fg.copy(alpha = 0.8f))
    }
}

/** Small status pill with an icon: "Nudging", "Waiting", "Done", "High priority". */
@Composable
fun StatusPill(text: String, icon: ImageVector?, background: Color, foreground: Color, modifier: Modifier = Modifier, border: Color? = null) {
    Row(
        modifier
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .background(background)
            .let { if (border != null) it.border(1.dp, border, CircleShape) else it }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1)
    }
}

/** Dashed indigo pill: "Draft · not saved". */
@Composable
fun DraftPill(text: String, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = c.route,
        modifier = modifier.provenanceBorder(suggested = true, radius = 14.dp).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- surfaces

/** Card surface with a hairline edge; the default container for grouped content. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    background: Color = Color.Unspecified,
    border: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (background == Color.Unspecified) c.surface else background)
            .border(1.dp, if (border == Color.Unspecified) c.line else border, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Vertical group of rows with hairline dividers (settings groups, "Not today" lists). */
@Composable
fun RowGroup(modifier: Modifier = Modifier, radius: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(radius)
    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape), content = content)
}

@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = AutopilotTheme.colors.line)
}

/** A tappable row inside a [RowGroup]: optional leading icon circle, title, subtitle, trailing. */
@Composable
fun GroupRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    iconBackground: Color = Color.Unspecified,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    chevron: Boolean = onClick != null && trailing == null,
    divider: Boolean = false,
    minHeight: Dp = 64.dp,
    contentDescription: String? = null,
) {
    val c = AutopilotTheme.colors
    if (divider) GroupDivider()
    Row(
        modifier
            .fillMaxWidth()
            .let { m -> if (onClick != null) m.clickable(role = Role.Button, onClick = onClick) else m }
            .let { m -> if (contentDescription != null) m.semantics(mergeDescendants = true) { this.contentDescription = contentDescription } else m }
            .heightIn(min = minHeight)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            IconCircle(icon, tint = if (iconTint == Color.Unspecified) c.ink2 else iconTint, background = if (iconBackground == Color.Unspecified) c.sunken else iconBackground)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = if (subtitleColor == Color.Unspecified) c.ink2 else subtitleColor)
        }
        if (trailing != null) trailing()
        if (chevron) Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp))
    }
}

/** Switch row with [Role.Switch] semantics on the whole row. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    divider: Boolean = false,
    enabled: Boolean = true,
    switchTestTag: String? = null,
) {
    val c = AutopilotTheme.colors
    if (divider) GroupDivider()
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) IconCircle(icon, tint = if (checked) c.onSignalSoft else c.ink2, background = if (checked) c.signalSoft else c.sunken)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.ink else c.ink3)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
        }
        InkSwitch(checked = checked, modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier)
    }
}

/** Ink-filled switch (selection is ink, never brand-tinted). Used inside toggleable rows. */
@Composable
fun InkSwitch(checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val c = AutopilotTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onInverse, checkedTrackColor = c.inverse, checkedBorderColor = c.inverse,
            uncheckedThumbColor = c.surface, uncheckedTrackColor = c.lineStrong, uncheckedBorderColor = c.lineStrong,
        ),
    )
}

/** 40dp tinted circle holding a 20dp icon. */
@Composable
fun IconCircle(icon: ImageVector, tint: Color, background: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

// ---------------------------------------------------------------- text

/** Section header in ink-2 ("Today's nudges"), announced as a heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = AutopilotTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = c.ink2, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = c.ink3)
    }
}

/** Mono, tabular clock time. */
@Composable
fun TimeText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, size: Int = 14) {
    Text(text, style = TimeTextStyle.copy(fontSize = size.sp, lineHeight = (size + 4).sp), color = if (color == Color.Unspecified) AutopilotTheme.colors.ink else color, modifier = modifier)
}

/** Small uppercase kind tag ("ADD STEP · FIRST"). */
@Composable
fun KindTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

/** Body copy in ink-2. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(text, style = style, color = AutopilotTheme.colors.ink2, modifier = modifier)
}

/** Icon + text line in ink-2 (notes under a sheet). */
@Composable
fun NoteLine(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val c = AutopilotTheme.colors
    val fg = if (color == Color.Unspecified) c.ink2 else color
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = fg)
    }
}

// ---------------------------------------------------------------- provenance

/**
 * The "suggested" edge: dashed indigo for anything AI drafted, solid grey once the user edited
 * it. Also exposed in words through [androidx.compose.ui.semantics.stateDescription] by callers.
 */
fun Modifier.provenanceBorder(suggested: Boolean, radius: Dp = 14.dp, width: Dp = 1.5.dp): Modifier = composed {
    val c = LocalAutopilot.current
    drawBehind {
        val stroke = Stroke(
            width = width.toPx(),
            pathEffect = if (suggested) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null,
        )
        drawRoundRect(color = if (suggested) c.route else c.lineStrong, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
    }
}

/** Dashed hairline edge in ink (ghost rows, inbox prompt). */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val stroke = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
    drawRoundRect(color = color, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
}

/** Provenance label with a dot: "Suggested" (route) or "Edited by you" (ink). */
@Composable
fun ProvenanceTag(suggested: Boolean, modifier: Modifier = Modifier, suggestedText: String = "Suggested", editedText: String = "Edited by you") {
    val c = AutopilotTheme.colors
    val color = if (suggested) c.route else c.ink2
    val text = if (suggested) suggestedText else editedText
    Row(
        modifier.semantics { stateDescription = if (suggested) "Suggested by AI" else "Edited by you" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (suggested) c.route else c.ink))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Progress bar for checklist completion (96×4dp). */
@Composable
fun StepsBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Row(modifier.semantics { contentDescription = "$done of $total steps" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(96.dp).height(4.dp).clip(CircleShape).background(c.sunken)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(c.done))
        }
        Text("$done of $total steps", style = MaterialTheme.typography.bodySmall, color = c.ink3)
    }
}

/** Provides ink-2 as the content color for a block. */
@Composable
fun MutedContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides AutopilotTheme.colors.ink2, content = content)
}

/** Surface + hairline background for a full screen column. */
@Composable
fun PaperBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = AutopilotTheme.colors.paper, contentColor = AutopilotTheme.colors.ink) { content() }
}
