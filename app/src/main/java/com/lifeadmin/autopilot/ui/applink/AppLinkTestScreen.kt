package com.lifeadmin.autopilot.ui.applink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
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
    LazyColumn(
        Modifier.fillMaxSize().testTag("app_link_test"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackHeader(null, onClose, Modifier.statusBarsPadding().offset(x = (-8).dp), backLabel = "Close") }
        item { PageTitle("ChatGPT Link Test") }
        item {
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
                    null -> Text("No link received. Open the test link from ChatGPT or a browser.", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.testTag("app_link_none"))
                }
            }
        }
        item {
            SurfaceCard(Modifier.testTag("app_link_diagnostics")) {
                Text("For the test record", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.semantics { heading() })
                Fact("Opened by", state.openedBy ?: "Not reported", mono = state.openedBy != null, tag = "app_link_opened_by")
                Fact("Delivered", deliveryText(state), tag = "app_link_delivery")
                Fact("Domain $host", diagnostics.hostState, tag = "app_link_domain_state")
                Fact("Signing certificate SHA-256", diagnostics.signingSha256 ?: "Unavailable", mono = true, selectable = true)
            }
        }
        item { PrimaryButton("Close", onClose, Modifier.fillMaxWidth().navigationBarsPadding().testTag("app_link_close")) }
    }
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
