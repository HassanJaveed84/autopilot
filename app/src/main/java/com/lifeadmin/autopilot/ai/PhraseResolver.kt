package com.lifeadmin.autopilot.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Deterministic interpretation of common date phrases, used to check (and override) the model's
 * date arithmetic, and to stop vague or event-dependent wording from becoming a firm date.
 */
object PhraseResolver {
    enum class Kind {
        /** A specific day the app can compute itself ("tomorrow", "next Friday", "in 3 days"). */
        PRECISE,

        /** Timing that is real but fuzzy ("soon", "next weekend") — never a firm deadline. */
        VAGUE,

        /** Depends on an event whose date the app doesn't know ("before my trip", "when they reply"). */
        EVENT,

        /** Nothing recognised; the model's value is kept (and labelled as such). */
        UNKNOWN,
    }

    /** [alternative] is a second plausible reading ("next Friday" said early in the week). */
    data class Resolution(val kind: Kind, val date: LocalDate? = null, val alternative: LocalDate? = null)

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val eventPatterns = listOf(
        Regex("""\b(before|after|until|till|by)\s+(my|our|the|his|her|their)\s+\w+"""),
        Regex("""\b(when|once|after|as soon as)\s+(they|he|she|it|we|you|i)\s+(reply|replies|respond|responds|answer|answers|get back|gets back|hear|call|calls|confirm|confirms|send|sends|arrive|arrives|pay|pays)"""),
        Regex("""\bwhen\s+i\s+(hear|get)\b"""),
        Regex("""\b(once|when)\s+\w+\s+(replies|responds|confirms|answers|arrives|gets back)"""),
    )

    private val vaguePatterns = listOf(
        Regex("""\b(soon|soonish|sometime|some time|someday|eventually|later|asap|when i can|early enough|in good time|at some point|in a while)\b"""),
        Regex("""\b(this|next|coming)\s+(weekend|week|month|year)\b"""),
        Regex("""\b(end of|later this|early next|mid)\s*-?\s*(the\s+)?(week|month|year)\b"""),
        Regex("""\bin\s+(a few|a couple of|some)\s+(days|weeks|months)\b"""),
    )

    fun classify(phrase: String, today: LocalDate): Resolution {
        val p = phrase.lowercase().trim()
        if (p.isEmpty()) return Resolution(Kind.UNKNOWN)
        if (eventPatterns.any { it.containsMatchIn(p) }) return Resolution(Kind.EVENT)
        precise(p, today)?.let { return it }
        if (vaguePatterns.any { it.containsMatchIn(p) }) return Resolution(Kind.VAGUE)
        return Resolution(Kind.UNKNOWN)
    }

    private fun precise(p: String, today: LocalDate): Resolution? {
        fun on(d: LocalDate) = Resolution(Kind.PRECISE, d)
        if (Regex("""\bday after tomorrow\b""").containsMatchIn(p)) return on(today.plusDays(2))
        if (Regex("""\b(tomorrow|tmrw|tmr)\b""").containsMatchIn(p)) return on(today.plusDays(1))
        if (Regex("""\b(today|tonight|this evening|this afternoon|this morning)\b""").containsMatchIn(p)) return on(today)
        Regex("""\bin\s+(\d{1,3}|one|two|three|four|five|six|seven)\s+(day|days|week|weeks)\b""").find(p)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: words[m.groupValues[1]] ?: return@let
            return on(if (m.groupValues[2].startsWith("week")) today.plusWeeks(n.toLong()) else today.plusDays(n.toLong()))
        }
        Regex("""\b(next|this|coming|on)?\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri)\b""").find(p)?.let { m ->
            val day = weekdays.getValue(m.groupValues[2])
            // "Friday" / "this Friday" = the coming Friday (strictly after today). "Next Friday"
            // is genuinely ambiguous: the coming one or the one a week later. Both are reported.
            val coming = today.with(TemporalAdjusters.next(day))
            return Resolution(Kind.PRECISE, coming, alternative = if (m.groupValues[1] == "next") coming.plusWeeks(1) else null)
        }
        return null
    }

    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7)
}
