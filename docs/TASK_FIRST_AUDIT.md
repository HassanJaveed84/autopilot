# Task-First Product Audit — nine everyday tasks

Date: 2026-10-24 · Base: `ccr-e03bf7f8-hvk42n` @ `7971e20` (AI-first Research Online integration, not merged) · Branch: `ccr-2b291a9b-kqjl8r` · Audit only: no app code changed.

**Central question:** if I casually tell Autopilot something I need to do, does it make that responsibility easier to finish without me managing the assistant?

**Short answer:** the hard parts are solid: reliable local reminders, Done/Snooze from the notification, honest AI review-before-write, and source-cited research. But the step that matters most, **turning what I said into a nudge at the right moment**, is missing whenever AI is off, and it is wrong for bedtime tasks even when the user sets it up by hand. Every task you save with one tap gets the same 09:00 and 18:00 daily nudge, whatever you wrote. Two of the nine tasks (fan, blanket) can't be served properly without changing a global setting. Deadlines never affect when nudges arrive.

## How this was checked

| Evidence | What it covers |
| --- | --- |
| Code reading of the current branch | Capture (`CaptureSheet`, `CaptureViewModel`, `TaskActions.capture`), task lifecycle (`TaskRepository`, `TaskStateMachine`), scheduling (`ReminderPlanner`, `PolicySlotGenerator`, `QuietHoursCalculator`, `SnoozeCalculator`, `ReminderEngine.notificationBody`), Today (`TodayPlanner`), stall hints (`StallDetector`), Plan with AI (`PlanningPrompt`, `PlanSchema`, `ProposalValidator`, `ProposalMapper`, `PlanReview`), Research Online (`ResearchOnlineService`, `SmallCalls`), task detail (`TaskDetailScreen`) |
| **Simulation run** (`docs/task-first-audit/ScheduleSim.kt`, output in `ScheduleSim-output.txt`) | The app's own pure scheduling code (`domain/model`, `domain/scheduling`, `PhraseResolver`) compiled with standalone `kotlinc` 2.2.21 and replayed for the nine tasks, using the shipped `AppSettings()` defaults, Europe/London, "now" = Fri 23 Oct 14:00 |
| Existing reports | `AI_FIRST_RESEARCH_INTEGRATION_REPORT.md` (557 unit/Robolectric tests, 16 instrumented, recorded by that session), `live-eval/ai-first-validation/REPORT.md` |

**Not done:** the Gradle Android test suite was not re-run, because this container has no Android SDK. No paid API calls were made. Nothing was run on a device or emulator. What Plan with AI would actually produce for these nine sentences is **inferred from the prompt and validator**, not observed. None of the recorded live research scenarios (V1–V5) is one of the nine tasks. Inexact alarm timing on real phones (precision mode is off by default) is not verified.

## What actually happens today, task by task

The fastest path is "type the sentence → **Save & nudge**". With shipped defaults, that path gives **all nine tasks the same schedule**: the Persistent preset, 09:00 and 18:00 every day until Done (simulation [A]). Capture never reads the sentence for dates or times. The placeholder text, *"Renew car insurance before 3 Nov, remind me Friday"*, suggests it does, but that only happens through Plan with AI.

| # | Task | One-tap capture (AI off) | Plan with AI (inferred from prompt + validator) | Research Online | Completion / follow-through |
| --- | --- | --- | --- | --- | --- |
| 1 | Buy clothes | 09:00 + 18:00 daily, indefinitely. That's too much for an open-ended errand. After 6 nudges (about 3 days), a stall hint asks "Is something blocking this?" | Probably a title and 0–3 steps. No date gets invented (prompt rule). | Offered, but adds little | Fine: check off from the list or tap Done on the notification |
| 2 | Buy a gift for someone | Same twice-daily nudge. Good: nothing about the recipient or occasion is invented. | Prompt forbids inventing people or dates and caps steps at 6 ("don't pad"). The questions it raises ("Who is it for? When is the occasion?") are **shown but can't be answered, and aren't saved** with the task. | Useful once the recipient and budget are known. The user types the question from a blank box; the task title and notes can be shared as context. | The occasion date has nowhere to go: `eventLocalDate` exists in Room v6 but has **no UI**. Using a deadline instead doesn't change when nudges arrive. |
| 3 | Get a car inspection | Same twice-daily nudge. No due date unless the user opens the disclosure. | Can pick up a stated date. "Every year" is classified UNKNOWN; yearly repeats are **unsupported** by the engine. | Could help ("what's checked, what to bring"). Booking is out of scope. | **Waiting on… + check-in** fits "waiting for the garage to confirm". After Done, nothing reminds you next year. |
| 4 | Buy perfume | Same as #2 | Same as #2 | **The best fit for research** (the M3A.2 perfume benchmark). Suggestions can add a `NOTE` such as the chosen scent, via the one Proposal Review. | The decision is kept only as a note or in the research thread |
| 5 | Change a lightbulb | Twice-daily nudge for a 2-minute job. Works, but heavy. | Probably "check fitting → buy bulb → replace". The notification would show "Next: check fitting", which is useful. | Offered, irrelevant | "Need to buy a bulb first" can be a step. Waiting is meant for other people. |
| 6 | Work update before a deadline | Deadline needs: disclosure → *Date & time…* → picker. **Nudges ignore the deadline**: 09:00 and 18:00 every day, including **18:00 on the deadline day, an hour after it passed**, and still a week later (simulation [D]). Today shows "Due 17:00" only when there's no nudge that day. | "By Monday 5pm" resolves to a precise deadline (`PhraseResolver` PRECISE). The reminder is still a separate suggestion, unrelated to the deadline. | Offered, irrelevant | Overdue stall hint after a 2-day grace period. Nothing gets more urgent as the deadline approaches. |
| 7 | Turn off the fan before bed | Default nudges at 18:00 and **09:00** (the 09:00 one is meaningless). If the user tunes it to daily 22:30, **every nudge is deferred to 08:00 the next morning** by default quiet hours (21:00–08:00). Choosing "Skip" means **it never rings** (simulation [B]). There's no per-task "ring anyway"; the only fixes are changing global quiet hours or choosing a time before 21:00. | "Before bed" → UNKNOWN. If the model picks 22:00, the validator honestly warns "delivered at 08:00 instead" but offers no fix. | Offered on the detail screen, irrelevant | **Done ends the routine forever** (simulation [C]): there's no "done for tonight". If the user never taps Done, the stall hint fires after 6 nudges. |
| 8 | Check daughter's blanket at bedtime | Same as #7, and it really is a nightly routine. If nudged at 20:45, **Snooze 1 h → 08:00 next morning**; the notification's Snooze button (default "tomorrow morning") → 09:00 (simulation [G]). | Same as #7 | Irrelevant | Same as #7: Done is permanent |
| 9 | Hans Zimmer concert tickets | Twice-daily nudge, but what matters is **the on-sale time**. A Once reminder at an exact date and time works if the user already knows it and it falls outside quiet hours. | Picks up a stated sale time | **Materially helpful** for finding the on-sale date, presale and venue. But suggestions can only be `STEP`/`NOTE`, so "General sale Fri 10:00" **can't become a reminder** without editing the follow-up by hand. | Waiting fits "waiting for the presale code" |

## 1. What already works well

- **Capture is fast and safe.** One field, one tap, idempotent save (`captureId`), full offline use, and "Saved. First nudge today 18:00" confirms exactly what will ring.
- **Reminders you can trust.** One-shot alarms, generation invalidation, boot/time-zone reconciliation, quiet-hours deferral with coalescing, a 30-a-day cap, and a live preview in the Follow-up sheet driven by the engine's own planner (`ReminderProjection`), so preview and reality agree.
- **Completing and postponing take little effort:** Done/Snooze/Open on the notification, check-off from list cards, Snooze/Wake/Waiting/Pause on the detail screen. Dismissing never completes a task.
- **Next action and blockers are remembered.** The first open step is marked *Next* and shown in the notification body ("Next: Buy tickets"). Waiting has a reason and a check-in. `StallDetector` raises gentle, dismissible hints (snoozed 3×, 6 nudges with no progress, overdue, missed check-in) without changing anything.
- **AI doesn't invent things and only writes after review.** The planning prompt forbids invented people, dates and prices. `PhraseResolver` overrides model date arithmetic and refuses to turn vague or event wording into firm dates. Proposals go through one review. Research uses real provider citations, preflight cost estimates and a ledger, and asks a clarifying question before spending.
- **AI is never forced on you.** Nothing calls a model unless you tap, which is the right default for tasks 5, 7 and 8.

## 2. Implemented, but adds unnecessary friction

1. **The default nudge doesn't fit the task.** Persistent twice-daily nudges for every one-tap capture: 18 notifications a day for these nine tasks. It's too much for clothes and lightbulbs, wrong for bedtime tasks, and unrelated to deadlines.
2. **The quiet-hours default undermines evening tasks.** Quiet hours start at 21:00 and can only defer or skip. Bedtime nudges and bedtime snoozes slide to the morning (#7, #8). The fix is a global setting buried in Settings, followed by "Advanced" in the Follow-up sheet.
3. **The capture placeholder overpromises.** *"…remind me Friday"* is ignored unless the user picks Plan with AI.
4. **Two separate buttons push the decision onto the user.** With AI on, the user still has to decide between Save & nudge and Plan with AI before the app has understood anything. Plan with AI is a full-screen review for what is often a one-line reminder.
5. **The same "Move this forward" card appears on every task**, with Plan next step + Research online on the fan and blanket tasks too. It's opt-in, but it's noise.
6. **Plan questions are thrown away.** "Who's the gift for?" is shown read-only and isn't saved with a new task.
7. **Research results stop at notes.** For the concert, the useful finding is a time, and it can't become a nudge or deadline from the research view.
8. **Setting a deadline takes 3+ taps** behind a disclosure, for something the sentence often already says ("by Monday 5pm").

## 3. Missing from capture to completion

| Gap | Tasks hit | Severity |
| --- | --- | --- |
| Capture doesn't read timing (time of day, "tonight", "before bed", "by Monday 5pm", weekdays) without AI | all, especially 6–9 | **Critical** |
| No way for a bedtime nudge to ring during quiet hours (per task); no "bedtime" anchor | 7, 8 | **Critical** |
| No repeating routine: Done is permanent, with no "done for today" | 7, 8 (later 3) | **Critical** |
| Deadlines don't drive nudges (no lead-time nudge, no change after the deadline) | 6, 3, 9, 2 | **Critical** for #6 |
| Event or occasion date and open questions can't be recorded (fields exist without UI) | 2, 4 | Important |
| Research → proposed reminder or deadline (STEP/NOTE only today) | 9 | Important |
| Weekly or yearly repeats (engine has once / daily / every N days only) | 3, routines | Nice-to-have now |
| Snooze choices relative to bedtime ("in 15 min" that ignores quiet hours) | 8 | Folded into the routine fix |
| A deliberate light default for open-ended errands (e.g. Gentle or Inbox) | 1, 5 | Nice-to-have (product decision) |

## 4. The three improvements with the biggest real-world benefit

1. **Understand timing at capture, locally.** Read the sentence the user typed and pre-fill the existing follow-up row and deadline: "Nudge me tonight 21:45 · from your words · tap to change". No AI, no new screen. This affects all nine tasks and makes the one-tap path right most of the time.
2. **Make bedtime routines work.** Add a bedtime anchor that may ring during quiet hours for that task only, plus **Done for tonight** for daily routines, which silences the rest of today and keeps the routine. This fixes #7 and #8, which are broken today.
3. **Deadline-aware nudges.** Add a lead-time nudge (e.g. the morning of the deadline day, plus 2 h before for exact deadlines), and replace "repeat forever" after the deadline with one overdue prompt. This fixes #6 and helps #3, #9 and #2.

Research → reminder (#9) and saved open questions (#2) are worth doing next, but they matter less than these three.

## 5. Recommended next milestone: "Right-time nudges" (small, local, no AI)

**Goal:** say it casually, get nudged at the moment it matters, and finish routines without re-creating them. AI-off first; Plan with AI and Research Online unchanged.

**Adds:**
- **Capture timing hints.** Extend `PhraseResolver` (it's already deterministic and tested) with times of day ("at 7pm", "tonight", "this evening"), "before bed / at bedtime" (→ a new *Bedtime* setting, default 21:30), "every night / every day" (→ daily), and deadline phrases ("by Monday 5pm"). `CaptureViewModel` turns a match into the existing `CaptureDraft.policy` / `CaptureDraft.deadline`, labelled *from your words*. The user can see and undo it in the existing follow-up row and disclosure. If nothing matches, behaviour stays exactly as today. Nothing is applied silently; the text stays verbatim in the title and notes.
- **Bedtime may ring in quiet hours.** A third `QuietHoursBehavior` value, e.g. `RING`, set automatically only for bedtime-anchored nudges and selectable in the Follow-up sheet's Advanced section. It's stored as an enum name in Room and backups, so **no schema migration** is needed. Note that an older app restoring a newer backup would reject the value.
- **Done for tonight** for recurring policies (detail screen + notification action): reuse the snooze machinery (`snoozedUntil` = the next day's first slot, `snoozeRemindAtEnd = false`) and log a distinct event so `StallDetector` counts it as progress, not as a snooze. The task stays open; explicit **Done** keeps its current meaning.
- **Small text fix:** make the capture placeholder honest when AI is off.

**Reuses:** `PhraseResolver`, `CaptureDraft` and the capture follow-up row, `FollowUpSheet` + `ReminderProjection` preview, `ReminderPlanner`/`QuietHoursCalculator`, `TaskRepository.snooze` and generation bumping, `IntentContract` + `Receivers` (one new notification action), `StallDetector`, `SettingsRepository`, existing unit tests (`ReminderPlannerTest`, `QuietHoursCalculatorTest`, `StallDetectorTest`, `CaptureAtomicityTest`). This audit's simulation can become regression cases.

**Acceptance (sketch):** with AI off, "Turn off the fan before bed" saved with one tap rings tonight at bedtime despite 21:00 quiet hours. Done for tonight silences it until tomorrow's bedtime, and it rings again tomorrow. "Complete the work update by Monday 5pm" gets an exact deadline from the sentence. "Buy a gift for Sam" gets no date, no steps and no invented details. Existing tasks, alarms, channels and backups are unchanged. Physical-device checks of the bedtime nudge with the screen off and Doze are reported separately.

**Explicitly out:** deadline lead-time nudges (recommended as the following slice: it adds an occurrence stream and needs its own engine tests), weekly/yearly repeats, research-to-reminder proposals, event-date UI, any AI changes, Research Online changes, M3B multi-task review, shopping, booking, payments.

**Open decisions for the owner:** the bedtime default (21:30?) and whether bedtime should ring through quiet hours by default. Whether to change the shipped default preset for new installs (Persistent → Daily or Gentle); existing users keep theirs either way.
