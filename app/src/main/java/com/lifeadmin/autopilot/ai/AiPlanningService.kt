package com.lifeadmin.autopilot.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.label
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Online check seam; null = unknown (then the request is attempted). */
fun interface NetworkStatus {
    fun isOnline(): Boolean?

    companion object {
        val UNKNOWN = NetworkStatus { null }
    }
}

class AndroidNetworkStatus(private val context: Context) : NetworkStatus {
    override fun isOnline(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrNull()
}

sealed interface AiAvailability {
    /** The user hasn't turned AI planning on (the default). */
    data object Off : AiAvailability

    /** Turned on but not usable yet. */
    data class NeedsSetup(val needsConsent: Boolean, val needsKey: Boolean) : AiAvailability

    data class Ready(val model: String) : AiAvailability
}

sealed interface ProposalOutcome {
    data class Ready(val draft: ProposalDraft, val usage: PlanningUsage?) : ProposalOutcome
    data class Failed(val error: PlanningError) : ProposalOutcome
}

/**
 * Orchestrates optional AI planning: availability, consent, spend caps, minimal context, the
 * provider call and validation. It never writes tasks — that only happens through
 * [TaskRepository.createPlannedTask] / [TaskRepository.applyTaskEdit] after user approval.
 */
class AiPlanningService(
    private val settings: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val provider: TaskPlanningProvider,
    private val repository: TaskRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    /** Local usage ledger (M3A.2); null in tests that don't care. Records every planning call, incl. failures. */
    private val ledger: com.lifeadmin.autopilot.ai.usage.UsageLedger? = null,
    private val network: NetworkStatus = NetworkStatus.UNKNOWN,
) {
    suspend fun availability(s: AppSettings? = null): AiAvailability {
        val st = s ?: settings.current()
        if (!st.aiEnabled) return AiAvailability.Off
        val needsConsent = st.aiConsentAt == null
        val needsKey = !keyStore.hasKey()
        return if (needsConsent || needsKey) AiAvailability.NeedsSetup(needsConsent, needsKey) else AiAvailability.Ready(AiConfig.modelOrDefault(st.aiModel))
    }

    suspend fun proposeNewTask(text: String): ProposalOutcome {
        val clean = text.trim()
        precheck(clean)?.let { return ProposalOutcome.Failed(it) }
        val (key, model) = credentials() ?: return ProposalOutcome.Failed(PlanningError.NoApiKey)
        val st = settings.current()
        val context = context(st)
        val request = PlanningRequest(PlanningMode.CAPTURE, clean, context)
        val result = callProvider(request, key, model) ?: return ProposalOutcome.Failed(PlanningError.DailyLimit)
        return when (result) {
            is PlanningResult.Failure -> ProposalOutcome.Failed(result.error)
            is PlanningResult.Success -> ProposalOutcome.Ready(
                ProposalValidator.validate(
                    ProposalValidator.Input(
                        raw = result.plan, originalText = clean, mode = PlanningMode.CAPTURE, now = context.nowLocal,
                        quietHours = context.quietHours, proposalId = ids.newId(), model = model,
                    ),
                ),
                result.usage,
            )
        }
    }

    /** [text] is optional extra guidance from the user ("I keep putting this off because…"). */
    suspend fun proposeFollowThrough(taskId: String, text: String): ProposalOutcome {
        val clean = text.trim()
        if (clean.length > AiConfig.MAX_INPUT_CHARS) return ProposalOutcome.Failed(PlanningError.InputTooLong)
        gate()?.let { return ProposalOutcome.Failed(it) }
        val details = repository.observeDetails(taskId).first() ?: return ProposalOutcome.Failed(PlanningError.Unexpected("task missing"))
        val (key, model) = credentials() ?: return ProposalOutcome.Failed(PlanningError.NoApiKey)
        val st = settings.current()
        val context = context(st)
        val request = PlanningRequest(PlanningMode.FOLLOW_THROUGH, clean, context, snapshot(details))
        val result = callProvider(request, key, model) ?: return ProposalOutcome.Failed(PlanningError.DailyLimit)
        return when (result) {
            is PlanningResult.Failure -> ProposalOutcome.Failed(result.error)
            is PlanningResult.Success -> ProposalOutcome.Ready(
                ProposalValidator.validate(
                    ProposalValidator.Input(
                        raw = result.plan, originalText = clean, mode = PlanningMode.FOLLOW_THROUGH, now = context.nowLocal,
                        quietHours = context.quietHours, proposalId = ids.newId(), model = model,
                        existingSteps = details.steps.map { it.title }, existingTitle = details.task.title, existingNotes = details.task.notes,
                        targetTaskId = taskId, targetUpdatedAtMillis = details.task.updatedAt.toEpochMilli(),
                    ),
                ),
                result.usage,
            )
        }
    }

    suspend fun testConnection(): PlanningError? {
        val (key, model) = credentials() ?: return PlanningError.NoApiKey
        if (network.isOnline() == false) return PlanningError.Offline
        return provider.testConnection(key, model)
    }

    /** "Delete AI settings": key, consent, model choice and usage counters. Tasks are untouched. */
    suspend fun deleteAllAiData() {
        keyStore.delete()
        (provider as? com.lifeadmin.autopilot.ai.anthropic.AnthropicTaskPlanningProvider)?.reset()
        settings.update { it.copy(aiEnabled = false, aiConsentAt = null, aiModel = "", aiUsageDay = "", aiUsageCount = 0) }
    }

    suspend fun requestsToday(): Int {
        val s = settings.current()
        return if (s.aiUsageDay == today()) s.aiUsageCount else 0
    }

    // ------------------------------------------------------------------ internals

    private suspend fun precheck(text: String): PlanningError? {
        if (text.isEmpty()) return PlanningError.EmptyInput
        if (text.length > AiConfig.MAX_INPUT_CHARS) return PlanningError.InputTooLong
        return gate()
    }

    private suspend fun gate(): PlanningError? = when (val a = availability()) {
        AiAvailability.Off -> PlanningError.Disabled
        is AiAvailability.NeedsSetup -> if (a.needsConsent) PlanningError.NoConsent else PlanningError.NoApiKey
        is AiAvailability.Ready -> if (network.isOnline() == false) PlanningError.Offline else null
    }

    private suspend fun credentials(): Pair<String, String>? {
        val key = keyStore.get() ?: return null
        return key to AiConfig.modelOrDefault(settings.current().aiModel)
    }

    /** Reserves one request against the daily cap; null when the cap is reached. */
    private suspend fun callProvider(request: PlanningRequest, key: String, model: String): PlanningResult? {
        var allowed = false
        val day = today()
        settings.update { s ->
            val used = if (s.aiUsageDay == day) s.aiUsageCount else 0
            if (used >= AiConfig.MAX_REQUESTS_PER_DAY) s else { allowed = true; s.copy(aiUsageDay = day, aiUsageCount = used + 1) }
        }
        if (!allowed) return null
        val record = com.lifeadmin.autopilot.ai.usage.UsageRecord(ids.newId(), com.lifeadmin.autopilot.ai.usage.UsageFeature.PLANNING, model, clock.now(), stage = request.mode.name)
        ledger?.record(record)
        val started = System.nanoTime()
        val result = try {
            provider.plan(request, key, model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            ledger?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { it.record(com.lifeadmin.autopilot.ai.usage.UsageRecord.priced(record, null, com.lifeadmin.autopilot.ai.usage.CallOutcome.STOPPED, null)) } }
            throw e
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        ledger?.record(
            when (result) {
                is PlanningResult.Success -> com.lifeadmin.autopilot.ai.usage.UsageRecord.priced(
                    record, result.usage?.let { com.lifeadmin.autopilot.ai.usage.MeasuredUsage(it.inputTokens, it.outputTokens, it.cacheWriteTokens, 0, it.cacheReadTokens, 0) },
                    com.lifeadmin.autopilot.ai.usage.CallOutcome.COMPLETE, elapsed, result.usage?.messageId,
                )
                is PlanningResult.Failure -> com.lifeadmin.autopilot.ai.usage.UsageRecord.priced(
                    record, null,
                    if (notBilled(result.error)) com.lifeadmin.autopilot.ai.usage.CallOutcome.FAILED_NOT_BILLED else com.lifeadmin.autopilot.ai.usage.CallOutcome.FAILED_UNKNOWN,
                    elapsed,
                )
            },
        )
        return result
    }

    /** Rejected before processing: Anthropic bills nothing. Everything else may have been processed. */
    private fun notBilled(e: PlanningError): Boolean = when (e) {
        PlanningError.Unauthorized, PlanningError.Forbidden, is PlanningError.BadRequest, is PlanningError.RateLimited, PlanningError.Offline -> true
        else -> false
    }

    private fun today(): String = clock.now().atZone(zoneProvider.current()).toLocalDate().toString()

    fun context(s: AppSettings): PlanningContext {
        val zone: ZoneId = zoneProvider.current()
        val now = clock.now().atZone(zone)
        val defaultTimes: List<LocalTime> = ReminderPresets.applyTo(
            ReminderPolicy(id = "", taskId = "", lastEditedAt = Instant.EPOCH), s.defaultPreset, now.toLocalDate(),
        ).times
        return PlanningContext(
            nowLocal = now.toLocalDateTime().withSecond(0).withNano(0),
            zoneId = zone.id,
            utcOffset = now.offset.id.let { if (it == "Z") "+00:00" else it },
            quietHours = s.quietHours.takeIf { it.isEffective }?.let { it.start to it.end },
            defaultReminderTimes = defaultTimes,
        )
    }

    private fun snapshot(d: TaskDetails): TaskSnapshot {
        val now = clock.now()
        val zone = zoneProvider.current()
        val since = now.minus(Duration.ofDays(14))
        return TaskSnapshot(
            title = d.task.title,
            notes = d.task.notes,
            status = d.task.status.label(),
            steps = d.steps.map { it.title to it.isDone },
            deadline = when (d.task.deadlineType) {
                DeadlineType.NONE -> null
                DeadlineType.DATE_ONLY -> d.task.deadlineLocalDate?.toString()
                DeadlineType.EXACT -> d.task.deadlineInstant?.atZone(zone)?.toLocalDateTime()?.toString()
            },
            reminders = PolicyDescriber.describe(d.policy, zone),
            ageDays = Duration.between(d.task.createdAt, now).toDays(),
            snoozesLast14Days = d.events.count { it.type == EventType.SNOOZED && it.at.isAfter(since) },
            waitingFor = d.task.waitingReason,
        )
    }
}
