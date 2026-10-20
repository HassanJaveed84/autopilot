package com.lifeadmin.autopilot.testing

import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.PlanningRequest
import com.lifeadmin.autopilot.ai.PlanningResult
import com.lifeadmin.autopilot.ai.PlanningUsage
import com.lifeadmin.autopilot.ai.RawDeadline
import com.lifeadmin.autopilot.ai.RawPlan
import com.lifeadmin.autopilot.ai.RawPriority
import com.lifeadmin.autopilot.ai.RawReminder
import com.lifeadmin.autopilot.ai.RawWaiting
import com.lifeadmin.autopilot.ai.TaskPlanningProvider
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/** Fake API key for tests — deliberately not in Anthropic's real key format. */
const val FAKE_KEY = "test-key-0000000000000000000000000000"

/**
 * Deterministic provider: answers from a script (text → result) or a fixed queue. Records every
 * request so tests can assert what would have been sent, and how many paid calls were made.
 */
class FakePlanningProvider : TaskPlanningProvider {
    override val id = "fake"
    val requests = CopyOnWriteArrayList<PlanningRequest>()
    val keysSeen = CopyOnWriteArrayList<String>()
    var next: (PlanningRequest) -> PlanningResult = { PlanningResult.Failure(PlanningError.Unexpected("no script")) }
    var connectionError: PlanningError? = null

    /** When set, [plan] suspends until completed (for cancel/in-flight tests). */
    var gate: CompletableDeferred<Unit>? = null

    fun respond(plan: RawPlan) { next = { PlanningResult.Success(plan, PlanningUsage(800, 300, "claude-haiku-5-5")) } }
    fun fail(error: PlanningError) { next = { PlanningResult.Failure(error) } }

    override suspend fun plan(request: PlanningRequest, apiKey: String, model: String): PlanningResult {
        requests += request
        keysSeen += apiKey
        gate?.await()
        return next(request)
    }

    override suspend fun testConnection(apiKey: String, model: String): PlanningError? = connectionError
}

/** Compact builder for model-output fixtures. */
fun rawPlan(
    title: String,
    steps: List<String> = emptyList(),
    nextStep: String = "",
    notes: String = "",
    deadline: RawDeadline = RawDeadline(),
    reminder: RawReminder = RawReminder(),
    waiting: RawWaiting = RawWaiting(),
    priority: RawPriority = RawPriority(),
    assumptions: List<String> = emptyList(),
    questions: List<String> = emptyList(),
    ignored: List<String> = emptyList(),
    advice: String = "",
) = RawPlan(title, notes, steps, nextStep, deadline, reminder, waiting, priority, assumptions, questions, ignored, advice)

fun daily(vararg times: String, phrase: String, provenance: String = "explicit") =
    RawReminder(kind = "daily_times", provenance = provenance, times = times.toList(), requestedPhrase = phrase)

/**
 * Deterministic research provider: answers from a script, records requests and keys, and can be
 * held open with [gate] to test Stop/in-flight behaviour. Never touches the network.
 */
class FakeResearchProvider : com.lifeadmin.autopilot.ai.research.ResearchProvider {
    override val id = "fake-research"
    val requests = CopyOnWriteArrayList<com.lifeadmin.autopilot.ai.research.ResearchRequest>()
    val keysSeen = CopyOnWriteArrayList<String>()
    val modelsSeen = CopyOnWriteArrayList<String>()
    var next: (com.lifeadmin.autopilot.ai.research.ResearchRequest) -> com.lifeadmin.autopilot.ai.research.ResearchResponse =
        { com.lifeadmin.autopilot.ai.research.ResearchResponse.Failure(com.lifeadmin.autopilot.ai.research.ResearchError.Unexpected("no script"), 0) }
    var gate: CompletableDeferred<Unit>? = null
    /** Progress events to emit before answering (after the gate). */
    var progress: List<com.lifeadmin.autopilot.ai.research.ResearchProgress> = emptyList()

    override suspend fun research(
        request: com.lifeadmin.autopilot.ai.research.ResearchRequest,
        apiKey: String,
        model: String,
        onProgress: (com.lifeadmin.autopilot.ai.research.ResearchProgress) -> Unit,
    ): com.lifeadmin.autopilot.ai.research.ResearchResponse {
        requests += request
        keysSeen += apiKey
        modelsSeen += model
        onProgress(com.lifeadmin.autopilot.ai.research.ResearchProgress.Sent)
        progress.forEach(onProgress)
        gate?.await()
        return next(request)
    }
}
