package com.lifeadmin.autopilot.ai.research

import java.net.URI

/**
 * Conservative source tiering and URL safety for citations and search results.
 *
 * A host name is not evidence of who runs a site: look-alike domains, user-controlled subdomains
 * and brand words in a domain are all cheap. The only tier claim the app makes is *Official* for
 * recognized government domains, matched on whole host labels; everything else is *Web page*.
 */
object Sources {
    /** Public government suffixes, matched on label boundaries (`gov.uk` or `*.gov.uk`, never `*gov.uk`). */
    private val governmentSuffixes = listOf(
        "gov", "mil",
        "gov.uk", "gov.scot", "gov.wales", "nhs.uk",
        "gov.au", "gov.ie", "gov.in", "gov.sg", "gov.za", "gov.br",
        "gc.ca", "canada.ca", "govt.nz", "gouv.fr", "bund.de", "europa.eu", "go.jp", "gob.es", "gob.mx",
    )

    /** Host name of a URL in lower case, or null when it cannot be parsed. */
    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * OFFICIAL only for a recognized government domain on a label boundary; otherwise WEB. Operator
     * or brand names appearing in a host never qualify — a look-alike can carry any name.
     */
    fun tier(url: String): SourceTier {
        val h = host(url) ?: return SourceTier.WEB
        if (h.startsWith("[") || isIpLiteral(h)) return SourceTier.WEB
        return if (isGovernmentHost(h)) SourceTier.OFFICIAL else SourceTier.WEB
    }

    private fun isGovernmentHost(host: String): Boolean = governmentSuffixes.any { suffix ->
        val multiLabel = suffix.contains('.')
        (multiLabel && host == suffix) || host.endsWith(".$suffix")
    }

    /**
     * Only `http`/`https` URLs with a public host may be handed to the browser. Returns the trimmed
     * URL or null. (The UI converts to an Android Uri; this stays Android-free for tests.)
     */
    fun safeExternalUrl(raw: String): String? {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.userInfo != null) return null
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || isIpLiteral(host)) return null
        return trimmed
    }

    private fun isIpLiteral(host: String): Boolean =
        host.startsWith("[") || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)
}
