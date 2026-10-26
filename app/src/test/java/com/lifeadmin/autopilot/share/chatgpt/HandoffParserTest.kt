package com.lifeadmin.autopilot.share.chatgpt

import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.handoffBlock
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.validHandoffJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffParserTest {
    private fun found(text: String): AutopilotHandoff {
        val r = HandoffParser.parse(text)
        assertTrue("$r", r is HandoffResult.Found)
        return (r as HandoffResult.Found).handoff
    }

    private fun problems(text: String): List<String> {
        val r = HandoffParser.parse(text)
        assertTrue("$r", r is HandoffResult.Malformed)
        return (r as HandoffResult.Malformed).problems
    }

    @Test fun parsesAFullHandoffBlockInsideProse() {
        val h = found("Here are your final decisions.\n\n${handoffBlock(validHandoffJson)}\n\nGood luck!")
        assertEquals(HandoffTarget("ap:task:synthetic-1", "Choose a widget"), h.target)
        assertEquals("Widget A fits the budget.", h.summary)
        assertEquals(listOf(HandoffDecision("Buy Widget A", "Cheaper and quieter")), h.decisions)
        assertEquals(listOf("Compare warranty terms"), h.notes)
        assertEquals(listOf(HandoffStep("Order Widget A", "2026-11-03"), HandoffStep("Recycle the old widget", null)), h.steps)
        assertEquals(HandoffWhen("2026-11-15", "17:00", "Return window closes"), h.deadline)
        assertEquals(listOf(HandoffWhen("2026-11-02", "09:00", "Check the price")), h.reminders)
        assertEquals(listOf("Does it fit the shelf?"), h.openQuestions)
    }

    @Test fun aMinimalHandoffNeedsOnlyTheSchemaAndATitle() {
        val h = found(handoffBlock("""{"schema":"autopilot.handoff.v1","target":{"title":"  Call the plumber "}}"""))
        assertEquals(HandoffTarget(null, "Call the plumber"), h.target)
        assertTrue(h.steps.isEmpty() && h.decisions.isEmpty() && h.deadline == null)
    }

    @Test fun aJsonFenceCountsOnlyWhenItDeclaresTheSchema() {
        assertEquals("Choose a widget", found(handoffBlock(validHandoffJson, info = "json")).target.title)
        assertEquals("Choose a widget", found(handoffBlock(validHandoffJson, info = "")).target.title)
        assertEquals(HandoffResult.NotFound, HandoffParser.parse(handoffBlock("""{"title":"Not a handoff"}""", info = "json")))
        assertEquals(HandoffResult.NotFound, HandoffParser.parse(handoffBlock(validHandoffJson, info = "kotlin")))
    }

    @Test fun ordinaryRepliesAreNeverHandoffs() {
        listOf(
            "You should add a step to call the bank tomorrow and set a reminder for 9am.",
            "Remind me to buy Widget A by 2026-11-03. Mark the task done.",
            // Bare JSON in prose is not a block, even with the schema.
            "Use this: " + validHandoffJson.replace("\n", " "),
            "Inline `{\"schema\":\"autopilot.handoff.v1\"}` code is not a block.",
            handoffBlock("val x = 1", info = "kotlin"),
            "",
        ).forEach { assertEquals(it, HandoffResult.NotFound, HandoffParser.parse(it)) }
    }

    @Test fun malformedJsonInAHandoffFenceIsReportedNotGuessed() {
        assertEquals(listOf("not a JSON object"), problems(handoffBlock("""{"schema":"autopilot.handoff.v1","target":{"title":"x"}""")))
        assertEquals(listOf("not a JSON object"), problems(handoffBlock("""["autopilot.handoff.v1"]""")))
        assertEquals(listOf("schema: missing"), problems(handoffBlock("""{"target":{"title":"x"}}""")))
        assertEquals(listOf("schema: unknown"), problems(handoffBlock("""{"schema":"other","target":{"title":"x"}}""")))
        assertEquals(listOf("fields have the wrong types"), problems(handoffBlock("""{"schema":"autopilot.handoff.v1","target":{"title":"x"},"steps":"buy it"}""")))
        assertEquals(listOf("target.title: missing"), problems(handoffBlock("""{"schema":"autopilot.handoff.v1","decisions":[{"text":"x"}]}""")))
    }

    @Test fun fieldValuesAreValidated() {
        val p = problems(
            handoffBlock(
                """{"schema":"autopilot.handoff.v1","target":{"title":"x"},"steps":[{"text":""},{"text":"ok","due":"next Friday"}],""" +
                    """"deadline":{"date":"2026-02-30","time":"5pm"},"reminders":[{"date":"2026-11-02","time":"25:00"}],"open_questions":["${"q".repeat(2_001)}"]}""",
            ),
        )
        assertEquals(
            listOf("steps[0].text: empty", "steps[1].due: not yyyy-MM-dd", "deadline.date: not yyyy-MM-dd", "deadline.time: not HH:mm", "reminders[0].time: not HH:mm", "open_questions[0]: too long"),
            p,
        )
        val many = (1..31).joinToString(",") { """{"text":"s$it"}""" }
        assertEquals(listOf("steps: more than 30 items"), problems(handoffBlock("""{"schema":"autopilot.handoff.v1","target":{"title":"x"},"steps":[$many]}""")))
    }

    @Test fun anotherVersionIsRefused() {
        assertEquals(HandoffResult.UnsupportedVersion("autopilot.handoff.v2"), HandoffParser.parse(handoffBlock("""{"schema":"autopilot.handoff.v2","target":{"title":"x"}}""")))
    }

    @Test fun twoHandoffBlocksAreAmbiguous() {
        assertEquals(HandoffResult.Ambiguous(2), HandoffParser.parse(handoffBlock(validHandoffJson) + "\n\nOr:\n\n" + handoffBlock(validHandoffJson, info = "json")))
    }

    @Test fun fenceVariantsAreRecognized() {
        assertEquals("Choose a widget", found("~~~autopilot-handoff\n$validHandoffJson\n~~~").target.title)
        assertEquals("Choose a widget", found("````AUTOPILOT-HANDOFF\n$validHandoffJson\n````").target.title)
        // A shorter inner fence does not close a longer outer one.
        assertEquals(HandoffResult.NotFound, HandoffParser.parse("````markdown\n${handoffBlock(validHandoffJson)}\n````"))
    }

    @Test fun anOversizedBlockIsRefused() {
        val big = """{"schema":"autopilot.handoff.v1","target":{"title":"x"},"notes":["${"n".repeat(33 * 1024)}"]}"""
        assertEquals(listOf("block larger than 32 KB"), problems(handoffBlock(big)))
    }
}
