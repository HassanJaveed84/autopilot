package com.lifeadmin.autopilot.domain.capture

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Reads explicit timing from a captured sentence, offline and deterministically, so the capture
 * sheet can prefill its follow-up and deadline. Deliberately small: tonight / today / tomorrow /
 * weekday names, explicit clock times ("5pm", "19:30", "noon"), "before bed" / "at bedtime",
 * "every night" / "every day", and deadlines introduced by "by" or "due".
 *
 * It never invents a clock time: a day without a time keeps the user's usual nudge times, a bare
 * hour ("at 7") is used only in an evening context ("tonight at 10" → 22:00) and otherwise left
 * for the user, and vague or event wording ("soon", "next week", "before my trip") sets nothing.
 * The text itself is never changed.
 */
object CaptureTiming {

    sealed interface Nudge {
        /** The words this came from, for "from your words: …". */
        val phrase: String

        /** One nudge at [at]. [bedtime]: anchored to the user's bedtime or said "before bed". */
        data class Once(val at: Instant, val bedtime: Boolean, override val phrase: String) : Nudge

        /** A confirmed routine ("every night"). [times] null = the user's usual nudge times. */
        data class Daily(val times: List<LocalTime>?, val bedtime: Boolean, override val phrase: String) : Nudge

        /** A day without a time ("tomorrow", "Monday"): the user's usual nudges, starting that day. */
        data class FromDay(val date: LocalDate, override val phrase: String) : Nudge
    }

    data class Deadline(val spec: DeadlineSpec, val phrase: String)

    data class Reading(
        val nudge: Nudge? = null,
        val deadline: Deadline? = null,
        /** Timing that was noticed but deliberately not used, explained in one line each. */
        val notes: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = nudge == null && deadline == null && notes.isEmpty()
    }

    /** Snooze length for bedtime nudges, so a snooze stays in the evening. */
    const val BEDTIME_SNOOZE_MINUTES = 15

    // Android compiles these with ICU, where a set starting with "[:" opens a POSIX class
    // ("[:alpha:]") and fails to compile: keep ":" out of the first position ("[.:]", not "[:.]").
    // CaptureTimingDeviceTest runs this reader on the emulator to catch any such difference.
    private const val WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|tues|thurs"
    private const val DAY = "today|tonight|this evening|tomorrow night|tomorrow evening|tomorrow|tmrw|(?:(?:this|on|next) )?(?:$WEEKDAY)"
    private const val TIME = """\d{1,2}(?:[.:]\d{2})? ?(?:am|pm)|(?:[01]?\d|2[0-3])[.:][0-5]\d|noon|midday"""

    private val deadlineRx = Regex("""\b(?:due by|due|by) (?:the )?((?:$DAY)(?: (?:at )?(?:$TIME))?|(?:$TIME)(?: (?:on )?(?:$DAY))?)\b""")
    private val dayRx = Regex("""\b($DAY)\b""")
    private val timeRx = Regex("""\b(?:at )?($TIME)(?![\w:])""")
    private val bareHourRx = Regex("""\bat (\d{1,2})\b(?![.:]?\d| ?(?:am|pm))""")
    private val bedtimeRx = Regex("""\b(?:before (?:i go to |going to )?bed(?:time)?|at bedtime|bedtime|before sleep(?:ing)?)\b""")
    private val nightlyRx = Regex("""\b(?:(?:every|each) (?:night|evening)|nightly)\b""")
    private val dailyRx = Regex("""\b(?:(?:every|each) day|daily)\b""")

    fun read(text: String, now: Instant, zone: ZoneId, bedtime: LocalTime): Reading {
        var s = normalize(text)
        if (s.isBlank()) return Reading()
        val today = now.atZone(zone).toLocalDate()
        val notes = mutableListOf<String>()

        // 1) A deadline ("by Monday 5pm") is read first and removed, so its day/time is not also a nudge.
        var deadline: Deadline? = null
        deadlineRx.find(s)?.let { m ->
            val phrase = m.value
            val whenText = m.groupValues[1]
            val day = dayRx.find(whenText)?.groupValues?.get(1)
            val rawTime = timeRx.find(whenText)?.groupValues?.get(1)
            val ambiguous = rawTime != null && isTwelveHourAmbiguous(rawTime)
            if (ambiguous) notes += "“$rawTime” could be morning or evening, so the deadline is set for the day only."
            val time = rawTime?.takeUnless { ambiguous }?.let(::parseExplicitTime)
            when {
                day != null && day.startsWith("next ") -> notes += "“$phrase” could mean two different weeks, so no deadline was set."
                else -> resolveDeadline(day, time, today, now, zone)?.let { deadline = Deadline(it, phrase) }
                    ?: run { notes += "“$phrase” has already passed, so no deadline was set." }
            }
            s = s.replaceRange(m.range, " ".repeat(m.value.length))
        }

        // 2) What and when to nudge.
        val nightly = nightlyRx.find(s)
        val daily = dailyRx.find(s)
        val bed = bedtimeRx.find(s)
        val dayMatch = dayRx.find(s)
        val day = dayMatch?.groupValues?.get(1)
        val evening = nightly != null || bed != null || day in EVENING_DAYS
        val explicit = timeRx.find(s)
        var time: LocalTime? = explicit?.groupValues?.get(1)?.let(::parseExplicitTime)
        var timePhrase = explicit?.value
        if (time == null) bareHourRx.find(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (evening && h in 1..11) { time = LocalTime.of(h + 12, 0); timePhrase = m.value }
            else if (h in 0..23) notes += "“${m.value}” could be morning or evening — choose the time in the follow-up."
        }
        // An ambiguous "7:30" (no am/pm, hour 1–12) reads as evening only in an evening context.
        if (explicit != null && time != null && evening && isTwelveHourAmbiguous(explicit.groupValues[1]) && time!!.hour < 12) {
            time = time!!.plusHours(12)
        } else if (explicit != null && time != null && !evening && isTwelveHourAmbiguous(explicit.groupValues[1]) && time!!.hour in 1..11) {
            notes += "“${explicit.value}” could be morning or evening — choose the time in the follow-up."
            time = null; timePhrase = null
        }

        fun phraseOf(vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" ").trim()

        val nudge: Nudge? = when {
            nightly != null || daily != null -> {
                val rec = (nightly ?: daily)!!.value
                when {
                    time != null -> Nudge.Daily(listOf(time!!), bedtime = bed != null || nightly != null, phrase = phraseOf(rec, timePhrase))
                    nightly != null || bed != null -> Nudge.Daily(listOf(bedtime), bedtime = true, phrase = phraseOf(rec, bed?.value))
                    else -> Nudge.Daily(null, bedtime = false, phrase = rec)
                }
            }
            bed != null -> {
                val at = time ?: bedtime
                val date = day?.let { dateOf(it, today, at, now, zone) }
                when {
                    day != null && day.startsWith("next ") -> null.also { notes += "“$day” could mean two different weeks — choose the day in the follow-up." }
                    date != null -> LocalTimeResolver.resolve(date, at, zone).takeIf { it > now }?.let { Nudge.Once(it, bedtime = true, phrase = phraseOf(day, bed.value, timePhrase)) }
                        ?: null.also { notes += "${at.format()} has already passed today, so no nudge was set from “${bed.value}”." }
                    else -> {
                        val tonight = LocalTimeResolver.resolve(today, at, zone)
                        Nudge.Once(if (tonight > now) tonight else LocalTimeResolver.resolve(today.plusDays(1), at, zone), bedtime = true, phrase = phraseOf(bed.value, timePhrase))
                    }
                }
            }
            time != null -> {
                val t = time!!
                when {
                    day != null && day.startsWith("next ") -> null.also { notes += "“$day” could mean two different weeks — choose the day in the follow-up." }
                    day != null -> {
                        val date = dateOf(day, today, t, now, zone)
                        LocalTimeResolver.resolve(date, t, zone).takeIf { it > now }?.let { Nudge.Once(it, bedtime = false, phrase = phraseOf(day, timePhrase)) }
                            ?: null.also { notes += "“${phraseOf(day, timePhrase)}” has already passed, so no nudge was set." }
                    }
                    else -> {
                        val todayAt = LocalTimeResolver.resolve(today, t, zone)
                        Nudge.Once(if (todayAt > now) todayAt else LocalTimeResolver.resolve(today.plusDays(1), t, zone), bedtime = false, phrase = timePhrase.orEmpty())
                    }
                }
            }
            day != null -> when {
                day.startsWith("next ") -> null.also { notes += "“$day” could mean two different weeks — choose the day in the follow-up." }
                day in EVENING_DAYS || day == "today" -> null.also { notes += "No time given for “$day” — choose one in the follow-up, or say a time like “at 8pm”." }
                else -> Nudge.FromDay(dateOf(day, today, null, now, zone), phrase = day)
            }
            else -> null
        }
        return Reading(nudge, deadline, notes.distinct())
    }

    /**
     * The follow-up a reading proposes. Bedtime nudges snooze for [BEDTIME_SNOOZE_MINUTES] so a
     * snooze stays in the evening; [ringInQuietHours] is only ever the user's explicit choice.
     */
    fun policyFor(nudge: Nudge, settings: AppSettings, now: Instant, zone: ZoneId, ringInQuietHours: Boolean = false): ReminderPolicy {
        val today = now.atZone(zone).toLocalDate()
        val blank = ReminderPolicy(id = "", taskId = "", lastEditedAt = now, ringInQuietHours = ringInQuietHours)
        return when (nudge) {
            is Nudge.Once -> blank.copy(
                enabled = true, kind = ReminderKind.ONCE, atInstant = nudge.at,
                snoozeDefaultMinutes = if (nudge.bedtime) BEDTIME_SNOOZE_MINUTES else null,
            )
            is Nudge.Daily -> blank.copy(
                enabled = true, kind = ReminderKind.DAILY_TIMES, dayInterval = 1, startLocalDate = today, routine = true,
                times = nudge.times ?: ReminderPresets.applyTo(blank, settings.defaultPreset, today).times,
                snoozeDefaultMinutes = if (nudge.bedtime) BEDTIME_SNOOZE_MINUTES else null,
            )
            is Nudge.FromDay -> ReminderPresets.applyTo(blank, settings.defaultPreset, today).copy(enabled = true, startLocalDate = nudge.date)
        }
    }

    // ------------------------------------------------------------------ helpers

    private val EVENING_DAYS = setOf("tonight", "this evening", "tomorrow night", "tomorrow evening")

    private fun normalize(text: String): String = text.lowercase()
        .replace('’', '\'').replace('‘', '\'')
        .replace(Regex("""a\.m\.?"""), "am").replace(Regex("""p\.m\.?"""), "pm")
        .replace(Regex("""[^\p{L}\p{N}:.' ]+"""), " ")
        .replace(Regex("""\.(?!\d)"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** "7:30" or "7.30" with an hour 1–12 and no am/pm: a 24-hour reading would be a guess. */
    private fun isTwelveHourAmbiguous(raw: String): Boolean {
        if (raw.contains("am") || raw.contains("pm") || raw == "noon" || raw == "midday") return false
        val h = raw.split(':', '.').first().toIntOrNull() ?: return false
        return h in 1..12 && !raw.startsWith("0")
    }

    private fun parseExplicitTime(raw: String): LocalTime? {
        val r = raw.replace(" ", "")
        if (r == "noon" || r == "midday") return LocalTime.NOON
        val m = Regex("""^(\d{1,2})(?:[.:](\d{2}))?(am|pm)?$""").find(r) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (min > 59) return null
        when (ap) {
            "am" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            "pm" -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
            else -> if (h > 23 || m.groupValues[2].isEmpty()) return null
        }
        return LocalTime.of(h, min)
    }

    private fun weekday(word: String): DayOfWeek? = when (word.removePrefix("this ").removePrefix("on ").removePrefix("next ")) {
        "monday" -> DayOfWeek.MONDAY
        "tuesday", "tues" -> DayOfWeek.TUESDAY
        "wednesday" -> DayOfWeek.WEDNESDAY
        "thursday", "thurs" -> DayOfWeek.THURSDAY
        "friday" -> DayOfWeek.FRIDAY
        "saturday" -> DayOfWeek.SATURDAY
        "sunday" -> DayOfWeek.SUNDAY
        else -> null
    }

    /**
     * The calendar day a day word means. A weekday is the coming one, today included when the
     * stated time is still ahead (or no time was given); otherwise the same weekday next week.
     */
    private fun dateOf(day: String, today: LocalDate, time: LocalTime?, now: Instant, zone: ZoneId): LocalDate = when (day) {
        "today", "tonight", "this evening" -> today
        "tomorrow", "tmrw", "tomorrow night", "tomorrow evening" -> today.plusDays(1)
        else -> {
            val dow = weekday(day) ?: return today
            val d = today.with(TemporalAdjusters.nextOrSame(dow))
            if (d == today && time != null && LocalTimeResolver.resolve(d, time, zone) <= now) d.plusWeeks(1) else d
        }
    }

    private fun resolveDeadline(day: String?, time: LocalTime?, today: LocalDate, now: Instant, zone: ZoneId): DeadlineSpec? {
        if (day == null && time == null) return null
        if (time == null) {
            val date = dateOf(day!!, today, null, now, zone)
            return DeadlineSpec(DeadlineType.DATE_ONLY, date = date)
        }
        val date = when {
            day != null -> dateOf(day, today, time, now, zone)
            LocalTimeResolver.resolve(today, time, zone) > now -> today
            else -> today.plusDays(1)
        }
        val at = LocalTimeResolver.resolve(date, time, zone)
        return if (at > now) DeadlineSpec(DeadlineType.EXACT, instant = at) else null
    }

    private fun LocalTime.format(): String = "%02d:%02d".format(hour, minute)
}
