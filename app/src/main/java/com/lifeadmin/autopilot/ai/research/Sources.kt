package com.lifeadmin.autopilot.ai.research

import java.net.URI

/** Conservative source tiering and URL safety for citations and search results. */
object Sources {
    private val governmentSuffixes = listOf(
        ".gov", ".gov.uk", ".gov.au", ".gov.ie", ".gov.in", ".gov.sg", ".gov.za", ".gov.br", ".gov.scot", ".gov.wales",
        ".gc.ca", ".canada.ca", ".govt.nz", ".gouv.fr", ".bund.de", ".europa.eu", ".go.jp", ".gob.es", ".gob.mx", ".nhs.uk", ".mil",
    )

    /** Host name of a URL in lower case, or null when it cannot be parsed. */
    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * OFFICIAL only when it can be determined conservatively: a government domain, or a domain that
     * contains the operator/service the user typed (at least 4 letters). Everything else is WEB.
     */
    fun tier(url: String, details: Map<DetailField, String>): SourceTier {
        val h = host(url) ?: return SourceTier.WEB
        if (governmentSuffixes.any { h == it.removePrefix(".") || h.endsWith(it) }) return SourceTier.OFFICIAL
        val operator = details[DetailField.OPERATOR]?.let(::compact).orEmpty()
        if (operator.length >= 4 && compact(h.substringBeforeLast('.')).contains(operator)) return SourceTier.OFFICIAL
        return SourceTier.WEB
    }

    private fun compact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

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
