package com.lifeadmin.autopilot.ui.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.share.chatgpt.ImportResult
import com.lifeadmin.autopilot.share.chatgpt.ShareImporter
import com.lifeadmin.autopilot.share.chatgpt.ShareLink
import com.lifeadmin.autopilot.share.chatgpt.SharedText
import com.lifeadmin.autopilot.share.chatgpt.SharedTextParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ShareImportUi {
    /** The shared text held no ChatGPT share link. [otherLink]: it had some other link. */
    data class NoLink(val otherLink: Boolean) : ShareImportUi

    /** Preview: the link and an explicit Fetch. Nothing has been downloaded. */
    data class Ready(val link: ShareLink) : ShareImportUi
    data class Fetching(val link: ShareLink) : ShareImportUi
    data class Done(val link: ShareLink, val result: ImportResult) : ShareImportUi

    /**
     * Text selected in another app and sent through its text-selection menu (PROCESS_TEXT spike).
     * [text] is untrusted and at most [ShareImportViewModel.MAX_SELECTION] chars; [receivedChars] is
     * the length that arrived. Shown only: never returned to the sender, stored, sent or parsed.
     */
    data class Selection(val text: String, val receivedChars: Int, val readOnly: Boolean) : ShareImportUi {
        val truncated: Boolean get() = receivedChars > text.length
    }

    /** A text-selection request arrived without any text. */
    data object EmptySelection : ShareImportUi
}

/**
 * ChatGPT share import (proof of concept). Accepting a share only shows a preview: the
 * conversation is downloaded when the user taps Fetch, held in memory for this screen and never
 * saved. Only the validated link survives process death, so a restored screen is a preview again.
 */
class ShareImportViewModel(
    private val saved: SavedStateHandle,
    private val importer: ShareImporter,
) : ViewModel() {
    private val _state = MutableStateFlow<ShareImportUi>(
        restoredSelection()
            ?: saved.get<String>(KEY_LINK)?.let(ShareLink::parse)?.let { ShareImportUi.Ready(it) }
            ?: ShareImportUi.NoLink(otherLink = false),
    )
    val state: StateFlow<ShareImportUi> = _state
    private var job: Job? = null

    /** A share intent's text. The same link again (a repeated or replayed intent) changes nothing. */
    fun onShared(text: String?) {
        val parsed = SharedTextParser.parse(text)
        val link = (parsed as? SharedText.Found)?.link
        if (link != null && link == currentLink()) return
        job?.cancel()
        clearSelection()
        if (link == null) {
            saved.remove<String>(KEY_LINK)
            _state.value = ShareImportUi.NoLink(otherLink = parsed == SharedText.OtherLink)
        } else {
            saved[KEY_LINK] = link.url
            _state.value = ShareImportUi.Ready(link)
        }
    }

    /** The explicit, user-initiated download. Ignored while one is running or once content is shown. */
    fun fetch() {
        val link = when (val s = _state.value) {
            is ShareImportUi.Ready -> s.link
            is ShareImportUi.Done -> if (s.result is ImportResult.Extracted) return else s.link
            else -> return
        }
        _state.value = ShareImportUi.Fetching(link)
        job = viewModelScope.launch {
            val result = importer.import(link)
            if (currentLink() == link) _state.value = ShareImportUi.Done(link, result)
        }
    }

    /**
     * Text from another app's text-selection menu (`EXTRA_PROCESS_TEXT`). Only displayed: no fetch,
     * no parsing, no task change. The same selection again (a repeated intent) changes nothing.
     */
    fun onSelectedText(text: CharSequence?, readOnly: Boolean) {
        val received = text?.toString().orEmpty()
        val next = if (received.isBlank()) ShareImportUi.EmptySelection
        else ShareImportUi.Selection(received.take(MAX_SELECTION).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }, received.length, readOnly)
        if (next == _state.value) return
        job?.cancel()
        saved.remove<String>(KEY_LINK)
        _state.value = next
        if (next is ShareImportUi.Selection) {
            // Kept for process recreation only (in-memory saved state, capped); never written to disk by the app.
            saved[KEY_SELECTION] = next.text
            saved[KEY_SELECTION_CHARS] = next.receivedChars
            saved[KEY_SELECTION_READONLY] = next.readOnly
        } else {
            clearSelection()
        }
    }

    private fun restoredSelection(): ShareImportUi.Selection? {
        val text = saved.get<String>(KEY_SELECTION) ?: return null
        return ShareImportUi.Selection(text, saved.get<Int>(KEY_SELECTION_CHARS) ?: text.length, saved.get<Boolean>(KEY_SELECTION_READONLY) ?: true)
    }

    private fun clearSelection() {
        saved.remove<String>(KEY_SELECTION)
        saved.remove<Int>(KEY_SELECTION_CHARS)
        saved.remove<Boolean>(KEY_SELECTION_READONLY)
    }

    private fun currentLink(): ShareLink? = when (val s = _state.value) {
        is ShareImportUi.Ready -> s.link
        is ShareImportUi.Fetching -> s.link
        is ShareImportUi.Done -> s.link
        is ShareImportUi.NoLink, is ShareImportUi.Selection, ShareImportUi.EmptySelection -> null
    }

    companion object {
        const val KEY_LINK = "share_import_link"
        const val KEY_SELECTION = "share_import_selection"
        const val KEY_SELECTION_CHARS = "share_import_selection_chars"
        const val KEY_SELECTION_READONLY = "share_import_selection_readonly"

        /** Selected text kept and shown, in chars; more is counted but dropped (saved state stays small). */
        const val MAX_SELECTION = 20_000
    }
}
