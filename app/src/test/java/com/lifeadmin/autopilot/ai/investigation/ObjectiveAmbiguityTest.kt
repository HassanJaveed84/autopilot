package com.lifeadmin.autopilot.ai.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Document F R-N(b) and §1.13: an answer that depends on which place is meant needs the place before any
 * authorization exists, unless the question names it. Detection follows the ask (address, hours, directions,
 * phone, nearest) and whether a place is given; it never consults a list of ambiguous names.
 */
class ObjectiveAmbiguityTest {
    private fun asksWhere(q: String) {
        val o = ObjectivePreparer.local(q)
        assertFalse("$q should not be established", o.established)
        assertNotNull(q, o.clarification)
        assertTrue(q, o.unknowns.any { it.blocking })
    }

    private fun clear(q: String) {
        val o = ObjectivePreparer.local(q)
        assertTrue("$q should be established", o.established)
        assertNull(q, o.clarification)
    }

    @Test fun aLocationDependentAskWithNoPlaceAsksWhichOneFirst() {
        asksWhere("address of Union Station")
        asksWhere("What is the address of Union Station?")
        asksWhere("What time does the Apple Store close today?")
        asksWhere("Opening hours of Central Library")
        asksWhere("phone number of St. Mary's Hospital")
        asksWhere("How do I get to the airport?")
        asksWhere("Where is the nearest pharmacy?")
    }

    @Test fun aQuestionThatNamesThePlaceIsEstablishedWithNoInterview() {
        clear("What is the address of Union Station in Washington, DC?")
        clear("address of Union Station, Washington, DC")
        clear("Opening hours of the British Museum in London")
        clear("phone number of Mayo Clinic in Rochester, MN")
        clear("Where is 10 Downing Street?")
        clear("What time does the post office at 02139 close?")
    }

    @Test fun asksThatDoNotDependOnAPlaceAreUnchanged() {
        clear("What documents do I need to renew my passport?")
        clear("Where can I buy Thorne Basic Nutrients 2/Day at the best price from a reputable retailer?")
        clear("Compare Thorne and Pure Encapsulations multivitamins")
        clear("Query Snowflake directly from a customer-facing backend, or replicate to Postgres?")
        val worth = ObjectivePreparer.local("is it worth it?")
        assertFalse(worth.established)
        assertTrue("the no-subject question keeps its own clarification", worth.clarification!!.question.startsWith("What are you weighing up"))
    }

    @Test fun answeringTheClarificationEstablishesTheObjectiveForThatPlace() {
        val amb = ObjectivePreparer.local("address of Union Station")
        val fixed = ObjectivePreparer.clarified(amb, "Chicago")
        assertTrue(fixed.established)
        assertTrue(fixed.question.contains("Chicago"))
        assertEquals("the clarification adds the place, not a different objective", AskKind.FACT, fixed.askKind)
    }
}
