package com.lifeadmin.autopilot.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.capture.CaptureTiming
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.scheduling.QuietHoursCalculator
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.domain.scheduling.ReminderProjection
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything typed into the capture sheet. Lives in the ViewModel so it survives rotation and leaving the sheet. */
data class CaptureDraft(
    val text: String = "",
    val notes: String = "",
    val priority: Priority = Priority.NORMAL,
    /** The deadline the user picked in the disclosure; it wins over [timing] once [deadlineTouched]. */
    val deadline: DeadlineSpec? = null,
    val deadlineTouched: Boolean = false,
    /** Follow-up switch: on = Save & nudge (actionable), off = Save to Inbox. */
    val nudge: Boolean = true,
    /** Custom schedule chosen in the Follow-up sheet; null = from the words ([timing]) or the default preset. */
    val policy: ReminderPolicy? = null,
    /** What the words say about timing, read offline as the user types. The text itself is never changed. */
    val timing: CaptureTiming.Reading = CaptureTiming.Reading(),
    /** "Ignore" on the timing line: the reading it dismissed. A different reading shows again. */
    val dismissedTiming: CaptureTiming.Reading? = null,
    /** "Every night instead" on a one-off bedtime nudge: makes it a confirmed routine. */
    val makeNightly: Boolean = false,
    /** The user's explicit, per-task permission to ring during the app's quiet hours. Never set automatically. */
    val ringInQuietHours: Boolean = false,
    val detailsOpen: Boolean = false,
    /**
     * Idempotency key for this capture: a retry after a failed save (or a double tap) creates the
     * same task once instead of a duplicate. A fresh draft gets a fresh key.
     */
    val captureId: String = java.util.UUID.randomUUID().toString(),
) {
    val isEmpty: Boolean get() = text.isBlank()

    /** The reading in use: none once dismissed. */
    val activeTiming: CaptureTiming.Reading? get() = timing.takeIf { !it.isEmpty && it != dismissedTiming }

    /** The nudge the words ask for (before "Every night instead"). */
    val inferredNudge: CaptureTiming.Nudge? get() = activeTiming?.nudge
}

/** Where the capture's follow-up comes from, for the "from your words" label. */
enum class FollowUpSource { DEFAULT, WORDS, CUSTOM }

data class CaptureUiState(
    val draft: CaptureDraft = CaptureDraft(),
    val settings: AppSettings = AppSettings(),
    val saving: Boolean = false,
    val error: String? = null,
) {
    /** The nudge from the words with "Every night instead" applied: a routine at the one-off nudge's time. */
    fun inferredNudge(zone: java.time.ZoneId): CaptureTiming.Nudge? = draft.inferredNudge?.let { n ->
        if (draft.makeNightly && n is CaptureTiming.Nudge.Once && n.bedtime) CaptureTiming.Nudge.Daily(listOf(n.at.atZone(zone).toLocalTime()), bedtime = true, phrase = n.phrase) else n
    }

    fun followUpSource(): FollowUpSource = when {
        draft.policy != null -> FollowUpSource.CUSTOM
        draft.inferredNudge != null -> FollowUpSource.WORDS
        else -> FollowUpSource.DEFAULT
    }

    /** Effective follow-up for the "Nudge me …" row: custom, else from the words, else the default preset. */
    fun effectivePolicy(now: java.time.Instant, zone: java.time.ZoneId): ReminderPolicy {
        val base = draft.policy
            ?: inferredNudge(zone)?.let { CaptureTiming.policyFor(it, settings, now, zone) }
            ?: ReminderPresets.applyTo(ReminderPolicy(id = "", taskId = "", lastEditedAt = now), settings.defaultPreset, now.atZone(zone).toLocalDate()).copy(enabled = true)
        return base.copy(ringInQuietHours = draft.ringInQuietHours)
    }

    /** The deadline that will be saved: the user's pick once touched, else the one in the words. */
    fun effectiveDeadline(): DeadlineSpec? = if (draft.deadlineTouched) draft.deadline else draft.activeTiming?.deadline?.spec

    /**
     * True when at least one of the next nudges would fall in quiet hours without the exception
     * (or the exception is already on), so the capture sheet offers the per-task switch.
     */
    fun quietHoursMatter(now: java.time.Instant, zone: java.time.ZoneId): Boolean {
        if (!draft.nudge || !settings.quietHours.isEffective) return false
        if (draft.ringInQuietHours) return true
        // With the exception on, the planner returns the nominal times; check those against quiet hours.
        val quiet = QuietHoursCalculator(settings.quietHours)
        return ReminderProjection.upcomingForNewTask(effectivePolicy(now, zone).copy(ringInQuietHours = true), settings, now, zone, 3)
            .any { quiet.isQuiet(it.plannedAt, zone) }
    }
}

class CaptureViewModel(
    private val actions: TaskActions,
    private val repository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private val draft = MutableStateFlow(CaptureDraft())
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update { it.copy(settings = s) }
                // The bedtime may arrive after the first keystrokes: read the words again with it.
                draft.update { d -> if (d.text.isNotBlank()) withTiming(d) else d }
            }
        }
        viewModelScope.launch { draft.collect { d -> _state.update { it.copy(draft = d) } } }
    }

    fun now() = clock.now()
    fun zone() = zoneProvider.current()

    fun update(transform: (CaptureDraft) -> CaptureDraft) {
        draft.update(transform)
        _state.update { it.copy(error = null) }
    }

    fun setText(text: String) = update { withTiming(it.copy(text = text.take(MAX_INPUT_CHARS))) }

    /** Re-reads the words for timing (offline, deterministic). A changed reading shows again even after "Ignore". */
    private fun withTiming(d: CaptureDraft): CaptureDraft {
        val reading = CaptureTiming.read(d.text, clock.now(), zoneProvider.current(), _state.value.settings.bedtime)
        val nudgeChanged = reading.nudge?.phrase != d.timing.nudge?.phrase
        return d.copy(timing = reading, makeNightly = d.makeNightly && !nudgeChanged)
    }

    /** "Ignore" on the timing line: back to the default follow-up and no deadline from the words. */
    fun ignoreTiming() = update { it.copy(dismissedTiming = it.timing, makeNightly = false) }
    fun setMakeNightly(on: Boolean) = update { it.copy(makeNightly = on) }
    fun setRingInQuietHours(on: Boolean) = update { it.copy(ringInQuietHours = on) }
    fun setNotes(notes: String) = update { it.copy(notes = notes.take(TaskRepository.MAX_NOTES)) }
    fun setPriority(p: Priority) = update { it.copy(priority = p) }
    fun setDeadline(d: DeadlineSpec?) = update { it.copy(deadline = d?.takeIf { s -> s.type != DeadlineType.NONE }, deadlineTouched = true) }
    fun setNudge(on: Boolean) = update { it.copy(nudge = on) }
    fun setPolicy(p: ReminderPolicy?) = update { it.copy(policy = p, nudge = p?.enabled ?: it.nudge, ringInQuietHours = p?.ringInQuietHours ?: it.ringInQuietHours) }
    fun toggleDetails() = update { it.copy(detailsOpen = !it.detailsOpen) }

    /** Pre-fills the field from an example prompt (only when nothing has been typed yet). */
    fun prefill(text: String) = update { if (it.text.isBlank()) withTiming(it.copy(text = text.take(MAX_INPUT_CHARS))) else it }

    /** Title = first line, cut at a word if over the limit; every other character goes to the notes. */
    private fun split(d: CaptureDraft): Pair<String, String?> = CaptureText.split(d.text, d.notes).let { it.title to it.notes }

    /**
     * Commits exactly once per tap (re-entrant taps are ignored while saving) and resets the
     * draft on success so the sheet opens empty next time.
     */
    fun save(onSaved: (taskId: String, withReminder: Boolean, firstNudge: String?) -> Unit) {
        val d = draft.value
        if (d.isEmpty || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        // The state's copy of the draft can trail the latest keystroke by a frame; save what was typed.
        val s = _state.value.copy(draft = d)
        val now = clock.now()
        val zone = zoneProvider.current()
        // The default preset keeps going through TaskActions (so history names the preset); words
        // or a custom choice are saved as the exact schedule the row showed.
        val policy = if (s.followUpSource() == FollowUpSource.DEFAULT && !d.ringInQuietHours) null else s.effectivePolicy(now, zone)
        val deadline = s.effectiveDeadline()
        viewModelScope.launch {
            val (title, notes) = split(d)
            runCatching { actions.capture(title, notes, d.priority, d.nudge, policy, deadline, captureId = d.captureId) }
                .onSuccess { r ->
                    draft.value = CaptureDraft()
                    _state.update { it.copy(saving = false) }
                    val first = r.firstNudge?.let { Format.relativeLower(it.plannedAt, clock.now(), zoneProvider.current()) }
                    onSaved(r.taskId, d.nudge, first)
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }

    companion object {
        /** Longest capture text; the counter under the field shows it. */
        const val MAX_INPUT_CHARS = 2_000
    }
}
