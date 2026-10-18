package com.lifeadmin.autopilot.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig

/** What leaves the device, shown before the first request and on this screen. */
const val AI_PRIVACY_TEXT =
    "When you tap “Create plan” or “Help me move this forward”, the text you typed is sent to Anthropic over an encrypted " +
        "connection, together with the current date, time, time zone, your quiet hours and default reminder times. For " +
        "“Help me move this forward”, the selected task's title, notes, checklist, status, deadline and reminder summary " +
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

@Composable
fun AiSettingsScreen(viewModel: AiSettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showConsent by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var editingKey by remember { mutableStateOf(false) }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI planning") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("ai_settings_list"), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Section {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = s.enabled, role = Role.Switch,
                            onValueChange = { on -> if (on && s.consentAt == null) showConsent = true else viewModel.setEnabled(on) },
                        ).testTag("ai_enabled_row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use AI planning", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Optional. Turns a sentence like “book the train, remind me at 9 and 6 until done” into a draft task you review before anything is saved. Off by default; every other feature works without it.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = null, modifier = Modifier.testTag("ai_enabled_switch"))
                    }
                }
            }
            item { InfoCard("What is sent to Anthropic", AI_PRIVACY_TEXT, "ai_privacy") }
            item { InfoCard("Billing", AI_BILLING_TEXT, "ai_billing") }
            item {
                Section {
                    Header("Anthropic API key")
                    Text(
                        if (s.hasKey) "Key saved on this device" + (s.keyHint?.let { " (ends …$it)" } ?: "") else "No key set up",
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ai_key_status"),
                    )
                    if (!s.hasKey || editingKey) {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it.take(400); keyError = null },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_key_input"),
                            label = { Text(if (s.hasKey) "New API key" else "API key") },
                            placeholder = { Text("sk-ant-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            isError = keyError != null,
                            supportingText = { Text(keyError ?: "Create one at console.anthropic.com → API keys.") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button(
                                onClick = { viewModel.saveKey(keyInput) { err -> keyError = err; if (err == null) { keyInput = ""; editingKey = false } } },
                                enabled = keyInput.isNotBlank(),
                                modifier = Modifier.testTag("ai_key_save"),
                            ) { Text(if (s.hasKey) "Replace key" else "Save key") }
                            if (editingKey) TextButton(onClick = { editingKey = false; keyInput = ""; keyError = null }) { Text("Cancel") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = viewModel::testConnection, enabled = !s.testing, modifier = Modifier.testTag("ai_test_connection")) { Text("Test connection") }
                            OutlinedButton(onClick = { editingKey = true }, modifier = Modifier.testTag("ai_key_replace")) { Text("Replace") }
                            TextButton(onClick = { confirmDeleteKey = true }, modifier = Modifier.testTag("ai_key_delete")) { Text("Delete") }
                        }
                        if (s.testing) {
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                                Text("Checking with Anthropic…")
                            }
                        }
                        s.testResult?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodyMedium,
                                color = if (s.testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp).testTag("ai_test_result"),
                            )
                        }
                    }
                    Text(AI_KEY_SECURITY_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                Section {
                    Header("Model")
                    Column(Modifier.selectableGroup()) {
                        AiConfig.MODELS.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = s.model == m.id, role = Role.RadioButton, onClick = { viewModel.setModel(m.id) })
                                    .padding(vertical = 4.dp).testTag("ai_model_${m.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.model == m.id, onClick = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Text(
                        "Requests today: ${s.requestsToday} of ${AiConfig.MAX_REQUESTS_PER_DAY} · max ${AiConfig.MAX_OUTPUT_TOKENS} output tokens per request",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                Section {
                    Header("Remove")
                    Text("Deletes the API key, privacy consent, model choice and usage counter from this device and turns AI planning off. Tasks are not affected.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.padding(top = 8.dp).testTag("ai_delete_all")) { Text("Delete AI settings") }
                }
            }
        }
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
private fun Section(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
}

@Composable
private fun InfoCard(title: String, body: String, tag: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
