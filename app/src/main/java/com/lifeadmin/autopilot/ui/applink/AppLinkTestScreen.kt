package com.lifeadmin.autopilot.ui.applink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.share.applink.HandoffFragment
import com.lifeadmin.autopilot.share.applink.HandoffIssue
import com.lifeadmin.autopilot.share.applink.HandoffProblem
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.share.applink.TestLinkProblem
import com.lifeadmin.autopilot.share.applink.TestLinkResult
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.PageTitle
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AtkinsonMono
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/** Result of the App Link spike's fixed test route. Read-only: nothing on this screen changes a task. */
@Composable
fun AppLinkTestScreen(state: AppLinkTestUi, diagnostics: AppLinkDiagnostics, host: String, onClose: () -> Unit) {
    val c = AutopilotTheme.colors
    // A plain scrolling column: the screen is short, and every row stays in the semantics tree.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp).testTag("app_link_test"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader(null, onClose, Modifier.statusBarsPadding().offset(x = (-8).dp), backLabel = "Close")
        val handoffLink = state.result is TestLinkResult.HandoffReceived || state.result is TestLinkResult.HandoffRejected
        PageTitle(if (handoffLink) "ChatGPT Handoff Test" else "ChatGPT Link Test")
        SurfaceCard(Modifier.testTag("app_link_result").semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val r = state.result) {
                is TestLinkResult.Received -> {
                    Text("Link received successfully", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                    Fact("Received message", r.message, mono = true, tag = "app_link_message")
                    Fact("Source", if (state.viewIntent) "Android App Link" else "Not a link (opened another way)", tag = "app_link_source")
                    Text("No task changes made", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 10.dp).testTag("app_link_no_changes"))
                }
                is TestLinkResult.Rejected -> {
                    Text("Link not accepted", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(problemText(r.problem, host), style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("app_link_rejected"))
                    Text("No task changes made", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 10.dp).testTag("app_link_no_changes"))
                }
                is TestLinkResult.HandoffReceived -> {
                    HandoffContent(r.handoff)
                    Text("No task changes made", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 12.dp).testTag("app_link_no_changes"))
                }
                is TestLinkResult.HandoffRejected -> {
                    Text("Handoff not accepted", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(handoffIssueText(r.issue), style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("app_link_rejected"))
                    Text("No task changes made", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 10.dp).testTag("app_link_no_changes"))
                }
                null -> Text("No link received. Open the test link from ChatGPT or a browser.", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.testTag("app_link_none"))
            }
        }
        SurfaceCard(Modifier.testTag("app_link_diagnostics")) {
            Text("For the test record", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.semantics { heading() })
            when (val r = state.result) {
                is TestLinkResult.HandoffReceived -> {
                    Fact("Fields received", fieldSummary(r.handoff), tag = "app_link_field_count")
                    Fact("Decoding and validation", "Succeeded", tag = "app_link_validation")
                    Fact("Same as the fixed example", if (r.handoff == LinkHandoff.FIXED_EXAMPLE) "Yes, every field" else "No", tag = "app_link_fixed_match")
                }
                is TestLinkResult.HandoffRejected -> {
                    Fact("Fields received", "${r.issue.fieldsReceived}", tag = "app_link_field_count")
                    Fact("Decoding and validation", "Failed", tag = "app_link_validation")
                }
                else -> Unit
            }
            Fact("Opened by", state.openedBy ?: "Not reported", mono = state.openedBy != null, tag = "app_link_opened_by")
            Fact("Delivered", deliveryText(state), tag = "app_link_delivery")
            Fact("Domain $host", diagnostics.hostState, tag = "app_link_domain_state")
            Fact("Signing certificate SHA-256", diagnostics.signingSha256 ?: "Unavailable", mono = true, selectable = true)
        }
        PrimaryButton("Close", onClose, Modifier.fillMaxWidth().navigationBarsPadding().testTag("app_link_close"))
    }
}

@Composable
private fun HandoffContent(handoff: LinkHandoff) {
    val c = AutopilotTheme.colors
    Text("Task: ${handoff.task}", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("handoff_task"))
    Section(if (handoff.decisions.size == 1) "Decision" else "Decisions", handoff.decisions, "handoff_decision")
    Section(if (handoff.steps.size == 1) "Step" else "Steps", handoff.steps, "handoff_step", numbered = true)
    Section(if (handoff.notes.size == 1) "Note" else "Notes", handoff.notes, "handoff_note")
}

@Composable
private fun Section(title: String, items: List<String>, tag: String, numbered: Boolean = false) {
    if (items.isEmpty()) return
    val c = AutopilotTheme.colors
    Text(title, style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(top = 14.dp).semantics { heading() })
    items.forEachIndexed { i, item ->
        Text(if (numbered) "${i + 1}. $item" else "• $item", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 4.dp).testTag("${tag}_$i"))
    }
}

internal fun fieldSummary(h: LinkHandoff): String {
    fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    return "${h.fieldCount} (format, task, ${count(h.decisions.size, "decision", "decisions")}, " +
        "${count(h.steps.size, "step", "steps")}, ${count(h.notes.size, "note", "notes")})"
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = false, tag: String? = null, selectable: Boolean = false) {
    val style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = AtkinsonMono) else it }
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AutopilotTheme.colors.ink3)
        val text = @Composable { Text(value, style = style, color = AutopilotTheme.colors.ink, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) }
        if (selectable) SelectionContainer { text() } else text()
    }
}

private fun deliveryText(state: AppLinkTestUi): String {
    val how = when (state.delivery) {
        Delivery.NEW_SCREEN -> "Opened this screen"
        Delivery.OPEN_SCREEN -> "Sent to the already open screen"
        Delivery.RESTORED -> "Restored after Android recreated the screen"
    }
    return "$how · links received here: ${state.received}"
}

internal fun problemText(problem: TestLinkProblem, host: String): String = when (problem) {
    TestLinkProblem.MALFORMED -> "The link couldn't be read."
    TestLinkProblem.NOT_HTTPS -> "Only https links are accepted."
    TestLinkProblem.WRONG_HOST -> "This link isn't from $host."
    TestLinkProblem.WRONG_PATH -> "This isn't the Autopilot test address."
    TestLinkProblem.NO_MESSAGE -> "The link has no test message. It may have been cut off."
    TestLinkProblem.INVALID_MESSAGE -> "The test message isn't in the expected form (1–64 letters, digits or - . _ ~)."
}

/** Names the rule and the field position only; the received values are never shown. */
internal fun handoffIssueText(issue: HandoffIssue): String {
    val reason = when (issue.problem) {
        HandoffProblem.UNSUPPORTED_VERSION -> "The link must start with ap=v1."
        HandoffProblem.MALFORMED_FIELD -> "A field isn't in key=value form (empty or missing key, or a stray &)."
        HandoffProblem.UNKNOWN_FIELD -> "A field has a key other than t, d, s or n."
        HandoffProblem.DUPLICATE_FIELD -> "A field that may appear only once (ap or t) appears again."
        HandoffProblem.EMPTY_VALUE -> "A field has no text."
        HandoffProblem.BAD_ENCODING -> "A field has a broken %-escape or isn't valid UTF-8."
        HandoffProblem.AMBIGUOUS_PLUS -> "A field contains a raw +. Write a space as %20 and a plus sign as %2B."
        HandoffProblem.DISALLOWED_CHARACTER -> "A field contains a character that must be %-encoded, a line break or another control character."
        HandoffProblem.EDGE_WHITESPACE -> "A field starts or ends with a space."
        HandoffProblem.VALUE_TOO_LONG -> "A field is too long (task ${HandoffFragment.MAX_TASK}, others ${HandoffFragment.MAX_ITEM} characters at most)."
        HandoffProblem.TOO_MANY_ITEMS -> "Too many items (at most ${HandoffFragment.MAX_DECISIONS} decisions, ${HandoffFragment.MAX_STEPS} steps, ${HandoffFragment.MAX_NOTES} notes)."
        HandoffProblem.MISSING_TASK -> "The link has no task (t=)."
        HandoffProblem.NO_ITEMS -> "The link has a task but no decision, step or note."
    }
    return if (issue.field != null) "$reason Field ${issue.field}." else reason
}
