package com.lifeadmin.autopilot.ai.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RES-060–063: fixed claim-status sets map to fixed states; conditions and unresolved decisive facts are told apart. */
class ResultDeriverTest {
    private fun c(id: String, status: ClaimStatus, load: Boolean = true, dependsOnUser: Boolean = false, checked: Boolean = true) =
        Claim(id, "claim $id", ClaimType.FACT, loadBearing = load, dependsOnUser = dependsOnUser, status = status, turnId = "t1", checked = checked)

    @Test fun fixedClaimSetsMapToTheExpectedStates() {
        assertEquals(ResultState.ANSWERED, ResultDeriver.derive(listOf(c("a", ClaimStatus.ESTABLISHED)), TurnEnding.COMPLETE).state)
        assertEquals(ResultState.ANSWERED_WITH_CONDITIONS, ResultDeriver.derive(listOf(c("a", ClaimStatus.ESTABLISHED), c("b", ClaimStatus.DEPENDS_ON_INPUT, dependsOnUser = true)), TurnEnding.COMPLETE).state)
        assertEquals(ResultState.PARTIALLY_ANSWERED, ResultDeriver.derive(listOf(c("a", ClaimStatus.ESTABLISHED), c("b", ClaimStatus.NOT_ESTABLISHED)), TurnEnding.COMPLETE).state)
        assertEquals(ResultState.PARTIALLY_ANSWERED, ResultDeriver.derive(listOf(c("a", ClaimStatus.ESTABLISHED), c("b", ClaimStatus.CONTESTED)), TurnEnding.COMPLETE).state)
        assertEquals(ResultState.NOT_ESTABLISHED, ResultDeriver.derive(listOf(c("a", ClaimStatus.NOT_ESTABLISHED), c("b", ClaimStatus.INDICATIVE)), TurnEnding.COMPLETE).state)
        assertEquals(ResultState.NEEDS_INPUT, ResultDeriver.derive(listOf(c("a", ClaimStatus.DEPENDS_ON_INPUT, dependsOnUser = true)), TurnEnding.COMPLETE).state)
        assertEquals(ResultState.NOT_ESTABLISHED, ResultDeriver.derive(emptyList(), TurnEnding.COMPLETE).state)
    }

    @Test fun aDecisiveFactTheInvestigationCouldNotEstablishIsBlockingNotACondition() {
        // RES-062: compatibility unknown → partial; a user-resolvable requirement → condition.
        val gap = ResultDeriver.derive(listOf(c("rec", ClaimStatus.ESTABLISHED), c("compat", ClaimStatus.NOT_ESTABLISHED)), TurnEnding.COMPLETE)
        assertEquals(ResultState.PARTIALLY_ANSWERED, gap.state)
        assertEquals(listOf("compat"), gap.blocking.map { it.id })
        val cond = ResultDeriver.derive(listOf(c("rec", ClaimStatus.ESTABLISHED), c("p95", ClaimStatus.DEPENDS_ON_INPUT, dependsOnUser = true)), TurnEnding.COMPLETE)
        assertEquals(ResultState.ANSWERED_WITH_CONDITIONS, cond.state)
        assertEquals(listOf("p95"), cond.conditions.map { it.id })
    }

    @Test fun nonLoadBearingGapsNeverChangeTheState() {
        val d = ResultDeriver.derive(listOf(c("a", ClaimStatus.ESTABLISHED), c("b", ClaimStatus.NOT_ESTABLISHED, load = false)), TurnEnding.COMPLETE)
        assertEquals(ResultState.ANSWERED, d.state)
    }

    @Test fun aStoppedRunDerivesFromWhatWasKeptAndNamesWhatWasNotChecked() {
        val d = ResultDeriver.derive(listOf(c("a", ClaimStatus.ESTABLISHED), c("b", ClaimStatus.NOT_ESTABLISHED, checked = false)), TurnEnding.STOPPED)
        assertEquals(ResultState.PARTIALLY_ANSWERED, d.state)
        assertEquals(listOf("b"), d.notChecked.map { it.id })
        assertTrue(d.stateLine.startsWith("Stopped early · Partly answered"))
        assertTrue(d.stateLine.contains("wasn't checked"))
        val budget = ResultDeriver.derive(listOf(c("a", ClaimStatus.NOT_ESTABLISHED, checked = false)), TurnEnding.BUDGET_SEARCHES)
        assertEquals(ResultState.NOT_ESTABLISHED, budget.state)
        assertTrue(budget.stateLine.startsWith("Search budget reached · Not established: nothing was checked"))
    }

    @Test fun supersededClaimsNeverDecideTheCurrentState() {
        val old = c("old", ClaimStatus.ESTABLISHED).copy(superseded = true, supersededByClaimId = "new")
        val d = ResultDeriver.derive(listOf(old, c("new", ClaimStatus.NOT_ESTABLISHED)), TurnEnding.COMPLETE)
        assertEquals(ResultState.NOT_ESTABLISHED, d.state)
    }

    @Test fun stateLinesArePlainLanguage() {
        ResultState.entries.forEach { st ->
            val d = Derivation(st, emptyList(), emptyList(), emptyList(), TurnEnding.COMPLETE)
            assertTrue(LeakageScan.scan(d.stateLine).isEmpty())
        }
    }
}
