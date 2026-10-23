package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research2.ResearchMode
import com.lifeadmin.autopilot.ai.research2.StageKind
import com.lifeadmin.autopilot.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R48: four presets plus Custom; saved settings keep their meaning; dimensions stay independent (RES-130). */
class ResearchPresetsTest {
    @Test fun legacyModesMapOneToOneAndNothingBecomesStandard() {
        assertEquals(ResearchDepth.QUICK, ResearchDepth.fromLegacy(ResearchMode.QUICK))
        assertEquals(ResearchDepth.THOROUGH, ResearchDepth.fromLegacy(ResearchMode.THOROUGH))
        assertEquals(ResearchDepth.DEEP, ResearchDepth.fromLegacy(ResearchMode.DEEP))
        assertEquals(ResearchDepth.CUSTOM, ResearchDepth.fromLegacy(ResearchMode.CUSTOM))
        // Quick / Thorough / Deep keep the M3A.2 model, budget and stages exactly.
        assertEquals(Triple("claude-haiku-5-5", 3, listOf(StageKind.GATHER)), ResearchPresets.QUICK.let { Triple(it.model, it.searches, it.stages) })
        assertEquals(Triple("claude-sonnet-5-5", 10, listOf(StageKind.GATHER, StageKind.FILL_GAPS)), ResearchPresets.THOROUGH.let { Triple(it.model, it.searches, it.stages) })
        assertEquals(Triple("claude-opus-5-5", 25, 4), ResearchPresets.DEEP.let { Triple(it.model, it.searches, it.stages.size) })
        assertEquals(listOf("Quick", "Standard", "Thorough", "Deep"), ResearchPresets.PRESETS.map { it.depth.label })
    }

    @Test fun savedOverridesKeepTheirDepthAndStandardHasItsOwnNewSetting() {
        val s = AppSettings(researchThoroughModel = "claude-opus-5-5", researchSpendLimitCents = 50)
        val thorough = ResearchPresets.resolve(ResearchDepth.THOROUGH, s)
        assertEquals("claude-opus-5-5", thorough.model)
        assertEquals(10, thorough.searchBudget)
        assertEquals(50, thorough.spendLimitCents)
        assertEquals("a changed model is shown as Custom, the depth itself is unchanged", "Custom", thorough.presetName)
        assertEquals(ResearchDepth.THOROUGH, thorough.depth)
        val standard = ResearchPresets.resolve(ResearchDepth.STANDARD, s)
        assertEquals("the Thorough override never leaks into Standard", "claude-sonnet-5-5", standard.model)
        assertEquals(6, standard.searchBudget)
        assertTrue(standard.budgetFollowsDepth)
        assertEquals("Standard", standard.presetName)
    }

    @Test fun dimensionsAreIndependent() {
        val s = AppSettings()
        val opusQuick = ResearchPresets.resolve(ResearchDepth.QUICK, s, modelOverride = "claude-opus-5-5")
        assertEquals(3, opusQuick.searchBudget)
        assertEquals(listOf(StageKind.GATHER), opusQuick.stages)
        val sonnetDeep = ResearchPresets.resolve(ResearchDepth.DEEP, s, modelOverride = "claude-sonnet-5-5")
        assertEquals(25, sonnetDeep.searchBudget)
        assertEquals(4, sonnetDeep.stages.size)
        val custom = ResearchPresets.resolve(ResearchDepth.STANDARD, s, searches = 12)
        assertEquals(12, custom.searchBudget)
        assertFalse(custom.budgetFollowsDepth)
        assertEquals("Custom", custom.presetName)
        val withEffort = ResearchPresets.resolve(ResearchDepth.STANDARD, s, effort = "high")
        assertEquals("high", withEffort.effort)
        assertEquals("effort changes no budget or depth", 6, withEffort.searchBudget)
        assertEquals(ResearchDepth.STANDARD, withEffort.depth)
    }

    @Test fun effortIsOnlyAppliedWhereTheModelSupportsItAndTheReasonIsStated() {
        val s = AppSettings(researchEffort = "high")
        assertNull("Haiku: not configurable, so nothing is sent", ResearchPresets.resolve(ResearchDepth.QUICK, s).effort)
        assertEquals("high", ResearchPresets.resolve(ResearchDepth.STANDARD, s).effort)
        assertTrue(EffortSupport.reason("claude-haiku-5-5")!!.contains("isn't configurable"))
        assertNull(EffortSupport.reason("claude-sonnet-5-5"))
    }

    @Test fun fewSearchesTrimTheStagesAndTheQuickWarningInformsOnly() {
        assertEquals(listOf(StageKind.GATHER), ResearchPresets.resolve(ResearchDepth.DEEP, AppSettings(), searches = 2).stages)
        val judgment = Objective("q", "o", AskKind.RECOMMEND)
        assertTrue(ResearchPresets.insufficientLevelWarning(ResearchDepth.QUICK, judgment)!!.contains("may not be enough"))
        assertNull(ResearchPresets.insufficientLevelWarning(ResearchDepth.STANDARD, judgment))
        assertNull(ResearchPresets.insufficientLevelWarning(ResearchDepth.QUICK, Objective("q", "o", AskKind.FACT, recommendedDepth = ResearchDepth.QUICK)))
    }

    @Test fun theTurnConfigRecordsEveryDimension() {
        val c = ResearchPresets.resolve(ResearchDepth.THOROUGH, AppSettings(), effort = "low", limitCents = 100).turnConfig()
        assertEquals("claude-sonnet-5-5", c.model)
        assertEquals("low", c.effort)
        assertEquals("thorough", c.depth)
        assertEquals(10, c.searchBudget)
        assertEquals(100, c.spendLimitCents)
    }
}
