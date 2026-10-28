package com.lifeadmin.autopilot.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * One labelled web link on a step (Rich Steps, v0.2.1). The first link of a step is its main link,
 * shown on the checklist row. Links are only ever opened by an explicit tap, in the browser; the app
 * never fetches them, and opening one never changes the step.
 */
@Serializable
data class StepLink(val url: String, val label: String? = null) {
    /** The destination domain shown next to every link, so the user sees where it goes. */
    val host: String get() = WebLink.displayHost(url)

    /** What the row and the sheet show: the label, or the domain when there is none. */
    val title: String get() = label?.takeIf { it.isNotBlank() } ?: host
}

/** Validation and normalisation shared by the step editor, the ChatGPT handoff and backup import. */
object WebLink {
    const val MAX_URL = 2_000
    const val MAX_LABEL = 80

    /**
     * Tracking parameters removed on save. Only these, exactly: anything else in the query (a product
     * variant, a size, a search) is kept byte for byte, in order.
     */
    private val TRACKING_EXACT = setOf("gclid", "gbraid", "wbraid", "dclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "igshid", "yclid", "twclid", "ttclid")
    private const val TRACKING_PREFIX = "utm_"

    sealed interface Checked {
        data class Ok(val url: String) : Checked
        data class Bad(val reason: String) : Checked
    }

    /**
     * An absolute `http`/`https` address with an ASCII host that has a dot, no user info
     * (`https://bank.example@evil.example`) and no whitespace. [allowMissingScheme] lets the step
     * editor accept `www.example.com/page` as `https://…`; the ChatGPT handoff never guesses.
     * Returns the address with recognised tracking parameters removed.
     */
    fun check(raw: String, allowMissingScheme: Boolean = false): Checked {
        var u = raw.trim()
        if (u.isEmpty()) return Checked.Bad("Add a web address")
        if (u.any { it.isWhitespace() || it.isISOControl() }) return Checked.Bad("A web address can't contain spaces")
        if (!u.startsWith("https://", ignoreCase = true) && !u.startsWith("http://", ignoreCase = true)) {
            if (!allowMissingScheme || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(u)) return Checked.Bad("Use a web address that starts with https://")
            u = "https://$u"
        }
        if (u.length > MAX_URL) return Checked.Bad("That web address is too long")
        val uri = runCatching { URI(u) }.getOrNull() ?: return Checked.Bad("That doesn't look like a web address")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Checked.Bad("Use a web address that starts with https://")
        val host = uri.host ?: return Checked.Bad("That doesn't look like a web address")
        if (uri.rawUserInfo != null) return Checked.Bad("That web address hides where it goes")
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host.any { it.code >= 0x80 }) return Checked.Bad("That doesn't look like a web address")
        return Checked.Ok(stripTracking(u))
    }

    /** Removes only the recognised tracking parameters from the query; the rest keeps its exact bytes and order. */
    fun stripTracking(url: String): String {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash >= 0) url.substring(0, hash) else url
        val fragment = if (hash >= 0) url.substring(hash) else ""
        val q = beforeFragment.indexOf('?')
        if (q < 0) return url
        val base = beforeFragment.substring(0, q)
        val kept = beforeFragment.substring(q + 1).split('&').filter { part ->
            if (part.isEmpty()) return@filter false
            val name = part.substringBefore('=').lowercase()
            !(name.startsWith(TRACKING_PREFIX) || name in TRACKING_EXACT)
        }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    /** The host as people read it: lowercase, without a leading `www.`. */
    fun displayHost(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull() ?: return url
        return host.lowercase().removePrefix("www.")
    }

    /**
     * Two addresses are the same page for duplicate checks when they differ only in scheme, letter
     * case of the host, a leading `www.`, a default port, a trailing `/`, a `#fragment` or tracking
     * parameters. Query parameters that matter (a size, a colour) keep pages apart.
     */
    fun sameKey(url: String): String {
        val u = runCatching { URI(stripTracking(url)) }.getOrNull() ?: return url.trim()
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return url.trim()
        val port = if (u.port == -1 || u.port == 80 || u.port == 443) "" else ":${u.port}"
        val path = (u.rawPath ?: "").trimEnd('/')
        return host + port + path + (u.rawQuery?.let { "?$it" } ?: "")
    }

    fun cleanLabel(raw: String?): String? = raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
}

/** Limits and merging for a step's description and links, shared by every writer. */
object StepDetails {
    const val MAX_DESCRIPTION = 2_000
    const val MAX_LINKS = 10

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(StepLink.serializer())

    /** Storage form of the links column: a JSON array, or null when there are none. */
    fun encodeLinks(links: List<StepLink>): String? = links.takeIf { it.isNotEmpty() }?.let { json.encodeToString(listSerializer, it) }

    fun decodeLinks(raw: String?): List<StepLink> = if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())

    /** Null when blank; otherwise trimmed, with Windows line endings normalised. Throws [IllegalArgumentException] when too long. */
    fun cleanDescription(raw: String?): String? {
        val d = raw?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim()?.ifEmpty { null } ?: return null
        require(d.codePointCount(0, d.length) <= MAX_DESCRIPTION) { "The description is too long (max $MAX_DESCRIPTION characters)" }
        return d
    }

    /**
     * Validated links: each address checked (tracking parameters removed), labels trimmed and capped,
     * duplicates of the same page dropped (the first one wins, so the main link stays main).
     * Throws [IllegalArgumentException] with a readable message.
     */
    fun cleanLinks(raw: List<StepLink>, allowMissingScheme: Boolean = false): List<StepLink> {
        val seen = HashSet<String>()
        val out = ArrayList<StepLink>()
        for (l in raw) {
            val url = when (val c = WebLink.check(l.url, allowMissingScheme)) {
                is WebLink.Checked.Ok -> c.url
                is WebLink.Checked.Bad -> throw IllegalArgumentException(c.reason)
            }
            val label = WebLink.cleanLabel(l.label)
            require(label == null || label.codePointCount(0, label.length) <= WebLink.MAX_LABEL) { "A link label is too long (max ${WebLink.MAX_LABEL} characters)" }
            if (seen.add(WebLink.sameKey(url))) out += StepLink(url, label)
        }
        require(out.size <= MAX_LINKS) { "A step can hold at most $MAX_LINKS links" }
        return out
    }

    /** Paragraphs of a description, for "is this already there?" checks. */
    fun paragraphs(text: String?): List<String> = text.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The part of [added] not already in [existing] (case, spacing and punctuation ignored). */
    fun newParagraphs(existing: String?, added: List<String>, key: (String) -> String): List<String> {
        val have = paragraphs(existing).map(key).toMutableSet()
        return added.map { it.trim() }.filter { it.isNotEmpty() && have.add(key(it)) }
    }

    /** Links from [added] whose page isn't already in [existing]. */
    fun newLinks(existing: List<StepLink>, added: List<StepLink>): List<StepLink> {
        val have = existing.map { WebLink.sameKey(it.url) }.toMutableSet()
        return added.filter { have.add(WebLink.sameKey(it.url)) }
    }

    /** The user's description with [added] appended after a blank line; nothing of theirs is rewritten. */
    fun appendDescription(existing: String?, added: List<String>): String? {
        val mine = existing?.trimEnd()?.takeIf { it.isNotBlank() }
        val more = added.takeIf { it.isNotEmpty() }?.joinToString("\n")
        return listOfNotNull(mine, more).joinToString("\n\n").ifEmpty { null }
    }
}
