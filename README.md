# Life Admin Autopilot

An offline-first Android follow-through assistant. Capture a commitment in seconds, choose when
to be reminded, and the app keeps following up — for example **“Book train ride” at 09:00 and 18:00
every day** — until you explicitly mark it Done. Dismissing a notification never completes a task.

No account, no backend, no cloud sync, no analytics. Tasks and reminders work fully offline.
**Optional AI planning** (M2, off by default) can turn a sentence into a reviewed task using your
own Anthropic API key; the network is used only when you tap a button that asks for it.

> Status: M0–M1C (offline MVP) and M2 (optional AI capture/planning) implemented and verified with
> JVM/Robolectric tests, lint and CI emulator tests. M2 has **not** been tested against the live
> Anthropic API or on a physical device yet — see `docs/M2_AI_CAPTURE_REPORT.md`.

## Features

The UI follows the **Flight Plan** design (see `docs/FLIGHT_PLAN_UI_REPORT.md`): calm ink on
mineral paper, orange for time, indigo (always dashed) for anything AI suggested, green for done.

- **Today** is a flight plan: a headline sentence, then the day's nudges on a rail with a "Now"
  marker — the first nudge of each task as a card, later repeats as dashed ghost rows, plus
  attention rows for overdue deadlines, deadlines due today and check-ins that have passed even
  when no reminder is set. Waiting on others, Not today and Done today follow as compact rows.
- **Inbox** triage: each note gets Nudge me · Plan with AI · Waiting on… · Done already · Delete.
  **Tasks**: search, All / Needs you / Waiting / Snoozed / Paused / Done / Archived with counts,
  sort by next nudge, deadline or newest.
- **Capture** bar on every tab: one field, a follow-up row with a switch (the primary button
  reads "Save & nudge" or "Save to Inbox"), "Plan with AI" beside it, and a notes / priority /
  deadline disclosure.
- **Task detail**: title, notes, priority, date-only or exact deadline, status actions
  (Make actionable, Waiting with check-in, Pause/Resume, Done, Cancel, Reopen), snooze with exact
  next-reminder preview, checklist (add/check/reorder/remove), reminder schedule, activity log and
  recent reminder ledger, archive and delete.
- **Reminder schedules**: Off, once at a date/time, daily at one or more times, every N days at
  set times; editable presets (Gentle, Daily, Persistent 09:00 + 18:00); follow device time zone or
  fixed zone; per-task quiet-hours override.
- **Reliable-as-Android-allows delivery**: one durable occurrence ledger in Room, a single next
  alarm per task, idempotent receivers, recovery after reboot / app update / time change /
  time-zone change / exact-alarm permission change, catch-up with a grace window and no
  notification storms.
- **Notifications** with Done / Snooze / Open actions; tapping opens the task.
- **Quiet hours** (default 21:00–08:00, defer-and-combine or skip), daily notification cap,
  lock-screen privacy.
- **Reminder health screen** (Settings → Reminder health): a plain-language verdict (“Reminders
  will probably be silent — phone in Vibrate mode and channel vibration off”); permission,
  channel importance, sound, vibration, pop-up, ringer mode, Do Not Disturb and volume, with direct
  links to the right Android settings; approximate vs precise timing with measured late-delivery
  stats; Samsung battery guidance; **Send test notification now** and **Schedule test reminder in
  2 minutes**; a recent delivery log (scheduled → alarm received → posted, with actual times and
  lateness); copy/share of privacy-safe diagnostics.
- **Backup**: plaintext JSON export/import through the system file picker, validated, merged by
  id, all-or-nothing.
- **Plan with AI** (optional): from Capture, type or dictate e.g. “book the train ride for my
  daughter, find parking and tickets, remind me at 9 and 6 every day until done”. Claude drafts
  a title, notes, up to 6 steps, a next step, deadline and reminders. You review and edit
  everything before saving. Suggested dates are labelled and not set unless you turn them on.
  Unsupported schedules (e.g. “every weekday”) are shown with a supported alternative, never
  silently converted.
- **Help me move this forward** (optional, on a task): a suggested next step or missing steps,
  shown as a diff you confirm. Separately, a local, AI-free hint appears when a task is snoozed
  repeatedly or stalls (“add a smaller step”, “move the date”, “mark waiting”, “pause”).

## Build

Requirements: JDK 17+ (21 used), Android SDK with platform 36 and build-tools 36.0.0.

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM + Robolectric tests
./gradlew lintDebug
./gradlew connectedDebugAndroidTest  # needs an emulator/device
./gradlew assembleRelease          # minified; signed with the debug key for personal use
```

Toolchain: AGP 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21, Compose BOM 2026.06.01, Room 2.8.5,
WorkManager 2.11.2, DataStore 1.2.1. CI: `.github/workflows/android-ci.yml` (lint, unit tests, debug
and release APKs as artifacts, emulator instrumented tests).

### Screenshots for UI review

`.github/workflows/screenshots.yml` runs only on demand (Actions → **Screenshots** → *Run workflow*).
It drives the debug app on an API 34 emulator through every main screen, dialog and AI state
(`app/src/androidTest/.../ScreenshotTour.kt`) and uploads the PNGs as the artifact
`screenshots-<display>-<theme>-font<scale>`. Options: display `phone` / `small` (320 dp) /
`tablet`, `light` / `dark` theme, font scale `1.0` / `1.3` / `2.0`, and an optional subset such as
`com.lifeadmin.autopilot.ScreenshotTour#c_taskDetail`. AI screens use a scripted answer, not the
live API. It also runs when a push changes `.github/screenshots-request.json` (up to 6 variants per
request; edit `reason` to request a fresh run) — that is how a coding agent without permission to
dispatch workflows asks for screenshots.

### Install on your phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk     # package com.lifeadmin.autopilot.debug
# or the minified build:
adb install -r app/build/outputs/apk/release/app-release.apk # package com.lifeadmin.autopilot
```

Or download the `apks` artifact from the latest GitHub Actions run.

**Keep your data across updates.** Android only installs an update signed with the same key as the
installed app, and a plain debug build is signed with a key that differs per machine (and per CI
run), which forces an uninstall and wipes the app's data. Personal builds therefore use one stable
key when it is available and fall back to the debug key otherwise:

- CI: add the repository secrets `AUTOPILOT_KEYSTORE_B64` (the `.jks` file, base64) and
  `AUTOPILOT_KEYSTORE_PASSWORD`. Forks and repos without them still build, with the debug key.
- Locally: put the same file at `keystore/personal.jks` (gitignored) and set
  `AUTOPILOT_KEYSTORE_PASSWORD` in the environment or `autopilot.keystorePassword` in
  `~/.gradle/gradle.properties`.

Create one with `keytool -genkeypair -keystore personal.jks -alias autopilot -keyalg RSA -keysize 2048 -validity 10000`.
This key is for sideloading only, **not** for store distribution. Switching keys requires one
uninstall: use Settings → Export first, then Import after installing.

## Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | Show reminders. Requested the first time you turn on a reminder; if denied, tasks still work and the app shows a warning instead of pretending reminders were delivered. |
| `RECEIVE_BOOT_COMPLETED` | Android clears alarms on reboot; the app rebuilds them from its database. |
| `SCHEDULE_EXACT_ALARM` | Only used if you enable **Precise timing** and grant “Alarms & reminders” access. Otherwise reminders are best-effort. `USE_EXACT_ALARM` is intentionally not used. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Only for optional AI planning requests to `api.anthropic.com`, started by you. TLS only, system CAs only. Nothing runs in the background. |

No contacts, location, microphone, SMS, calendar or battery-exemption permissions.
(`WAKE_LOCK` / `ACCESS_NETWORK_STATE` are merged in by WorkManager and do not grant network access;
WorkManager's `FOREGROUND_SERVICE` is explicitly removed.)

## Optional AI planning (bring your own key)

1. Create an API key at [console.anthropic.com](https://console.anthropic.com) (API usage is billed
   to that Console account; a Claude app or Claude Code subscription does **not** cover it).
2. In the app: Settings → **Plan with AI** → turn on **Use AI planning**. Read and accept the
   privacy note: only the text you submit, the date/time/zone and your reminder settings are sent.
3. Paste the key, tap **Save key**, then **Test connection** (a free models-endpoint check).
4. Capture → **Plan with AI** (with AI off this opens the setup sheet in place, keeping your
   note), or on a task → **Stuck? Ask Claude → Suggest**.

The key is encrypted with an Android Keystore key, stored only in app-private storage, excluded
from backups and Export, and never logged. This is a personal-use compromise; a public app would
use a server-side proxy instead. Default model: Claude Haiku 5.5 (cheapest). Sonnet 5.5 and
Opus 5.5 are selectable. Limits: 2,000 characters per request, 6,000 output tokens, 60 requests
per day, no automatic retries. **Delete AI settings** removes the key and all AI settings.

### Optional web research (M3 / M3A.2, bring your own key)

On a task, **Move this forward → Research online** asks Claude to search the web (Anthropic's
`web_search` server tool) and returns a structured result shaped by what you asked for: a quick
answer, a shortlist with a comparison table, one pick per requested item, how-to steps or a
checklist. Every requirement you listed is reported with a status (answered, your call, needs a
check, partly, not found), facts are tagged by where they come from (Fact / Reviews / Community /
Judgment / Calc / Unknown), and prices show the retailer, size and when they were checked.

- **Preflight first.** The app extracts your requirements on the phone (editable, nothing sent),
  proposes a deliverable template, and lets you pick a depth: Quick (Haiku, 3 searches), Thorough
  (Sonnet, 10 searches, two passes) or Deep (Opus, 25 searches, cross-check and write-up). Model,
  search budget (1–30) and an optional soft spending limit are adjustable; the estimate is shown as
  a range before you tap Start. No limit is the default — quality first, with an honest estimate.
- **Honest progress and outcomes.** Stages come from the app's own plan and real responses; counts
  are only what Anthropic reported. A result is *Complete* only when every requested item is
  covered — running out of searches or money is reported as *Incomplete*, with a one-tap
  **Continue researching** that picks up from the saved result (sources keep their numbers, nothing
  is searched twice). Stop offers keep-what's-found or discard.
- **Proposals are separate and editable.** One reviewable proposal per requested item; edit the
  wording and the note, untick, "Add anyway" past a duplicate, then Apply once (Undo while the task
  is unchanged). Research never changes reminders, status, priority or deadlines.
- **Cost ledger.** Settings → Plan with AI → **AI usage** shows today, this month, by feature and
  per run, estimated from Anthropic's reported tokens and searches × a versioned price table.
  Unknown usage (a stopped or failed call) is never shown as $0. An optional monthly budget gives a
  note at 80% and a confirmation at 100%; nothing is ever blocked. Anthropic's Console is the bill.
- Results are saved with the task, open offline and with AI off, and are included in backups
  (never transcripts, prompts or the key). Only structured citations from the API become sources;
  links open in your browser, never inside the app.

Live verification against the real API with a personal key is pending (see
`docs/M3A2_IMPLEMENTATION_REPORT.md`); everything is tested against fakes and a local MockWebServer.

## Limitations (honest)

- Default delivery is **best-effort**: Android may delay alarms by minutes, more in Doze or
  Battery Saver. Precise timing requires the special access and still isn’t an absolute guarantee.
- **Force stop** cancels all alarms until you open the app again.
- Some manufacturers (e.g. Samsung “Sleeping apps”) may delay or block reminders; set battery
  usage for the app to *Unrestricted* if reminders arrive late.
- Data lives only on the device and is excluded from Android cloud backup; export a backup file
  yourself. The export is plaintext.

## Troubleshooting

- *No reminders*: Settings → Reminder health. Check permission, app notifications, the “Task
  reminders” channel and the in-app “Reminder notifications” switch; tap **Re-check schedule**.
- *Reminders late*: Reminder health shows how late recent reminders were and whether the alarm
  itself was late or the reminder was caught up when the app opened. Enable Precise timing (and
  grant “Alarms & reminders”), and set battery usage to Unrestricted.
- *No sound or vibration*: Reminder health → check “Phone sound mode” and “Channel vibration”. In
  Vibrate mode you only feel reminders if the channel's Vibrate option is on. Use **Send test
  notification now** to check.
- *After moving time zones*: reminders follow the phone’s zone by default (09:00 stays 09:00
  local); choose “Keep fixed to …” on a task to pin a zone.
- *Bug report*: Settings → Copy diagnostics (contains counts and scheduler state, no task text).

## Docs

`docs/SPEC.md` (product/technical spec), `docs/ARCHITECTURE.md`, `docs/REMINDER_ENGINE.md`,
`docs/TEST_MATRIX.md`, `docs/PROGRESS.md`, `docs/OVERNIGHT_REPORT.md`,
`docs/REMINDER_RELIABILITY_REPORT.md`, `docs/M2_AI_CAPTURE_REPORT.md`.
