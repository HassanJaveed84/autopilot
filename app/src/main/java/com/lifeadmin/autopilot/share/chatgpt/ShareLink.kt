package com.lifeadmin.autopilot.share.chatgpt

import java.net.URI

/*
 * ChatGPT share-handoff proof of concept (docs/CHATGPT_SHARE_HANDOFF_POC.md). This package is
 * deliberately isolated and replaceable: it knows one URL shape on one host and nothing else.
 */

/** A validated `https://chatgpt.com/share/<id>` link. [id] is lower-case; query and fragment are dropped. */
@JvmInline
value class ShareLink private constructor(val id: String) {
    /** The only URL this module ever requests for the link. */
    val url: String get() = "https://$HOST/share/$id"

    override fun toString(): String = url

    companion object {
        const val HOST = "chatgpt.com"
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val PATH = Regex("/share/(${ID.pattern})/?", RegexOption.IGNORE_CASE)

        /**
         * Strict validation: scheme `https`, host exactly `chatgpt.com` (no subdomains, no user
         * info, default port), path exactly `/share/<id>`. Anything else is not a share link.
         */
        fun parse(raw: String): ShareLink? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.rawUserInfo != null) return null
            if (!uri.host.equals(HOST, ignoreCase = true)) return null
            if (uri.port != -1 && uri.port != 443) return null
            val match = PATH.matchEntire(uri.rawPath ?: return null) ?: return null
            return ShareLink(match.groupValues[1].lowercase())
        }
    }
}

/** What a piece of shared text contained. */
sealed interface SharedText {
    data class Found(val link: ShareLink) : SharedText

    /** Text had links, but none was a ChatGPT share link (other host, other path, `http`, …). */
    data object OtherLink : SharedText

    /** Text had no link at all. */
    data object NoLink : SharedText
}

object SharedTextParser {
    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private const val MAX_TEXT = 8_192

    /** Finds the first valid share link in text from `EXTRA_TEXT` (the app may add a title around it). */
    fun parse(text: String?): SharedText {
        if (text.isNullOrBlank()) return SharedText.NoLink
        val candidates = URL.findAll(text.take(MAX_TEXT)).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }.toList()
        if (candidates.isEmpty()) return SharedText.NoLink
        return candidates.firstNotNullOfOrNull { ShareLink.parse(it) }?.let { SharedText.Found(it) } ?: SharedText.OtherLink
    }
}
