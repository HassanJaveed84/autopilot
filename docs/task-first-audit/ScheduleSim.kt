// Task-first audit (2026-10-24): read-only replay of the app's pure scheduling code for the nine audit tasks.
// Not part of the Gradle build. Reproduce (no Android SDK needed), from the repo root:
//   A=app/src/main/java/com/lifeadmin/autopilot
//   kotlinc docs/task-first-audit/ScheduleSim.kt $A/ai/PhraseResolver.kt $A/domain/model/*.kt $A/domain/scheduling/*.kt -include-runtime -d sim.jar && java -jar sim.jar
import com.lifeadmin.autopilot.ai.PhraseResolver
import com.lifeadmin.autopilot.domain.model.*
import com.lifeadmin.autopilot.domain.scheduling.*
import java.time.*
import java.time.format.DateTimeFormatter

// Read-only simulation of the app's pure scheduling code (no Android, no network).
val zone: ZoneId = ZoneId.of("Europe/London")
val now: Instant = LocalDateTime.of(2026, 10, 23, 14, 0).atZone(zone).toInstant() // Fri 14:00
val today: LocalDate = now.atZone(zone).toLocalDate()
val settings = AppSettings() // shipped defaults
val fmt = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm")
fun f(i: Instant) = i.atZone(zone).format(fmt)

fun task(title: String, status: TaskStatus = TaskStatus.ACTIONABLE, dl: DeadlineType = DeadlineType.NONE, dlInstant: Instant? = null) =
    Task(id = title, title = title, status = status, createdAt = now, updatedAt = now, deadlineType = dl, deadlineInstant = dlInstant)
fun blank() = ReminderPolicy(id = "p", taskId = "t", lastEditedAt = now)
fun preset(p: ReminderPreset) = ReminderPresets.applyTo(blank(), p, today)
fun daily(vararg t: LocalTime, q: QuietHoursBehavior? = null) = blank().copy(enabled = true, kind = ReminderKind.DAILY_TIMES, times = t.toList(), startLocalDate = today, quietHoursBehavior = q)
fun once(at: LocalDateTime) = blank().copy(enabled = true, kind = ReminderKind.ONCE, atInstant = at.atZone(zone).toInstant())

fun show(label: String, t: Task, p: ReminderPolicy?, n: Int = 4, from: Instant = now) {
    val up = ReminderProjection.upcoming(t, p, settings, from, zone, n)
    println("  $label")
    if (up.isEmpty()) println("      (nothing will ever ring)")
    up.forEach { println("      ${f(it.plannedAt)}" + if (it.deferred) "   <- deferred from ${f(it.slotAt)} by quiet hours" else "") }
}

fun main() {
    println("now = ${f(now)} $zone; defaults: preset=${settings.defaultPreset}, quiet=${settings.quietHours.start}-${settings.quietHours.end} ${settings.quietHoursBehavior}, precision=${settings.precisionMode}")
    println("\n[A] Capture -> 'Save & nudge' with no edits (the one-tap path) for all nine tasks:")
    val nine = listOf("Buy clothes", "Buy a gift for Sam", "Get a car inspection", "Buy perfume", "Change the hallway lightbulb",
        "Complete the work update by Monday 5pm", "Turn off the fan before bed", "Check my daughter's blanket at bedtime", "Buy Hans Zimmer concert tickets")
    nine.forEach { println("  - $it") }
    show("every one of the nine gets the same schedule:", task(nine[0]), preset(settings.defaultPreset), 6)
    println("  => ${nine.size} tasks x 2 = ${nine.size * 2} notifications/day (cap ${settings.maxNotificationsPerDay})")

    println("\n[B] 'Turn off the fan before bed' - user tunes Follow-up to Daily 22:30:")
    show("default quiet-hours behaviour (DEFER):", task(nine[6]), daily(LocalTime.of(22, 30)))
    show("Advanced -> 'Skip that nudge':", task(nine[6]), daily(LocalTime.of(22, 30), q = QuietHoursBehavior.SKIP))
    show("Once, tonight 22:30:", task(nine[6]), once(LocalDateTime.of(today, LocalTime.of(22, 30))))
    show("Daily 20:45 (works, but only because it's before 21:00):", task(nine[6]), daily(LocalTime.of(20, 45)))

    println("\n[C] Routine lifecycle: Done on a daily routine")
    show("after tapping Done (status DONE):", task(nine[7], TaskStatus.DONE), daily(LocalTime.of(20, 45)))

    println("\n[D] 'Complete the work update' with EXACT deadline Mon 26 Oct 17:00 and the default nudge:")
    val dl = LocalDateTime.of(2026, 10, 26, 17, 0).atZone(zone).toInstant()
    val w = task(nine[5], dl = DeadlineType.EXACT, dlInstant = dl)
    show("nudges around the deadline (deadline is not an input to the planner):", w, preset(settings.defaultPreset), 8)
    show("a week after the deadline, still:", w, preset(settings.defaultPreset), 2, from = dl.plus(Duration.ofDays(7)))

    println("\n[E] Snooze 'tomorrow morning' at 23:30 tonight (default snooze):")
    val late = LocalDateTime.of(today, LocalTime.of(23, 30)).atZone(zone).toInstant()
    println("  target = ${f(SnoozeCalculator.target(SnoozePreset.TOMORROW_MORNING, late, zone))}")

    println("\n[G] Blanket check nudged at 20:45, user taps Snooze 1 hour (snooze end falls in quiet hours):")
    val at2045 = LocalDateTime.of(today, LocalTime.of(20, 45)).atZone(zone).toInstant()
    val sn = task(nine[7]).copy(snoozedUntil = SnoozeCalculator.target(SnoozePreset.ONE_HOUR, at2045, zone), snoozeRemindAtEnd = true)
    show("next deliveries after the snooze:", sn, daily(LocalTime.of(20, 45)), 2, from = at2045)
    println("  notification Snooze button (default TOMORROW_MORNING) at 20:45 -> ${f(SnoozeCalculator.target(SnoozePreset.TOMORROW_MORNING, at2045, zone))}")

    println("\n[F] PhraseResolver (used only to check AI plan dates; capture never parses text):")
    listOf("before bed", "at bedtime", "tonight", "by Monday 5pm", "next month", "this weekend", "before they sell out", "before my sister's birthday", "every year", "when the garage replies")
        .forEach { println("  '%s' -> %s".format(it, PhraseResolver.classify(it, today).let { r -> r.kind.name + (r.date?.let { d -> " $d" } ?: "") })) }
}
