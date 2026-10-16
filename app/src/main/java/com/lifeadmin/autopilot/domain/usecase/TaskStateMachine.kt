package com.lifeadmin.autopilot.domain.usecase

import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.TaskStatus.ACTIONABLE
import com.lifeadmin.autopilot.domain.model.TaskStatus.CANCELED
import com.lifeadmin.autopilot.domain.model.TaskStatus.DONE
import com.lifeadmin.autopilot.domain.model.TaskStatus.INBOX
import com.lifeadmin.autopilot.domain.model.TaskStatus.PAUSED
import com.lifeadmin.autopilot.domain.model.TaskStatus.WAITING

/**
 * Allowed status transitions (SPEC §5.1). Terminal states (DONE/CANCELED) only leave via an
 * explicit Reopen, which is modelled separately so it is always logged as a REOPENED event.
 *
 * Assumption: INBOX → WAITING is allowed so "waiting for the mechanic" can be captured and
 * classified without a detour through ACTIONABLE.
 */
object TaskStateMachine {
    private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        INBOX to setOf(ACTIONABLE, WAITING, DONE, CANCELED),
        ACTIONABLE to setOf(WAITING, PAUSED, DONE, CANCELED),
        WAITING to setOf(ACTIONABLE, DONE, CANCELED),
        PAUSED to setOf(ACTIONABLE, DONE, CANCELED),
        DONE to emptySet(),
        CANCELED to emptySet(),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = to in allowed.getValue(from)

    fun targets(from: TaskStatus): Set<TaskStatus> = allowed.getValue(from)

    fun canReopen(from: TaskStatus): Boolean = from.isTerminal

    fun requireTransition(from: TaskStatus, to: TaskStatus) {
        require(canTransition(from, to)) { "Illegal status transition $from -> $to" }
    }
}
