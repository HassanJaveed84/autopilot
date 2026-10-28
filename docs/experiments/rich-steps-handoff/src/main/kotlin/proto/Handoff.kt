package proto

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * Prototype of the `ap=v2` handoff: v1's grammar plus two step-detail keys.
 *   s   step title (as v1)
 *   sd  one paragraph of the step's description; belongs to the nearest `s` before it; 0..4 per step
 *   sl  "<label> <url>" or "<url>"; belongs to the nearest `s` before it; 0..5 per step; first = main link
 * `ap=v1` is read exactly as before (sd/sl there are UNKNOWN_FIELD).
 * One tolerance over v1, v2 only: a raw `=` inside a value is kept (the key ends at the first `=`, so it is
 * unambiguous). It lets `?utm_source=chatgpt.com` style queries through; `&` still has to be %26.
 */

data class StepLink(val label: String?, val url: String)

data class HandoffStep(val title: String, val description: List<String> = emptyList(), val links: List<StepLink> = emptyList()) {
    val hasDetails: Boolean get() = description.isNotEmpty() || links.isNotEmpty()
}

data class LinkHandoff(
    val version: Int,
    val task: String,
    val decisions: List<String>,
    val steps: List<HandoffStep>,
    val notes: List<String>,
    val ref: String? = null,
)

enum class Problem {
    UNSUPPORTED_VERSION, MALFORMED_FIELD, UNKNOWN_FIELD, DUPLICATE_FIELD, EMPTY_VALUE, BAD_ENCODING,
    AMBIGUOUS_PLUS, DISALLOWED_CHARACTER, EDGE_WHITESPACE, VALUE_TOO_LONG, TOO_MANY_ITEMS, MISSING_TASK, NO_ITEMS,
    DETAIL_WITHOUT_STEP, BAD_LINK, INCOMPLETE,
}

sealed interface Parsed {
    data class Valid(val handoff: LinkHandoff) : Parsed
    data class Invalid(val problem: Problem, val field: Int?) : Parsed
}

object HandoffV2 {
    const val MAX_TASK = 120
    const val MAX_ITEM = 200
    const val MAX_REF = 64
    const val MAX_DECISIONS = 5
    const val MAX_STEPS = 10
    const val MAX_NOTES = 5
    const val MAX_PARAGRAPH = 300
    const val MAX_PARAGRAPHS = 4
    const val MAX_LINKS = 5
    const val MAX_LABEL = 80
    const val MAX_LINK_URL = 1_000

    fun parse(rawFragment: String): Parsed {
        val pairs = rawFragment.split('&')
        fun invalid(p: Problem, field: Int? = null) = Parsed.Invalid(p, field)
        val version = when (pairs.first()) { "ap=v1" -> 1; "ap=v2" -> 2; else -> return invalid(Problem.UNSUPPORTED_VERSION, 1) }
        var task: String? = null
        var ref: String? = null
        val decisions = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val steps = mutableListOf<HandoffStep>()
        // v2 ends with exactly `end=1`, so a link cut short anywhere (a renderer, a copy) is refused, never read as shorter
        val last = if (version == 2) {
            if (pairs.size < 2 || pairs.last() != "end=1") return invalid(Problem.INCOMPLETE)
            pairs.size - 1
        } else pairs.size
        var detailsOpen = false
        for (i in 1 until last) {
            val field = i + 1
            val pair = pairs[i]
            val eq = pair.indexOf('=')
            if (eq <= 0) return invalid(Problem.MALFORMED_FIELD, field)
            val key = pair.substring(0, eq)
            val known = if (version == 1) setOf("t", "r", "d", "s", "n") else setOf("t", "r", "d", "s", "n", "sd", "sl")
            if (key == "end") return invalid(Problem.DUPLICATE_FIELD, field)
            if (key == "ap") return invalid(Problem.DUPLICATE_FIELD, field)
            if (key !in known) return invalid(Problem.UNKNOWN_FIELD, field)
            val max = when (key) { "t" -> MAX_TASK; "r" -> MAX_REF; "sd" -> MAX_PARAGRAPH; "sl" -> MAX_LABEL + 1 + MAX_LINK_URL; else -> MAX_ITEM }
            val value = when (val d = decode(pair.substring(eq + 1), max, allowRawEquals = version >= 2)) {
                is Decoded.Ok -> d.value
                is Decoded.Bad -> return invalid(d.problem, field)
            }
            if (key != "sd" && key != "sl") detailsOpen = key == "s"
            else if (!detailsOpen) return invalid(Problem.DETAIL_WITHOUT_STEP, field)
            when (key) {
                "t" -> { if (task != null) return invalid(Problem.DUPLICATE_FIELD, field); task = value }
                "r" -> { if (ref != null) return invalid(Problem.DUPLICATE_FIELD, field); ref = value }
                "d" -> { if (decisions.size == MAX_DECISIONS) return invalid(Problem.TOO_MANY_ITEMS, field); decisions += value }
                "n" -> { if (notes.size == MAX_NOTES) return invalid(Problem.TOO_MANY_ITEMS, field); notes += value }
                "s" -> { if (steps.size == MAX_STEPS) return invalid(Problem.TOO_MANY_ITEMS, field); steps += HandoffStep(value) }
                "sd" -> {
                    val last = steps.lastOrNull() ?: return invalid(Problem.DETAIL_WITHOUT_STEP, field)
                    if (last.description.size == MAX_PARAGRAPHS) return invalid(Problem.TOO_MANY_ITEMS, field)
                    steps[steps.lastIndex] = last.copy(description = last.description + value)
                }
                "sl" -> {
                    val last = steps.lastOrNull() ?: return invalid(Problem.DETAIL_WITHOUT_STEP, field)
                    if (last.links.size == MAX_LINKS) return invalid(Problem.TOO_MANY_ITEMS, field)
                    val link = parseLink(value) ?: return invalid(Problem.BAD_LINK, field)
                    steps[steps.lastIndex] = last.copy(links = last.links + link)
                }
            }
        }
        if (task == null) return invalid(Problem.MISSING_TASK)
        if (decisions.isEmpty() && steps.isEmpty() && notes.isEmpty()) return invalid(Problem.NO_ITEMS)
        return Parsed.Valid(LinkHandoff(version, task, decisions, steps, notes, ref))
    }

    /** "<label> <url>" or "<url>": the URL is the last space-separated token and must be http(s). */
    fun parseLink(value: String): StepLink? {
        val cut = value.lastIndexOf(' ')
        val url = if (cut < 0) value else value.substring(cut + 1)
        val label = if (cut < 0) null else value.substring(0, cut).trim().takeIf { it.isNotEmpty() }
        if (label != null && label.codePointCount(0, label.length) > MAX_LABEL) return null
        return checkUrl(url)?.let { StepLink(label, it) }
    }

    /** http(s) only, an ASCII host with a dot, no user info (`https://bank.com@evil.example`), no spaces. */
    fun checkUrl(raw: String): String? {
        if (raw.length > MAX_LINK_URL) return null
        if (!raw.startsWith("https://", ignoreCase = true) && !raw.startsWith("http://", ignoreCase = true)) return null
        val u = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = u.host ?: return null
        if (u.rawUserInfo != null || !host.contains('.') || host.any { it.code >= 0x80 }) return null
        return raw
    }

    fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isUnreserved()) append(c) else append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
        }
    }

    /** What a model following "keep : / ? , as they are" would write: the RFC 3986 fragment-safe set stays raw. */
    fun encodeTolerant(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isUnreserved() || c in RAW_ALLOWED) append(c) else append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
        }
    }

    private sealed interface Decoded {
        data class Ok(val value: String) : Decoded
        data class Bad(val problem: Problem) : Decoded
    }

    private fun decode(raw: String, maxCodePoints: Int, allowRawEquals: Boolean): Decoded {
        if (raw.isEmpty()) return Decoded.Bad(Problem.EMPTY_VALUE)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '%' -> {
                    val hi = HEX.indexOf(raw.getOrNull(i + 1)?.uppercaseChar() ?: ' ')
                    val lo = HEX.indexOf(raw.getOrNull(i + 2)?.uppercaseChar() ?: ' ')
                    if (hi < 0 || lo < 0) return Decoded.Bad(Problem.BAD_ENCODING)
                    bytes.write(hi * 16 + lo); i += 3; continue
                }
                c == '+' -> return Decoded.Bad(Problem.AMBIGUOUS_PLUS)
                c == '=' && allowRawEquals -> bytes.write(c.code)
                c.isUnreserved() || c in RAW_ALLOWED -> bytes.write(c.code)
                c.code >= 0x80 && !c.isSurrogate() -> bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                c.isHighSurrogate() && raw.getOrNull(i + 1)?.isLowSurrogate() == true -> {
                    bytes.write(raw.substring(i, i + 2).toByteArray(Charsets.UTF_8)); i += 2; continue
                }
                c.code >= 0x80 -> return Decoded.Bad(Problem.BAD_ENCODING)
                else -> return Decoded.Bad(Problem.DISALLOWED_CHARACTER)
            }
            i++
        }
        val value = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) { return Decoded.Bad(Problem.BAD_ENCODING) }
        if (value.isBlank()) return Decoded.Bad(Problem.EMPTY_VALUE)
        if (value.codePoints().anyMatch(::isDisallowed)) return Decoded.Bad(Problem.DISALLOWED_CHARACTER)
        if (value.first().isWhitespace() || value.last().isWhitespace()) return Decoded.Bad(Problem.EDGE_WHITESPACE)
        if (value.codePointCount(0, value.length) > maxCodePoints) return Decoded.Bad(Problem.VALUE_TOO_LONG)
        return Decoded.Ok(value)
    }

    private fun isDisallowed(cp: Int): Boolean =
        Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x061C || cp == 0x200E || cp == 0x200F ||
            cp in 0x202A..0x202E || cp in 0x2066..0x2069

    private fun Char.isUnreserved() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"
    private const val RAW_ALLOWED = "!$'()*,;:@/?"
    private const val HEX = "0123456789ABCDEF"
}

/** Builds a link the way the app's prompt prefix + ChatGPT would. */
fun buildUrl(h: LinkHandoff, enc: (String) -> String = HandoffV2::encode, host: String = "hassanjaveed84.github.io"): String = buildString {
    append("https://").append(host).append("/autopilot/import#ap=v").append(h.version)
    h.ref?.let { append("&r=").append(HandoffV2.encode(it)) }
    append("&t=").append(HandoffV2.encode(h.task))
    h.decisions.forEach { append("&d=").append(enc(it)) }
    h.steps.forEach { s ->
        append("&s=").append(enc(s.title))
        s.description.forEach { append("&sd=").append(enc(it)) }
        s.links.forEach { l -> append("&sl=").append(enc(listOfNotNull(l.label, l.url).joinToString(" "))) }
    }
    h.notes.forEach { append("&n=").append(enc(it)) }
    if (h.version == 2) append("&end=1")
}
