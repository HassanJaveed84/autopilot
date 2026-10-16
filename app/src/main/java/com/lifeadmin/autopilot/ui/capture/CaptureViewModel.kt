package com.lifeadmin.autopilot.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CaptureViewModel(
    private val actions: TaskActions,
    settings: SettingsRepository,
) : ViewModel() {
    val defaultPreset: StateFlow<ReminderPreset> = settings.settings.map { it.defaultPreset }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderPreset.PERSISTENT)

    fun save(
        title: String,
        notes: String?,
        priority: Priority,
        withReminder: Boolean,
        onSaved: (taskId: String, withReminder: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) = viewModelScope.launch {
        runCatching { actions.quickCapture(title, withReminder, notes, priority) }
            .onSuccess { onSaved(it, withReminder) }
            .onFailure { onError(it.userMessage()) }
    }
}
