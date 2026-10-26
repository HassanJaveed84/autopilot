package com.lifeadmin.autopilot.share.chatgpt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ImportResult {
    data class Extracted(val conversation: SharedConversation, val handoff: HandoffResult) : ImportResult
    data object Unavailable : ImportResult
    data class FetchFailed(val failure: FetchResult.Failure) : ImportResult
    data class Unrecognized(val reason: String) : ImportResult
}

/** Fetch → extract → look for a handoff in the chosen response. Holds nothing; persists nothing. */
class ShareImporter(
    private val fetcher: ShareFetcher = ShareFetcher(),
    private val parsing: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun import(link: ShareLink): ImportResult {
        val page = when (val r = fetcher.fetch(link)) {
            is FetchResult.Page -> r.html
            is FetchResult.Failure -> return ImportResult.FetchFailed(r)
        }
        return withContext(parsing) {
            when (val e = ShareExtractor.extract(page)) {
                is Extraction.Success -> ImportResult.Extracted(e.conversation, HandoffParser.parse(e.conversation.target.text))
                Extraction.Unavailable -> ImportResult.Unavailable
                is Extraction.Unrecognized -> ImportResult.Unrecognized(e.reason)
            }
        }
    }
}
