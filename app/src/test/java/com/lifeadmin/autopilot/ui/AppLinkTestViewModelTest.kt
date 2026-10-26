package com.lifeadmin.autopilot.ui

import androidx.lifecycle.SavedStateHandle
import com.lifeadmin.autopilot.share.applink.TestLinkParser
import com.lifeadmin.autopilot.share.applink.TestLinkProblem
import com.lifeadmin.autopilot.share.applink.TestLinkResult
import com.lifeadmin.autopilot.ui.applink.AppLinkTestViewModel
import com.lifeadmin.autopilot.ui.applink.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkTestViewModelTest {
    private val host = "links.example.org"
    private val url = TestLinkParser.testUrl(host)
    private val saved = SavedStateHandle()
    private val vm = AppLinkTestViewModel(saved, TestLinkParser(host))

    @Test fun aRepeatedLinkShowsTheSameMessageAndOnlyCountsTheRepeat() {
        vm.onLink(url, viewIntent = true, openedBy = "com.openai.chatgpt", delivery = Delivery.NEW_SCREEN)
        vm.onLink(url, viewIntent = true, openedBy = "com.openai.chatgpt", delivery = Delivery.OPEN_SCREEN)
        val s = vm.state.value
        assertEquals(TestLinkResult.Received("hello-from-chatgpt"), s.result)
        assertEquals(Delivery.OPEN_SCREEN, s.delivery)
        assertEquals(2, s.received)
        assertEquals("com.openai.chatgpt", s.openedBy)
    }

    @Test fun processRecreationRestoresTheValidatedMessageNotTheRawUrl() {
        vm.onLink("https://$host/autopilot/test?utm_source=x#message=hello", viewIntent = true, openedBy = null, delivery = Delivery.NEW_SCREEN)
        // SavedStateHandle is what survives process death; nothing in it may be the raw link.
        assertFalse(saved.keys().any { saved.get<Any>(it).toString().contains("https://") })
        val restored = AppLinkTestViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), TestLinkParser(host)).state.value
        assertEquals(TestLinkResult.Received("hello"), restored.result)
        assertEquals(Delivery.RESTORED, restored.delivery)
        assertEquals(1, restored.received)
    }

    @Test fun aRefusedLinkKeepsOnlyItsCategory() {
        vm.onLink("https://evil.example/autopilot/test#message=hello", viewIntent = true, openedBy = null, delivery = Delivery.NEW_SCREEN)
        assertEquals(TestLinkResult.Rejected(TestLinkProblem.WRONG_HOST), vm.state.value.result)
        val restored = AppLinkTestViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), TestLinkParser(host)).state.value
        assertEquals(TestLinkResult.Rejected(TestLinkProblem.WRONG_HOST), restored.result)
    }

    @Test fun aValidLinkAfterARefusedOneReplacesIt() {
        vm.onLink("http://$host/autopilot/test#message=hello", viewIntent = false, openedBy = null, delivery = Delivery.NEW_SCREEN)
        vm.onLink(url, viewIntent = true, openedBy = null, delivery = Delivery.OPEN_SCREEN)
        assertEquals(TestLinkResult.Received("hello-from-chatgpt"), vm.state.value.result)
    }

    @Test fun aLaunchWithoutALinkShowsNothingReceived() {
        assertNull(vm.state.value.result)
        vm.onLink(null, viewIntent = false, openedBy = null, delivery = Delivery.NEW_SCREEN)
        assertNull(vm.state.value.result)
    }
}
