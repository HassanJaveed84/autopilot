package com.lifeadmin.autopilot.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.share.chatgpt.AutopilotHandoff
import com.lifeadmin.autopilot.share.chatgpt.FetchResult
import com.lifeadmin.autopilot.share.chatgpt.HandoffParser
import com.lifeadmin.autopilot.share.chatgpt.HandoffResult
import com.lifeadmin.autopilot.share.chatgpt.ImportResult
import com.lifeadmin.autopilot.share.chatgpt.Role
import com.lifeadmin.autopilot.share.chatgpt.SharedConversation
import com.lifeadmin.autopilot.share.chatgpt.TargetChoice
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.PageTitle
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AtkinsonMono
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/** Import preview for a shared ChatGPT link. Read-only: nothing on this screen changes a task. */
@Composable
fun ShareImportScreen(state: ShareImportUi, onFetch: () -> Unit, onClose: () -> Unit) {
    val c = AutopilotTheme.colors
    LazyColumn(
        Modifier.fillMaxSize().testTag("share_import"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackHeader(null, onClose, Modifier.statusBarsPadding().offset(x = (-8).dp), backLabel = "Close") }
        val selection = state is ShareImportUi.Selection || state == ShareImportUi.EmptySelection
        item { PageTitle(if (selection) "Selected text" else "Import from ChatGPT") }
        when (state) {
            is ShareImportUi.Selection -> selectionItems(state, onClose)
            ShareImportUi.EmptySelection -> item {
                Column {
                    SurfaceCard(Modifier.testTag("selection_empty")) {
                        Text("No text arrived. Select some text, then choose Send to Autopilot again.", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    }
                    PrimaryButton("Close", onClose, Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
            is ShareImportUi.NoLink -> item {
                Column {
                    SurfaceCard(Modifier.testTag("share_no_link")) {
                        Text(
                            if (state.otherLink) "This isn't a ChatGPT shared link. Autopilot only imports links that start with https://chatgpt.com/share/."
                            else "Nothing to import. Share a conversation from the ChatGPT app and choose Autopilot.",
                            style = MaterialTheme.typography.bodyLarge, color = c.ink,
                        )
                    }
                    PrimaryButton("Close", onClose, Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
            is ShareImportUi.Ready -> {
                linkItems(state.link.url)
                item { PrimaryButton("Fetch conversation", onFetch, Modifier.fillMaxWidth().testTag("share_fetch")) }
            }
            is ShareImportUi.Fetching -> {
                linkItems(state.link.url)
                item {
                    Row(Modifier.testTag("share_fetching").semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Fetching from chatgpt.com…", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            is ShareImportUi.Done -> {
                linkItems(state.link.url)
                when (val r = state.result) {
                    is ImportResult.Extracted -> extractedItems(r.conversation, r.handoff, onClose)
                    else -> item {
                        Column {
                            SurfaceCard(Modifier.testTag("share_error").semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(errorText(r), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                            }
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (retryable(r)) SecondaryButton("Try again", onFetch, Modifier.weight(1f).testTag("share_retry"))
                                PrimaryButton("Close", onClose, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Text-selection spike: proof the text arrived. Shown only; not a shared link, not a handoff, not a task change. */
private fun androidx.compose.foundation.lazy.LazyListScope.selectionItems(s: ShareImportUi.Selection, onClose: () -> Unit) {
    item {
        val c = AutopilotTheme.colors
        SurfaceCard(Modifier.testTag("selection_summary")) {
            Text("Received through the text-selection menu", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(
                "%,d characters".format(s.receivedChars) + (if (s.truncated) ", showing the first %,d".format(s.text.length) else "") +
                    (if (s.readOnly) " · read-only selection" else " · editable selection, left unchanged"),
                style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("selection_count"),
            )
            Text(
                "This is not a shared link: nothing was downloaded, saved or sent, and your tasks are unchanged.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    item {
        SurfaceCard(Modifier.testTag("selection_preview")) {
            Text("Preview", style = MaterialTheme.typography.labelLarge, color = AutopilotTheme.colors.ink2, modifier = Modifier.semantics { heading() })
            val preview = if (s.text.length > PREVIEW_CHARS) s.text.take(PREVIEW_CHARS) + "…" else s.text
            SelectionContainer { Text(preview, style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink, modifier = Modifier.padding(top = 6.dp).testTag("selection_text")) }
        }
    }
    item { PrimaryButton("Close", onClose, Modifier.fillMaxWidth().navigationBarsPadding().testTag("selection_close")) }
}

private const val PREVIEW_CHARS = 2_000

private fun androidx.compose.foundation.lazy.LazyListScope.linkItems(url: String) {
    item {
        SurfaceCard {
            Text("Shared link", style = MaterialTheme.typography.labelLarge, color = AutopilotTheme.colors.ink2)
            Text(url, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AtkinsonMono), color = AutopilotTheme.colors.ink, modifier = Modifier.padding(top = 4.dp).testTag("share_url"))
        }
    }
    item {
        Text(
            "Shared ChatGPT links are public: anyone with this link can open the conversation. Autopilot downloads it from chatgpt.com only when you tap Fetch, keeps it on this screen only, and doesn't change your tasks.",
            style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2, modifier = Modifier.testTag("share_privacy"),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.extractedItems(conv: SharedConversation, handoff: HandoffResult, onClose: () -> Unit) {
    item {
        val c = AutopilotTheme.colors
        val replies = conv.messages.count { it.role == Role.ASSISTANT }
        SurfaceCard(Modifier.testTag("share_summary")) {
            Text(conv.title ?: "Untitled conversation", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(
                "${conv.messages.size} messages, $replies from ChatGPT. " +
                    if (conv.targetChoice == TargetChoice.HIGHLIGHTED) "Showing the response you shared." else "Showing the latest response.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    item { HandoffCard(handoff) }
    item {
        SurfaceCard(Modifier.testTag("share_response")) {
            Text("ChatGPT's response", style = MaterialTheme.typography.labelLarge, color = AutopilotTheme.colors.ink2, modifier = Modifier.semantics { heading() })
            SelectionContainer { Text(conv.target.text, style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
    item { PrimaryButton("Close", onClose, Modifier.fillMaxWidth().navigationBarsPadding()) }
}

@Composable
private fun HandoffCard(result: HandoffResult) {
    val c = AutopilotTheme.colors
    SurfaceCard(Modifier.testTag("share_handoff").semantics { liveRegion = LiveRegionMode.Polite }) {
        when (result) {
            is HandoffResult.Found -> {
                Text("Autopilot handoff found", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                HandoffSummary(result.handoff)
                Text("Preview only: nothing has been added to your tasks.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 8.dp).testTag("share_preview_only"))
            }
            HandoffResult.NotFound -> {
                Text("No Autopilot handoff in this response", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(
                    "Ask ChatGPT to finish with a ```${HandoffParser.FENCE_INFO} code block, then share again. Ordinary replies are never turned into changes.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp),
                )
            }
            is HandoffResult.Ambiguous -> Problem("This response has ${result.count} handoff blocks. Ask ChatGPT for exactly one.")
            is HandoffResult.UnsupportedVersion -> Problem("This handoff uses a format this version of Autopilot doesn't read.")
            is HandoffResult.Malformed -> Problem("The handoff block couldn't be read: ${result.problems.take(5).joinToString("; ")}.")
        }
    }
}

@Composable
private fun Problem(text: String) {
    Text("Handoff not usable", style = MaterialTheme.typography.titleMedium, color = AutopilotTheme.colors.ink, modifier = Modifier.semantics { heading() })
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun HandoffSummary(h: AutopilotHandoff) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line("Task", h.target.title + if (h.target.taskRef != null) " (linked)" else " (no task link)")
        h.summary?.let { Line("Summary", it) }
        h.decisions.forEach { Line("Decision", it.text + (it.rationale?.let { r -> " — $r" } ?: "")) }
        h.notes.forEach { Line("Note", it) }
        h.steps.forEach { Line("Step", it.text + (it.due?.let { d -> " (by $d)" } ?: "")) }
        h.deadline?.let { Line("Deadline", listOfNotNull(it.date, it.time, it.note).joinToString(" ")) }
        h.reminders.forEach { Line("Reminder", listOfNotNull(it.date, it.time, it.note).joinToString(" ")) }
        h.openQuestions.forEach { Line("Open question", it) }
        if (h.decisions.isEmpty() && h.steps.isEmpty() && h.notes.isEmpty()) Text("No decisions, notes or steps.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
    }
}

@Composable
private fun Line(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AutopilotTheme.colors.ink3)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink)
    }
}

private fun retryable(r: ImportResult): Boolean = r is ImportResult.FetchFailed && when (r.failure) {
    is FetchResult.Timeout, is FetchResult.Network, is FetchResult.Refused, is FetchResult.HttpError -> true
    else -> false
}

internal fun errorText(r: ImportResult): String = when (r) {
    ImportResult.Unavailable -> "This shared conversation isn't available. It may have been deleted, the link revoked, or the link is wrong."
    is ImportResult.Unrecognized -> "chatgpt.com answered, but the page wasn't in a format Autopilot can read, so nothing was imported. ChatGPT may have changed its pages."
    is ImportResult.FetchFailed -> when (val f = r.failure) {
        is FetchResult.RedirectBlocked -> "chatgpt.com tried to send Autopilot to another address${f.toHost?.let { " ($it)" } ?: ""}. It wasn't followed."
        FetchResult.TooManyRedirects -> "Too many redirects. Nothing was imported."
        is FetchResult.Refused -> "chatgpt.com refused the request (${f.status}). Try again later."
        is FetchResult.HttpError -> "chatgpt.com answered with an error (${f.status})."
        is FetchResult.NotHtml -> "chatgpt.com didn't return a conversation page."
        FetchResult.TooLarge -> "The page is larger than Autopilot will download."
        FetchResult.Timeout -> "chatgpt.com didn't answer in time."
        is FetchResult.Network -> "Couldn't reach chatgpt.com. Check your connection."
    }
    is ImportResult.Extracted -> ""
}
