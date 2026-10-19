# Flight Plan UI migration — report

**Branch:** `ccr-0d6d408c-plqo7g` (pushed; nothing merged into `main`).
**Design source:** `Life_Admin_Autopilot_Flight_Plan_Overnight.zip` → `design/Claude_Design_Redesign.html`
(decoded into its five boards and rendered to PNG with headless Chromium for reference). Direction **A: Flight Plan**.
**Progress log:** `docs/FLIGHT_PLAN_UI_PROGRESS.md`.

## 1. What was built

Native Jetpack Compose / Material 3 screens wired to the existing ViewModels, repositories, Room database,
reminder engine, notification channel and Anthropic integration. No WebView, no mock data, no hard-coded dates.

| Screen / flow | Implementation |
|---|---|
| Theme | `ui/theme/Color.kt`: `AutopilotColors` light + dark tokens from the design system (paper, surface, sunken, line, ink ×3, signal, route, done, danger, toast…) exposed via `LocalAutopilot`; mapped onto Material 3 (`Theme.kt`) with **dynamic color off**. Appearance preference System / Light / Dark persisted in DataStore (`AppSettings.appearance`) and applied in `MainActivity` with matching system-bar icon styles. |
| Type | Bricolage Grotesque (display/titles), Atkinson Hyperlegible Next (body/labels), Atkinson Hyperlegible Mono (clock times, tabular) bundled as variable TTFs in `res/font` (SIL OFL; licence texts in `assets/licenses` and shown in Settings → Open-source licences). Nothing below 12sp; captions 13sp. |
| Icons | `ui/components/AutopilotIcons.kt`: the prototype's 24-unit stroke path data ported to `ImageVector`s; route-mark notification icon and adaptive launcher icon (`res/drawable`). |
| Shell | `ui/LifeAdminRoot.kt` + `ui/components/Shell.kt`: persistent capture bar above a three-tab bar (Today · Inbox with count badge · Tasks); Settings is a header button with a warning dot when reminder health needs attention; toast-styled snackbar. |
| Shared components | `ui/components/Primitives.kt` (ink-filled primary / outlined secondary / route buttons, choice chips, status pills, cards, row groups, switch rows, provenance border, link buttons), `TaskCard.kt` (card, check circle, compact rows, done-today row), `TaskPresentation.kt` (one status-sentence formatter: "09:00 & 18:00 daily, until done", "Waiting on X · check in …", "Snoozed until …", "Paused · no nudges"). |
| Today | `ui/today/TodayPlanner.kt` + `TodayScreen.kt`: headline sentence, inbox prompt, nudge rail with Now marker; first nudge per task is a card, later repeats are dashed ghost rows; attention rows ("Overdue", "Due 17:00", "Check-in due", "Nudge due") for items with no scheduled nudge; Waiting on others, Not today, Done today; "A clear day" empty state with example prompts (open Capture pre-filled) and the explicit example-task button. |
| Rail times | `domain/scheduling/ReminderProjection.kt` iterates `ReminderPlanner.next` (the engine's own rules: quiet-hours deferral and coalescing, fixed zones, snooze, check-ins). The first result is the occurrence Android holds; later ones are a preview of what the engine will plan next. |
| Capture | `ui/capture/CaptureSheet.kt` + `CaptureViewModel.kt`: one field with a 2000-character counter, follow-up row ("Nudge me 09:00 & 18:00 daily · your default · tap to change") with a switch, primary button reads **Save & nudge** / **Save to Inbox**, **Plan with AI** beside it, notes / priority / deadline disclosure with the real date and date-time pickers. Draft text lives in the ViewModel (survives rotation, leaving and returning); saving commits once via `TaskActions.capture`. |
| AI setup in place | With AI off or not set up, Plan with AI swaps to "Turn on AI planning?": note kept, Paste for the key, what is sent, full privacy/billing note, "I understand · Turn on & plan" records consent, stores the key (encrypted store, never in UI state) and continues only when the service reports Ready. |
| Drafting / error | `ui/plan/PlanScreen.kt`: full-screen drafting state with stages driven by the real request lifecycle (sent → waiting → checking), Cancel cancels the job; "Couldn't reach Anthropic" / rate limit / etc. keep the words and offer **Save without AI** / **Try again**. Arriving from Capture sends the request once (that tap is the explicit request); rotation and process death never resend. |
| Plan review | `ui/plan/PlanReview.kt`: dashed indigo = suggested, solid = edited by you (flags on the draft only, `ProposalDraft.*Edited`, ignored by `ProposalMapper`); steps include/edit/remove/add; follow-up block with "You said …", interpretation callout (Looks right / Change), first-nudges preview from the planner, schedule adjustment; deadline row; "Draft · not saved" pill and a "Saves 1 task · 3 steps · nudges …" footer. Follow-through suggestions use the same review with the existing Apply confirmation. |
| Task detail | `ui/detail/TaskDetailScreen.kt`: status pill (+ High priority), Bricolage title and notes, follow-up card in plain words with Change / Add / Wake / Resume / Edit, full-width **Mark done** (Reopen when closed), Snooze / Waiting on… / Pause tiles with contextual inverses (Wake, Stop waiting, Resume, Make actionable), inline steps with a **Next** marker and reorder/remove, Deadline and Priority rows, "Stuck? Ask Claude" card (or "AI suggestions are off · Set up"), collapsible history with recent nudges. More actions sheet: edit, archive/restore, cancel, delete. |
| Follow-up sheet | `ui/detail/FollowUpSheet.kt`: preset cards (Persistent, Daily, Gentle, Once), No nudges, fine-tune (Once / Daily / Every N days, time chips, N stepper), until-done and quiet-hours notes, **Next nudges** preview, advanced start date / quiet-hours override / time zone. Used by Capture, Task detail and Inbox "Nudge me". |
| Snooze / Waiting | `ui/detail/DetailSheets.kt`: snooze options show the resulting clock time (In 1 hour · 09:42 …, Next week, Pick a date & time) with the exact next-reminder preview and "Remind me when the snooze ends"; waiting sheet with who/what and check-in chips. |
| Inbox | Triage cards: Nudge me (follow-up sheet → policy + Actionable), Plan with AI (existing follow-through flow on that task, no duplicate task), Waiting on…, Done already (Undo = reopen), Delete (confirmed). |
| Tasks | Search, All / Needs you / Waiting / Snoozed / Paused / Done / Archived with counts, sort by Next nudge / Deadline / Newest. |
| Settings | Health status card first, Follow-up group ("Save & nudge uses" preset sheet, snooze button options, quiet hours with from/until and behaviour), Privacy, AI planning row with On/Off pill, Appearance, Your data (Export / Import with the same confirmations), About + licences. |
| Reminder health | Hero coloured by level, Test a nudge (the same two real tests), What Android reports (rows with check/warn badges and **Fix** buttons deep-linking to the matching Android settings), timing / Precise timing, battery, app switches, delivery log with copy/share. Channel id `action_reminders` untouched. |
| AI planning settings | Switch (same consent dialog), key with Paste / Remove / Test connection / Replace, "Exactly what's sent" lists, billing, model choice, removal. |

Undo is offered only where a real inverse exists and is wired: Done → reopen (restores status and reschedules),
Snooze → clear snooze, Pause → resume. Delete uses a confirmation instead of a decorative Undo. The prototype's
"Preview the first nudge" (a simulated notification) was deliberately not built.

## 2. Preserved functionality (feature matrix)

| Capability | Status |
|---|---|
| Inbox / Actionable / Waiting / Paused / Done / Canceled / Archived states and transitions | Preserved (same `TaskRepository` / `TaskStateMachine`) |
| Task CRUD, notes, priority, date-only and exact deadlines | Preserved (Edit dialog, Deadline dialog, Priority chips) |
| Checklist add / check / reorder / remove | Preserved (inline steps with Next marker) |
| One-time and recurring reminders, presets, every N days, times, start date, quiet-hours override, fixed zone | Preserved (Follow-up sheet incl. Advanced) |
| Snooze with exact next-reminder preview, "remind at snooze end" | Preserved (Snooze sheet) |
| Waiting with reason and check-in (incl. custom date/time) | Preserved (Waiting sheet) |
| Quiet hours, default preset, default snooze, lock-screen privacy | Preserved (Settings) |
| Notification channel, Done / Snooze / Open actions, deep link to task and to health | Unchanged (only the small icon asset changed) |
| Precise / best-effort timing, exact-alarm permission flow, notification permission request | Preserved (Health) |
| Reminder health tests, diagnostics copy/share, channel / app / DND / exact-alarm / app-details deep links | Preserved (+ Fix buttons) |
| Backup export / import (SAF, confirmations) | Preserved |
| AI BYOK settings, consent, key store, test connection, model, daily cap, delete all | Preserved (+ in-place setup from Capture) |
| Natural-language AI planning with editable proposal and explicit approval; follow-through suggestions | Preserved (approval still writes once, idempotent `createPlannedTask` / `applyTaskEdit`) |
| Stall hints | Preserved (card on task detail) |
| Onboarding example task | Preserved (explicit button on the empty Today) |
| Room schema, migrations, receivers, WorkManager, alarm engine | Untouched |
| Settings tab | Replaced by a header button + pushed screen (design decision 12) |
| "Needs action / Waiting / Next 24h" count tiles | Replaced by the headline sentence and the rail (design decision: counts live on Tasks filters) |

Data layer changes, all additive: `TaskSummary.policy` (read-only join in `observeSummaries`),
`AppSettings.appearance` + DataStore key, `ProposalDraft` presentation flags (default false, not mapped to tasks),
`TaskActions.capture` (composes existing `createTask` / `savePolicy` / `setDeadline`), `ReminderProjection` (pure).

## 3. Tests and build evidence

All commands were run in the cloud container (JDK 21, Android SDK platform 36 / build-tools 36.0.0, Gradle 8.14.3,
Maven Central via Google's mirror because `repo.maven.apache.org` rate-limits the container).

Baseline before any UI change: `lintDebug testDebugUnitTest assembleDebug` → 295 tests, 0 failures, lint clean.

Final run on the branch head:

```
./gradlew --offline --stacktrace lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
```

| Check | Result |
|---|---|
| `lintDebug` | passed — "No errors or warnings" |
| `testDebugUnitTest` | **309 tests, 0 failures, 0 errors** (295 existing + 14 new) |
| `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (≈ 28.9 MB) |
| `assembleRelease` | `app/build/outputs/apk/release/app-release.apk` (≈ 3.9 MB; minified, signed with the debug key as before) |
| `assembleDebugAndroidTest` | built (instrumented tests run only in GitHub Actions) |

New JVM tests (14): `TaskPresentationTest` (status sentences, deadline tones, TalkBack text), `TodayPlannerTest`
(card vs ghost repeats, attention rows for deadlines and check-ins, snoozed/paused/waiting kept out of "now", inbox /
done today / empty) and `ReminderProjectionTest` (matches `ReminderPlanner`, quiet-hours coalescing, once, snooze-end).
Existing Compose UI tests (Robolectric: `AppUiTest`, `AccessibilityUiTest` at 200% font scale on a 360dp screen,
`PlanScreenUiTest`, `AiSettingsUiTest`, `ReminderHealthUiTest`, `MainActivitySmokeTest`) were updated for the new
flows and pass; the AI tests still exercise the mocked-HTTP provider, approval idempotency and key security.

Not run here (no KVM / emulator / device in the container): `connectedDebugAndroidTest` and the screenshot tour.
Both run on GitHub Actions: Android CI on every push (`.github/workflows/android-ci.yml`, emulator API 34) and the
screenshot tour requested by the commit that changed `.github/screenshots-request.json` (light, dark, 200% text,
small display). See §5 for the run links.

## 4. Visual verification

- The design boards were rendered to PNG (headless Chromium) and used as the reference while building; the
  layouts, copy, spacing (4dp grid, 20dp gutter, 16dp card padding), radii and tokens follow the gallery.
- The app itself could not be launched in the container, so **no app screenshots were captured locally** and visual
  parity is not claimed here. The CI screenshot tour (§5) produces the real screens on an emulator in light, dark,
  200% text and a 320dp display; compare them with `design/Claude_Design_Redesign.html` boards 1 and 3.

## 5. Branch, CI and PR

- Branch `ccr-0d6d408c-plqo7g`; draft PR #1: https://github.com/HassanJaveed84/autopilot/pull/1 (base: the
  repository default branch `ccr-016fbc2f-7v3dn7`; no `main` exists). Commits "Flight Plan migration: Phase A …"
  through "Flight Plan polish …", a merge of the default branch, then the three follow-ups below.
- **Android CI run #34 on `e1a9011` — green**: lint, 309 unit/Robolectric tests, debug/release/test APKs, and the
  emulator instrumented tests (API 34). https://github.com/HassanJaveed84/autopilot/actions/runs/39236307210
  Run #35 (the pull_request-event twin of #34 on the same commit) failed one instrumented test,
  `InstrumentedUiTest.captureThenMarkWaitingWithTypedReason`, with "Expected exactly 1 node but found 2": during
  the tab crossfade the new task's title is on Today's inbox prompt as well as on the Inbox card. Test-side race;
  the click is now scoped to the Inbox list. Runs #29–#33 were cancelled by the concurrency rule as commits landed.
- **Screenshots run #8 on `e1a9011` — green on all four variants** (phone light, phone dark, phone at 200% text,
  320dp small display): https://github.com/HassanJaveed84/autopilot/actions/runs/39236307168, artifacts
  `screenshots-phone-light-font1.0`, `screenshots-phone-dark-font1.0`, `screenshots-phone-light-font2.0`,
  `screenshots-small-light-font1.0` (≈50 screens each, 30-day retention). The screens were downloaded and compared
  with boards 1 and 3 of `design/Claude_Design_Redesign.html`: Today rail, Capture, AI setup, Review plan, task
  detail, Tasks, Settings, Reminder health and the dark theme match the boards. Two large-text problems were
  visible in the 200% set and fixed in `42cee13`: Inbox triage labels truncated to "Nudge…" (buttons now stack in
  one column from font scale 1.3) and the Inbox badge grew over its icon (fixed dp size).
  Run #7 on `8f5b297` had failed in the tour's own selectors after the card redesign (title merged into the
  clickable node; cards below the fold at 200% / 320dp); fixed in `e1a9011`.
- **Runs on `33c69d9`**: Android CI #38 (push event) green incl. emulator tests
  (https://github.com/HassanJaveed84/autopilot/actions/runs/39239561905); its pull_request twin #39 failed one
  pre-existing emulator test, `InstrumentedReminderTest.oneTimeReminderPostsNotificationThenDoneClearsIt`, which
  gave the notification's Done broadcast 15 s to land while two emulator jobs shared the runner (test timing; the
  wait is now 60 s). Screenshots #9 on `42cee13`: three of four variants green; the dark variant lost one Back
  press to a dialog still settling in `d_otherDetails` (tour timing; Back now repeats until the tab bar is back).
  The 200% artifacts from #9 confirmed the stacked triage buttons and the fixed-size badge, and showed the
  follow-up card's "Change" link squeezing the sentence and "Waiting on…" truncating on the action tiles; both
  now reflow from font scale 1.3 (`5913c89`).
- **Final commit `5913c89` — all green**: Android CI #40 (push) and #41 (pull_request), each with lint, 309 unit
  tests, APKs and the emulator instrumented tests
  (https://github.com/HassanJaveed84/autopilot/actions/runs/39244018515,
  https://github.com/HassanJaveed84/autopilot/actions/runs/39244023678); Screenshots #10 green on all four
  variants (https://github.com/HassanJaveed84/autopilot/actions/runs/39244018421), whose `screenshots-*`
  artifacts are the visual evidence for this report.
- Soft-skipped detail shots (edit dialog, overflow menu) are captured since `42cee13`: the header is the detail
  list's first item and had scrolled out of composition.

## 5b. Design parity pass (after the first review)

A screen-by-screen comparison of the CI screenshots with the handoff boards found seven gaps. All seven are
fixed on the branch (`4661328`, `ad52a10`) and verified again on the emulator:

| # | Board | Was | Now |
|---|-------|-----|-----|
| 1 | 09 Move this forward | Reused the Plan-review layout; "Apply changes…" truncated; extra confirmation dialog | Checkable diff cards (uppercase kind, route tint when selected), "Not now" / "Apply N changes" counting the selected cards, "Sent: …" footer, no dialog |
| 2 | 15 AI unreachable / plan input | Stock Material field with floating label; two overlapping explanations; back arrow; "Retry" | Capture-style field (label above, sunken box, helper row); X + Draft pill; one explanation; the words as a read-only card with "Edit your words"; "Try again" |
| 3 | 03 AI setup | "I understand · Turn on & plan" truncated | "Turn on & plan" |
| 4 | 04 Drafting | Different stage copy, model name in the subline, large Cancel button | Board copy ("Reading your note / Breaking it into steps / Fitting nudges around your quiet hours"), no model name, X cancels |
| 5 | 07 Task detail steps | Reorder/delete controls on every row, two plus icons | Plain checklist; reorder/delete behind "Edit" in the card header; one plus |
| 6 | 10 Snooze | Checkbox, preview line and Cancel/Snooze buttons | One tap per row; "Remind me when the snooze ends" as a switch at the bottom; "09:42" / "Tue 09:00" column |
| 7 | 06 Notification | "Snooze" | "Snooze · tomorrow 09:00" from the same per-task or preset target the action uses |

Also verified for the first time (the tour now captures them): the empty Today (board 13), Inbox clear (board 14)
and the notification shade (board 06). Two further large-text fixes came out of the 200% screenshots: button
pairs stack from font scale 1.3 (Plan screen, Capture, AI setup) and the detail follow-up card / action tiles
reflow.

Evidence: Android CI #45 (push) and #46 (pull_request) green on `ad52a10`, including the emulator tests
(https://github.com/HassanJaveed84/autopilot/actions/runs/39324032239). Screenshots #12: phone light and phone
dark green (https://github.com/HassanJaveed84/autopilot/actions/runs/39324032160); the 200% and small-display
jobs in that run never booted their emulator ("Timeout waiting for emulator to boot", no test ran) — the same
two variants had passed on the previous commit's run #11, which exercised the same tour code. The notification
shot comes from run #11's 200% job; in run #12 the one-off alarm did not fire within the tour's two-minute wait.
`PlanScreenUiTest.followThroughCountFollowsTheSelectedCards` covers the Apply count going from 3 to 2 when a
card is deselected.

Kept deliberately off the boards: "approximate timing" on the follow-up card and the "Once" preset wording
(the spec forbids claiming exact delivery and hard-coded prototype times), and the extra privacy/billing links
on the AI setup sheet.

## 6. Known limitations and risks

- Rail times beyond the first are previews computed with the planner; the engine schedules one occurrence per task
  and plans the next after it fires. If a later preview looked wrong it would be a planner rule, not a UI rule.
- "Nudge due" attention rows appear when Android has not yet delivered an occurrence whose time has passed (late
  within the grace window); they disappear once the engine delivers or settles it.
- The in-place AI setup sheet records consent with the same text as the Settings dialog; billing and privacy notes
  are one tap away ("Read the full privacy and billing note") rather than forced reading.
- Bundled fonts add ≈570 KB to the APK. Variable-font axes need API 26+ (the app's minSdk).
- Predictive back is enabled app-wide (`enableOnBackInvokedCallback`); Compose/Material 3 sheets handle it, but
  dialogs should be checked on Android 14+ devices.
- The old `TopAppBar`-based screens are gone; `LifeAdminTheme(dynamicColor = …)` keeps its parameter for source
  compatibility but dynamic color is always off by design.
- Robolectric cannot type into a text field inside a `ModalBottomSheet` reliably, so the Waiting-sheet typing flow
  stays an instrumented test (`InstrumentedUiTest`), as before with the dialog.

## 7. Real-device checklist (not done here)

1. Install `app-debug.apk`; open Today with an existing database from the previous build: tasks, reminders, settings
   and the API key must survive (no migration was added; schema version unchanged).
2. Capture "Book train ride" with the switch on → card on the rail at 09:00 and a dashed 18:00 ghost; notification
   at 09:00 with Done / Snooze / Open; Done from the notification clears the rail entry.
3. Capture with the switch off → Inbox; Nudge me → Follow-up sheet → "Make it a task" moves it to the rail.
4. A task with a deadline today and no reminder appears as "Due …" on the rail; an overdue one as "Overdue".
5. Snooze from a notification and from the sheet: the rail shows "Snooze ends …", the task is not in "needs you now",
   Undo on the snackbar wakes it.
6. Waiting with a check-in today: slim check-in row on the rail, task listed under Waiting on others.
7. Settings → Appearance Dark / Light / System; status-bar icons stay readable; Reminder health rows and Fix buttons
   open the right Android pages; Send a test now / Test with phone locked still post on the `action_reminders` channel
   with the user's sound/vibration settings unchanged.
8. Plan with AI with AI off → setup sheet keeps the note; Not now returns to Capture with the text; with a key →
   drafting → review → Save task creates exactly one task with real reminder occurrences (check Reminder health "Next
   reminder"). Airplane mode → "Couldn't reach Anthropic" → Save without AI.
9. Font size 200% and TalkBack: rail time labels sit above the cards; every control announces (cards merge into one
   sentence, check circles say "Mark <title> done"); keyboard open over the capture sheet keeps the buttons reachable.
10. Export then import on a second device; lock-screen privacy; predictive back on Android 14+.
