package com.lifeadmin.autopilot.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.data.repository.label
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Instant
import java.time.ZoneId

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

@Composable
fun MetaChip(text: String, emphasis: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasis) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (emphasis) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

fun reminderLabel(summary: TaskSummary, now: Instant, zone: ZoneId): String? {
    val next = summary.nextReminder ?: return null
    val prefix = when (next.kind) {
        OccurrenceKind.POLICY -> "Reminder"
        OccurrenceKind.CHECK_IN -> "Check-in"
        OccurrenceKind.SNOOZE_END -> "Snoozed until"
    }
    return "$prefix ${Format.relative(next.plannedAt, now, zone)}"
}

@Composable
fun TaskRow(
    summary: TaskSummary,
    now: Instant,
    zone: ZoneId,
    onOpen: () -> Unit,
    onDone: (() -> Unit)?,
    onSnooze: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showStatus: Boolean = false,
) {
    val task = summary.task
    val overdue = task.isOverdue(now, zone)
    val chips = buildList {
        if (showStatus || task.status == TaskStatus.WAITING || task.status == TaskStatus.PAUSED) add(task.status.label() to false)
        if (task.archivedAt != null) add("Archived" to false)
        Format.deadline(task, now, zone)?.let { add((if (overdue) "Overdue · " else "") + it to overdue) }
        reminderLabel(summary, now, zone)?.let { add(it to false) }
        if (summary.stepsTotal > 0) add("${summary.stepsDone}/${summary.stepsTotal} steps" to false)
        if (task.priority == Priority.HIGH) add("High priority" to true)
    }
    val description = buildString {
        append(task.title)
        chips.forEach { append(", ").append(it.first) }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier
                .clickable(onClickLabel = "Open task", onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (task.priority == Priority.HIGH) {
                Surface(Modifier.size(width = 4.dp, height = 36.dp), shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.tertiary) {}
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description }) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (task.status.isTerminal) FontWeight.Normal else FontWeight.Medium,
                    color = if (task.status.isTerminal) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                summary.nextStepTitle?.takeIf { !task.status.isTerminal }?.let {
                    Text("Next: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chips.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.forEach { (text, emph) -> MetaChip(text, emph) }
                    }
                }
            }
            if (onSnooze != null) {
                TextButton(onClick = onSnooze) { Text("Snooze") }
            }
            if (onDone != null) {
                FilledTonalIconButton(onClick = onDone) {
                    Icon(Icons.Filled.Check, contentDescription = "Mark ${task.title} done")
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
