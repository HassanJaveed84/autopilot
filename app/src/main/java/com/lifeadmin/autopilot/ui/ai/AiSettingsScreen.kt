package com.lifeadmin.autopilot.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSnackbar
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.GroupDivider
import com.lifeadmin.autopilot.ui.components.InkSwitch
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.components.showToast
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/** What leaves the device, shown before the first request and on this screen. */
const val AI_PRIVACY_TEXT =
    "When you tap “Plan with AI” or “Move this forward”, the text you typed is sent to Anthropic over an encrypted " +
        "connection, together with the current date, time, time zone, your quiet hours and default reminder times. For " +
        "“Move this forward”, the selected task's title, notes, checklist, status, deadline and reminder summary " +
        "are sent too. Nothing else is sent: not your other tasks, history, notifications or diagnostics. Nothing is sent " +
        "in the background. Anthropic processes requests under its API terms and privacy policy."

const val AI_BILLING_TEXT =
    "A Claude app or Claude Code subscription does not cover this. AI planning uses your own Anthropic API key " +
        "(console.anthropic.com), and each request is billed to that API account. With the default model a plan " +
        "typically costs a fraction of a cent. The app limits itself to ${AiConfig.MAX_REQUESTS_PER_DAY} requests per day."

const val AI_KEY_SECURITY_TEXT =
    "The key is encrypted with a key held in this phone's Android Keystore and stored only in app-private storage. It is " +
        "excluded from backups and from Export, and never written to logs. This is a personal-use compromise: a key on a " +
        "device can be extracted by someone with full access to it. A public app would instead use a server that holds the key."

/** AI planning settings: the switch, your key (Paste / Remove / Test), model, exactly what is sent, and removal. */
@Composable
fun AiSettingsScreen(viewModel: AiSettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var showConsent by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteResearch by remember { mutableStateOf(false) }
    var confirmDeleteKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var editingKey by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showToast(it); viewModel.messageShown() } }

    Scaffold(containerColor = c.paper, contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) { data -> AutopilotSnackbar(data) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("ai_settings_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
            item { BackHeader("AI planning", onBack, Modifier.statusBarsPadding().offset(x = (-8).dp)) }
            item {
                SurfaceCard(modifier = Modifier.padding(top = 10.dp), radius = 22.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = s.enabled, role = Role.Switch,
                            onValueChange = { on -> if (on && s.consentAt == null) showConsent = true else viewModel.setEnabled(on) },
                        ).testTag("ai_enabled_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Use AI planning", style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), color = c.ink, modifier = Modifier.weight(1f))
                        InkSwitch(checked = s.enabled, modifier = Modifier.testTag("ai_enabled_switch"))
                    }
                    Text(
                        "Turns a sentence into a draft task — steps, nudges, deadline — that you edit and approve before anything is saved. Off by default. Every other feature works without it.",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item {
                SurfaceCard(modifier = Modifier.padding(top = 12.dp), radius = 22.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AutopilotIcons.Key, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                        Text("Your Anthropic API key", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink, modifier = Modifier.semantics { heading() })
                    }
                    Text(
                        if (s.hasKey) "Key saved on this device" + (s.keyHint?.let { " (ends …$it)" } ?: "") else "No key set up",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("ai_key_status"),
                    )
                    if (!s.hasKey || editingKey) {
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = keyInput,
                                onValueChange = { keyInput = it.take(400); keyError = null },
                                modifier = Modifier.weight(1f).testTag("ai_key_input"),
                                placeholder = { Text("sk-ant-…", color = c.ink3) },
                                singleLine = true, shape = RoundedCornerShape(14.dp),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                isError = keyError != null,
                            )
                            SecondaryButton("Paste", onClick = { clipboard.getText()?.text?.let { keyInput = it.trim().take(400); keyError = null } }, icon = AutopilotIcons.Clip, minHeight = 48.dp)
                        }
                        Text(keyError ?: "Create one at console.anthropic.com → API keys.", style = MaterialTheme.typography.bodySmall, color = if (keyError != null) c.danger else c.ink2, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                if (s.hasKey) "Replace key" else "Save key",
                                onClick = { viewModel.saveKey(keyInput) { err -> keyError = err; if (err == null) { keyInput = ""; editingKey = false } } },
                                enabled = keyInput.isNotBlank(), minHeight = 48.dp, modifier = Modifier.testTag("ai_key_save"),
                            )
                            if (editingKey) SecondaryButton("Cancel", onClick = { editingKey = false; keyInput = ""; keyError = null }, minHeight = 48.dp)
                        }
                    } else {
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.sunken).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                                TimeText("sk-ant-••••••••" + (s.keyHint ?: "••••"), size = 15)
                            }
                            SecondaryButton("Remove", onClick = { confirmDeleteKey = true }, minHeight = 48.dp, modifier = Modifier.testTag("ai_key_delete"))
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Test connection", onClick = viewModel::testConnection, enabled = !s.testing, minHeight = 44.dp, modifier = Modifier.testTag("ai_test_connection"))
                            SecondaryButton("Replace", onClick = { editingKey = true }, minHeight = 44.dp, modifier = Modifier.testTag("ai_key_replace"))
                        }
                        if (s.testing) {
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp).padding(end = 0.dp), color = c.route)
                                Text("Checking with Anthropic…", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                        s.testResult?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (s.testOk) c.done else c.danger, modifier = Modifier.padding(top = 8.dp).testTag("ai_test_result"))
                        }
                    }
                    Text("Stored only on this phone. Create one at console.anthropic.com — usage is billed to your Anthropic account.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
                    LinkButton(if (showSecurity) "Hide how the key is protected" else "How the key is protected", onClick = { showSecurity = !showSecurity })
                    if (showSecurity) Text(AI_KEY_SECURITY_TEXT, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
            }

            item { SectionTitle("Exactly what's sent", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                SurfaceCard(radius = 22.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("ai_privacy")) {
                    SentBlock("When you tap Plan with AI", listOf("The text you typed", "Date, time and time zone", "Your quiet hours and default nudge times"))
                    GroupDivider()
                    SentBlock("When you ask to move a task forward", listOf("That one task's title, notes, steps, status, deadline and follow-up"))
                    GroupDivider()
                    Column(Modifier.fillMaxWidth().background(c.sunken)) { SentBlock("Never sent", listOf("Your other tasks or Inbox", "History, notifications or diagnostics", "Anything in the background — only when you tap")) }
                }
                Text(AI_PRIVACY_TEXT, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
            }
            item { SectionTitle("Billing", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                SurfaceCard(radius = 22.dp, modifier = Modifier.testTag("ai_billing")) {
                    Text(AI_BILLING_TEXT, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    Text(
                        "Requests today: ${s.requestsToday} of ${AiConfig.MAX_REQUESTS_PER_DAY} · max ${AiConfig.MAX_OUTPUT_TOKENS} output tokens per request",
                        style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item { SectionTitle("Model", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                SurfaceCard(radius = 22.dp, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    Column(Modifier.selectableGroup()) {
                        AiConfig.MODELS.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                                    .selectable(selected = s.model == m.id, role = Role.RadioButton, onClick = { viewModel.setModel(m.id) })
                                    .padding(horizontal = 8.dp, vertical = 6.dp).testTag("ai_model_${m.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.model == m.id, onClick = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                                }
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Web research", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                SurfaceCard(radius = 22.dp, modifier = Modifier.testTag("ai_research")) {
                    Text(
                        "From a task, “Research online” searches the web with Anthropic’s web search tool and cites sources. Each request is started by you, capped at " +
                            "${com.lifeadmin.autopilot.ai.research.ResearchConfig.MAX_SEARCHES_PER_REQUEST} searches, and billed by Anthropic on top of tokens. Nothing runs in the background.",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink2,
                    )
                    Text(
                        "Research today: ${s.researchToday} of ${com.lifeadmin.autopilot.ai.research.ResearchConfig.MAX_REQUESTS_PER_DAY} · saved briefs: ${s.briefCount}" +
                            (if (s.researchExplainerAccepted) " · one-time note accepted" else " · one-time note shown before the first request"),
                        style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp).testTag("ai_research_usage"),
                    )
                    Text("Saved briefs open offline and are included in backups (never transcripts or the key). Deleting them keeps any steps you added.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
                    SecondaryButton("Delete all research", onClick = { confirmDeleteResearch = true }, enabled = s.briefCount > 0 || s.researchExplainerAccepted, minHeight = 48.dp, modifier = Modifier.padding(top = 10.dp).testTag("ai_research_delete"))
                }
            }
            item { SectionTitle("Remove", Modifier.padding(top = 26.dp, bottom = 10.dp, start = 4.dp)) }
            item {
                SurfaceCard(radius = 22.dp) {
                    Text("Deletes the API key, privacy consent, model choice and usage counter from this device and turns AI planning off. Tasks are not affected.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    SecondaryButton("Delete AI settings", onClick = { confirmDeleteAll = true }, minHeight = 48.dp, modifier = Modifier.padding(top = 10.dp).testTag("ai_delete_all"))
                }
            }
        }
    }

    if (confirmDeleteResearch) {
        AlertDialog(
            onDismissRequest = { confirmDeleteResearch = false },
            title = { Text("Delete all research?") },
            text = { Text("Removes every saved research brief from this phone and shows the one-time research note again next time. Steps and notes already added to tasks stay.") },
            confirmButton = { TextButton(onClick = { confirmDeleteResearch = false; viewModel.deleteAllResearch() }, modifier = Modifier.testTag("ai_research_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteResearch = false }) { Text("Keep") } },
        )
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Before you turn on AI planning") },
            text = { Text("$AI_PRIVACY_TEXT\n\n$AI_BILLING_TEXT", modifier = Modifier.testTag("ai_consent_text")) },
            confirmButton = { TextButton(onClick = { showConsent = false; viewModel.acceptConsentAndEnable() }, modifier = Modifier.testTag("ai_consent_accept")) { Text("I understand, turn on") } },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Not now") } },
        )
    }
    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { confirmDeleteKey = false },
            title = { Text("Delete API key?") },
            text = { Text("AI planning will stop working until you add a key again. Revoke the key in the Anthropic Console too if you no longer use it.") },
            confirmButton = { TextButton(onClick = { confirmDeleteKey = false; viewModel.deleteKey() }) { Text("Delete key") } },
            dismissButton = { TextButton(onClick = { confirmDeleteKey = false }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete AI settings?") },
            text = { Text("This removes the API key and all AI settings from this device. Your tasks stay as they are.") },
            confirmButton = { TextButton(onClick = { confirmDeleteAll = false; viewModel.deleteAllAiData() }, modifier = Modifier.testTag("ai_delete_all_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SentBlock(title: String, lines: List<String>) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = c.ink)
        lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(top = 4.dp)) }
    }
}
