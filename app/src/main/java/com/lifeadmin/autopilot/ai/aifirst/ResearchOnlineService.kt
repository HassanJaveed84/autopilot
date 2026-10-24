package com.lifeadmin.autopilot.ai.aifirst

import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.context.ContextItem
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchCallProvider
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The one research turn the app may have in flight, observable from any screen. */
sealed interface AfRunState {
    data object Idle : AfRunState
    data class Running(val threadId: String, val turnId: String, val taskId: String?, val title: String, val config: AfConfig, val startedAtMs: Long, val progress: AfProgress, val stopRequested: Boolean = false) : AfRunState
    data class Finished(val threadId: String, val turnId: String, val status: AfStatus) : AfRunState
}

sealed interface AskResult {
    data class Ready(val threadId: String, val turn: AfTurn) : AskResult
    data class Rejected(val error: ResearchError) : AskResult
}

sealed interface StartResult {
    data class Started(val threadId: String, val turnId: String) : StartResult
    data class Rejected(val error: ResearchError) : StartResult
}

/**
 * Research Online orchestration in app scope, so a run survives navigation. Asking creates the turn
 * and runs the small clarification check; the authorization card is built from the stored turn and
 * the person's choices; only [start] with that exact authorization sends anything for research. One
 * turn at a time; every call ledgered; tasks change only through the Proposal Review.
 */
class ResearchOnlineService(
    private val settings: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val planning: AiPlanningService,
    provider: ResearchCallProvider,
    private val tasks: TaskRepository,
    private val store: AfThreadRepository,
    private val legacy: InvestigationRepository,
    private val ledger: UsageLedger,
    private val proposalSets: ProposalSetRepository,
    private val applier: ProposalApplier,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val network: NetworkStatus = NetworkStatus.UNKNOWN,
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    val engine = AiFirstResearch(provider, ledger, store, clock, ids)
    private val clarifier = AfClarifier(provider, ledger, clock, ids)
    private val suggester = AfTaskSuggester(provider, ledger, clock, ids)

    private val _state = MutableStateFlow<AfRunState>(AfRunState.Idle)
    val state: StateFlow<AfRunState> = _state.asStateFlow()
    private var job: Job? = null
    private val startLock = Mutex()
    private val suggestLock = Mutex()

    // ------------------------------------------------------------------ gating, defaults, context

    suspend fun gate(s: AppSettings? = null): ResearchGate {
        val st = s ?: settings.current()
        return ResearchGate(planning.availability(st), st.researchExplainerVersion >= ResearchConfig.EXPLAINER_VERSION, requestsToday(st), network.isOnline(), cap = Research2Config.MAX_SESSIONS_PER_DAY)
    }

    suspend fun acceptExplainer() = settings.update { it.copy(researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION) }

    suspend fun requestsToday(s: AppSettings? = null): Int {
        val st = s ?: settings.current()
        return if (st.researchUsageDay == today()) st.researchUsageCount else 0
    }

    /** The card's starting configuration: saved defaults, else Sonnet, 5 searches, a $1 limit. */
    fun defaults(s: AppSettings): AfConfig {
        val model = s.researchStandardModel.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        return AfConfig(
            model = model,
            searchBudget = s.researchDefaultSearches.takeIf { it > 0 }?.coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES) ?: DEFAULT_SEARCHES,
            spendLimitCents = s.researchSpendLimitCents.takeIf { it > 0 } ?: DEFAULT_LIMIT_CENTS,
            effort = s.researchEffort.takeIf { it.isNotBlank() && EffortSupport.supports(model) && it in EffortSupport.LEVELS },
        )
    }

    fun localeLine(): String {
        val date = clock.now().atZone(zoneProvider.current()).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH))
        return "$date · ${locale().getDisplayName(Locale.ENGLISH)}"
    }

    /**
     * The context the card offers: for a task-linked thread the task's title, notes and steps, each with
     * its own switch; for any thread the date line. Nothing else exists as an input, so nothing else can be sent.
     */
    suspend fun contextItems(taskId: String?, scope: ResearchScope = ResearchScope()): List<ContextItem> = buildList {
        val details = taskId?.let { tasks.observeDetails(it).first() }
        if (details != null) {
            add(ContextItem("title", "Task title", details.task.title, included = scope.shareTitle))
            details.task.notes?.takeIf { it.isNotBlank() }?.let { add(ContextItem("notes", "Task notes", it, included = scope.shareNotes)) }
            if (details.steps.isNotEmpty()) add(ContextItem("steps", "Current steps", details.steps.joinToString("; ") { it.title }, included = scope.shareSteps))
        }
        add(ContextItem("today", "Today's date and language", localeLine(), included = scope.shareLocale))
    }

    // ------------------------------------------------------------------ asking and clarifying

    /**
     * Saves the question as a new turn (a new thread when [threadId] is null), then runs the clarification
     * check when AI is set up and the phone is online. No web search; the check is ledgered.
     */
    suspend fun ask(question: String, threadId: String?, taskId: String?): AskResult {
        val q = question.trim()
        if (q.isEmpty()) return AskResult.Rejected(ResearchError.EmptyQuestion)
        if (q.length > Research2Config.MAX_REQUEST_CHARS) return AskResult.Rejected(ResearchError.QuestionTooLong)
        val now = clock.now()
        val existing = threadId?.let { id ->
            if (store.isLegacy(id)) return AskResult.Rejected(ResearchError.Unexpected("saved with the earlier research format; start a new research to ask more"))
            store.get(id) ?: return AskResult.Rejected(ResearchError.Unexpected("research missing"))
        }
        if (existing?.running == true) return AskResult.Rejected(ResearchError.Busy)
        val id = existing?.id ?: ids.newId()
        // A follow-up replaces a question that was never started.
        existing?.turns?.filter { it.status.pending }?.forEach { store.saveTurn(id, it.copy(status = AfStatus.DECLINED, note = "Replaced by a newer question; nothing was sent.")) }
        var turn = AfTurn(ids.newId(), (existing?.turns?.maxOfOrNull { it.index } ?: -1) + 1, q, now.toEpochMilli(), AfStatus.READY)
        // Saved before the check is sent, so a question is never lost.
        if (existing == null) store.create(id, taskId, q.take(80), q, turn, now) else store.saveTurn(id, turn)
        val s = settings.current()
        val key = keyStore.get()
        turn = if (planning.availability(s) is AiAvailability.Ready && key != null && network.isOnline() != false) {
            val earlier = existing?.turns.orEmpty().filter { it.status.ran }.sortedBy { it.index }.map { it.authorization?.question ?: it.question }
            val c = clarifier.check(q, earlier, key, id, turn.id, existing?.taskId ?: taskId)
            turn.copy(clarification = c, status = if (c.waitingForAnswer) AfStatus.CLARIFYING else AfStatus.READY)
        } else {
            turn.copy(clarification = AfClarification(checked = false, note = "The question wasn't checked for ambiguity (AI isn't set up or you're offline)."))
        }
        store.saveTurn(id, turn)
        return AskResult.Ready(id, turn)
    }

    suspend fun answerClarification(threadId: String, turnId: String, answer: String): AfTurn? = editPending(threadId, turnId) { t ->
        val a = answer.trim().take(200)
        val c = t.clarification ?: return@editPending t
        if (a.isEmpty()) t else t.copy(clarification = c.copy(answer = a), status = AfStatus.READY)
    }

    suspend fun skipClarification(threadId: String, turnId: String): AfTurn? = editPending(threadId, turnId) { t ->
        t.copy(clarification = t.clarification?.copy(skipped = true), status = AfStatus.READY)
    }

    /** Nothing paid happened; the turn is kept as declined so the thread shows the decision. */
    suspend fun decline(threadId: String, turnId: String): AfTurn? = editPending(threadId, turnId) { it.copy(status = AfStatus.DECLINED, note = "Not started. Nothing was sent for research.") }

    private suspend fun editPending(threadId: String, turnId: String, edit: (AfTurn) -> AfTurn): AfTurn? {
        val t = store.get(threadId)?.turns?.firstOrNull { it.id == turnId } ?: return null
        if (!t.status.pending) return t
        return edit(t).also { store.saveTurn(threadId, it) }
    }

    // ------------------------------------------------------------------ authorize and run

    /** The card for the stored pending turn and the person's choices. Pure; sends nothing. */
    suspend fun preview(threadId: String, turnId: String, context: List<ContextItem>, config: AfConfig): AfAuthorization? {
        val thread = store.get(threadId) ?: return null
        val turn = thread.turns.firstOrNull { it.id == turnId && it.status == AfStatus.READY } ?: return null
        return runCatching { engine.authorize(thread, turn.question, clarified(turn), context.filter { it.included }.map { AfContextLine(it.id, it.label, it.value) }, config) }.getOrNull()
    }

    private fun clarified(t: AfTurn): AfClarified? = t.clarification?.let { c -> if (c.question != null && c.answer != null) AfClarified(c.question, c.answer) else null }

    /**
     * Starts the paid turn from exactly [auth], the card the person approved. Refuses without the gate or
     * a key, over the daily cap, while another turn runs, or when [auth] is not what the stored turn and the
     * currently offered context produce (a changed task, an edited history, a forged context line).
     */
    suspend fun start(threadId: String, turnId: String, auth: AfAuthorization): StartResult = startLock.withLock {
        if (_state.value is AfRunState.Running) return StartResult.Rejected(ResearchError.Busy)
        val thread = store.get(threadId) ?: return StartResult.Rejected(ResearchError.Unexpected("research missing"))
        val turn = thread.turns.firstOrNull { it.id == turnId } ?: return StartResult.Rejected(ResearchError.Unexpected("question missing"))
        if (turn.status != AfStatus.READY) return StartResult.Rejected(if (turn.status == AfStatus.CLARIFYING) ResearchError.Malformed("the question still needs your answer") else ResearchError.Busy)
        if (auth.question != turn.question || auth.clarified != clarified(turn)) return StartResult.Rejected(ResearchError.Malformed("the approval is for a different question"))
        // Compared the way the card builds them (trimmed): any line that isn't on offer right now is refused.
        val offered = contextItems(thread.taskId, ResearchScope(true, true, true, true)).map { AfContextLine(it.id, it.label, it.value.trim()) }
        if (!offered.containsAll(auth.context)) return StartResult.Rejected(ResearchError.Malformed("the task changed after you approved; check the card again"))
        engine.mismatch(thread, auth)?.let { return StartResult.Rejected(ResearchError.Malformed(it)) }
        val gate = gate()
        gate.blocker?.let { return StartResult.Rejected(it) }
        val key = keyStore.get() ?: return StartResult.Rejected(ResearchError.NoApiKey)
        if (!reserve()) return StartResult.Rejected(ResearchError.DailyLimit)
        val running = turn.copy(authorization = auth, status = AfStatus.RUNNING, startedAtMs = clock.now().toEpochMilli())
        // The approval is on record before anything leaves the device.
        store.saveTurn(threadId, running)
        _state.value = AfRunState.Running(threadId, turnId, thread.taskId, thread.title, auth.config, clock.now().toEpochMilli(), AfProgress(0, 0, 0, false, callInFlight = false))
        job = scope.launch {
            var status = AfStatus.FAILED
            try {
                status = engine.run(threadId, running, key, thread.taskId) { p -> _state.update { s -> if (s is AfRunState.Running && s.turnId == turnId) s.copy(progress = p) else s } }.status
            } catch (e: CancellationException) {
                status = AfStatus.STOPPED
            } catch (e: Exception) {
                // An unexpected crash still leaves a readable turn: what the last checkpoint held, marked failed.
                withContext(NonCancellable) {
                    store.get(threadId)?.turns?.firstOrNull { it.id == turnId }?.let { t ->
                        store.saveTurn(threadId, t.copy(status = AfStatus.FAILED, costIsFloor = true, note = "Something went wrong in the app (${e.javaClass.simpleName}). What was saved before it is kept.", finishedAtMs = clock.now().toEpochMilli()))
                    }
                }
            } finally {
                withContext(NonCancellable) { _state.value = AfRunState.Finished(threadId, turnId, status) }
            }
        }
        StartResult.Started(threadId, turnId)
    }

    /** Stops the running turn; whatever already arrived is kept and the stop is ledgered. */
    fun stop(turnId: String) {
        val s = _state.value as? AfRunState.Running ?: return
        if (s.turnId != turnId) return
        _state.value = s.copy(stopRequested = true)
        job?.cancel(CancellationException("stopped by user"))
    }

    fun dismiss(turnId: String) = _state.update { s -> if (s is AfRunState.Finished && s.turnId == turnId) AfRunState.Idle else s }

    private suspend fun cancelActive(threadId: String? = null, taskId: String? = null) {
        val running = _state.value as? AfRunState.Running ?: return
        if (threadId != null && running.threadId != threadId) return
        if (taskId != null && running.taskId != taskId) return
        job?.let { it.cancel(CancellationException("research deleted")); it.join() }
    }

    /**
     * App start after a process death: a turn still marked running can never finish. It becomes
     * Interrupted with everything its last checkpoint held; the in-flight call's usage is unknown and
     * shown as such. Nothing is re-sent; continuing is a new question with its own approval.
     */
    suspend fun recoverInterrupted(): Int {
        if (_state.value is AfRunState.Running) return 0
        ledger.settleInterrupted()
        var n = 0
        for (t in store.running()) for (turn in t.turns.filter { it.status == AfStatus.RUNNING }) {
            store.saveTurn(t.id, turn.copy(status = AfStatus.INTERRUPTED, costIsFloor = true, finishedAtMs = clock.now().toEpochMilli(),
                note = "Interrupted: the app closed while this was running. " + (if (turn.hasAnswer) "What arrived before is kept. " else "Nothing had arrived yet. ") + "A call may have been in flight; its cost is unknown."))
            n++
        }
        n += legacy.settleRunning(clock.now())
        return n
    }

    // ------------------------------------------------------------------ task suggestions (explicit, reviewed)

    /** The suggestion set already made for this turn, if any. */
    suspend fun existingSuggestions(threadId: String, turnId: String): ProposalSet? = proposalSets.get(AfTaskSuggester.setId(threadId, turnId))

    /** What [suggest] would send, for the confirmation sheet; null when the turn can't have suggestions. */
    suspend fun suggestionPayload(threadId: String, turnId: String): String? {
        val thread = store.get(threadId) ?: return null
        val turn = thread.turns.firstOrNull { it.id == turnId }?.takeIf { it.ran && it.hasAnswer } ?: return null
        val snapshot = thread.taskId?.let { applier.snapshot(it) } ?: return null
        return AfTaskSuggester.user(turn, snapshot)
    }

    /** One small no-search call; the result is a proposal set that changes nothing until applied in the review. */
    suspend fun suggest(threadId: String, turnId: String): SuggestResult = suggestLock.withLock {
        existingSuggestions(threadId, turnId)?.let { return SuggestResult.Ready(it) }
        val thread = store.get(threadId) ?: return SuggestResult.Failed("This research no longer exists.")
        val taskId = thread.taskId ?: return SuggestResult.Failed("Link this research to a task first.")
        val turn = thread.turns.firstOrNull { it.id == turnId }?.takeIf { it.ran && it.hasAnswer } ?: return SuggestResult.Failed("This turn has no answer to suggest from.")
        if (planning.availability() !is AiAvailability.Ready) return SuggestResult.Failed("AI isn't set up. Turn it on in Settings → Plan with AI.")
        if (network.isOnline() == false) return SuggestResult.Failed(ResearchError.Offline.message)
        val key = keyStore.get() ?: return SuggestResult.Failed(ResearchError.NoApiKey.message)
        val snapshot = applier.snapshot(taskId) ?: return SuggestResult.Failed("The task no longer exists.")
        val r = suggester.suggest(threadId, turn, snapshot, taskId, key)
        if (r is SuggestResult.Ready) proposalSets.save(r.set)
        r
    }

    // ------------------------------------------------------------------ association and deletion

    suspend fun associate(threadId: String, taskId: String?, note: String) = store.associate(threadId, taskId, note, clock.now())
    suspend fun delete(threadId: String) { cancelActive(threadId = threadId); store.delete(threadId) }
    suspend fun deleteForTask(taskId: String) { cancelActive(taskId = taskId); store.deleteForTask(taskId) }
    suspend fun deleteAll() { cancelActive(); store.deleteAll() }

    private suspend fun reserve(): Boolean {
        var allowed = false
        val day = today()
        settings.update { s ->
            val used = if (s.researchUsageDay == day) s.researchUsageCount else 0
            if (used >= Research2Config.MAX_SESSIONS_PER_DAY) s else { allowed = true; s.copy(researchUsageDay = day, researchUsageCount = used + 1) }
        }
        return allowed
    }

    private fun today(): String = clock.now().atZone(zoneProvider.current()).toLocalDate().toString()

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_SEARCHES = 5
        const val DEFAULT_LIMIT_CENTS = 100
        val LIMIT_CHOICES_CENTS = listOf(25, 50, 100, 200, 500)
        val MODELS = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
    }
}

/**
 * Reasoning effort, sent as the provider's `output_config.effort` only for models the app lists as
 * supporting it. The list is the app's reading of the provider's documentation, not a live check.
 */
object EffortSupport {
    val LEVELS = listOf("low", "medium", "high")
    private val SUPPORTED = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1")

    fun supports(model: String): Boolean = model.trim() in SUPPORTED
    fun label(effort: String?): String = when (effort) { null, "" -> "Model default"; else -> effort.replaceFirstChar { it.uppercase() } }
}
