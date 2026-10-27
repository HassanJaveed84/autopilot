package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.share.chatgpt.ChatGptPrompt
import com.lifeadmin.autopilot.share.chatgpt.ChatGptShare
import com.lifeadmin.autopilot.share.chatgpt.DiscussChoices
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.InkSwitch
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.ZoneId

/**
 * "Discuss in ChatGPT" on task detail (docs/CHATGPT_ROUND_TRIP_UX.md §2). Independent of the AI
 * settings: it needs no API key, makes no request and writes nothing; it only prepares text and hands
 * it to the ChatGPT app.
 */
@Composable
internal fun DiscussCard(onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = "Discuss in ChatGPT", onClick = onClick).padding(16.dp).testTag("action_discuss_chatgpt"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(AutopilotIcons.Chat, tint = c.ink, background = c.sunken)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Discuss in ChatGPT", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink)
            Text("Talk it through in your ChatGPT app, then send the decisions back here.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
        }
        Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp))
    }
}

/** What ChatGPT will see, with notes off by default, and one button to open it. */
@Composable
internal fun DiscussSheet(d: TaskDetails, zone: ZoneId, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    val context = LocalContext.current
    val task = d.task
    var steps by rememberSaveable { mutableStateOf(true) }
    var deadline by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var showFull by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val installed = remember { ChatGptShare.isAvailable(context) }
    val choices = DiscussChoices(steps = steps, deadline = deadline, notes = notes)
    val prompt = ChatGptPrompt.build(task, d.steps, choices, BuildConfig.APP_LINK_HOST, zone)
    val deadlineText = ChatGptPrompt.deadlineText(task, zone)
    val done = d.steps.count { it.isDone }
    val open = d.steps.size - done

    AutopilotSheet(
        title = "Discuss in ChatGPT", onDismiss = onDismiss, modifier = Modifier.testTag("discuss_sheet"),
        footer = {
            if (failed) Text("No app on this phone can take shared text.", style = MaterialTheme.typography.bodyMedium, color = c.danger, modifier = Modifier.padding(bottom = 8.dp).testTag("discuss_failed"))
            PrimaryButton(
                if (installed) "Open ChatGPT" else "Share…",
                onClick = { if (ChatGptShare.open(context, prompt)) onDismiss() else failed = true },
                icon = if (installed) AutopilotIcons.OpenExternal else null,
                modifier = Modifier.fillMaxWidth().testTag("discuss_open"),
            )
        },
    ) {
        Text("ChatGPT will see", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.semantics { heading() })
        ShareRow("Task", task.title, checked = null, tag = "discuss_task")
        if (d.steps.isNotEmpty()) {
            val summary = listOfNotNull(done.takeIf { it > 0 }?.let { "$it done" }, open.takeIf { it > 0 }?.let { "$it to do" }).joinToString(", ")
            ShareRow("Steps", summary, checked = steps, tag = "discuss_steps") { steps = it }
        }
        if (deadlineText != null) ShareRow("Deadline", deadlineText, checked = deadline, tag = "discuss_deadline") { deadline = it }
        task.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { n ->
            ShareRow("Notes", n.lineSequence().first(), checked = notes, tag = "discuss_notes") { notes = it }
        }
        Text(
            if (installed) "Autopilot also adds a short note telling ChatGPT how to send your decisions back. Nothing is sent until you press send in ChatGPT, and your task doesn't change."
            else "ChatGPT isn't on this phone. You can share the message to another app instead. Nothing changes in Autopilot.",
            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp).testTag("discuss_explainer"),
        )
        LinkButton(if (showFull) "Hide the full message" else "See the full message", onClick = { showFull = !showFull }, modifier = Modifier.padding(top = 4.dp).testTag("discuss_full_toggle"))
        if (showFull) {
            SelectionContainer {
                Text(
                    prompt, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = c.ink2,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(12.dp).testTag("discuss_full"),
                )
            }
        }
    }
}

/** One shared field: always included when [checked] is null, otherwise a switch on the whole row. */
@Composable
private fun ShareRow(label: String, value: String, checked: Boolean?, tag: String, onChange: (Boolean) -> Unit = {}) {
    val c = AutopilotTheme.colors
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    Row(
        (if (checked == null) base else base.toggleable(value = checked, role = Role.Switch, onValueChange = onChange)).padding(vertical = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink2)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (checked == false) c.ink3 else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (checked == null) Icon(AutopilotIcons.Check, contentDescription = "Always included", tint = c.ink2, modifier = Modifier.size(20.dp))
        else InkSwitch(checked = checked)
    }
}
