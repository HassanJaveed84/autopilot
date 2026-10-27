package com.lifeadmin.autopilot.share.applink

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * Structured handoff experiment on the App Link test route (docs/CHATGPT_APP_LINK_HANDOFF.md).
 * Display-only: a decoded handoff is shown on the test screen and nowhere else. It is not the
 * `autopilot.handoff.v1` JSON format and never reaches HandoffParser or Proposal Review.
 *
 * Fragment contract, `ap=v1` first, then `key=value` pairs joined by `&`:
 *   t  task title, exactly once
 *   d  decision, s  step, n  note: each 0..n times, order kept, at least one item overall
 * Values are percent-encoded UTF-8, decoded exactly once. Keys are lowercase and case-sensitive.
 */

data class LinkHandoff(val task: String, val decisions: List<String>, val steps: List<String>, val notes: List<String>) {
    /** Every `key=value` pair in the fragment, including `ap=v1`. */
    val fieldCount: Int get() = 2 + decisions.size + steps.size + notes.size

    /** The canonical link: every byte outside the URL-unreserved set is percent-encoded. */
    fun toUrl(host: String): String = buildString {
        append("https://").append(host).append(TestLinkParser.PATH).append("#ap=v1&t=").append(HandoffFragment.encode(task))
        decisions.forEach { append("&d=").append(HandoffFragment.encode(it)) }
        steps.forEach { append("&s=").append(HandoffFragment.encode(it)) }
        notes.forEach { append("&n=").append(HandoffFragment.encode(it)) }
    }

    companion object {
        /** The fixed example the real-device test uses. */
        val FIXED_EXAMPLE = LinkHandoff(
            task = "Choose a coffee grinder",
            decisions = listOf("Choose Model A"),
            steps = listOf("Check warranty", "Compare retailer prices"),
            notes = listOf("Budget is \$1,000"),
        )
    }
}

/** Why a structured fragment was refused. Shown by category and field position; values are never echoed. */
enum class HandoffProblem {
    UNSUPPORTED_VERSION, MALFORMED_FIELD, UNKNOWN_FIELD, DUPLICATE_FIELD, EMPTY_VALUE, BAD_ENCODING,
    AMBIGUOUS_PLUS, DISALLOWED_CHARACTER, EDGE_WHITESPACE, VALUE_TOO_LONG, TOO_MANY_ITEMS, MISSING_TASK, NO_ITEMS,
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
    const val MAX_DECISIONS = 5
    const val MAX_STEPS = 10
    const val MAX_NOTES = 5

    /**
     * Parses a raw (still percent-encoded) fragment that starts with [PREFIX]. Splitting on `&` and the
     * first `=` happens before decoding, so `%26` and `%3D` stay inside a value.
     */
    fun parse(rawFragment: String): HandoffParseResult {
        val pairs = rawFragment.split('&')
        fun invalid(problem: HandoffProblem, field: Int? = null) = HandoffParseResult.Invalid(HandoffIssue(problem, field, pairs.size))
        if (pairs.first() != "ap=v1") return invalid(HandoffProblem.UNSUPPORTED_VERSION, 1)
        var task: String? = null
        val decisions = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (i in 1 until pairs.size) {
            val field = i + 1
            val pair = pairs[i]
            val eq = pair.indexOf('=')
            if (eq <= 0) return invalid(HandoffProblem.MALFORMED_FIELD, field)
            val (list, cap) = when (pair.substring(0, eq)) {
                "t" -> null to 1
                "d" -> decisions to MAX_DECISIONS
                "s" -> steps to MAX_STEPS
                "n" -> notes to MAX_NOTES
                "ap" -> return invalid(HandoffProblem.DUPLICATE_FIELD, field)
                else -> return invalid(HandoffProblem.UNKNOWN_FIELD, field)
            }
            val value = when (val decoded = decode(pair.substring(eq + 1), if (list == null) MAX_TASK else MAX_ITEM)) {
                is Decoded.Ok -> decoded.value
                is Decoded.Bad -> return invalid(decoded.problem, field)
            }
            if (list == null) {
                if (task != null) return invalid(HandoffProblem.DUPLICATE_FIELD, field)
                task = value
            } else {
                if (list.size == cap) return invalid(HandoffProblem.TOO_MANY_ITEMS, field)
                list += value
            }
        }
        if (task == null) return invalid(HandoffProblem.MISSING_TASK)
        if (decisions.isEmpty() && steps.isEmpty() && notes.isEmpty()) return invalid(HandoffProblem.NO_ITEMS)
        return HandoffParseResult.Valid(LinkHandoff(task, decisions, steps, notes))
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
    private fun decode(raw: String, maxCodePoints: Int): Decoded {
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
