package com.lifeadmin.autopilot.domain.usecase

/**
 * Wraps every schedule-affecting mutation. Implementations serialize mutations with alarm
 * delivery (so Done and a concurrent delivery cannot interleave), then reconcile the OS alarm
 * projection with the authoritative database state.
 */
interface ScheduleCoordinator {
    suspend fun <T> mutateTask(taskId: String, dismissNotification: Boolean = false, block: suspend () -> T): T

    /** No-op coordinator for code paths/tests that do not exercise scheduling. */
    object Direct : ScheduleCoordinator {
        override suspend fun <T> mutateTask(taskId: String, dismissNotification: Boolean, block: suspend () -> T): T = block()
    }
}
