package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.testing.Research2Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequirementExtractorTest {
    @Test fun supplementsBenchmarkKeepsFourItemsBrandAndStepOutput() {
        val r = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST)
        assertEquals(DeliverableTemplate.PICK_PER_ITEM, r.template)
        val items = r.requirements.filter { it.type == RequirementType.ITEM }
        assertEquals(listOf("general multivitamin", "vitamin D", "magnesium", "glucosamine"), items.map { it.label })
        assertEquals(listOf("r-i0", "r-i1", "r-i2", "r-i3"), items.map { it.id })
        val constraints = r.requirements.filter { it.type == RequirementType.CONSTRAINT }.map { it.label }
        assertTrue(constraints.toString(), constraints.any { it.startsWith("Brand preference: Thorne and Pure Encapsulations") })
        val outputs = r.requirements.filter { it.type == RequirementType.OUTPUT }.map { it.label }
        assertEquals(listOf("One step per item, added to the task"), outputs)
        assertTrue(r.confident)
    }

    @Test fun fragranceBenchmarkIsAShortlistWithBudgetRegionAndGift() {
        val r = RequirementExtractor.extract(Research2Fixtures.FRAGRANCE_REQUEST)
        assertEquals(DeliverableTemplate.SHORTLIST, r.template)
        val items = r.requirements.filter { it.type == RequirementType.ITEM }
        assertEquals(1, items.size)
        assertTrue(items[0].label, items[0].label.contains("men's perfume"))
        val c = r.requirements.filter { it.type == RequirementType.CONSTRAINT }.map { it.label }
        assertTrue(c.toString(), c.contains("Budget: up to $150"))
        assertTrue(c.toString(), c.contains("Region: United States"))
        assertTrue(c.toString(), c.any { it.startsWith("For a gift") })
        assertEquals(listOf("A shortlist with a recommendation"), r.requirements.filter { it.type == RequirementType.OUTPUT }.map { it.label })
    }

    @Test fun plainQuestionIsAQuickAnswerAndHowToIsSteps() {
        val q = RequirementExtractor.extract("When do advance train tickets go on sale?")
        assertEquals(DeliverableTemplate.QUICK_ANSWER, q.template)
        assertEquals(1, q.requirements.size)
        val h = RequirementExtractor.extract("How do I renew a UK passport from abroad?")
        assertEquals(DeliverableTemplate.HOW_TO, h.template)
        assertTrue(h.requirements.any { it.type == RequirementType.OUTPUT && it.label == "Steps in order" })
        assertTrue(h.requirements.any { it.type == RequirementType.CONSTRAINT && it.label == "Region: United Kingdom" } || h.requirements.none { it.type == RequirementType.CONSTRAINT })
        val c = RequirementExtractor.extract("What do I need to bring for a US passport appointment? Make a checklist.")
        assertEquals(DeliverableTemplate.CHECKLIST, c.template)
    }

    @Test fun exclusionsAndUnsureCasesAreFlagged() {
        val r = RequirementExtractor.extract("Need a laptop for video editing under £1200, no Apple.")
        assertTrue(r.requirements.toString(), r.requirements.any { it.type == RequirementType.CONSTRAINT && it.label == "Exclude: Apple" })
        assertTrue(r.requirements.any { it.type == RequirementType.CONSTRAINT && it.label == "Budget: up to £1200" })
        val long = RequirementExtractor.extract("I have been thinking about whether it makes sense to move my pension across providers given fees and the way transfers work and I am not sure what to ask")
        assertFalse(long.confident)
        assertEquals(DeliverableTemplate.QUICK_ANSWER, long.template)
        assertEquals(emptyList<Requirement>(), RequirementExtractor.extract("   ").requirements)
    }

    @Test fun userIdsNeverCollide() {
        val reqs = listOf(Requirement("r-i0", RequirementType.ITEM, "x"), Requirement("r-u0", RequirementType.USER_ADDED, "y", RequirementOrigin.USER), Requirement("r-u3", RequirementType.USER_ADDED, "z", RequirementOrigin.USER))
        assertEquals("r-u4", RequirementExtractor.nextUserId(reqs))
        assertEquals("r-u0", RequirementExtractor.nextUserId(reqs.take(1)))
    }
}
