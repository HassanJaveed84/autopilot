package com.lifeadmin.autopilot.ui.handoff

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.ProposalValidator
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.share.applink.HandoffDestination
import com.lifeadmin.autopilot.share.applink.HandoffImporter
import com.lifeadmin.autopilot.share.applink.HandoffPrepared
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.ImportLinkResult
import com.lifeadmin.autopilot.share.applink.ImportRefusal
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ImportStage {
    /** Opened without a link (for example from Recents after the link was handled). */
    EMPTY,
    WORKING,
    REFUSED,

    /** Pick the destination: the reference didn't resolve, or the user tapped Change. */
    CHOOSE,
    REVIEW,
    NOTHING_NEW,

    /** Something unexpected went wrong; nothing was changed. */
    FAILED,
}

data class ImportUi(
    val stage: ImportStage,
    val handoff: LinkHandoff? = null,
    val refusal: ImportRefusal? = null,
    /** Why the destination is being chosen; null when the user asked to change it. */
    val why: HandoffDestination.Why? = null,
    val taskId: String? = null,
    val setId: String? = null,
    /** The set came from an earlier tap of the same link. */
    val existed: Boolean = false,
    /** ChatGPT's title for the task isn't the task's title (shown on the destination card). */
    val titleDiffers: Boolean = false,
    /** CHOOSE was reached from the review; Back returns to it. */
    val changing: Boolean = false,
    /** Package that opened the link, as Android reports it. */
    val openedBy: String? = null,
)

/**
 * The ChatGPT import screen's state. It holds the validated handoff fields (never the raw URL),
 * the chosen task and the pending set's id, all in [SavedStateHandle] so rotation and process death
 * come back to the same place. Every step after validation is idempotent (finding the set again by
 * its id), so a restored screen simply redoes it. It never writes a task: the review it opens does,
 * through ProposalApplier, when the user applies. A newer link cancels the work of the one it
 * replaces; that cancellation is rethrown, never shown as a failure, so the replaced work can't
 * overwrite the newer link's screen.
 */
class HandoffImportViewModel(
    private val saved: SavedStateHandle,
    private val parser: ImportLinkParser,
    private val importer: HandoffImporter,
    summaries: Flow<List<TaskSummary>>,
) : ViewModel() {
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<ImportUi> = _state
    private var work: Job? = null

    private val _query = MutableStateFlow(saved.get<String>(KEY_QUERY).orEmpty())
    val query: StateFlow<String> = _query

    /** Open tasks for the picker: an exact title match first (still needing a tap), then the most recently changed. */
    val candidates: StateFlow<List<TaskSummary>> = combine(summaries, _query, _state) { all, q, s ->
        val wanted = s.handoff?.task?.let(::key)
        all.asSequence()
            .filter { !it.task.status.isTerminal && it.task.archivedAt == null }
            .filter { q.isBlank() || it.task.title.contains(q.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<TaskSummary> { wanted != null && key(it.task.title) == wanted }.thenByDescending { it.task.updatedAt })
            .take(MAX_CANDIDATES)
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Process death mid-step: redo it. Resolution and preparation find what they already saved.
        val s = _state.value
        if (s.stage == ImportStage.WORKING && s.handoff != null) {
            if (s.taskId != null) prepare(s.handoff, s.taskId, s.titleDiffers) else resolve(s.handoff)
        }
    }

    fun onLink(url: String?, openedBy: String?) {
        work?.cancel()
        _query.value = ""
        saved[KEY_QUERY] = ""
        when (val r = url?.let(parser::parse)) {
            null -> publish(ImportUi(ImportStage.EMPTY, openedBy = openedBy))
            is ImportLinkResult.Refused -> publish(ImportUi(ImportStage.REFUSED, refusal = r.reason, openedBy = openedBy))
            is ImportLinkResult.Valid -> {
                publish(ImportUi(ImportStage.WORKING, handoff = r.handoff, openedBy = openedBy))
                resolve(r.handoff)
            }
        }
    }

    fun choose(taskId: String) {
        val s = _state.value
        val handoff = s.handoff ?: return
        if (s.changing && taskId == s.taskId) return cancelChange()
        val previous = s.setId.takeIf { s.changing && !s.existed }
        publish(s.copy(stage = ImportStage.WORKING, taskId = taskId, setId = null, existed = false, titleDiffers = false, changing = false, why = null))
        work = viewModelScope.launch {
            try {
                previous?.let { importer.discardIfUntouched(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leaving the earlier pending set behind is harmless: it stays reviewable on its task.
            }
            prepareNow(handoff, taskId, titleDiffers = false)
        }
    }

    fun createTask() {
        val s = _state.value
        val handoff = s.handoff ?: return
        val previous = s.setId.takeIf { s.changing && !s.existed }
        publish(s.copy(stage = ImportStage.WORKING, setId = null, changing = false, why = null))
        work = viewModelScope.launch {
            val taskId = try {
                previous?.let { importer.discardIfUntouched(it) }
                importer.createTask(handoff)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                publish(_state.value.copy(stage = ImportStage.FAILED))
                return@launch
            }
            publish(_state.value.copy(taskId = taskId))
            prepareNow(handoff, taskId, titleDiffers = false)
        }
    }

    fun changeDestination() {
        val s = _state.value
        if (s.stage == ImportStage.REVIEW || s.stage == ImportStage.NOTHING_NEW) publish(s.copy(stage = ImportStage.CHOOSE, changing = true, why = null))
    }

    /** Back from the picker reached through Change: the review (or "already there") as it was. */
    fun cancelChange() {
        val s = _state.value
        if (!s.changing) return
        publish(s.copy(stage = if (s.setId != null) ImportStage.REVIEW else ImportStage.NOTHING_NEW, changing = false))
    }

    fun setQuery(q: String) {
        _query.value = q
        saved[KEY_QUERY] = q
    }

    private fun resolve(handoff: LinkHandoff) {
        work = viewModelScope.launch {
            val destination = try {
                importer.resolve(handoff)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                publish(_state.value.copy(stage = ImportStage.FAILED))
                return@launch
            }
            when (destination) {
                is HandoffDestination.Matched -> {
                    publish(_state.value.copy(taskId = destination.taskId, titleDiffers = destination.titleDiffers))
                    prepareNow(handoff, destination.taskId, destination.titleDiffers)
                }
                is HandoffDestination.Unresolved -> publish(_state.value.copy(stage = ImportStage.CHOOSE, why = destination.why, taskId = null))
            }
        }
    }

    private fun prepare(handoff: LinkHandoff, taskId: String, titleDiffers: Boolean) {
        work = viewModelScope.launch { prepareNow(handoff, taskId, titleDiffers) }
    }

    private suspend fun prepareNow(handoff: LinkHandoff, taskId: String, titleDiffers: Boolean) {
        val result = try {
            importer.prepare(handoff, taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            publish(_state.value.copy(stage = ImportStage.FAILED))
            return
        }
        val s = _state.value
        publish(
            when (result) {
                is HandoffPrepared.Ready -> s.copy(stage = ImportStage.REVIEW, taskId = taskId, setId = result.setId, existed = result.existed, titleDiffers = titleDiffers)
                HandoffPrepared.NothingNew -> s.copy(stage = ImportStage.NOTHING_NEW, taskId = taskId, setId = null, titleDiffers = titleDiffers)
                HandoffPrepared.TaskGone -> s.copy(stage = ImportStage.CHOOSE, why = HandoffDestination.Why.NOT_FOUND, taskId = null, setId = null)
            },
        )
    }

    private fun publish(ui: ImportUi) {
        _state.value = ui
        saved[KEY_STAGE] = ui.stage.name
        val h = ui.handoff
        saved[KEY_TASK] = h?.task
        saved[KEY_REF] = h?.ref
        saved[KEY_DECISIONS] = h?.decisions?.let(::ArrayList)
        saved[KEY_STEPS] = h?.steps?.let(::ArrayList)
        saved[KEY_NOTES] = h?.notes?.let(::ArrayList)
        saved[KEY_REFUSAL] = ui.refusal?.name
        saved[KEY_WHY] = ui.why?.name
        saved[KEY_TARGET] = ui.taskId
        saved[KEY_SET] = ui.setId
        saved[KEY_EXISTED] = ui.existed
        saved[KEY_DIFFERS] = ui.titleDiffers
        saved[KEY_CHANGING] = ui.changing
        saved[KEY_BY] = ui.openedBy
    }

    private fun restore(): ImportUi {
        val stage = saved.get<String>(KEY_STAGE)?.let { s -> ImportStage.entries.firstOrNull { it.name == s } } ?: return ImportUi(ImportStage.EMPTY)
        fun list(key: String) = saved.get<ArrayList<String>>(key).orEmpty().toList()
        val handoff = saved.get<String>(KEY_TASK)?.let { LinkHandoff(it, list(KEY_DECISIONS), list(KEY_STEPS), list(KEY_NOTES), saved.get<String>(KEY_REF)) }
        return ImportUi(
            stage = stage, handoff = handoff,
            refusal = saved.get<String>(KEY_REFUSAL)?.let { r -> ImportRefusal.entries.firstOrNull { it.name == r } },
            why = saved.get<String>(KEY_WHY)?.let { w -> HandoffDestination.Why.entries.firstOrNull { it.name == w } },
            taskId = saved.get<String>(KEY_TARGET), setId = saved.get<String>(KEY_SET),
            existed = saved.get<Boolean>(KEY_EXISTED) ?: false, titleDiffers = saved.get<Boolean>(KEY_DIFFERS) ?: false,
            changing = saved.get<Boolean>(KEY_CHANGING) ?: false, openedBy = saved.get<String>(KEY_BY),
        )
    }

    private fun key(s: String) = ProposalValidator.normalizeKeyPublic(s)

    private companion object {
        const val MAX_CANDIDATES = 50
        const val KEY_STAGE = "handoff_import_stage"
        const val KEY_TASK = "handoff_import_task"
        const val KEY_REF = "handoff_import_ref"
        const val KEY_DECISIONS = "handoff_import_decisions"
        const val KEY_STEPS = "handoff_import_steps"
        const val KEY_NOTES = "handoff_import_notes"
        const val KEY_REFUSAL = "handoff_import_refusal"
        const val KEY_WHY = "handoff_import_why"
        const val KEY_TARGET = "handoff_import_target"
        const val KEY_SET = "handoff_import_set"
        const val KEY_EXISTED = "handoff_import_existed"
        const val KEY_DIFFERS = "handoff_import_differs"
        const val KEY_CHANGING = "handoff_import_changing"
        const val KEY_BY = "handoff_import_by"
        const val KEY_QUERY = "handoff_import_query"
    }
}
