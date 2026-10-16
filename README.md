# Life Admin Autopilot

An offline-first Android follow-through assistant. Capture a commitment in seconds, choose when
to be reminded, and the app keeps following up — for example **“Book train ride” at 09:00 and 18:00
every day** — until you explicitly mark it Done. Dismissing a notification never completes a task.

No account, no backend, no cloud sync, no analytics, no network permission, no AI (AI is planned
as an opt-in milestone and is shown as “Off” in Settings).

> Status: MVP milestones M0–M1C implemented and verified with JVM/Robolectric tests and lint.
> **Not yet verified on a physical device** — see `docs/OVERNIGHT_REPORT.md` for the device checklist.

## Features

- **Today** dashboard: Needs action / Waiting / Next 24 h counts, overdue, upcoming reminders,
  snoozed items, one-tap Done (with Undo) and Snooze.
- **Inbox** for quick captures; **Tasks** with filters (All, Actionable, Waiting, Paused,
  Completed, Archived) and search over titles and notes.
- **Quick capture** (FAB): type a title, then one tap for “Save to Inbox” or “Save & remind”.
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
- **Reminder health panel**: notification permission, app/channel state, delivery mode
  (best-effort vs precise), exact-alarm access, battery caveat, next reminder, last check, copyable
  privacy-safe diagnostics.
- **Backup**: plaintext JSON export/import through the system file picker, validated, merged by
  id, all-or-nothing.

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

### Install on your phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk     # package com.lifeadmin.autopilot.debug
# or the minified build:
adb install -r app/build/outputs/apk/release/app-release.apk # package com.lifeadmin.autopilot
```

Or download the `apks` artifact from the latest GitHub Actions run. The release APK is signed with
the Android debug key — fine for personal sideloading, **not** for distribution. To publish, create
your own keystore (never commit it) and configure a release `signingConfig`.

## Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | Show reminders. Requested the first time you turn on a reminder; if denied, tasks still work and the app shows a warning instead of pretending reminders were delivered. |
| `RECEIVE_BOOT_COMPLETED` | Android clears alarms on reboot; the app rebuilds them from its database. |
| `SCHEDULE_EXACT_ALARM` | Only used if you enable **Precise timing** and grant “Alarms & reminders” access. Otherwise reminders are best-effort. `USE_EXACT_ALARM` is intentionally not used. |

No internet, contacts, location, microphone, SMS, calendar or battery-exemption permissions.
(`WAKE_LOCK` / `ACCESS_NETWORK_STATE` are merged in by WorkManager and do not grant network access;
WorkManager's `FOREGROUND_SERVICE` is explicitly removed.)

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
- *Reminders late*: enable Precise timing (and grant access), and set battery usage to Unrestricted.
- *After moving time zones*: reminders follow the phone’s zone by default (09:00 stays 09:00
  local); choose “Keep fixed to …” on a task to pin a zone.
- *Bug report*: Settings → Copy diagnostics (contains counts and scheduler state, no task text).

## Docs

`docs/SPEC.md` (product/technical spec), `docs/ARCHITECTURE.md`, `docs/REMINDER_ENGINE.md`,
`docs/TEST_MATRIX.md`, `docs/PROGRESS.md`, `docs/OVERNIGHT_REPORT.md`.
