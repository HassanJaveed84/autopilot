# Right-time nudges — implementation report

Date: 2026-10-25 · Branch: `right-time-nudges` (base `ccr-2b291a9b-kqjl8r` @ `7d498b4`, the task-first audit, which sits on the AI-first integration branch `ccr-e03bf7f8-hvk42n`) · Not merged · PR #8 untouched.

Scope is the milestone proposed in `docs/TASK_FIRST_AUDIT.md` §5 and approved by the owner on 2026-10-25: offline timing at capture, bedtime with a per-task quiet-hours exception, real per-occurrence completion for routines, and a lighter default for new installs. Plan with AI, Research Online and the Proposal Review are unchanged. No AI calls were added or made.

## What changed

### 1. Capture understands explicit timing, offline

`domain/capture/CaptureTiming` is a small, deterministic reader (regexes over the lower-cased text). The capture sheet runs it as you type.

| Words | What is prefilled |
| --- | --- |
| "tonight at 10pm", "at 5pm", "19:30", "noon" | One nudge at that time (today if still ahead, else tomorrow) |
| "tomorrow at 9am", "Tuesday 6pm" | One nudge on that day at that time. A weekday means the coming one; today counts only while the time is still ahead |
| "tomorrow", "Monday" (no time) | Your usual nudges (default preset), starting that day. No clock time is invented |
| "before bed", "at bedtime" | One nudge at your bedtime: tonight, or tomorrow if bedtime has passed |
| "every night", "nightly" | A routine at your bedtime (or at the stated time) |
| "every day", "daily" | A routine at your usual times (or at the stated time) |
| "by Monday 5pm", "due tomorrow", "by 5pm", "by Friday" | A deadline: exact with a time, date-only without. It is read first and not reused as a nudge |

Things it deliberately does not do:

- **It never invents a clock time.** A bare "at 7", or "7:30" without am/pm, reads as evening only in an evening context ("tonight at 10" → 22:00, "every night at 10" → 22:00). Otherwise it is not used, and a one-line note says "could be morning or evening — choose the time in the follow-up".
- **"next Friday" is not guessed.** It could mean two different weeks, so a note explains and nothing is set.
- **Bare "tonight"/"today" without a time is not guessed.** A note asks for a time.
- **Times that have passed are not used**, and a note explains.
- **Vague or event wording sets nothing**: "soon", "next week", "this weekend", "before my trip", "when they get back to me". Neither does "sun cream" (no abbreviated weekdays) or "stand by me" (not a day or time after "by").

The sheet shows **"Understood from your words"**: each inferred nudge or deadline, plus "from “before bed”" for the words it came from. It also offers **Ignore**, and, for a one-off bedtime nudge, **Every night instead**. The follow-up row then reads e.g. "Nudge me once, today 21:30 · Once · from your words · tap to change". A choice made in the Follow-up sheet, or a deadline picked in the disclosure, always wins over the words.

The text is never altered: the title and notes are what you typed. The placeholder now uses phrases the reader really handles ("Turn off the fan before bed, or send the report by Monday 5pm"). The old one promised "remind me Friday … before 3 Nov" parsing that only Plan with AI did.

### 2. Bedtime and the per-task quiet-hours exception

- **Settings → Follow-up → Bedtime**: an editable time, suggested at **21:30** (`AppSettings.bedtime`, DataStore key `bedtime`). Changing it affects future captures only; nudges you have already set keep their times.
- **`ReminderPolicy.ringInQuietHours`**: the per-task exception. It is **never set automatically**.
  - In the capture sheet, a "Ring during quiet hours" switch appears only when one of the next nudges would fall in quiet hours. It explains both states before saving. Off: "…so it would wait until 08:00". On: "Rings at its time even during your quiet hours (21:00–08:00), for this task only. Android's Do Not Disturb and notification settings still apply."
  - The same switch is in the Follow-up sheet, so it can be changed later.
- The planner lifts the app's quiet hours for **all** of that task's streams (policy slots, snooze end, check-in). Other tasks are unaffected. Nothing touches the notification channel, DND bypass or exact-alarm settings; system-level controls still apply.
- **Bedtime snooze stays in the evening.** Bedtime nudges are saved with a 15-minute snooze (the existing `snoozeDefaultMinutes`), so the notification's Snooze reads "Snooze · 21:45". With the exception on, that snooze end rings at 21:45. Without it, it waits like any other quiet-hours nudge.

### 3. Real recurring completion ("Done for tonight")

- **`ReminderPolicy.routine`** marks a confirmed routine. It is set by "every night / every day" at capture, by "Every night instead", or by the Follow-up sheet's "It's a routine" switch. It is only meaningful for recurring kinds (normalised off for Once).
- **`Task.cycleDoneUntil`** stores a finished cycle. `TaskRepository.finishCycle` sets it to the next 05:00 local (the same early-morning boundary snooze uses). This silences the rest of the current cycle and clears a pending snooze. It bumps the generation and logs a new `OCCURRENCE_DONE` event. The task stays **ACTIONABLE**; tomorrow's occurrence is planned by the unchanged engine. Repeating it in the same cycle does nothing. **Undo** clears it and logs `OCCURRENCE_UNDONE`.
- A 09:00 + 18:00 routine done at 10:00 is quiet for the rest of the day. A bedtime nudge answered after midnight still belongs to the evening it came from.
- **Where it appears:**
  - **Notification:** on a routine, the first action is "Done for tonight" (evening and night) or "Done for today", instead of the permanent Done. If the nudge being answered belongs to an earlier cycle (last night's 21:30 held by quiet hours until 08:00), the action reads "Done for last night" (or "Done for yesterday"). It answers only that cycle, so tonight's nudge stays on. The receiver re-checks Room: if the task is no longer an open routine, the tap only clears the notification and never completes the task.
  - **Detail screen:** the primary button is "Done for tonight". When the cycle is done, it becomes "Undo …" and the follow-up card says "Done for tonight · next tomorrow 21:30". A secondary **"Mark done · ends the routine"** keeps today's permanent meaning.
  - **Today and Tasks card checks:** on a routine, the check finishes the cycle (snackbar with Undo). Today lists the routine under "Done today", and it no longer shows on the rail or under "Later".
- **Mark done is unchanged everywhere:** DONE, future alarms cancelled, notification cleared.
- **History and stall detection stay accurate.** "Done for tonight" is its own event, never `SNOOZED`. `StallDetector` counts it as progress, so a routine answered nightly never looks stuck. A routine that is ignored for six nudges still raises "reminded 6 times".

### 4. Lighter default for new installs

`AppSettings.defaultPreset` now defaults to **Gentle** (every 2 days at 10:00) instead of Persistent (09:00 + 18:00 daily).

Existing installs keep what they have. Every settings write persists every key, including the preset, and the engine writes settings on its first reconcile after any app start. So any install that has run before has its preset stored explicitly. A test reproduces this with a DataStore seeded the way older versions wrote it.

Existing tasks keep their own stored schedules; nothing is rewritten.

## Schema change (Room v7 → v8) and backups

The change is additive only: `MIGRATION_7_8` adds three columns.

- `tasks.cycleDoneUntil INTEGER NULL`
- `reminder_policies.routine INTEGER NOT NULL DEFAULT 0`
- `reminder_policies.ringInQuietHours INTEGER NOT NULL DEFAULT 0`

Existing rows read as "not a routine, quiet hours apply", which is exactly their previous behaviour. Exported schema: `app/schemas/.../8.json`.

**Why a migration and not existing fields:**

- Reusing `snoozedUntil` for "done for tonight" would have shown the task as Snoozed, logged it as a snooze, counted it towards the stall detector's "snoozed 3 times", and collided with a real snooze.
- A routine must be distinguishable from "nudge until done" nudges, or every Gentle task would offer "Done for today".
- A new `QuietHoursBehavior` enum value was avoided because an older app would reject it on import. Booleans keep backups forward-readable.

**Backups** (`BackupRepository`): `TaskDto.cycleDoneUntil` and `PolicyDto.routine` / `ringInQuietHours` are optional fields with defaults.

- Older backups import exactly as before (tested).
- An older app version reading a newer backup ignores the new fields (`ignoreUnknownKeys`), with one exception. History entries of the new types `OCCURRENCE_DONE` / `OCCURRENCE_UNDONE` would be rejected by an **older** build's strict event-type check. Restoring a newer backup into an older app build is not a supported path, but this is recorded here.

Plan with AI and research proposals that set a reminder create a fresh policy, as before. That fresh policy is not a routine and has no quiet-hours exception, so an AI-proposed schedule never inherits the exception silently.

## Tests

New tests:

| Suite | Count | What it covers |
| --- | --- | --- |
| `domain/CaptureTimingTest` | 16 | The milestone's phrases (fan, blanket before bed / every night, work update by Monday 5pm, gift); all supported expressions; ambiguous and passed times; "next Friday"; vague and event wording; text untouched |
| `domain/RightTimeNudgesPlannerTest` | 8 | The exception versus deferral; per-task only; beats Skip; off when nudges are off; bedtime snooze at 21:45 versus 08:00; Done for tonight keeps tomorrow; non-routines unaffected; the 10:00 case; after midnight; DST cycle ends |
| `reminders/RightTimeNudgesEngineTest` | 12 | Room + engine + fake alarm/notification: fan with and without the exception; blanket nightly answered per night, idempotent; "Done for last night" on a held nudge; Undo; Mark done stays permanent; refused for non-routines; nightly answers aren't a stall; ignored routines still are; work update deadline + default; gift → Gentle and nothing else; existing Persistent schedules untouched |
| `reminders/AndroidIntegrationTest` (+2) | 2 | Robolectric with real receivers and NotificationManager: a routine's notification actions are [Done for …, Snooze · …, Open]; tapping the first keeps the task open, schedules the next cycle and logs no snooze. A forged/obsolete "done for now" on a non-routine never completes it |
| `ui/capture/RightTimeCaptureUiTest` | 7 | On the real app shell: fan before bed shows what was understood and asks before ringing in quiet hours; without the choice quiet hours apply; every night → routine → detail "Done for tonight" → "Done for tonight · next tomorrow 21:30"; Every night instead; deadline prefill; gift shows nothing and saves Gentle; Ignore |
| `data/MigrationTest` (+1) | 1 | v7 → v8 keeps a snoozed Persistent task, its Skip override, snooze minutes, generation and slots; new fields default off |
| `data/BackupRepositoryTest` (+1) | 1 | Round trip of routine / exception / finished cycle and its event; a pre-v8 file imports as before |
| `data/SettingsDefaultsTest` | 2 | New install → Gentle + 21:30; an existing store keeps Persistent across unrelated writes |
| `androidTest/InstrumentedReminderTest` (+1) | 1 | **Emulator (CI):** with quiet hours covering "now", a routine with the exception posts through the real AlarmManager → receiver → NotificationManager; its first action is "Done for tonight/today"; tapping it keeps the task open with the next cycle planned. The same schedule without the exception is held |

Updated: two `AppUiTest` cases asserted the old Persistent default (09:00 + 18:00). They now assert Gentle.

### Commands run (this container: Android SDK 36 installed for the session; no KVM, so no local emulator)

| Command | Tree | Result |
| --- | --- | --- |
| `./gradlew --no-build-cache lintDebug testDebugUnitTest` | baseline `7d498b4` (clean worktree) | **Pass**: lint clean; **557 tests, 0 failures** |
| `./gradlew --no-build-cache lintDebug testDebugUnitTest` | this branch (final code) | **Pass**: lint clean; **606 tests, 0 failures, 0 skipped** (557 + 49 new) |
| `./gradlew --no-build-cache assembleDebug assembleRelease assembleDebugAndroidTest` | this branch | **Pass**: all three APKs build (release signed with the debug key locally; CI uses the personal key secret when present) |

The build ran with `--no-build-cache`. An earlier "baseline" run in a second worktree picked up compiled classes from this branch through the shared Gradle build cache and Kotlin daemon, so it was discarded and rerun clean.

Maven Central was reached through Google's mirror via a local, uncommitted Gradle init script, because direct downloads from this container returned intermittent 404s.

### Results

CI: see "CI" below.

## What remains unsupported (by design, for later milestones)

- **Deadline-aware nudges.** A deadline still doesn't create lead-time nudges or change the schedule after it passes; "by Monday 5pm" sets the deadline and the default nudge. This is the next slice proposed in the audit.
- **Research-to-reminder proposals** (the Hans Zimmer case) and any change to Plan with AI or Research Online.
- **Other timing phrases:**
  - Dates like "3 Nov" or "the 24th"
  - "this weekend" or "next week" as ranges
  - "every Monday" or "weekdays"
  - "in 2 hours"
  - "morning" or "afternoon" anchors
  - "midnight"
  - Abbreviated weekdays other than "tues"/"thurs"
  - Locales other than English
- **The occasion behind a weekday.** "Gift for Sam's birthday on Saturday" starts the usual nudges on Saturday. This is visible and can be Ignored or changed, but the reader doesn't know the day is the occasion, not the day to act.
- **Repeats other than daily.** Routines are daily (or every N days from the Follow-up sheet); weekly and yearly repeats remain unsupported by the engine.
- **"Done for tonight" after editing a routine's times** applies to the slots of the new schedule until 05:00. A routine turned back into "until done" ignores a pending finished cycle.

## Needs physical-device verification

Not done by the agent; emulator results are wiring checks, not device behaviour.

1. **The bedtime nudge with the screen off overnight in Doze.**
   - Default inexact alarms (`setAndAllowWhileIdle`) can be batched or delayed by minutes on real phones.
   - Check how late "21:30" actually arrives with and without precise timing on your phone.
2. **Do Not Disturb.** With DND on, the exception should *not* break through (no DND bypass is requested). Confirm the reminder is silent or held by the system as Android decides.
3. **Notification action buttons.**
   - Confirm "Done for tonight" / "Snooze · 21:45" / "Open" on the lock screen and in the shade.
   - Confirm tapping "Done for tonight" leaves the task open and tomorrow's nudge listed in the app.
4. **Upgrade path.** Install over the current build with existing tasks:
   - Persistent tasks keep 09:00 + 18:00.
   - The Settings preset is unchanged.
   - The bedtime row shows 21:30.
   - No data is lost (Room v7 → v8).
5. **Backup.** Export on the new build and re-import on the same build.
