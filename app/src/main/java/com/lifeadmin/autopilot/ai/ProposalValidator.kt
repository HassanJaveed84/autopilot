package com.lifeadmin.autopilot.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns untrusted model output into a reviewable [ProposalDraft]. Deterministic and pure: the
 * same input always gives the same draft. Anything invalid is dropped with a visible notice;
 * anything uncertain is shown but not pre-selected. Nothing here writes data.
 */
object ProposalValidator {
    const val MAX_STEPS = 6
    const val MAX_TITLE = 300
    const val MAX_STEP = 300
    const val MAX_NOTES = 10_000
    const val MAX_TIMES = 12
    private const val MAX_LIST_ITEMS = 6
    private const val MAX_QUESTIONS = 3
    private const val MAX_LIST_TEXT = 300
    private const val MAX_YEARS_AHEAD = 5L
    private const val MAX_ONCE_MINUTES = 60 * 24 * 366

    private val dayLabel = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    data class Input(
        val raw: RawPlan,
        val originalText: String,
        val mode: PlanningMode,
        val now: LocalDateTime,
        val quietHours: Pair<LocalTime, LocalTime>?,
        val proposalId: String,
        val model: String,
        /** FOLLOW_THROUGH: steps already on the task (to avoid duplicates). */
        val existingSteps: List<String> = emptyList(),
        val existingTitle: String? = null,
        val existingNotes: String? = null,
        val targetTaskId: String? = null,
        val targetUpdatedAtMillis: Long? = null,
    )

    fun validate(input: Input): ProposalDraft {
        val raw = input.raw
        val today = input.now.toLocalDate()
        val notices = mutableListOf<Notice>()
        val assumptions = cleanList(raw.assumptions, MAX_LIST_ITEMS).toMutableList()
        val questions = cleanList(raw.questions, MAX_QUESTIONS).toMutableList()
        val text = input.originalText

        // Title
        var title = oneLine(raw.title).take(MAX_TITLE)
        if (title.isEmpty()) {
            title = input.existingTitle ?: oneLine(text).take(120)
            if (input.mode == PlanningMode.CAPTURE) notices += Notice(NoticeLevel.WARNING, "The AI didn't return a title, so your own words are used.")
        }

        val notes = raw.notes.trim().take(MAX_NOTES)

        // Steps: trimmed, de-duplicated (also against existing steps), capped.
        val seen = input.existingSteps.map(::normalizeKey).toMutableSet()
        val steps = mutableListOf<DraftStep>()
        var duplicates = 0
        var overlong = 0
        for (s in raw.steps) {
            val clean = oneLine(s)
            if (clean.isEmpty()) continue
            if (!seen.add(normalizeKey(clean))) { duplicates++; continue }
            if (clean.length > MAX_STEP) overlong++
            steps += DraftStep(clean.take(MAX_STEP))
        }
        if (duplicates > 0) notices += Notice(NoticeLevel.INFO, if (input.mode == PlanningMode.FOLLOW_THROUGH) "$duplicates suggested step(s) were already on your checklist and were left out." else "$duplicates duplicate step(s) removed.")
        if (overlong > 0) notices += Notice(NoticeLevel.INFO, "$overlong long step(s) were shortened to $MAX_STEP characters.")
        val finalSteps = if (steps.size > MAX_STEPS) {
            notices += Notice(NoticeLevel.INFO, "The AI suggested ${steps.size} steps; the first $MAX_STEPS are selected. Tick any others you want.")
            steps.take(10).mapIndexed { i, s -> s.copy(include = i < MAX_STEPS) }
        } else steps

        val nextStep = oneLine(raw.nextStep).take(MAX_STEP)

        val deadline = deadline(raw.deadline, text, today, notices, questions)
        val reminder = reminder(raw.reminder, text, input.now, input.quietHours, notices, assumptions)
        val waiting = waiting(raw.waiting, input.now, notices)
        if (waiting.apply && reminder.kind != DraftReminderKind.OFF && reminder.apply) {
            notices += Notice(NoticeLevel.INFO, "While a task is Waiting, regular reminders pause; only a check-in time reminds you.")
        }

        val priority = when (raw.priority.value.lowercase().trim()) {
            "low" -> "LOW"
            "normal" -> "NORMAL"
            "high" -> "HIGH"
            else -> null
        }
        val priorityReason = oneLine(raw.priority.reason).take(MAX_LIST_TEXT)

        val ignored = cleanList(raw.ignoredInstructions, 5)
        if (ignored.isNotEmpty()) {
            notices += Notice(NoticeLevel.WARNING, "Some text looked like instructions to the AI and was ignored. Only this task is planned; nothing else was accessed or done.")
        }

        val isEdit = input.mode == PlanningMode.FOLLOW_THROUGH
        return ProposalDraft(
            proposalId = input.proposalId,
            mode = input.mode,
            originalText = text,
            title = title,
            notes = notes,
            steps = finalSteps,
            nextStep = nextStep,
            addNextStep = false,
            deadline = deadline,
            reminder = if (isEdit) reminder.copy(apply = false) else reminder,
            waiting = if (isEdit) waiting.copy(apply = false) else waiting,
            priority = priority,
            priorityReason = priorityReason,
            applyPriority = !isEdit && priority != null && priorityReason.isNotEmpty(),
            assumptions = assumptions,
            questions = questions,
            ignoredInstructions = ignored,
            notices = notices.distinct(),
            advice = raw.advice.trim().take(1_000),
            model = input.model,
            targetTaskId = input.targetTaskId,
            targetUpdatedAtMillis = input.targetUpdatedAtMillis,
            applyTitle = false,
            applyNotes = false,
        )
    }

    /** Whether [ProposalDraft.nextStep] is already one of the selected steps (no "add" toggle needed). */
    fun nextStepListed(draft: ProposalDraft): Boolean =
        draft.nextStep.isEmpty() || draft.steps.any { normalizeKey(it.text) == normalizeKey(draft.nextStep) }

    // ------------------------------------------------------------------ deadline

    private fun deadline(raw: RawDeadline, text: String, today: LocalDate, notices: MutableList<Notice>, questions: MutableList<String>): DraftDeadline {
        var provenance = provenanceOf(raw.provenance)
        val phrase = oneLine(raw.sourcePhrase).take(MAX_LIST_TEXT)
        var date = raw.date.trim().takeIf { it.isNotEmpty() }?.let { s ->
            parseDate(s) ?: run { notices += Notice(NoticeLevel.WARNING, "The AI returned an invalid deadline date (\"${s.take(20)}\"); it was ignored."); null }
        }
        var time = raw.time.trim().takeIf { it.isNotEmpty() }?.let { s ->
            parseTime(s) ?: run { notices += Notice(NoticeLevel.WARNING, "The AI returned an invalid deadline time (\"${s.take(10)}\"); it was ignored."); null }
        }

        // Grounding: an "explicit" deadline must quote words that are actually in the text.
        if (provenance == Provenance.EXPLICIT && (phrase.isEmpty() || !containsLoosely(text, phrase))) {
            provenance = if (date != null) Provenance.INFERRED else Provenance.UNKNOWN
        }

        val resolution = PhraseResolver.classify(phrase, today)
        when (resolution.kind) {
            PhraseResolver.Kind.EVENT -> {
                if (date != null) notices += Notice(NoticeLevel.INFO, "“$phrase” depends on an event whose date isn't known, so no deadline was set.")
                date = null; time = null; provenance = Provenance.UNKNOWN
                if (questions.size < MAX_QUESTIONS && questions.none { containsLoosely(it, phrase) }) questions += "When is “$phrase”? Add a deadline once you know."
            }
            PhraseResolver.Kind.VAGUE -> if (provenance == Provenance.EXPLICIT) provenance = Provenance.INFERRED
            PhraseResolver.Kind.PRECISE -> {
                val computed = resolution.date!!
                if (date != null && date != computed) {
                    notices += Notice(NoticeLevel.INFO, "Adjusted the deadline: “$phrase” is ${computed.format(dayLabel)}.")
                }
                date = computed
                if (provenance == Provenance.UNKNOWN) provenance = Provenance.INFERRED
            }
            PhraseResolver.Kind.UNKNOWN -> Unit
        }

        if (date != null && date.isBefore(today)) {
            notices += Notice(NoticeLevel.WARNING, "The suggested deadline ${date.format(dayLabel)} is in the past and was ignored.")
            date = null; time = null; provenance = Provenance.UNKNOWN
        }
        if (date != null && date.isAfter(today.plusYears(MAX_YEARS_AHEAD))) {
            notices += Notice(NoticeLevel.WARNING, "The suggested deadline is implausibly far ahead and was ignored.")
            date = null; time = null; provenance = Provenance.UNKNOWN
        }
        if (date == null) { time = null; if (provenance != Provenance.UNKNOWN) provenance = Provenance.UNKNOWN }

        return DraftDeadline(
            provenance = provenance,
            date = date?.toString(),
            time = time?.format(hhmm),
            sourcePhrase = phrase,
            apply = provenance == Provenance.EXPLICIT && date != null,
        )
    }

    // ------------------------------------------------------------------ reminders

    private fun reminder(
        raw: RawReminder,
        text: String,
        now: LocalDateTime,
        quiet: Pair<LocalTime, LocalTime>?,
        notices: MutableList<Notice>,
        assumptions: MutableList<String>,
    ): DraftReminder {
        val phrase = oneLine(raw.requestedPhrase).take(MAX_LIST_TEXT)
        var provenance = if (raw.provenance.trim().equals("explicit", true)) Provenance.EXPLICIT else Provenance.SUGGESTED
        if (provenance == Provenance.EXPLICIT && (phrase.isEmpty() || !containsLoosely(text, phrase))) provenance = Provenance.SUGGESTED

        val (times, badTimes) = raw.times.map { it.trim() }.filter { it.isNotEmpty() }.partition { parseTime(it) != null }
        if (badTimes.isNotEmpty()) notices += Notice(NoticeLevel.WARNING, "Ignored invalid reminder time(s): ${badTimes.joinToString { it.take(8) }}.")
        var localTimes = times.mapNotNull(::parseTime).distinct().sorted()
        if (localTimes.size > MAX_TIMES) {
            notices += Notice(NoticeLevel.WARNING, "Too many reminder times; kept the first $MAX_TIMES.")
            localTimes = localTimes.take(MAX_TIMES)
        }
        val startDate = parseDate(raw.startDate.trim())?.takeIf { !it.isBefore(now.toLocalDate()) && !it.isAfter(now.toLocalDate().plusYears(1)) }

        fun recurring(kind: DraftReminderKind, interval: Int): DraftReminder {
            if (localTimes.isEmpty()) {
                notices += Notice(NoticeLevel.WARNING, "The AI suggested a recurring reminder without valid times, so reminders are off. Add times if you want them.")
                return DraftReminder(provenance = provenance, requestedPhrase = phrase)
            }
            quietNotice(localTimes, quiet)?.let { notices += it }
            return DraftReminder(
                kind = kind, times = localTimes.map { it.format(hhmm) }, dayInterval = interval,
                startDate = startDate?.toString(), provenance = provenance, requestedPhrase = phrase,
                apply = provenance == Provenance.EXPLICIT,
            )
        }

        return when (raw.kind.trim().lowercase()) {
            "once" -> once(raw, now, provenance, phrase, notices, assumptions, quiet)
            "daily_times" -> recurring(DraftReminderKind.DAILY, 1)
            "every_n_days" -> {
                val interval = raw.dayInterval
                when {
                    interval <= 1 -> recurring(DraftReminderKind.DAILY, 1)
                    interval > 365 -> {
                        notices += Notice(NoticeLevel.WARNING, "An interval of $interval days isn't supported; using every 365 days.")
                        recurring(DraftReminderKind.EVERY_N_DAYS, 365)
                    }
                    else -> recurring(DraftReminderKind.EVERY_N_DAYS, interval)
                }
            }
            "weekly_days" -> {
                val days = raw.weekdays.mapNotNull(::weekday).toSet()
                if (days.size == 7) recurring(DraftReminderKind.DAILY, 1) else unsupported(describeWeekdays(days, phrase), localTimes, provenance, phrase, notices)
            }
            "unsupported" -> unsupported(phrase.ifEmpty { "that schedule" }, localTimes, provenance, phrase, notices)
            else -> DraftReminder(provenance = Provenance.UNKNOWN, requestedPhrase = phrase)
        }
    }

    private fun once(
        raw: RawReminder,
        now: LocalDateTime,
        provenance: Provenance,
        phrase: String,
        notices: MutableList<Notice>,
        assumptions: MutableList<String>,
        quiet: Pair<LocalTime, LocalTime>?,
    ): DraftReminder {
        val at: LocalDateTime? = when {
            raw.onceInMinutes > 0 -> {
                if (raw.onceInMinutes > MAX_ONCE_MINUTES) null
                // Relative times are computed by the app, not the model.
                else now.plusMinutes(raw.onceInMinutes.toLong()).withSecond(0).withNano(0)
            }
            else -> {
                val time = parseTime(raw.onceTime.trim())
                val date = parseDate(raw.onceDate.trim())
                    ?: PhraseResolver.classify(phrase, now.toLocalDate()).takeIf { it.kind == PhraseResolver.Kind.PRECISE }?.date
                when {
                    date != null && time != null -> LocalDateTime.of(date, time)
                    date != null -> LocalDateTime.of(date, LocalTime.of(9, 0)).also { assumptions += "No time given for the reminder; using 09:00." }
                    time != null -> {
                        val today = LocalDateTime.of(now.toLocalDate(), time)
                        (if (today.isAfter(now)) today else today.plusDays(1)).also { assumptions += "Reminder at ${time.format(hhmm)} ${if (it.toLocalDate() == now.toLocalDate()) "today" else "tomorrow"}." }
                    }
                    else -> null
                }
            }
        }
        if (at == null) {
            notices += Notice(NoticeLevel.WARNING, "The suggested one-time reminder had no usable date/time, so reminders are off.")
            return DraftReminder(provenance = provenance, requestedPhrase = phrase)
        }
        if (!at.isAfter(now)) {
            notices += Notice(NoticeLevel.WARNING, "The suggested reminder time ${at.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))} has already passed, so it is not selected.")
            return DraftReminder(kind = DraftReminderKind.ONCE, onceAt = at.toString(), provenance = provenance, requestedPhrase = phrase, apply = false)
        }
        quietNotice(listOf(at.toLocalTime()), quiet)?.let { notices += it }
        return DraftReminder(kind = DraftReminderKind.ONCE, onceAt = at.withSecond(0).withNano(0).toString(), provenance = provenance, requestedPhrase = phrase, apply = provenance == Provenance.EXPLICIT)
    }

    private fun unsupported(description: String, times: List<LocalTime>, provenance: Provenance, phrase: String, notices: MutableList<Notice>): DraftReminder {
        val altTimes = times.ifEmpty { listOf(LocalTime.of(9, 0)) }.map { it.format(hhmm) }
        val label = "Every day at ${altTimes.joinToString(" and ")}"
        notices += Notice(NoticeLevel.WARNING, "Reminders “$description” aren't supported yet. Supported: once, every day at set times, or every N days. You can choose “$label” instead.")
        return DraftReminder(
            kind = DraftReminderKind.OFF,
            provenance = provenance,
            requestedPhrase = phrase,
            apply = false,
            unsupported = UnsupportedSchedule(description, DraftReminderKind.DAILY, altTimes, 1, label),
        )
    }

    private fun quietNotice(times: List<LocalTime>, quiet: Pair<LocalTime, LocalTime>?): Notice? {
        quiet ?: return null
        val (start, end) = quiet
        if (start == end) return null
        val inside = times.filter { t -> if (start < end) t >= start && t < end else t >= start || t < end }
        if (inside.isEmpty()) return null
        return Notice(NoticeLevel.INFO, "${inside.joinToString { it.format(hhmm) }} falls in your quiet hours (${start.format(hhmm)}–${end.format(hhmm)}); it will be delivered at ${end.format(hhmm)} instead.")
    }

    // ------------------------------------------------------------------ waiting

    private fun waiting(raw: RawWaiting, now: LocalDateTime, notices: MutableList<Notice>): DraftWaiting {
        if (!raw.isWaiting) return DraftWaiting()
        val who = oneLine(raw.waitingFor).take(MAX_STEP)
        val date = parseDate(raw.checkInDate.trim())
        val time = parseTime(raw.checkInTime.trim())
        var checkIn = date?.let { LocalDateTime.of(it, time ?: LocalTime.of(9, 0)) }
        if (checkIn != null && !checkIn.isAfter(now)) {
            notices += Notice(NoticeLevel.WARNING, "The suggested check-in time is in the past and was ignored.")
            checkIn = null
        }
        return DraftWaiting(apply = true, waitingFor = who, checkIn = checkIn?.toString())
    }

    // ------------------------------------------------------------------ helpers

    private fun provenanceOf(raw: String): Provenance = when (raw.trim().lowercase()) {
        "explicit" -> Provenance.EXPLICIT
        "inferred" -> Provenance.INFERRED
        else -> Provenance.UNKNOWN
    }

    fun parseDate(raw: String): LocalDate? = if (raw.isEmpty()) null else runCatching { LocalDate.parse(raw) }.getOrNull()

    /** Accepts H:MM / HH:MM (24-hour) only. */
    fun parseTime(raw: String): LocalTime? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(raw) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun weekday(raw: String): DayOfWeek? = when (raw.trim().uppercase().take(3)) {
        "MON" -> DayOfWeek.MONDAY
        "TUE" -> DayOfWeek.TUESDAY
        "WED" -> DayOfWeek.WEDNESDAY
        "THU" -> DayOfWeek.THURSDAY
        "FRI" -> DayOfWeek.FRIDAY
        "SAT" -> DayOfWeek.SATURDAY
        "SUN" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun describeWeekdays(days: Set<DayOfWeek>, phrase: String): String {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        return when {
            days == weekdays -> "on weekdays only"
            days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "on weekends only"
            days.isNotEmpty() -> "only on " + days.sorted().joinToString { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
            else -> phrase.ifEmpty { "on certain days" }
        }
    }

    private fun oneLine(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

    private fun normalizeKey(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Case- and whitespace-insensitive containment, so quoted phrases ground in the source text. */
    fun containsLoosely(haystack: String, needle: String): Boolean {
        val n = normalizeKey(needle)
        return n.isNotEmpty() && normalizeKey(haystack).contains(n)
    }

    private fun cleanList(items: List<String>, max: Int): List<String> =
        items.map(::oneLine).filter { it.isNotEmpty() }.distinctBy(::normalizeKey).take(max).map { it.take(MAX_LIST_TEXT) }
}
