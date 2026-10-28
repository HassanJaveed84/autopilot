package com.lifeadmin.autopilot.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.domain.model.StepDetails
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.WebLink
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/** What the step editor holds while the user types: every field as text, links in order (first = main). */
data class StepDraft(val title: String, val description: String = "", val links: List<LinkDraft> = emptyList()) {
    companion object {
        fun of(title: String, description: String?, links: List<StepLink>) = StepDraft(title, description.orEmpty(), links.map { LinkDraft(it.url, it.label.orEmpty()) })

        val saver: Saver<StepDraft, List<String>> = Saver(
            save = { d -> listOf(d.title, d.description) + d.links.flatMap { listOf(it.url, it.label) } },
            restore = { l -> StepDraft(l[0], l[1], l.drop(2).chunked(2).map { LinkDraft(it[0], it.getOrElse(1) { "" }) }) },
        )
    }
}

data class LinkDraft(val url: String = "", val label: String = "")

/** A validated draft: the wording, a description or null, and clean links (tracking parameters removed). */
data class StepEdit(val title: String, val description: String?, val links: List<StepLink>)

/** Problems found on Save, by field; links by their position in the editor. */
data class StepDraftErrors(val title: String? = null, val description: String? = null, val links: Map<Int, String> = emptyMap(), val overall: String? = null) {
    val isEmpty: Boolean get() = title == null && description == null && links.isEmpty() && overall == null
}

object StepDraftValidator {
    /**
     * Rows with an empty address are dropped; a row with a label but no address is an error, so a
     * typed label never disappears silently. The editor accepts `www.example.com` as `https://…`.
     */
    fun validate(d: StepDraft, titleRequired: Boolean = true): Pair<StepEdit?, StepDraftErrors> {
        val title = d.title.trim()
        val titleError = when {
            !titleRequired -> null
            title.isEmpty() -> "Give the step a short name"
            title.length > 300 -> "Step is too long (max 300 characters)"
            else -> null
        }
        val description = runCatching { StepDetails.cleanDescription(d.description) }
        val linkErrors = LinkedHashMap<Int, String>()
        val links = ArrayList<StepLink>()
        d.links.forEachIndexed { i, l ->
            if (l.url.isBlank()) {
                if (l.label.isNotBlank()) linkErrors[i] = "Add the web address for this link"
                return@forEachIndexed
            }
            when (val c = WebLink.check(l.url, allowMissingScheme = true)) {
                is WebLink.Checked.Bad -> linkErrors[i] = c.reason
                is WebLink.Checked.Ok -> {
                    val label = WebLink.cleanLabel(l.label)
                    if (label != null && label.codePointCount(0, label.length) > WebLink.MAX_LABEL) linkErrors[i] = "Keep the label under ${WebLink.MAX_LABEL} characters"
                    else links += StepLink(c.url, label)
                }
            }
        }
        val clean = if (linkErrors.isEmpty()) runCatching { StepDetails.cleanLinks(links) } else null
        val errors = StepDraftErrors(
            title = titleError,
            description = description.exceptionOrNull()?.message,
            links = linkErrors,
            overall = clean?.exceptionOrNull()?.message,
        )
        if (!errors.isEmpty) return null to errors
        return StepEdit(title, description.getOrNull(), clean!!.getOrThrow()) to errors
    }
}

/**
 * The step editor (Rich Steps): wording, an optional plain-text description and labelled links;
 * the first link is the main one, shown on the checklist. Used from a saved step and from Proposal
 * Review, where [titleLocked] keeps the user's own wording when ChatGPT only adds details.
 * [onSave] receives a validated edit; nothing is written from here.
 */
@Composable
fun StepEditorSheet(
    heading: String,
    initial: StepDraft,
    onDismiss: () -> Unit,
    onSave: (StepEdit) -> Unit,
    titleLocked: Boolean = false,
    lockedHint: String? = null,
) {
    val c = AutopilotTheme.colors
    var draft by rememberSaveable(stateSaver = StepDraft.saver) { mutableStateOf(initial) }
    // Errors show after a Save attempt and clear on the next edit; they needn't survive rotation.
    var errors by remember { mutableStateOf<StepDraftErrors?>(null) }
    val e = errors ?: StepDraftErrors()
    fun update(d: StepDraft) { draft = d; if (errors != null) errors = null }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.lineStrong, cursorColor = c.ink, focusedTextColor = c.ink, unfocusedTextColor = c.ink)
    AutopilotSheet(
        title = heading,
        onDismiss = onDismiss,
        closeLabel = "Close without saving",
        modifier = Modifier.testTag("step_editor"),
        footer = {
            ButtonPair(
                modifier = Modifier.padding(top = 12.dp),
                secondary = { m -> SecondaryButton("Cancel", onClick = onDismiss, modifier = m.testTag("step_editor_cancel")) },
                primary = { m ->
                    PrimaryButton("Save", onClick = {
                        val (edit, problems) = StepDraftValidator.validate(draft, titleRequired = !titleLocked)
                        if (edit != null) onSave(edit) else errors = problems
                    }, modifier = m.testTag("step_editor_save"))
                },
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (titleLocked) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Step", style = MaterialTheme.typography.labelLarge, color = c.ink)
                    Text(draft.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.testTag("step_editor_title_locked"))
                    lockedHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
                }
            } else {
                OutlinedTextField(
                    value = draft.title, onValueChange = { update(draft.copy(title = it.take(300))) },
                    label = { Text("Step") }, modifier = Modifier.fillMaxWidth().testTag("step_editor_title"),
                    isError = e.title != null, supportingText = e.title?.let { { Text(it) } },
                    shape = RoundedCornerShape(14.dp), colors = colors, textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
            OutlinedTextField(
                value = draft.description, onValueChange = { update(draft.copy(description = it.take(StepDetails.MAX_DESCRIPTION + 200))) },
                label = { Text("Description (optional)") },
                placeholder = { Text("Sizes, prices to check, what to say…", color = c.ink3) },
                modifier = Modifier.fillMaxWidth().testTag("step_editor_description"),
                minLines = 3, maxLines = 8,
                isError = e.description != null, supportingText = e.description?.let { { Text(it) } },
                shape = RoundedCornerShape(14.dp), colors = colors, textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Links (optional)", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.semantics { heading() })
                if (draft.links.isEmpty()) Text("The first link shows on the checklist, so you can open it in one tap.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                draft.links.forEachIndexed { i, l ->
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp).testTag("step_editor_link_$i"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == 0) "Main link · shown on the checklist" else "Link ${i + 1}", style = MaterialTheme.typography.labelMedium, color = c.ink2, modifier = Modifier.weight(1f))
                            if (i > 0) RoundIconButton(
                                AutopilotIcons.ChevronDown, contentDescription = "Make link ${i + 1} the main link", tint = c.ink2,
                                onClick = { update(draft.copy(links = listOf(l) + draft.links.filterIndexed { k, _ -> k != i })) },
                                modifier = Modifier.rotate(180f).testTag("step_editor_link_main_$i"),
                            )
                            RoundIconButton(
                                AutopilotIcons.Trash, contentDescription = "Remove link ${i + 1}", tint = c.ink2,
                                onClick = { update(draft.copy(links = draft.links.filterIndexed { k, _ -> k != i })) },
                                modifier = Modifier.testTag("step_editor_link_remove_$i"),
                            )
                        }
                        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = l.url, onValueChange = { v -> update(draft.copy(links = draft.links.mapIndexed { k, x -> if (k == i) x.copy(url = v.take(WebLink.MAX_URL + 50)) else x })) },
                                label = { Text("Web address") }, placeholder = { Text("https://…", color = c.ink3) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("step_editor_link_url_$i"),
                                isError = e.links[i] != null, supportingText = e.links[i]?.let { { Text(it) } },
                                shape = RoundedCornerShape(14.dp), colors = colors,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            )
                            OutlinedTextField(
                                value = l.label, onValueChange = { v -> update(draft.copy(links = draft.links.mapIndexed { k, x -> if (k == i) x.copy(label = v.take(WebLink.MAX_LABEL + 20)) else x })) },
                                label = { Text("Label (optional)") }, placeholder = { Text("Uses the website name", color = c.ink3) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("step_editor_link_label_$i"),
                                shape = RoundedCornerShape(14.dp), colors = colors,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            )
                        }
                    }
                }
                e.overall?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                if (draft.links.size < StepDetails.MAX_LINKS) {
                    SecondaryButton(
                        "Add link", onClick = { update(draft.copy(links = draft.links + LinkDraft())) }, icon = AutopilotIcons.Plus, minHeight = 48.dp,
                        modifier = Modifier.testTag("step_editor_add_link"),
                    )
                }
                Text("Links open in your browser only when you tap them. Opening one never ticks the step.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
            }
        }
    }
}
