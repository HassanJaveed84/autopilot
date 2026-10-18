package com.lifeadmin.autopilot.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets

/**
 * Quick capture: type a title, then one tap saves to Inbox (no reminder) or saves as actionable
 * with the default reminder preset — satisfying "≤ 2 taps" from the FAB.
 */
@Composable
fun CaptureSheet(
    viewModel: CaptureViewModel,
    onDismiss: () -> Unit,
    onSaved: (taskId: String, withReminder: Boolean) -> Unit,
    onPlanWithAi: (text: String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(Priority.NORMAL) }
    var more by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val preset by viewModel.defaultPreset.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun save(withReminder: Boolean) {
        if (title.isBlank() || saving) return
        saving = true
        viewModel.save(title, notes.ifBlank { null }, priority, withReminder,
            onSaved = { id, r -> saving = false; onSaved(id, r) },
            onError = { saving = false; error = it })
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text("Capture", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(300); error = null },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus).testTag("capture_title"),
                label = { Text("What do you need to follow through on?") },
                singleLine = false,
                maxLines = 3,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save(withReminder = false) }),
            )
            if (more) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(10_000) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("capture_notes"),
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 6,
                )
                Text("Priority", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Priority.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = priority == p,
                            onClick = { priority = p },
                            shape = SegmentedButtonDefaults.itemShape(i, Priority.entries.size),
                        ) { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { more = true }) { Text("Add notes or priority") }
                    // Opt-in, user-triggered; quick capture below stays offline and instant.
                    TextButton(
                        onClick = { onPlanWithAi(listOf(title, notes).filter { it.isNotBlank() }.joinToString("\n")) },
                        modifier = Modifier.testTag("capture_plan_ai"),
                    ) { Text("Plan with AI…") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { save(withReminder = false) },
                    enabled = title.isNotBlank() && !saving,
                    modifier = Modifier.weight(1f).testTag("capture_save_inbox"),
                ) { Text("Save to Inbox") }
                Button(
                    onClick = { save(withReminder = true) },
                    enabled = title.isNotBlank() && !saving,
                    modifier = Modifier.weight(1f).testTag("capture_save_remind"),
                ) { Text("Save & remind") }
            }
            Text(
                "“Save & remind” uses your default: ${ReminderPresets.label(preset)}. You can edit the schedule afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
