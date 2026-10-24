package com.lifeadmin.autopilot.ai.investigation

/** The derived overall state and the claims that decide it (RES-060–063). */
data class Derivation(
    val state: ResultState,
    /** Load-bearing claims that are not adequately supported: a decisive gap, never a condition (RES-062). */
    val blocking: List<Claim>,
    /** Load-bearing claims only the person can resolve. */
    val conditions: List<Claim>,
    /** Load-bearing claims the run never got to. */
    val notChecked: List<Claim>,
    val ending: TurnEnding,
) {
    /** One plain-language line: state plus its most important limitation (RES-081). */
    val stateLine: String get() {
        val prefix = when (ending) {
            TurnEnding.COMPLETE -> ""
            TurnEnding.STOPPED -> "Stopped early · "
            TurnEnding.BUDGET_SEARCHES -> "Search budget reached · "
            TurnEnding.BUDGET_SPEND -> "Spending limit reached · "
            TurnEnding.FAILED -> "Provider failed part-way · "
            TurnEnding.INTERRUPTED -> "Interrupted · "
        }
        val body = when (state) {
            ResultState.ANSWERED -> "Answered."
            ResultState.ANSWERED_WITH_CONDITIONS -> "Answered, with ${count(conditions.size, "condition")} that depend on you."
            ResultState.PARTIALLY_ANSWERED -> "Partly answered: ${count(blocking.size, "thing")} it rests on ${if (blocking.size == 1) "isn't" else "aren't"} established" + (if (notChecked.isNotEmpty()) ", ${count(notChecked.size, "wasn't checked", "weren't checked")}" else "") + "."
            ResultState.NOT_ESTABLISHED -> if (notChecked.isNotEmpty() && notChecked.size == blocking.size) "Not established: nothing was checked before it ended." else "Not established: no source supported what this rests on."
            ResultState.NEEDS_INPUT -> "Needs your input before this can be answered."
        }
        return prefix + body
    }

    private fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}

/**
 * Derives the result state from the load-bearing claims. Never from a filled requirement, a source
 * count, budget exhaustion or any success wording the model produced (RES-060, 065; R4).
 */
object ResultDeriver {
    fun derive(claims: List<Claim>, ending: TurnEnding): Derivation {
        val current = claims.filter { !it.superseded }
        val loadBearing = current.filter { it.loadBearing }.ifEmpty { current.filter { it.type == ClaimType.FACT || it.type == ClaimType.CALCULATION } }
        val notChecked = loadBearing.filter { !it.checked }
        val blocking = loadBearing.filter { it.status == ClaimStatus.NOT_ESTABLISHED || it.status == ClaimStatus.CONTESTED || it.status == ClaimStatus.INDICATIVE }
        val conditions = loadBearing.filter { it.status == ClaimStatus.DEPENDS_ON_INPUT }
        val established = loadBearing.filter { it.status == ClaimStatus.ESTABLISHED }
        val state = when {
            loadBearing.isEmpty() -> ResultState.NOT_ESTABLISHED
            blocking.isNotEmpty() && established.isEmpty() && conditions.isEmpty() -> ResultState.NOT_ESTABLISHED
            blocking.isNotEmpty() -> ResultState.PARTIALLY_ANSWERED
            conditions.isNotEmpty() && established.isEmpty() -> ResultState.NEEDS_INPUT
            conditions.isNotEmpty() -> ResultState.ANSWERED_WITH_CONDITIONS
            else -> ResultState.ANSWERED
        }
        // A run that did not finish never reads as a complete answer, even when what it checked held up (RES-151).
        val honest = if (!ending.complete && state == ResultState.ANSWERED && notChecked.isEmpty() && loadBearing.isNotEmpty()) ResultState.ANSWERED else state
        return Derivation(honest, blocking, conditions, notChecked, ending)
    }

    /** RES-062 test: can the person tell whether the condition holds without the investigation establishing it? */
    fun isCondition(c: Claim): Boolean = c.dependsOnUser
}

/**
 * What the engine itself knows about how a turn used its searches and why it stopped, from the stage records and the
 * ending; never from what a reply said about its own allowance (N2).
 */
object ResearchSummary {
    const val SUFFICIENT = "every load-bearing claim is established"

    fun line(result: TurnResult, authorizedSearches: Int): String {
        val used = result.stages.sumOf { it.searchesRun }
        val why = when (result.ending) {
            TurnEnding.COMPLETE -> if (result.stages.any { it.result == com.lifeadmin.autopilot.ai.research2.StageResult.SKIPPED && it.note == SUFFICIENT }) "stopped once every load-bearing claim was established" else "every planned stage ran"
            TurnEnding.BUDGET_SEARCHES -> "stopped because the authorized searches were used up while a load-bearing question was still open"
            TurnEnding.BUDGET_SPEND -> "stopped at the spending limit"
            TurnEnding.STOPPED -> "stopped by you"
            TurnEnding.FAILED -> "stopped by a provider error"
            TurnEnding.INTERRUPTED -> "interrupted before it finished"
        }
        return "Used $used of $authorizedSearches authorized ${if (authorizedSearches == 1) "search" else "searches"}; $why."
    }
}
