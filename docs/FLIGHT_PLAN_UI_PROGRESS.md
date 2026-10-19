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
- [ ] Phase B — theme tokens, bundled OFL fonts, shapes, motion, icons, shared components, shell (capture bar + 3 tabs + settings button)
- [ ] Phase C — capture sheet, AI setup / drafting / review, follow-up sheet, task detail
- [ ] Phase D — Today flight plan, Inbox triage, Tasks list
- [ ] Phase E — Settings, Reminder health, AI settings, appearance, accessibility pass
- [ ] Phase F — tests, lint, APKs, report, push, draft PR

## Changed files

Maintained in `docs/FLIGHT_PLAN_UI_REPORT.md` at the end of the run (git history has the per-phase detail).

## Blockers / limitations

- No emulator or device in the container: screenshots of the real app are not possible here; the CI screenshot
  workflow (`.github/workflows/screenshots.yml`) remains the way to capture them.
