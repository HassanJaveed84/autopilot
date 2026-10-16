# Overnight report — Life Admin Autopilot offline MVP (M0 → M1C)

Session date: 2026-10-16 · Branch: `ccr-016fbc2f-7v3dn7` · Repo: `HassanJaveed84/autopilot`

## TL;DR

- All four milestones are implemented as real, working code, each with its own commit:
  **M0, M1A, M1B, M1C, plus a quality pass**. The canonical case — *“Book train ride”, remind at
  09:00 and 18:00 every day until Done* — works through the real engine and the Android adapters.
- **129 JVM/Robolectric tests pass, with 0 failures** (re-run twice to check for flakiness).
  Android lint reports **No issues found**. Debug and minified release APKs build locally.
- **Nothing has been run on a physical device or a local emulator.** This container has no
  KVM. GitHub Actions runs 3 instrumented tests on an emulator; see “CI” below for their status.
- **Draft PR not opened:** the GitHub repo had no commits and no `main` branch, so a PR has
  nothing to target. I did not create `main` myself (it is outside my authorized branch). See
  “Next actions”.

## Milestone checklist

| Milestone | Status | Commit |
|---|---|---|
| M0 Bootstrap: Gradle KTS + catalog, Room v1 + schema export, DataStore, manual DI with clock/zone/id seams, theme, nav scaffold, pure scheduler core, CI | ✅ | `1ac87e4` |
| M1A Lifecycle & UI: Today/Inbox/Tasks/Settings, detail, quick capture (≤2 taps), status machine, checklist, history, search/filter, deadline, archive/delete, JSON export/import, schema v2 + migration | ✅ | `f028211` |
| M1B Reminders: policy editor, slot recurrence, occurrence ledger, AlarmManager adapter, channels + Done/Snooze/Open, quiet hours, permission flow, boot/time/zone/exact-permission recovery, health UI, WorkManager safety net | ✅ JVM-verified · 📱 device pending | `b890165` |
| M1C Hardening: accessibility checks, diagnostics, performance baseline, permission cleanup, release APK, docs | ✅ container-verifiable parts · 📱 device pending | `7b724fe` |
| Fix: cold-start-from-notification crash | ✅ | `b03d0c2` |
| Quality pass: 8 review findings fixed | ✅ | `aaefdb7` |

The SPEC’s *definition of done* for M1B/M1C includes verifying on a real phone. That part is
**not done** and is listed below.

## What was implemented (highlights)

- **Tasks:** title, notes, priority, deadline (none / date only / exact), statuses INBOX,
  ACTIONABLE, WAITING (reason + check-in), PAUSED, DONE, CANCELED. Reopen. Snooze is a separate
  overlay, not a status. Archive and delete. Checklist steps. Activity log.
- **Reminders:** Off / once / daily at N times / every N days. Presets are Gentle, Daily and
  Persistent (09:00 + 18:00); all are editable. A reminder can follow the device time zone or use
  a fixed zone. Quiet hours (21:00–08:00) either defer and combine reminders, or skip them; each
  task can override the global setting. There is a grace window (4 h) and a daily cap (30).
- **Engine guarantees** (see `docs/REMINDER_ENGINE.md`):
  - one future occurrence per task;
  - unique `(policyId, plannedAt)` plus generation tokens and compare-and-set claims, so
    duplicate or stale broadcasts never notify twice;
  - Done cancels future alarms and dismisses the notification; swiping a notification away does
    nothing;
  - catch-up after downtime delivers up to 3 late reminders individually and combines more into
    a single summary;
  - if notification permission is denied, the reminder is recorded as “not shown” and never
    claimed as delivered.
- **Android behaviour:**
  - Default alarms are inexact `setAndAllowWhileIdle`. Exact alarms are used only if you turn on
    Precise timing *and* grant special access.
  - `USE_EXACT_ALARM`, foreground services, full-screen intents and battery exemptions are not
    used.
  - PendingIntents are immutable, internal receivers are not exported, and the system receiver
    handles only protected broadcasts.
- **Privacy:** no INTERNET permission, `allowBackup=false` with exclusion rules, logs contain only
  exception types, diagnostics contain only counts. The plaintext JSON backup shows a warning.

## Test evidence (exact commands, run in this container)

```
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
→ BUILD SUCCESSFUL · lint: "No issues found" · 129 tests, 0 failures, 0 errors
./gradlew testDebugUnitTest --rerun-tasks   (×2) → 129 / 0 failures both times
```

| Suite | Tests | Kind |
|---|---|---|
| ReminderPlannerTest | 28 | pure JVM: recurrence, DST, zones, quiet hours, snooze |
| QuietHoursCalculatorTest / LocalTimeResolverTest / TaskStateMachineTest | 7 / 5 / 5 | pure JVM |
| ReminderEngineTest | 32 | Robolectric Room + fake alarm/notification drivers |
| AndroidIntegrationTest | 9 | real AlarmManager / Notification adapters + receivers (Robolectric shadows) |
| TaskRepositoryTest / BackupRepositoryTest / DaoTest / MigrationTest | 11 / 7 / 5 / 1 | Robolectric Room/SQLite |
| AppUiTest / AccessibilityUiTest / MainActivitySmokeTest | 7 / 3 / 3 | Compose UI on Robolectric |
| ManifestContractTest / PerformanceAndDiagnosticsTest | 5 / 1 | manifest & performance baseline |
| androidTest (CI emulator only): InstrumentedDatabaseTest, InstrumentedReminderTest, InstrumentedUiTest | 3 | 🟦 not run locally |

Bugs that these tests and the review caught and that were fixed tonight:
- `LocalDate.ofInstant` needs API 34 (minSdk is 26);
- engine work on the main thread stalled Room transactions;
- a cold start from a notification tap crashed the app;
- receiver timeouts could cut engine work off part-way;
- reminders due just before a reboot were left without an alarm;
- combined “summary” reminders were marked delivered while the Summaries channel was off;
- quiet hours ending inside the repeated DST hour deferred a reminder by a day;
- deep links were replayed on rotation;
- plus minor issues. Full list in `docs/PROGRESS.md`.

Coverage mapped to SPEC §10: `docs/TEST_MATRIX.md`.

## CI (GitHub Actions, `.github/workflows/android-ci.yml`)

- Job 1: lint, unit/Robolectric tests, debug + release APKs, uploaded as the **`apks`** artifact.
- Job 2: emulator API 33, `connectedDebugAndroidTest`.
- Observed tonight:
  - The M0 build job passed; its emulator failed to boot. I hardened the emulator config after
    that.
  - The M1A run passed both jobs, but there were no instrumented tests yet.
  - Later runs were cancelled by newer pushes, which is intended (`cancel-in-progress`).
  - **Run #7 (commit `5f6b418`, final code + report):** build job ✅ **success** on GitHub's
    runner (lint, all unit/Robolectric tests, debug + release APKs uploaded as `apks`).
    Instrumented job ❌ **failed: “Timeout waiting for emulator to boot”**, so no instrumented test
    executed (this is not a test failure). The API 33 / pixel_6 / swiftshader config I added had
    not booted, while the original API 34 config booted on the M1A run. I reverted to API 34 with a
    longer boot timeout. The outcome of that run is in “CI follow-up” at the end of this file.

## APK location

- Built locally in this (ephemeral) container:
  - `app/build/outputs/apk/debug/app-debug.apk` (14.2 MB, package `com.lifeadmin.autopilot.debug`,
    sha256 `92e6ed00…784c36`)
  - `app/build/outputs/apk/release/app-release.apk` (2.2 MB, R8-minified, package
    `com.lifeadmin.autopilot`, signed with the **Android debug key**, `apksigner verify` OK,
    sha256 `7fdd00ee…e31814`)
- APKs are gitignored and the container will be reclaimed. Get them from the **`apks` artifact**
  of the latest Actions run on this branch, or rebuild with `./gradlew assembleDebug`.
- The debug key is fine for personal sideloading. Use your own keystore before distributing.

## Known limitations (Android OS / device; documented in the app and in the README)

- Best-effort delivery: reminders may be minutes late, more in Doze or Battery Saver. Precise
  timing needs “Alarms & reminders” access, and even then it is not an absolute guarantee.
- Force stop cancels all alarms until the app is opened again.
- OEM battery managers (e.g. Samsung “Sleeping apps”) can delay or block reminders.
- Flying west with “follow device zone” can make the same wall-clock slot fire twice on one
  calendar day. That is consistent with the design, but noted.
- Typing inside dialogs is tested only by the CI instrumented test, because of a Robolectric
  limitation.
- The toolchain is pinned to AGP 8.13.2 / compileSdk 36. Newer AndroidX needs compileSdk 37 +
  AGP 9.1.
- No AI, sync or agents (out of scope, M2+). Settings shows “AI assistance: Off”.

## What you must test on your physical phone (SPEC §10.3)

Install the debug APK. Allow notifications when prompted (the prompt appears the first time you
turn on a reminder). Open Settings → Reminder health and confirm everything is green.

1. **Train tickets:** create “Train tickets” and set 2 daily times a few minutes ahead (Task →
   Reminders → Set up → edit times). Lock the phone. Check that both reminders arrive and that
   the Done / Snooze / Open buttons work.
2. **Swipe away** a reminder. The task stays open and the next reminder still arrives.
3. **Done from the notification.** The task completes and no more alarms fire (Reminder health →
   “No reminders scheduled”). Reopen it; reminders resume.
4. **Snooze** from the notification (tomorrow 09:00). The 18:00 reminder is skipped and the
   schedule resumes the next day.
5. **Doze:** `adb shell dumpsys deviceidle force-idle`. Note how late the reminder arrives.
6. **Deny notification permission** (or turn it off in settings). The app still works, Today shows
   the red banner, and the task's “Recent” list says “Not shown”.
7. **Reboot.** Reminders return. Settings → Last check shows `boot: …`.
8. **Change time zone and wall clock.** The 09:00 reminder stays at 09:00 local. Changing the
   clock does not repeat a reminder that was already delivered.
9. **Precise timing:** turn it on and grant / revoke “Alarms & reminders”. The status switches
   between exact and best-effort.
10. **Force stop** in Android settings. Confirm reminders stop until you open the app (expected
    Android behaviour).
11. **Samsung battery / DND / channel blocked:** set battery usage to Unrestricted vs Optimized,
    and test with “Task reminders” blocked.
12. **Airplane mode:** create and edit a task with a near-future reminder; it still arrives.
13. **Export, uninstall, reinstall, import.** The data is back and there is no reminder flood.
14. **Load:** create about 100 tasks with 2–3 times each. Check startup speed and that Copy
    diagnostics shows about 100 active occurrences.

For each, log the device and Android version, permissions, battery mode, planned vs actual time,
and the result.

## Next actions for you

1. Create a base branch so a PR can be opened. For example, on GitHub create an empty `main`
   branch (or tell me to push one). Then open a **draft PR** from `ccr-016fbc2f-7v3dn7`.
2. Download the `apks` artifact and run the device checklist above.
3. Optional: decide whether a duplicate wall-clock slot after flying west should be suppressed.

## Sandbox notes (no repo impact)

- The Android SDK was installed from `dl.google.com`; the cmdline-tools sha1 was checked against
  Google's manifest.
- Maven Central rate-limited this container (HTTP 429 with a retry-after of about 17 h). I routed
  it through Google's official Maven Central mirror using `~/.gradle/init.d` and
  `~/.gradle/gradle.properties`. Neither file is committed.

## CI follow-up

- Run for the emulator-config revert: see the latest Actions run on this branch. If the emulator
  job is still red, the 3 instrumented tests (`app/src/androidTest`) have **not been executed
  anywhere**. Run them locally with `./gradlew connectedDebugAndroidTest` with a device attached.
