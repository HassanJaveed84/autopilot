package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.TaskStatus.ACTIONABLE
import com.lifeadmin.autopilot.domain.model.TaskStatus.CANCELED
import com.lifeadmin.autopilot.domain.model.TaskStatus.DONE
import com.lifeadmin.autopilot.domain.model.TaskStatus.INBOX
import com.lifeadmin.autopilot.domain.model.TaskStatus.PAUSED
import com.lifeadmin.autopilot.domain.model.TaskStatus.WAITING
import com.lifeadmin.autopilot.domain.usecase.TaskStateMachine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    @Test fun primaryFlow() {
        assertTrue(TaskStateMachine.canTransition(INBOX, ACTIONABLE))
        assertTrue(TaskStateMachine.canTransition(ACTIONABLE, DONE))
    }

    @Test fun waitingAndPausedReturnToActionable() {
        assertTrue(TaskStateMachine.canTransition(ACTIONABLE, WAITING))
        assertTrue(TaskStateMachine.canTransition(ACTIONABLE, PAUSED))
        assertTrue(TaskStateMachine.canTransition(WAITING, ACTIONABLE))
        assertTrue(TaskStateMachine.canTransition(PAUSED, ACTIONABLE))
        assertFalse(TaskStateMachine.canTransition(WAITING, PAUSED))
    }

    @Test fun anyNonTerminalCanBeDoneOrCanceled() {
        TaskStatus.entries.filterNot { it.isTerminal }.forEach {
            assertTrue("$it → DONE", TaskStateMachine.canTransition(it, DONE))
            assertTrue("$it → CANCELED", TaskStateMachine.canTransition(it, CANCELED))
        }
    }

    @Test fun terminalOnlyLeavesViaReopen() {
        listOf(DONE, CANCELED).forEach { from ->
            TaskStatus.entries.forEach { to -> assertFalse("$from → $to", TaskStateMachine.canTransition(from, to)) }
            assertTrue(TaskStateMachine.canReopen(from))
        }
        assertFalse(TaskStateMachine.canReopen(ACTIONABLE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun illegalTransitionThrows() {
        TaskStateMachine.requireTransition(DONE, ACTIONABLE)
    }
}
