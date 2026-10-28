package com.lifeadmin.autopilot.ui.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.ResearchBrief
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

// ------------------------------------------------------------------ brief

data class BriefUi(
    val loading: Boolean = true,
    val brief: ResearchBrief? = null,
    val details: TaskDetails? = null,
    val now: Instant = Instant.EPOCH,
    val sourceSheet: Int? = null,
) {
    val isStale: Boolean get() = brief?.isStale(now) == true
}

class ResearchBriefViewModel(
    val taskId: String,
    val briefId: String,
    briefs: ResearchRepository,
    tasks: TaskRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val sheet = MutableStateFlow<Int?>(null)

    /** Read-only (M4): a legacy brief is shown as it was saved; nothing here can change the task. */
    val state: StateFlow<BriefUi> = combine(briefs.observe(briefId), tasks.observeDetails(taskId), sheet) { b, d, sh ->
        BriefUi(loading = false, brief = b, details = d, now = clock.now(), sourceSheet = sh)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BriefUi())

    fun openSource(n: Int) { sheet.value = n }
    fun closeSource() { sheet.value = null }
}

