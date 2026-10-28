package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.WebLink
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * Structured `ap=v1` / `ap=v2` handoff fragment (docs/CHATGPT_APP_LINK_HANDOFF.md, docs/CHATGPT_ROUND_TRIP_REPORT.md).
 * On the import route (ImportLinkParser) a validated handoff becomes a pending proposal set through
 * HandoffProposalBridge, never a direct task write.
 *
 * Fragment contract, `ap=v1` first, then `key=value` pairs joined by `&`:
 *   r  opaque task reference, optional, at most once (added for the round trip; links without it stay valid)
 *   t  task title, exactly once
 *   d  decision, s  step, n  note: each 0..n times, order kept, at least one item overall
 * Values are percent-encoded UTF-8, decoded exactly once. Keys are lowercase and case-sensitive.
 *
 * `ap=v2` (Rich Steps, v0.2.1, docs/experiments/rich-steps-handoff/REPORT.md) is v1 plus:
 *   sd  one paragraph of a step's description, 0..4, directly after its `s` (or another detail of it)
 *   sl  "<label> <http(s) address>" or "<address>", 0..5, directly after its `s`; the first is the main link
 *   end=1, exactly once and last, so a link cut short anywhere is refused instead of read as shorter
 * and one tolerance: a raw `=` inside a value is kept (the key already ended at the first `=`).
 * `ap=v1` is read exactly as before: sd/sl/end are unknown fields there.
 */

/** A step's details from an `ap=v2` link: description paragraphs and links, in the order ChatGPT wrote them. */
@Serializable
data class HandoffStepDetails(val description: List<String> = emptyList(), val links: List<StepLink> = emptyList()) {
    val isEmpty: Boolean get() = description.isEmpty() && links.isEmpty()
}

data class LinkHandoff(
    val task: String,
    val decisions: List<String>,
    val steps: List<String>,
    val notes: List<String>,
    /** Copied by ChatGPT from the prompt; only an identifier, never trusted on its own ([TaskRef]). */
    val ref: String? = null,
    /** The `ap=` version the link was written in: 1 or 2. */
    val version: Int = 1,
    /** v2 only: details of the step at that index of [steps]; plain steps have no entry. */
    val stepDetails: Map<Int, HandoffStepDetails> = emptyMap(),
) {
    fun details(stepIndex: Int): HandoffStepDetails = stepDetails[stepIndex] ?: HandoffStepDetails()

    /** Every `key=value` pair in the fragment, including `ap=` and v2's `end=1`. */
    val fieldCount: Int get() = 2 + (if (ref != null) 1 else 0) + decisions.size + steps.size + notes.size +
        stepDetails.values.sumOf { it.description.size + it.links.size } + (if (version >= 2) 1 else 0)

    /** The canonical link: every byte outside the URL-unreserved set is percent-encoded. */
    fun toUrl(host: String, path: String = ImportLinkParser.PATH): String = buildString {
        append("https://").append(host).append(path).append("#ap=v").append(version)
        ref?.let { append("&r=").append(HandoffFragment.encode(it)) }
        append("&t=").append(HandoffFragment.encode(task))
        decisions.forEach { append("&d=").append(HandoffFragment.encode(it)) }
        steps.forEachIndexed { i, step ->
            append("&s=").append(HandoffFragment.encode(step))
            stepDetails[i]?.let { d ->
                d.description.forEach { append("&sd=").append(HandoffFragment.encode(it)) }
                d.links.forEach { l -> append("&sl=").append(HandoffFragment.encode(listOfNotNull(l.label, l.url).joinToString(" "))) }
            }
        }
        notes.forEach { append("&n=").append(HandoffFragment.encode(it)) }
        if (version >= 2) append("&").append(HandoffFragment.END)
    }
}

/** Why a structured fragment was refused. Shown by category and field position; values are never echoed. */
enum class HandoffProblem {
    UNSUPPORTED_VERSION, MALFORMED_FIELD, UNKNOWN_FIELD, DUPLICATE_FIELD, EMPTY_VALUE, BAD_ENCODING,
    AMBIGUOUS_PLUS, DISALLOWED_CHARACTER, EDGE_WHITESPACE, VALUE_TOO_LONG, TOO_MANY_ITEMS, MISSING_TASK, NO_ITEMS,

    /** v2: a `sd`/`sl` that doesn't directly follow its step. */
    DETAIL_WITHOUT_STEP,

    /** v2: a `sl` whose address isn't an http(s) web address (or whose label is too long). */
    BAD_LINK,

    /** v2: the link doesn't end with `end=1` (cut short, or not finished). */
    INCOMPLETE,
}

/** [field] is the 1-based position of the offending `key=value` pair, when one pair is to blame. */
data class HandoffIssue(val problem: HandoffProblem, val field: Int?, val fieldsReceived: Int)

sealed interface HandoffParseResult {
    data class Valid(val handoff: LinkHandoff) : HandoffParseResult
    data class Invalid(val issue: HandoffIssue) : HandoffParseResult
}

object HandoffFragment {
    const val PREFIX = "ap="
    const val MAX_TASK = 120
    const val MAX_ITEM = 200
    const val MAX_REF = 64
    const val MAX_DECISIONS = 5
    const val MAX_STEPS = 10
    const val MAX_NOTES = 5
    const val MAX_PARAGRAPH = 300
    const val MAX_PARAGRAPHS = 4
    const val MAX_LINKS_PER_STEP = 5
    const val END = "end=1"

    /**
     * Parses a raw (still percent-encoded) fragment that starts with [PREFIX]. Splitting on `&` and the
     * first `=` happens before decoding, so `%26` and `%3D` stay inside a value.
     */
    fun parse(rawFragment: String): HandoffParseResult {
        val pairs = rawFragment.split('&')
        fun invalid(problem: HandoffProblem, field: Int? = null) = HandoffParseResult.Invalid(HandoffIssue(problem, field, pairs.size))
        val version = when (pairs.first()) {
            "ap=v1" -> 1
            "ap=v2" -> 2
            else -> return invalid(HandoffProblem.UNSUPPORTED_VERSION, 1)
        }
        // v2 ends with exactly `end=1`; anything else means the link was cut short or never finished.
        val last = if (version == 2) {
            if (pairs.size < 2 || pairs.last() != END) return invalid(HandoffProblem.INCOMPLETE)
            pairs.size - 1
        } else {
            pairs.size
        }
        var task: String? = null
        var ref: String? = null
        val decisions = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val details = linkedMapOf<Int, HandoffStepDetails>()
        // True right after an `s` and its details: `sd`/`sl` may only appear there.
        var detailsOpen = false
        for (i in 1 until last) {
            val field = i + 1
            val pair = pairs[i]
            val eq = pair.indexOf('=')
            if (eq <= 0) return invalid(HandoffProblem.MALFORMED_FIELD, field)
            val key = pair.substring(0, eq)
            if (version == 2 && (key == "sd" || key == "sl")) {
                if (!detailsOpen) return invalid(HandoffProblem.DETAIL_WITHOUT_STEP, field)
                val max = if (key == "sd") MAX_PARAGRAPH else WebLink.MAX_LABEL + 1 + WebLink.MAX_URL
                val value = when (val decoded = decode(pair.substring(eq + 1), max, allowRawEquals = true)) {
                    is Decoded.Ok -> decoded.value
                    is Decoded.Bad -> return invalid(decoded.problem, field)
                }
                val index = steps.lastIndex
                val d = details[index] ?: HandoffStepDetails()
                details[index] = if (key == "sd") {
                    if (d.description.size == MAX_PARAGRAPHS) return invalid(HandoffProblem.TOO_MANY_ITEMS, field)
                    d.copy(description = d.description + value)
                } else {
                    if (d.links.size == MAX_LINKS_PER_STEP) return invalid(HandoffProblem.TOO_MANY_ITEMS, field)
                    d.copy(links = d.links + (parseLink(value) ?: return invalid(HandoffProblem.BAD_LINK, field)))
                }
                continue
            }
            val (list, cap) = when (key) {
                "t", "r" -> null to 1
                "d" -> decisions to MAX_DECISIONS
                "s" -> steps to MAX_STEPS
                "n" -> notes to MAX_NOTES
                "ap" -> return invalid(HandoffProblem.DUPLICATE_FIELD, field)
                "end" -> return invalid(if (version == 2) HandoffProblem.DUPLICATE_FIELD else HandoffProblem.UNKNOWN_FIELD, field)
                else -> return invalid(HandoffProblem.UNKNOWN_FIELD, field)
            }
            detailsOpen = key == "s"
            val max = when (key) { "t" -> MAX_TASK; "r" -> MAX_REF; else -> MAX_ITEM }
            val value = when (val decoded = decode(pair.substring(eq + 1), max, allowRawEquals = version >= 2)) {
                is Decoded.Ok -> decoded.value
                is Decoded.Bad -> return invalid(decoded.problem, field)
            }
            if (key == "r") {
                if (ref != null) return invalid(HandoffProblem.DUPLICATE_FIELD, field)
                ref = value
            } else if (list == null) {
                if (task != null) return invalid(HandoffProblem.DUPLICATE_FIELD, field)
                task = value
            } else {
                if (list.size == cap) return invalid(HandoffProblem.TOO_MANY_ITEMS, field)
                list += value
            }
        }
        if (task == null) return invalid(HandoffProblem.MISSING_TASK)
        if (decisions.isEmpty() && steps.isEmpty() && notes.isEmpty()) return invalid(HandoffProblem.NO_ITEMS)
        return HandoffParseResult.Valid(LinkHandoff(task, decisions, steps, notes, ref, version, details))
    }

    /** `sl`: "<label> <address>" or "<address>". The address is the last space-separated token and must be http(s). */
    fun parseLink(value: String): StepLink? {
        val cut = value.lastIndexOf(' ')
        val rawUrl = if (cut < 0) value else value.substring(cut + 1)
        val label = if (cut < 0) null else WebLink.cleanLabel(value.substring(0, cut))
        if (label != null && label.codePointCount(0, label.length) > WebLink.MAX_LABEL) return null
        val url = (WebLink.check(rawUrl) as? WebLink.Checked.Ok)?.url ?: return null
        return StepLink(url, label)
    }

    /** Percent-encodes UTF-8, leaving only `A–Z a–z 0–9 - . _ ~` as they are. */
    fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isUnreserved()) append(c) else append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
        }
    }

    private sealed interface Decoded {
        data class Ok(val value: String) : Decoded
        data class Bad(val problem: HandoffProblem) : Decoded
    }

    /**
     * One strict pass: `%XX` becomes a byte, URL-legal ASCII is kept, raw non-ASCII (an IRI) is kept as
     * UTF-8, and the bytes must form valid UTF-8. A raw `+` is refused rather than guessed: form
     * encoding reads it as a space, RFC 3986 as a plus sign.
     */
    private fun decode(raw: String, maxCodePoints: Int, allowRawEquals: Boolean = false): Decoded {
        if (raw.isEmpty()) return Decoded.Bad(HandoffProblem.EMPTY_VALUE)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '%' -> {
                    val hi = HEX.indexOf(raw.getOrNull(i + 1)?.uppercaseChar() ?: ' ')
                    val lo = HEX.indexOf(raw.getOrNull(i + 2)?.uppercaseChar() ?: ' ')
                    if (hi < 0 || lo < 0) return Decoded.Bad(HandoffProblem.BAD_ENCODING)
                    bytes.write(hi * 16 + lo)
                    i += 3
                    continue
                }
                c == '+' -> return Decoded.Bad(HandoffProblem.AMBIGUOUS_PLUS)
                c == '=' && allowRawEquals -> bytes.write(c.code)
                c.isUnreserved() || c in RAW_ALLOWED -> bytes.write(c.code)
                c.code >= 0x80 && !c.isSurrogate() -> bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                c.isHighSurrogate() && raw.getOrNull(i + 1)?.isLowSurrogate() == true -> {
                    bytes.write(raw.substring(i, i + 2).toByteArray(Charsets.UTF_8))
                    i += 2
                    continue
                }
                c.code >= 0x80 -> return Decoded.Bad(HandoffProblem.BAD_ENCODING)
                else -> return Decoded.Bad(HandoffProblem.DISALLOWED_CHARACTER)
            }
            i++
        }
        val value = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            return Decoded.Bad(HandoffProblem.BAD_ENCODING)
        }
        if (value.isBlank()) return Decoded.Bad(HandoffProblem.EMPTY_VALUE)
        if (value.codePoints().anyMatch(::isDisallowed)) return Decoded.Bad(HandoffProblem.DISALLOWED_CHARACTER)
        if (value.first().isWhitespace() || value.last().isWhitespace()) {
            return Decoded.Bad(HandoffProblem.EDGE_WHITESPACE)
        }
        if (value.codePointCount(0, value.length) > maxCodePoints) return Decoded.Bad(HandoffProblem.VALUE_TOO_LONG)
        return Decoded.Ok(value)
    }

    /** Line breaks, tabs and other controls, and bidi overrides that could reorder what the screen shows. */
    private fun isDisallowed(cp: Int): Boolean =
        Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x061C || cp == 0x200E || cp == 0x200F ||
            cp in 0x202A..0x202E || cp in 0x2066..0x2069

    private fun Char.isUnreserved() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

    /** RFC 3986 fragment characters other than the `&`, `=` and `+` separators and the escape `%`. */
    private const val RAW_ALLOWED = "!$'()*,;:@/?"
    private const val HEX = "0123456789ABCDEF"
}
