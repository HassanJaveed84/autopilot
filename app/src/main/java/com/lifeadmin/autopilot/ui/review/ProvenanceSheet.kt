package com.lifeadmin.autopilot.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where a piece of task content came from (INT-030–033, OD-9): origin, when it was applied, the
 * supporting findings with status and as-of date, their evidence keys, and a one-tap way back to the
 * investigation. Light on the task; full on inspection.
 */
@Composable
fun ProvenanceSheet(record: ProvenanceRecord, zone: ZoneId, onDismiss: () -> Unit, onOpenInvestigation: ((String) -> Unit)?) {
    val c = AutopilotTheme.colors
    val fmt = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH)
    val title = when (record.source) {
        ProposalSource.RESEARCH -> "From research"
        ProposalSource.PLANNING -> "From Plan with AI"
        ProposalSource.HANDOFF -> "From ChatGPT"
    }
    AutopilotSheet(title = title, onDismiss = onDismiss, modifier = Modifier.testTag("provenance_sheet")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (record.appliedText.isNotBlank()) Text(record.appliedText, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.testTag("provenance_text"))
            Text(
                "Applied ${Instant.ofEpochMilli(record.appliedAtMs).atZone(zone).toLocalDateTime().format(fmt)}" + (record.model.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                    (if (record.editedByUser) " · edited by you since" else ""),
                style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("provenance_meta"),
            )
            record.src?.let { src ->
                if (src.premises.isNotEmpty()) {
                    Text("Rests on", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 14.dp))
                    src.premises.forEach { p -> Text("• ${p.text}", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (src.findings.isNotEmpty()) {
                    Text("Supporting findings", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 14.dp))
                    src.findings.forEachIndexed { i, f ->
                        Column(Modifier.padding(top = 8.dp).testTag("provenance_finding_$i")) {
                            Text(f.text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            Text(listOfNotNull(f.status, f.asOf?.let { "as of $it" }, if (f.reused) "reused from an earlier turn, not re-checked" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                            f.keys.forEach { k -> Text(k, style = MaterialTheme.typography.bodySmall, color = c.ink3) }
                        }
                    }
                }
            }
            record.investigationId?.let { inv ->
                if (onOpenInvestigation != null) LinkButton("Open the research", onClick = { onOpenInvestigation(inv) }, modifier = Modifier.padding(top = 12.dp).testTag("provenance_open"))
            }
        }
    }
}
