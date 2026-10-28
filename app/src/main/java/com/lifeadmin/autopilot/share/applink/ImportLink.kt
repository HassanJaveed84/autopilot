package com.lifeadmin.autopilot.share.applink

import java.net.URI

/*
 * The App Link route of the ChatGPT round trip (docs/CHATGPT_ROUND_TRIP_REPORT.md):
 * `https://<host>/autopilot/import#ap=v2&r=<task ref>&t=…&d=…&s=…&sd=…&sl=…&n=…&end=1` (v1 links, without sd/sl/end, still read).
 * Strict envelope checks ([AppLinkEnvelope]) and the strict [HandoffFragment] parser. A valid link
 * only ever becomes a pending proposal set for the user to review; nothing here touches a task.
 */

/** Why an import link was refused, in the terms the import screen explains. Values are never echoed. */
enum class ImportRefusal {
    /** Not an `https` link to the import route on the verified host. */
    NOT_A_LINK,

    /** No `ap=` handoff in the fragment. */
    NO_HANDOFF,

    /** `ap=` with a version this build doesn't read. */
    NEWER_VERSION,

    /** The link, a value or an item count is over its limit. Nothing is cut down to fit. */
    TOO_BIG,

    /** Bad encoding, an unknown or repeated field, a missing title, and so on. */
    MALFORMED,
}

sealed interface ImportLinkResult {
    data class Valid(val handoff: LinkHandoff) : ImportLinkResult
    data class Refused(val reason: ImportRefusal) : ImportLinkResult
}

class ImportLinkParser(private val host: String) {
    fun parse(raw: String?): ImportLinkResult {
        if (raw != null && raw.length > MAX_URL) return ImportLinkResult.Refused(ImportRefusal.TOO_BIG)
        val fragment = when (val e = AppLinkEnvelope.fragment(raw, host, PATH, MAX_URL)) {
            is AppLinkEnvelope.Result.Ok -> e.fragment
            AppLinkEnvelope.Result.Refused -> return ImportLinkResult.Refused(ImportRefusal.NOT_A_LINK)
        }
        if (fragment == null || !fragment.startsWith(HandoffFragment.PREFIX)) return ImportLinkResult.Refused(ImportRefusal.NO_HANDOFF)
        return when (val r = HandoffFragment.parse(fragment)) {
            is HandoffParseResult.Valid -> ImportLinkResult.Valid(r.handoff)
            is HandoffParseResult.Invalid -> ImportLinkResult.Refused(
                when (r.issue.problem) {
                    HandoffProblem.UNSUPPORTED_VERSION -> ImportRefusal.NEWER_VERSION
                    // A v2 link cut short, a detail out of place or a bad web address: all "didn't come through right".
                    HandoffProblem.VALUE_TOO_LONG, HandoffProblem.TOO_MANY_ITEMS -> ImportRefusal.TOO_BIG
                    else -> ImportRefusal.MALFORMED
                },
            )
        }
    }

    companion object {
        const val PATH = "/autopilot/import"

        /** What every `ap=v2` link ends with (see [HandoffFragment.END]). */
        const val END_MARKER = "&" + HandoffFragment.END

        /**
         * A handoff with rich steps is long once percent-encoded: about 300 characters per step with a
         * description and a link (measured, docs/experiments/rich-steps-handoff). The prompt asks
         * ChatGPT to stay under 4,000 characters, so this is headroom. Over it, the link is refused, never cut.
         */
        const val MAX_URL = 8_192

        /** The start of every return link: route, version, the task's reference and its title, already encoded. */
        fun prefix(host: String, ref: String, title: String): String =
            "https://$host$PATH#ap=v2&r=${HandoffFragment.encode(ref)}&t=${HandoffFragment.encode(linkTitle(title))}"

        /** The title only identifies the task, so a long one is shortened to fit `t` rather than making the link invalid. */
        fun linkTitle(title: String): String {
            val t = title.trim().replace(Regex("\\s+"), " ")
            if (t.codePointCount(0, t.length) <= HandoffFragment.MAX_TASK) return t
            return t.substring(0, t.offsetByCodePoints(0, HandoffFragment.MAX_TASK - 1)).trimEnd() + "…"
        }
    }
}

/**
 * The checks the App Link route applies before reading its fragment: scheme exactly `https`, host
 * exactly [host] (case-insensitive, no subdomains), no user info, default port, path exactly as
 * given, and a length cap applied before parsing. A query string is ignored and never read (chat
 * apps may append tracking such as `utm_source`). Returns the raw (still percent-encoded) fragment.
 */
internal object AppLinkEnvelope {
    sealed interface Result {
        data class Ok(val fragment: String?) : Result
        data object Refused : Result
    }

    fun fragment(raw: String?, host: String, path: String, maxUrl: Int): Result {
        if (raw == null || raw.length > maxUrl) return Result.Refused
        val uri = runCatching { URI(raw) }.getOrNull() ?: return Result.Refused
        if (!uri.scheme.equals("https", ignoreCase = true)) return Result.Refused
        if (uri.rawUserInfo != null || uri.port.let { it != -1 && it != 443 } || !uri.host.equals(host, ignoreCase = true)) return Result.Refused
        if (uri.rawPath != path) return Result.Refused
        return Result.Ok(uri.rawFragment)
    }
}

/**
 * The opaque task reference carried by `r`: the task's own id. It identifies, it never authorises:
 * a match only preselects the destination, which the user still sees and can change, and nothing is
 * written without an apply in Proposal Review.
 */
object TaskRef {
    private val FORM = Regex("[A-Za-z0-9_-]{1,64}")

    fun of(taskId: String): String = taskId

    /** Same character rules as every other id the app accepts from outside (IntentContract). */
    fun isWellFormed(ref: String?): Boolean = ref != null && FORM.matches(ref)
}
