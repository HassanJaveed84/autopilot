package com.lifeadmin.autopilot.ui.components

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/*
 * Rich Steps link pieces shared by the checklist, the step sheet and Proposal Review. Every link
 * shows its destination domain; it opens only on the user's tap, in the browser, and opening it
 * never changes the step (ticking it stays the user's own action).
 */

/** Opens [url] in the browser. Returns false when nothing could open it; the step is never touched. */
fun openWebLink(context: Context, url: String): Boolean {
    @Suppress("UseKtx") // core-ktx is not a dependency of this module
    val uri = android.net.Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return false
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }.isSuccess
}

/** The quiet line under a step title: "Description · 2 more links". Null for a plain step. */
fun stepHint(step: TaskStep, mainLinkShown: Boolean): String? {
    val others = step.links.size - if (mainLinkShown && step.links.isNotEmpty()) 1 else 0
    val parts = listOfNotNull(
        step.description?.takeIf { it.isNotBlank() }?.let { "Description" },
        others.takeIf { it > 0 }?.let { n -> "$n ${if (mainLinkShown) "more " else ""}${if (n == 1) "link" else "links"}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The main link on a checklist row: an ink-outlined pill (ink is the action colour), its own 44dp
 * target (the LinkButton minimum), separate from the box (which ticks) and the title (which opens the step).
 */
@Composable
fun MainLinkPill(link: StepLink, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    // At large text the label gets its own line(s) and the domain goes under it, so neither is cut to a letter.
    val stacked = LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier
            .padding(vertical = 2.dp)
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .border(1.5.dp, c.lineStrong, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Open in browser", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Open ${link.title} on ${link.host}, opens your browser" }
            .padding(start = 11.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AutopilotIcons.OpenExternal, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
        if (stacked) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(link.title, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (link.label != null) Text(link.host, style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            // Both weighted, so a long domain can never squeeze the label away (and vice versa).
            Text(
                link.title, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (link.label != null) Text(
                "· ${link.host}", style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.6f, fill = false),
            )
        }
    }
}

/** The main link in the step sheet: a large ink-filled target with the domain under the label. */
@Composable
fun MainLinkButton(link: StepLink, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.inverse)
            .clickable(role = Role.Button, onClickLabel = "Open in browser", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Open ${link.title} on ${link.host}, opens your browser" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AutopilotIcons.OpenExternal, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(link.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.onInverse)
            Text("${link.host} · opens your browser", style = MaterialTheme.typography.bodySmall, color = c.onInverse.copy(alpha = 0.82f))
        }
    }
}

/** One link in a list: label, domain and an external-link mark; [main] adds the "Main" tag (review). */
@Composable
fun LinkRow(link: StepLink, onOpen: () -> Unit, modifier: Modifier = Modifier, main: Boolean = false) {
    val c = AutopilotTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClickLabel = "Open in browser", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = (if (main) "Main link: " else "") + "${link.title} on ${link.host}, opens your browser" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // The label has the full width; the "Main" tag sits on the domain line.
            Text(link.title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (main) Text("Main", style = MaterialTheme.typography.labelSmall, color = c.ink, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.sunken).padding(horizontal = 8.dp, vertical = 2.dp))
                Text(link.host, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Icon(AutopilotIcons.OpenExternal, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp))
    }
}

/** Links in a hairline group, the way the sheet and the review list them. */
@Composable
fun LinkList(links: List<StepLink>, onOpen: (StepLink) -> Unit, modifier: Modifier = Modifier, markMain: Boolean = false) {
    val c = AutopilotTheme.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).background(c.surface)) {
        links.forEachIndexed { i, l ->
            if (i > 0) GroupDivider()
            LinkRow(l, onOpen = { onOpen(l) }, main = markMain && i == 0)
        }
    }
}
