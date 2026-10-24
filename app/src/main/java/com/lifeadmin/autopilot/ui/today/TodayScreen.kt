package com.lifeadmin.autopilot.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.CardContext
import com.lifeadmin.autopilot.ui.components.CompactTaskRow
import com.lifeadmin.autopilot.ui.components.DoneTodayRow
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.RowGroup
import com.lifeadmin.autopilot.ui.components.ScreenHeader
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.TaskCard
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.components.dashedBorder
import com.lifeadmin.autopilot.ui.components.showToast
import com.lifeadmin.autopilot.ui.health.HealthBanner
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private val RAIL_COLUMN = 52.dp

/** The time column grows with the system font size so "09:00" stays on one line at 130–200%. */
@Composable
private fun railColumn(): androidx.compose.ui.unit.Dp = RAIL_COLUMN * LocalDensity.current.fontScale.coerceIn(1f, 2f)

/**
 * Today as a flight plan: a headline sentence, the inbox prompt, the nudge rail with a Now
 * marker (first nudge per task as a card, repeats as ghost rows, deadlines and due check-ins as
 * attention rows), then Waiting on others, Not today and Done today.
 */
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    snackbar: SnackbarHostState,
    onOpenTask: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSettings: () -> Unit = {},
    healthIssue: Boolean = false,
    onOpenResearch: (() -> Unit)? = null,
    showHealth: Boolean = false,
    container: AppContainer? = null,
    onOpenHealth: () -> Unit = {},
    onCaptureWithText: (String) -> Unit = {},
    /** Task that was just saved from the capture sheet: its card gets the green "just saved" ring. */
    highlightTaskId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val scope = rememberCoroutineScope()
    val plan = state.plan

    fun done(s: TaskSummary) = viewModel.complete(s.task.id) {
        scope.launch {
            val r = snackbar.showToast("Done. Nudges stopped.", actionLabel = "Undo")
            if (r == SnackbarResult.ActionPerformed) viewModel.undoComplete(s.task.id)
        }
    }
    fun reopen(s: TaskSummary) = viewModel.undoComplete(s.task.id)

    LazyColumn(
        Modifier.fillMaxSize().testTag("today_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item {
            ScreenHeader(state.dateText, onOpenSettings, Modifier.statusBarsPadding(), showMark = true, healthDot = healthIssue, onOpenResearch = onOpenResearch)
        }
        if (showHealth && container != null) item { HealthBanner(container, onFix = onOpenHealth) }

        if (state.isEmpty) {
            item { EmptyToday(state, onCaptureWithText, onExample = { viewModel.createExample(onOpenTask) }, onDismissOnboarding = viewModel::dismissOnboarding) }
            return@LazyColumn
        }

        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text(plan.headline, style = MaterialTheme.typography.displayMedium, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("today_headline"))
                if (plan.sub.isNotBlank()) Text(plan.sub, style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (plan.inbox.isNotEmpty()) item { InboxPrompt(plan.inbox.size, plan.inbox.first().task.title, onOpenInbox) }

        if (plan.rail.isNotEmpty()) {
            item {
                val qh = state.quietHours
                SectionTitle(
                    "Today's nudges",
                    Modifier.padding(top = 28.dp, bottom = 10.dp),
                    trailing = if (qh.isEffective) "Quiet ${qh.start.format(hhmm)}–${qh.end.format(hhmm)}" else null,
                )
            }
            itemsIndexed(plan.rail, key = { i, e -> railKey(i, e) }) { i, entry ->
                val last = i == plan.rail.lastIndex
                when (entry) {
                    is RailEntry.Now -> NowRow(entry.time, last)
                    is RailEntry.Attention -> RailCardRow(label = entry.label, labelTone = c.signal, last = last) {
                        TaskCard(entry.summary, state.now, state.zone, onOpen = { onOpenTask(entry.summary.task.id) }, context = CardContext.LIST, onToggleDone = { done(entry.summary) }, ring = entry.summary.task.id == highlightTaskId)
                    }
                    is RailEntry.Card -> RailCardRow(label = entry.time, labelTone = c.ink, last = last, mono = true) {
                        TaskCard(entry.summary, state.now, state.zone, onOpen = { onOpenTask(entry.summary.task.id) }, context = CardContext.RAIL, onToggleDone = { done(entry.summary) }, ring = entry.summary.task.id == highlightTaskId)
                    }
                    is RailEntry.Ghost -> GhostRow(entry.time, entry.summary.task.title, "again, if it's still open", AutopilotIcons.Bell, last) { onOpenTask(entry.summary.task.id) }
                    is RailEntry.CheckIn -> GhostRow(entry.time, entry.summary.task.title, "check in", AutopilotIcons.Hourglass, last) { onOpenTask(entry.summary.task.id) }
                }
            }
        }

        if (plan.waiting.isNotEmpty()) {
            item { SectionTitle("Waiting on others", Modifier.padding(top = 32.dp, bottom = 10.dp)) }
            item {
                RowGroup {
                    plan.waiting.forEachIndexed { i, s -> CompactTaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, divider = i > 0) }
                }
            }
        }
        if (plan.later.isNotEmpty()) {
            item { SectionTitle("Not today", Modifier.padding(top = 28.dp, bottom = 10.dp)) }
            item {
                RowGroup {
                    plan.later.forEachIndexed { i, s -> CompactTaskRow(s, state.now, state.zone, onOpen = { onOpenTask(s.task.id) }, divider = i > 0) }
                }
            }
        }
        if (plan.doneToday.isNotEmpty()) {
            item { SectionTitle("Done today", Modifier.padding(top = 28.dp, bottom = 6.dp)) }
            items(plan.doneToday, key = { "done-" + it.task.id }) { s -> DoneTodayRow(s, onOpen = { onOpenTask(s.task.id) }, onReopen = { reopen(s) }) }
        }
        if (plan.rail.isEmpty() && plan.waiting.isEmpty() && plan.later.isEmpty() && plan.inbox.isEmpty() && plan.doneToday.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun railKey(i: Int, e: RailEntry): String = when (e) {
    is RailEntry.Now -> "now"
    is RailEntry.Attention -> "a-${e.summary.task.id}"
    is RailEntry.Card -> "c-${e.summary.task.id}"
    is RailEntry.Ghost -> "g-${e.summary.task.id}-${e.at.toEpochMilli()}"
    is RailEntry.CheckIn -> "k-${e.summary.task.id}"
}

/** Draws the rail's vertical line behind a row (continuous across rows because each row draws its own segment). */
private fun Modifier.railLine(color: Color, column: androidx.compose.ui.unit.Dp, fromTop: Boolean = true, last: Boolean = false): Modifier = drawBehind {
    val x = column.toPx() / 2f
    val top = if (fromTop) 0f else size.height / 2f
    val bottom = if (last) size.height * 0.35f else size.height
    if (bottom > top) drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx())
}

@Composable
private fun NowRow(time: String, last: Boolean) {
    val c = AutopilotTheme.colors
    val column = railColumn()
    Row(
        Modifier.fillMaxWidth().height(32.dp).railLine(c.line, column, fromTop = false, last = last).semantics(mergeDescendants = true) { contentDescription = "Now, $time" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(column), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(c.signalSoft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(c.signal))
            }
        }
        TimeText("Now · $time", size = 13, color = c.signal)
    }
}

@Composable
private fun RailCardRow(label: String, labelTone: Color, last: Boolean, mono: Boolean = false, card: @Composable () -> Unit) {
    val c = AutopilotTheme.colors
    val column = railColumn()
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val labelContent: @Composable () -> Unit = {
        if (mono) TimeText(label, size = 13, color = labelTone, modifier = Modifier.background(c.paper).padding(vertical = 3.dp, horizontal = 4.dp))
        else Text(label, style = MaterialTheme.typography.labelSmall, color = labelTone, modifier = Modifier.background(c.paper).padding(vertical = 3.dp, horizontal = 4.dp), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (largeText) {
        // At ≥ 130% text size the time moves above the card so titles don't squeeze beside the rail.
        Column(Modifier.fillMaxWidth().padding(top = 10.dp).railLine(c.line, column, last = last)) {
            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) { labelContent() }
            Box(Modifier.padding(start = column - 16.dp, top = 4.dp)) { card() }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).railLine(c.line, column, last = last), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(column).padding(top = 20.dp), contentAlignment = Alignment.TopCenter) { labelContent() }
            Box(Modifier.weight(1f)) { card() }
        }
    }
}

@Composable
private fun GhostRow(time: String, title: String, suffix: String, icon: androidx.compose.ui.graphics.vector.ImageVector, last: Boolean, onOpen: () -> Unit) {
    val c = AutopilotTheme.colors
    val column = railColumn()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).railLine(c.line, column, last = last), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(column), contentAlignment = Alignment.Center) {
            TimeText(time, size = 13, color = c.ink2, modifier = Modifier.background(c.paper).padding(vertical = 3.dp).testTag("rail_time"))
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .dashedBorder(c.lineStrong, 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClickLabel = "Open task", onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "$time, $title, $suffix" }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
            Text(
                buildString { append(title); append(" "); append(suffix) },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InboxPrompt(count: Int, peek: String, onOpenInbox: () -> Unit) {
    val c = AutopilotTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .heightIn(min = 64.dp)
            .dashedBorder(c.lineStrong, 18.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpenInbox)
            .semantics(mergeDescendants = true) { contentDescription = "$count ${if (count == 1) "note" else "notes"} to sort in Inbox. $peek" }
            .testTag("today_inbox_prompt")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconCircle(AutopilotIcons.Tray, tint = c.ink2, background = c.sunken)
        Column(Modifier.weight(1f)) {
            Text(if (count == 1) "1 note to sort" else "$count notes to sort", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
            Text(peek, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.height(36.dp).clip(CircleShape).background(c.inverse).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text("Sort", style = MaterialTheme.typography.labelLarge, color = c.onInverse)
        }
    }
}

@Composable
private fun EmptyToday(state: TodayUiState, onCaptureWithText: (String) -> Unit, onExample: () -> Unit, onDismissOnboarding: () -> Unit) {
    val c = AutopilotTheme.colors
    val density = LocalDensity.current
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Horizon, rising arc, dotted route — drawn with theme colors so dark mode is free.
        Box(
            Modifier.fillMaxWidth().height(120.dp).drawBehind {
                val w = size.width
                val y = size.height * 0.78f
                drawLine(c.lineStrong, Offset(0f, y), Offset(w, y), strokeWidth = with(density) { 1.5.dp.toPx() })
                drawArc(c.signal, 180f, 180f, useCenter = false, topLeft = Offset(w * 0.6f, y - w * 0.14f), size = androidx.compose.ui.geometry.Size(w * 0.28f, w * 0.28f), style = Stroke(with(density) { 2.dp.toPx() }))
                val dots = PathEffect.dashPathEffect(floatArrayOf(6f, 9f))
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.1f, y + 14f); quadraticTo(w * 0.35f, y - 10f, w * 0.72f, y - w * 0.22f)
                }
                drawPath(path, c.ink3, style = Stroke(with(density) { 2.dp.toPx() }, pathEffect = dots))
                drawCircle(c.ink, radius = with(density) { 5.dp.toPx() }, center = Offset(w * 0.1f, y + 14f))
                drawCircle(c.ink, radius = with(density) { 4.5.dp.toPx() }, center = Offset(w * 0.72f, y - w * 0.22f), style = Stroke(with(density) { 2.dp.toPx() }))
            },
        )
        Text("A clear day.", style = MaterialTheme.typography.displayMedium, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("today_headline"))
        Text(
            if (state.showOnboarding) "Nothing needs you right now. Tell Autopilot what you mean to do — it remembers, and keeps after you until it's done. Everything stays on this phone: no account, no cloud. AI planning is optional and off unless you set it up. Dismissing a notification never completes a task."
            else "Nothing needs you right now. Tell Autopilot what you mean to do — it remembers, and keeps after you until it's done.",
            style = MaterialTheme.typography.bodyLarge, color = c.ink2,
        )
        Text("Try something like", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Renew car insurance before 3 Nov", "Call Mum on Sunday at 6", "Return the library books, remind me Friday").forEach { example ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClickLabel = "Capture this example") { onCaptureWithText(example) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(AutopilotIcons.Plus, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
                    Text(example, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        }
        if (state.showOnboarding) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkButton("Create the example task", onClick = onExample, modifier = Modifier.testTag("today_example"))
                LinkButton("Got it", onClick = onDismissOnboarding)
            }
            Text("The example creates “Book train ride” with nudges at 09:00 and 18:00 daily until you mark it done.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
        }
    }
}
