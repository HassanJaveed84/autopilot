package com.lifeadmin.autopilot.reminders

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ReminderDiagnosticEntity
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Write side of the delivery-lifecycle ledger. Failures never break reminder delivery. */
interface DiagnosticsRecorder {
    suspend fun record(entry: DiagnosticEntry)

    object None : DiagnosticsRecorder {
        override suspend fun record(entry: DiagnosticEntry) = Unit
    }
}

class RoomDiagnosticsLog(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val ids: IdGenerator,
) : DiagnosticsRecorder {
    private val dao = db.diagnosticDao()
    private var writesSincePrune = 0

    fun newId(): String = ids.newId()
    fun now(): Instant = clock.now()

    override suspend fun record(entry: DiagnosticEntry) {
        runCatching {
            dao.insert(entry.toEntity())
            if (++writesSincePrune >= PRUNE_EVERY) {
                writesSincePrune = 0
                prune()
            }
        }
    }

    /** Convenience builder: fills id and timestamp. */
    suspend fun record(type: DiagnosticType, at: Instant = clock.now(), build: DiagnosticEntry.() -> DiagnosticEntry = { this }) {
        record(DiagnosticEntry(id = ids.newId(), at = at, type = type).build())
    }

    suspend fun recent(limit: Int = 200): List<DiagnosticEntry> = dao.recent(limit).map { it.toDomain() }
    fun observeRecent(limit: Int = 50): Flow<List<DiagnosticEntry>> = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    suspend fun forOccurrence(occurrenceId: String): List<DiagnosticEntry> = dao.forOccurrence(occurrenceId).map { it.toDomain() }
    suspend fun recentOfTypes(types: Set<DiagnosticType>, limit: Int = 50): List<DiagnosticEntry> =
        dao.recentOfTypes(types.map { it.name }, limit).map { it.toDomain() }

    suspend fun prune(maxAge: Duration = MAX_AGE, keep: Int = MAX_ROWS) {
        dao.deleteBefore(clock.now().minus(maxAge))
        dao.trimTo(keep)
    }

    /**
     * Privacy-safe text export: ids are shortened, no task content is ever stored here.
     * Times are shown in [zone] with offset so planned vs actual can be compared.
     */
    suspend fun exportText(zone: ZoneId, limit: Int = 300): String = buildString {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX").withZone(zone)
        appendLine("# delivery lifecycle (newest first, zone=${zone.id}); times are actual wall-clock")
        for (e in recent(limit)) {
            append(fmt.format(e.at)).append(' ').append(e.type.name)
            e.occurrenceId?.let { append(" occ=").append(it.take(8)) }
            e.taskId?.let { append(" task=").append(it.take(8)) }
            e.plannedAt?.let { append(" planned=").append(fmt.format(it)) }
            e.triggerAt?.let { append(" trigger=").append(fmt.format(it)) }
            e.lateness?.let { append(" late=").append(formatLateness(it)) }
            e.exact?.let { append(" mode=").append(if (it) "exact" else "approximate") }
            e.source?.let { append(" via=").append(it) }
            e.outcome?.let { append(" outcome=").append(it) }
            e.detail?.let { append(" [").append(it).append(']') }
            appendLine()
        }
    }

    companion object {
        const val MAX_ROWS = 3_000
        val MAX_AGE: Duration = Duration.ofDays(45)
        private const val PRUNE_EVERY = 200

        fun formatLateness(d: Duration): String {
            val neg = d.isNegative
            val s = d.abs().seconds
            val text = when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m%02ds".format(s % 60)
                else -> "${s / 3600}h%02dm".format((s % 3600) / 60)
            }
            return if (neg) "-$text" else "+$text"
        }
    }
}

internal fun DiagnosticEntry.toEntity() = ReminderDiagnosticEntity(
    id = id, at = at, type = type.name, occurrenceId = occurrenceId, taskId = taskId, plannedAt = plannedAt,
    triggerAt = triggerAt, source = source?.take(60), exact = exact, latenessMs = lateness?.toMillis(),
    outcome = outcome?.take(60), detail = detail?.take(400),
)

internal fun ReminderDiagnosticEntity.toDomain() = DiagnosticEntry(
    id = id, at = at, type = runCatching { DiagnosticType.valueOf(type) }.getOrDefault(DiagnosticType.RECONCILE),
    occurrenceId = occurrenceId, taskId = taskId, plannedAt = plannedAt, triggerAt = triggerAt, source = source,
    exact = exact, lateness = latenessMs?.let(Duration::ofMillis), outcome = outcome, detail = detail,
)
