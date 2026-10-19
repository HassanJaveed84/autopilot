package com.lifeadmin.autopilot.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.Motion
import java.time.Instant
import java.time.ZoneId

@Composable
fun StatusGlyph.icon(): ImageVector = when (this) {
    StatusGlyph.BELL -> AutopilotIcons.Bell
    StatusGlyph.BELL_OFF -> AutopilotIcons.BellOff
    StatusGlyph.HOURGLASS -> AutopilotIcons.Hourglass
    StatusGlyph.MOON -> AutopilotIcons.Moon
    StatusGlyph.PAUSE -> AutopilotIcons.Pause
    StatusGlyph.CHECK -> AutopilotIcons.Check
    StatusGlyph.CALENDAR -> AutopilotIcons.Calendar
    StatusGlyph.WARN -> AutopilotIcons.Warn
    StatusGlyph.TRAY -> AutopilotIcons.Tray
    StatusGlyph.CLOSE -> AutopilotIcons.Close
}

@Composable
fun StatusTone.color(): Color = when (this) {
    StatusTone.SIGNAL -> AutopilotTheme.colors.signal
    StatusTone.MUTED -> AutopilotTheme.colors.ink2
    StatusTone.DONE -> AutopilotTheme.colors.done
    StatusTone.DANGER -> AutopilotTheme.colors.danger
}

/** Icon + bold 13sp sentence: the one status line on a card. */
@Composable
fun StatusText(line: StatusLine, modifier: Modifier = Modifier) {
    val color = line.tone.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(line.glyph.icon(), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(line.text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * The Flight Plan task card: title, next step, one status sentence (+ deadline), steps bar, and
 * a 48dp check toggle. The text column merges semantics into one announcement; the check keeps
 * its own ("Mark <title> done").
 */
@Composable
fun TaskCard(
    summary: TaskSummary,
    now: Instant,
    zone: ZoneId,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    context: CardContext = CardContext.LIST,
    /** null hides the check (e.g. archived); otherwise toggles done/reopen. */
    onToggleDone: (() -> Unit)?,
    /** "Just saved" ring in done-green (fades out by the caller). */
    ring: Boolean = false,
) {
    val c = AutopilotTheme.colors
    val task = summary.task
    val isDone = task.status.isTerminal
    val status = TaskPresentation.status(summary, now, zone, context)
    val deadline = TaskPresentation.deadline(task, now, zone)
    val description = TaskPresentation.describe(summary, now, zone, context)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(if (ring) 3.dp else 1.dp, if (ring) c.done else c.line, shape)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open task", onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isDone) c.ink3 else c.ink,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                isDone -> null
                summary.nextStepTitle != null -> "Next: ${summary.nextStepTitle}"
                !task.notes.isNullOrBlank() -> task.notes
                else -> null
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp), color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusText(status)
                if (deadline != null) StatusText(deadline)
                if (task.priority == Priority.HIGH && !isDone) StatusText(StatusLine(StatusGlyph.WARN, "High priority", StatusTone.SIGNAL))
            }
            if (summary.stepsTotal > 0 && !isDone) StepsBar(summary.stepsDone, summary.stepsTotal, Modifier.padding(top = 2.dp))
        }
        if (onToggleDone != null) {
            CheckCircle(
                checked = isDone,
                label = if (isDone) "Mark ${task.title} not done" else "Mark ${task.title} done",
                onToggle = onToggleDone,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 30dp circle inside a 48dp target; fills green when checked. */
@Composable
fun CheckCircle(checked: Boolean, label: String, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Int = 30) {
    val c = AutopilotTheme.colors
    val bg by animateColorAsState(if (checked) c.done else Color.Transparent, tween(Motion.CHECK_MS), label = "checkFill")
    val bd by animateColorAsState(if (checked) c.done else c.lineStrong, tween(Motion.CHECK_MS), label = "checkBorder")
    Box(
        modifier
            .size(48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(bg).border(2.dp, bd, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onDone, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}

/** Compact row for "Waiting on others" / "Not today": icon circle, title, status sentence, chevron. */
@Composable
fun CompactTaskRow(summary: TaskSummary, now: Instant, zone: ZoneId, onOpen: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = false) {
    val c = AutopilotTheme.colors
    val status = TaskPresentation.status(summary, now, zone, CardContext.LIST)
    val deadline = TaskPresentation.deadline(summary.task, now, zone)
    if (divider) GroupDivider()
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open task", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = TaskPresentation.describe(summary, now, zone) }
            .heightIn(min = 68.dp)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconCircle(status.glyph.icon(), tint = status.tone.color(), background = c.sunken)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(summary.task.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status.text, style = MaterialTheme.typography.bodySmall, color = c.ink2)
            if (deadline != null) Text(deadline.text, style = MaterialTheme.typography.bodySmall, color = deadline.tone.color())
        }
        Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp))
    }
}

/** "Done today" row: filled check (tap to reopen) + struck-through title. */
@Composable
fun DoneTodayRow(summary: TaskSummary, onOpen: () -> Unit, onReopen: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CheckCircle(checked = true, label = "Mark ${summary.task.title} not done", onToggle = onReopen, size = 26)
        Text(
            summary.task.title,
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink3,
            textDecoration = TextDecoration.LineThrough,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open task", onClick = onOpen).heightIn(min = 48.dp).padding(vertical = 12.dp),
        )
    }
}
