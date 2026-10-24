package com.lifeadmin.autopilot.testing

// Debug-only fixtures shared by unit tests, Robolectric UI tests and the emulator screenshot tour.
// Fictional sources and figures; never part of a release build.

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage

/**
 * Provider replies for Research Online: the clarification check, research answers as Anthropic's
 * web search returns them (text blocks with structured citations), a paused partial answer and the
 * task-suggestion reply.
 */
object ResearchFixtures {
    fun clear(): CallResult.Done = small("CLEAR", "msg_clear")

    const val UNION_STATION = "What's the address of Union Station?"
    fun askWhichCity(): CallResult.Done = small("ASK: Which city's Union Station do you mean?\nOPTION: Washington, DC\nOPTION: Los Angeles\nOPTION: Chicago", "msg_ask")

    const val PASSPORT = "What documents do I need to renew my passport?"
    val passportGov = RawCitation("https://www.passports.gov.example/renew", "Renew a passport | Passport Office", "Send your most recent passport, one new photo, the DS-82 form and the fee.")
    val passportNameGov = RawCitation("https://www.passports.gov.example/name-change", "Name changes | Passport Office", "If your name changed, include the original or certified name-change document.")

    fun passportAnswer(): CallResult.Done = done(
        listOf(
            RawSegment("## What to send\n- ", emptyList()),
            RawSegment("Your most recent passport, one new photo, the renewal form (DS-82) and the fee", listOf(passportGov)),
            RawSegment("\n- ", emptyList()),
            RawSegment("If your name has changed, the original or a certified copy of the name-change document", listOf(passportNameGov)),
            RawSegment("\n\nRenewing by mail usually applies only when your last passport is recent; I'd check the eligibility rules on the office's page before sending anything.", emptyList()),
        ),
        listOf(RawSearch("renew passport documents", listOf(RawSearchResult(passportGov.url, passportGov.title!!, "September 2026"), RawSearchResult(passportNameGov.url, passportNameGov.title!!, null)))),
        MeasuredUsage(9_000, 700, 0, 0, 0, 1), "msg_passport",
    )

    const val MULTIS = "Compare Thorne and Pure Encapsulations multivitamins"
    val thorne = RawCitation("https://www.thorne.example/basic-nutrients", "Basic Nutrients 2/Day | Thorne", "Two capsules daily; NSF Certified for Sport.")
    val pure = RawCitation("https://www.pureencapsulations.example/one", "O.N.E. Multivitamin | Pure Encapsulations", "One capsule daily; free from wheat, gluten, nuts.")

    fun multisAnswer(): CallResult.Done = done(
        listOf(
            RawSegment("| | Thorne Basic Nutrients 2/Day | Pure O.N.E. |\n|---|---|---|\n| Dose | ", emptyList()),
            RawSegment("Two capsules daily", listOf(thorne)),
            RawSegment(" | ", emptyList()),
            RawSegment("One capsule daily", listOf(pure)),
            RawSegment(" |\n\nIn my judgment neither is clearly better; pick on dose convenience and certification.", emptyList()),
        ),
        listOf(RawSearch("Thorne vs Pure multivitamin", listOf(RawSearchResult(thorne.url, thorne.title!!, "1 year ago"), RawSearchResult(pure.url, pure.title!!, null)))),
        MeasuredUsage(12_000, 900, 0, 0, 0, 2), "msg_multis",
    )

    fun ironFollowUp(): CallResult.Done = done(
        listOf(RawSegment("Both standard formulas are iron-free", listOf(pure)), RawSegment(", so the earlier comparison still applies; the \"with iron\" variant is the one to avoid.", emptyList())),
        listOf(RawSearch("Pure O.N.E. iron", listOf(RawSearchResult(pure.url, pure.title!!, null)))),
        MeasuredUsage(14_000, 400, 0, 0, 0, 1), "msg_iron",
    )

    /** The first call pauses mid-research with some text already written. */
    fun pausedPartial(): CallResult.Done = done(
        listOf(RawSegment("So far: the maker's page states a 99% reduction in lab tests", listOf(RawCitation("https://www.aquapure.example/x9", "AquaPure X9", "Reduces microplastics by 99% in lab tests.")))),
        listOf(RawSearch("AquaPure X9 microplastics", listOf(RawSearchResult("https://www.aquapure.example/x9", "AquaPure X9", null)))),
        MeasuredUsage(6_000, 200, 0, 0, 0, 1), "msg_paused", stop = CallStop.PAUSE_TURN,
    )

    fun suggestions(): CallResult.Done = small("STEP: Fill in the DS-82 renewal form\nNOTE: Only one new photo is needed, not two.", "msg_suggest")

    fun small(text: String, id: String): CallResult.Done =
        CallResult.Done(listOf(RawSegment(text, emptyList())), emptyList(), MeasuredUsage(400, 30, 0, 0, 0, 0), CallStop.END, 400, id)

    private fun done(segments: List<RawSegment>, searches: List<RawSearch>, usage: MeasuredUsage, id: String, stop: CallStop = CallStop.END) =
        CallResult.Done(segments, searches, usage, stop, 2_000, id, paused = if (stop == CallStop.PAUSE_TURN) "paused-$id" else null)
}
