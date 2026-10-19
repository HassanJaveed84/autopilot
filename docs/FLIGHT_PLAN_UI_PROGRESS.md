# Flight Plan UI migration — progress log

Branch: `ccr-0d6d408c-plqo7g` (the session's designated branch; nothing is merged into `main`).
Design source of truth: `Life_Admin_Autopilot_Flight_Plan_Overnight.zip` → `design/Claude_Design_Redesign.html`
(decoded boards: interactive prototype, three directions, 24-state screen gallery, design system, handoff).
The ZIP was extracted into a session scratch directory, **not** into the repository.

## Environment (Phase A)

- Linux container, JDK 21, Gradle 8.14.3 wrapper, AGP 8.13.2, Kotlin 2.2.21.
- Android SDK installed from `dl.google.com` (command-line tools 11076708, platform 36, build-tools 36.0.0,
  platform-tools) at `/opt/android-sdk`; `local.properties` is git-ignored.
- Maven Central rate-limits this container (HTTP 429). Session-only workaround, **not part of the repo**:
  `~/.gradle/init.d/central-mirror.gradle.kts` redirects `repo.maven.apache.org` to Google's mirror
  `maven-central.storage-download.googleapis.com`, and `~/.gradle/gradle.properties` sets `robolectricRepoUrl`.
- No KVM / emulator / device: instrumented tests and the screenshot tour run only in GitHub Actions.
- Headless Chromium (Playwright) was available, so the design boards were rendered to PNG for visual reference.

## Baseline before any UI change (Phase A)

Command: `./gradlew --stacktrace lintDebug testDebugUnitTest assembleDebug`

| Check | Result |
|---|---|
| `lintDebug` | passed (no errors) |
| `testDebugUnitTest` | **295 tests, 0 failures, 0 errors, 0 skipped** |
| `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` built |

No pre-existing failures.

## Feature-preservation matrix (current UI action → redesigned destination)

| Area | Today's app | Flight Plan destination | Status |
|---|---|---|---|
| Navigation | Bottom nav Today · Inbox · Tasks · Settings | Today · Inbox (count badge) · Tasks; Settings as a header button (warning dot on health issues) | Phase B |
| Capture | FAB on Today/Inbox/Tasks → sheet with Save to Inbox / Save & remind | Persistent capture bar; sheet with one field, follow-up row + switch, one primary button (Save & nudge / Save to Inbox) | Phase C |
| Capture extras | Add notes or priority | "Notes, priority, deadline" disclosure (deadline uses the real pickers) | Phase C |
| Plan with AI | Link in capture → separate screen | Button beside Save in the same sheet; text carries over; AI-off → setup sheet in place | Phase C |
| AI setup | Settings → AI planning (consent dialog, key field) | Setup sheet in place (consent + key) and the full AI planning screen | Phase C/E |
| AI drafting | Progress bar + cancel | Drafting screen with request-lifecycle stages, Cancel, "Couldn't reach" error with Save without AI / Try again | Phase C |
| Plan review | Cards with switches; provenance chips | Provenance-tagged fields (dashed = suggested, solid = edited), steps include/edit, follow-up with "You said…", deadline row, Draft · not saved pill, summary footer | Phase C |
| Follow-through suggestions | Detail card → Get a suggestion… | "Stuck? Ask Claude" card → same review flow with opt-in changes and confirmation | Phase C |
| Task detail | Done/Snooze…/Waiting…/Pause buttons; cards | Status pill, follow-up card, full-width Mark done, Snooze / Waiting on… / Pause tiles (Wake / Stop waiting / Resume), inline steps with Next marker, deadline + priority rows, AI card, history | Phase C |
| Reminder editor | Full-screen dialog (presets, Once/Daily/Every N, times, start, quiet hours, zone, preview) | Follow-up sheet: preset cards, No nudges, fine-tune, time chips, next nudges preview; advanced section keeps start date, quiet-hours rule, time zone | Phase C |
| Snooze | Dialog with 1h/3h/tomorrow + custom, exact next-reminder preview | Sheet with concrete clock times, custom picker, same preview | Phase C |
| Waiting | Dialog: reason + check-in choices + custom | Sheet: who/what + check-in chips + custom | Phase C |
| Today | Count tiles, Overdue, Needs action, Waiting, Upcoming, Snoozed | Headline sentence, inbox prompt, nudge rail (Now marker, real next occurrences, ghost repeats), attention entries for overdue / due-today / due check-ins without reminders, Waiting on others, Not today, Done today, empty state | Phase D |
| Inbox | Row + Make actionable / Schedule… | Triage card: Nudge me, Plan with AI, Waiting on…, Done already, Delete (confirmed) | Phase D |
| Tasks | Search + All/Actionable/Waiting/Paused/Completed/Archived | Search + All / Needs you / Waiting / Snoozed / Paused / Done / Archived with counts, sort by Next nudge / Deadline / Newest | Phase D |
| Settings | Presets, snooze, quiet hours, privacy, backup, AI, about | Health status card first, Follow-up group, Privacy, AI row, Appearance (System/Light/Dark), Your data, About + open-source licences | Phase E |
| Reminder health | Status card, tests, alert details, timing, battery, switches, log, copy/share | Hero, Test a nudge, What Android reports (Fix buttons → same deep links), timing/precision, app switches, log, copy/share | Phase E |
| Notifications | Channel `action_reminders` with Done/Snooze/Open | Unchanged (channel id and user settings preserved; only the small icon asset may change) | — |
| Backup | Export/Import with confirmations | Same, in "Your data" | Phase E |
| Onboarding | Welcome card with "Try an example" | Empty Today state with example prompts and the explicit example-task button | Phase D |

## Checklist

- [x] Phase A — baseline build/tests, environment notes, preservation matrix
- [x] Phase B — theme tokens (`ui/theme/Color.kt`, light + dark), bundled OFL fonts (`res/font`, licences in
      `assets/licenses` and a Licences screen), typography/shapes/motion, icon set ported from the prototype path data
      (`ui/components/AutopilotIcons.kt`), shared primitives (buttons, chips, pills, cards, groups, switch rows, provenance
      border, task card, status-sentence formatter), shell (capture bar + Today/Inbox/Tasks + Settings header button with
      health dot), appearance preference (System/Light/Dark) persisted in DataStore
- [x] Phase C — capture sheet (one field, follow-up row + switch, one primary button, Plan with AI beside it, notes/priority/
      deadline disclosure, in-place AI setup sheet with consent + key), Follow-up sheet (presets, No nudges, fine-tune,
      next-three preview via the engine's planner, advanced start/quiet-hours/zone), drafting screen driven by the real
      request lifecycle, review with dashed/solid provenance, task detail (status pill, follow-up card, Mark done, Snooze /
      Waiting on… / Pause tiles, inline steps with Next, deadline + priority rows, AI card, history), snooze/waiting/more sheets
- [x] Phase D — Today planner (`ui/today/TodayPlanner.kt`: Now marker, first nudge card + ghost repeats from
      `ReminderProjection`, attention rows for overdue / due-today / due check-ins / late nudges, Waiting on others, Not today,
      Done today, inbox prompt, empty "A clear day" state), Inbox triage cards, Tasks filters with counts + sort + search
- [x] Phase E — Settings (health first, Follow-up group, Privacy, AI row, Appearance, Your data, About + licences), Reminder
      health (hero, test a nudge, What Android reports with Fix deep links, timing, battery, switches, log), AI planning screen
- [x] Phase F — final run `lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`:
      lint clean, 309 unit tests green, debug + release + test APKs built; branch pushed; CI and the screenshot
      tour requested on GitHub Actions; `docs/FLIGHT_PLAN_UI_REPORT.md` written; draft PR opened

## Changed files

See the per-phase commits on the branch ("Flight Plan Phase B/C/D/E", "Flight Plan tests", "Flight Plan polish").
Summary: `ui/theme/*` (new tokens, type, shapes, motion), `ui/components/*` (icons, primitives, shell, task card,
presentation, sheets), `ui/today/*`, `ui/tasks/*`, `ui/capture/*`, `ui/plan/*`, `ui/detail/*`, `ui/settings/*`,
`ui/health/Health.kt`, `ui/ai/AiSettingsScreen.kt`, `ui/LifeAdminRoot.kt`, `MainActivity.kt`, `res/font`,
`assets/licenses`, launcher/notification icons; additive data changes (`TaskSummary.policy`,
`AppSettings.appearance`, `ProposalDraft` provenance flags, `TaskActions.capture`, `ReminderProjection`).
Note: the Phase B–E commits were developed together and build only from Phase E onwards.

## Blockers / limitations

- No emulator or device in the container: screenshots of the real app are not possible here; the CI screenshot
  workflow (`.github/workflows/screenshots.yml`) remains the way to capture them.
