# Life Admin Autopilot

An offline-first Android follow-through assistant. Capture a commitment in seconds, choose when
to be reminded, and the app keeps following up — for example **“Book train ride” at 09:00 and 18:00
every day** — until you explicitly mark it Done. Dismissing a notification never completes a task.

No account, no backend, no cloud sync, no analytics, no network access. Tasks and reminders work
fully offline.

**Autopilot manages commitments; ChatGPT handles intelligence.** To plan, research, compare or
decide, tap **Discuss in ChatGPT** on a task: the conversation happens in your ChatGPT app, and its
**Open in Autopilot** link brings the suggestions back for you to review and apply selectively.
The built-in AI (Plan with AI, Move this forward, Research Online, the Anthropic API key and usage
screens) was retired on 2026-10-27; research saved by earlier versions stays readable, read-only
(`docs/RETIRE_EMBEDDED_AI_REPORT.md`).

## Features

The UI follows the **Flight Plan** design (see `docs/FLIGHT_PLAN_UI_REPORT.md`): calm ink on
mineral paper, orange for time, indigo for suggestions waiting for review, green for done.

- **Today** is a flight plan: a headline sentence, then the day's nudges on a rail with a "Now"
  marker — the first nudge of each task as a card, later repeats as dashed ghost rows, plus
  attention rows for overdue deadlines, deadlines due today and check-ins that have passed even
  when no reminder is set. Waiting on others, Not today and Done today follow as compact rows.
- **Inbox** triage: each note gets Nudge me · Waiting on… · Done already · Delete.
  **Tasks**: search, All / Needs you / Waiting / Snoozed / Paused / Done / Archived with counts,
  sort by next nudge, deadline or newest.
- **Capture** bar on every tab: one field, a follow-up row with a switch (the primary button
  reads "Save & nudge" or "Save to Inbox"), timing read offline from your words ("tonight",
  "every night", "by Monday 5pm"), and a notes / priority / deadline disclosure.
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
- **Discuss in ChatGPT** (on an open task): a sheet shows exactly what ChatGPT will see (the
  task always; steps, deadline and notes only if you switch them on) and hands it to the ChatGPT
  app. ChatGPT's **Open in Autopilot** link (`https://hassanjaveed84.github.io/autopilot/import#ap=v1…`,
  a verified App Link) opens the **Proposal Review** for that task: pick the steps and notes you
  want, apply them in one go, undo if needed. Repeated links never propose twice. No API key and
  no request from Autopilot itself (`docs/CHATGPT_ROUND_TRIP_REPORT.md`).
- **Stall hints**: a local hint appears when a task is snoozed repeatedly or stalls (“add a
  smaller step”, “move the date”, “mark waiting”, “pause”).
- **Saved research** from earlier versions with built-in AI: readable from its task and from
  Settings → Saved research (shown only when there is some), with its sources; suggestions it had
  already made still open in the Proposal Review. Nothing new can be researched in the app.

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
It drives the debug app on an API 34 emulator through every main screen and dialog
(`app/src/androidTest/.../ScreenshotTour.kt`) and uploads the PNGs as the artifact
`screenshots-<display>-<theme>-font<scale>`. Options: display `phone` / `small` (320 dp) /
`tablet`, `light` / `dark` theme, font scale `1.0` / `1.3` / `2.0`, and an optional subset such as
`com.lifeadmin.autopilot.ScreenshotTour#c_taskDetail`. Saved research is seeded locally. It also runs when a push changes `.github/screenshots-request.json` (up to 6 variants per
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

No `INTERNET` permission: the app makes no network requests (ChatGPT is a separate app; the App
Link is verified by Android). No contacts, location, microphone, SMS, calendar or battery-exemption
permissions.
(`WAKE_LOCK` / `ACCESS_NETWORK_STATE` are merged in by WorkManager and do not grant network access;
WorkManager's `FOREGROUND_SERVICE` is explicitly removed.)

## Built-in AI, retired

Earlier versions had optional Plan with AI, Move this forward and Research Online on your own
Anthropic API key. They were removed on 2026-10-27 in favour of Discuss in ChatGPT. What stays:

- Research saved by those versions (M3A briefs, M3A.2 sessions, M4 investigations, Research Online
  threads) is kept in the database and in backups and opens read-only; delete a thread from its
  screen if you no longer want it.
- Changes you already applied, and their provenance (“From Plan with AI”, “From research”), stay on
  your tasks; pending suggestions from that research can still be reviewed.
- If an earlier version stored an Anthropic API key, Settings → **Remove the old Anthropic API key**
  deletes it (it is never used or sent). The row disappears once it is gone. Revoke the key in the
  Anthropic Console too if you no longer use it.

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
`docs/REMINDER_RELIABILITY_REPORT.md`, `docs/CHATGPT_ROUND_TRIP_REPORT.md`,
`docs/RETIRE_EMBEDDED_AI_REPORT.md` (and the historical AI reports it links to).
