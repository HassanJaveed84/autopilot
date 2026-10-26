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
        saved.get<String>(KEY_LINK)?.let(ShareLink::parse)?.let { ShareImportUi.Ready(it) } ?: ShareImportUi.NoLink(otherLink = false),
    )
    val state: StateFlow<ShareImportUi> = _state
    private var job: Job? = null

    /** A share intent's text. The same link again (a repeated or replayed intent) changes nothing. */
    fun onShared(text: String?) {
        val parsed = SharedTextParser.parse(text)
        val link = (parsed as? SharedText.Found)?.link
        if (link != null && link == currentLink()) return
        job?.cancel()
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

    private fun currentLink(): ShareLink? = when (val s = _state.value) {
        is ShareImportUi.Ready -> s.link
        is ShareImportUi.Fetching -> s.link
        is ShareImportUi.Done -> s.link
        is ShareImportUi.NoLink -> null
    }

    companion object {
        const val KEY_LINK = "share_import_link"
    }
}
