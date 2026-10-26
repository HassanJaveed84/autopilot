package com.lifeadmin.autopilot.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.share.chatgpt.FetchResult
import com.lifeadmin.autopilot.share.chatgpt.HandoffResult
import com.lifeadmin.autopilot.share.chatgpt.ImportResult
import com.lifeadmin.autopilot.share.chatgpt.RawResponse
import com.lifeadmin.autopilot.share.chatgpt.ShareFetcher
import com.lifeadmin.autopilot.share.chatgpt.ShareImporter
import com.lifeadmin.autopilot.share.chatgpt.ShareLink
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures
import com.lifeadmin.autopilot.share.chatgpt.ShareTransport
import com.lifeadmin.autopilot.ui.share.ShareImportUi
import com.lifeadmin.autopilot.ui.share.ShareImportViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ShareImportViewModelTest {
    private val url = SharePageFixtures.URL
    private val link = ShareLink.parse(url)!!
    private val other = "https://chatgpt.com/share/11111111-2222-4333-8444-555555555555"

    private class CountingTransport(var page: () -> RawResponse) : ShareTransport {
        @Volatile var calls = 0
        override fun get(url: String, maxBytes: Int): RawResponse { calls++; return page() }
    }

    private val handoffPage = SharePageFixtures.dataPage(
        listOf(
            SharePageFixtures.user("u1", "Decide for me"),
            SharePageFixtures.assistant("a1", "Done.\n\n" + SharePageFixtures.handoffBlock(SharePageFixtures.validHandoffJson)),
        ),
    )
    private val transport = CountingTransport { RawResponse(200, "text/html", null, handoffPage.toByteArray(), false) }

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = ShareImportViewModel(saved, ShareImporter(ShareFetcher(transport)))

    private fun awaitDone(vm: ShareImportViewModel): ShareImportUi.Done {
        val until = System.currentTimeMillis() + 10_000
        while (vm.state.value !is ShareImportUi.Done && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return vm.state.value as ShareImportUi.Done
    }

    @Test fun aShareShowsAPreviewAndDownloadsNothing() {
        val vm = vm()
        vm.onShared("Widget chat $url?ogimg=plain")
        assertEquals(ShareImportUi.Ready(link), vm.state.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, transport.calls)
    }

    @Test fun fetchExtractsAndFindsTheHandoffWithoutWriting() {
        val vm = vm()
        vm.onShared(url)
        vm.fetch()
        val done = awaitDone(vm)
        val r = done.result as ImportResult.Extracted
        assertEquals("a1", r.conversation.target.id)
        assertTrue(r.handoff is HandoffResult.Found)
        assertEquals(1, transport.calls)
        vm.fetch() // already shown: no second download
        assertEquals(1, transport.calls)
    }

    @Test fun aRepeatedShareOfTheSameLinkChangesNothing() {
        val vm = vm()
        vm.onShared(url)
        vm.fetch()
        val done = awaitDone(vm)
        vm.onShared(url)
        vm.onShared("$url/?ogimg=plain")
        assertEquals(done, vm.state.value)
        assertEquals(1, transport.calls)
    }

    @Test fun aDifferentShareReplacesThePreview() {
        val vm = vm()
        vm.onShared(url)
        vm.fetch()
        awaitDone(vm)
        vm.onShared(other)
        assertEquals(ShareImportUi.Ready(ShareLink.parse(other)!!), vm.state.value)
    }

    @Test fun textWithoutAShareLinkIsRejected() {
        val vm = vm()
        vm.onShared("Buy milk")
        assertEquals(ShareImportUi.NoLink(otherLink = false), vm.state.value)
        vm.onShared("https://evil.example/share/${SharePageFixtures.ID}")
        assertEquals(ShareImportUi.NoLink(otherLink = true), vm.state.value)
        vm.fetch()
        assertEquals(0, transport.calls)
    }

    @Test fun processRecreationRestoresOnlyThePreview() {
        val saved = SavedStateHandle()
        val first = vm(saved)
        first.onShared(url)
        first.fetch()
        awaitDone(first)
        val calls = transport.calls
        val restored = vm(saved)
        assertEquals(ShareImportUi.Ready(link), restored.state.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(calls, transport.calls)
    }

    @Test fun anUnavailableShareCanBeRetriedAfterAFailure() {
        transport.page = { RawResponse(503, "text/html", null, ByteArray(0), false) }
        val vm = vm()
        vm.onShared(url)
        vm.fetch()
        assertEquals(ImportResult.FetchFailed(FetchResult.Refused(503)), awaitDone(vm).result)
        transport.page = { RawResponse(200, "text/html", null, SharePageFixtures.errorPage().toByteArray(), false) }
        vm.fetch()
        assertEquals(ImportResult.Unavailable, awaitDone(vm).result)
        assertEquals(2, transport.calls)
    }
}
