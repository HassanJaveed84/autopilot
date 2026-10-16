package com.lifeadmin.autopilot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.ImportException
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val backup: BackupRepository,
    private val onSettingsChanged: suspend () -> Unit,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    /** Schedule-affecting settings changes trigger a full reconcile. */
    fun update(reschedule: Boolean = true, transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        settingsRepository.update(transform)
        if (reschedule) onSettingsChanged()
    }

    fun export(open: () -> OutputStream?) = viewModelScope.launch {
        runCatching {
            val text = backup.exportJson()
            (open() ?: error("Could not open file")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onSuccess { messages.send("Backup exported") }
            .onFailure { messages.send("Export failed") }
    }

    fun import(open: () -> InputStream?) = viewModelScope.launch {
        runCatching {
            val raw = (open() ?: error("Could not open file")).use { backup.readBounded(it) }
            backup.importJson(raw)
        }.onSuccess { r -> messages.send("Imported: ${r.created} new, ${r.updated} updated, ${r.skipped} unchanged") }
            .onFailure { e -> messages.send(if (e is ImportException) "Import failed: ${e.message}. Nothing was changed." else "Import failed. Nothing was changed.") }
    }
}
