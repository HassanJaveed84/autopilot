# Reminder reliability report

Trigger: real-device test on a Samsung Galaxy S24 Ultra, 2026-10-16. A one-time reminder for
“Test” was set for 18:25. The activity log showed **“Reminder delivered (late)” at about 18:49**.
At 19:30 the notification was in the shade showing **18:25**. Nobody noticed a sound or
vibration. Settings at the time:

- notification permission granted;
- channel “Alert”, sound Spaceline, *Show as pop-up* off, *Vibrate* off;
- phone most likely in Vibrate mode;
- Precise timing off.

## 1. Root causes — confirmed vs suspected

Phase 1 audit of the code as shipped in the APK you tested (`aaefdb7`). Each item is labelled
by the strength of the evidence.

### Confirmed by code inspection

| # | Finding | Evidence |
|---|---|---|
| F1 | **The time in the shade is the scheduled time, not the posting time.** “18:25” says nothing about when the notification was posted. | `AndroidNotificationPublisher.postReminder` called `setWhen(notification.plannedAt)`. |
| F2 | **The reminder was handed to Android about 24 minutes after 18:25.** The event’s timestamp is the moment the delivery code ran. The “(late)” suffix is only added when that moment is more than 15 minutes after the planned time. | `ReminderEngine.deliverLocked` writes `REMINDER_DELIVERED` with `at = clock.now()`; `late = overdue > LATE_LABEL (15 min)`. |
| F3 | **The log cannot say *why* it was late.** The identical text is written by: (a) the alarm receiver, (b) catch-up when the app is started or opened (`app_start`, `app_resume`), (c) the 6-hour WorkManager safety net, and (d) boot or time-change recovery. When the alarm broadcast arrived was never recorded, nor which path posted the notification. | `deliverLocked` is reached from `onAlarm` and from `reconcileTaskLocked` (catch-up). The event metadata only stored `kind`. |
| F4 | **“Vibrate: off” and “Show as pop-up: off” are this app’s own channel defaults.** The channel was created without `enableVibration(true)` (Android’s default is no vibration) and with `IMPORTANCE_DEFAULT` (sound, no heads-up). | `ensureChannels()` → `NotificationChannel(CHANNEL_REMINDERS, …, IMPORTANCE_DEFAULT)` with no vibration setting. |
| F5 | **Best-effort mode uses `AlarmManager.setAndAllowWhileIdle`.** Android documents this as *inexact*: it is never delivered early, but it “may be deferred”. In Doze, allow-while-idle alarms are also rate-limited. | `AndroidAlarmDriver.schedule` when `precisionMode = false`. |
| F6 | **The 6-hour safety net is much longer than the 4-hour grace window.** If the OS drops or long-defers an inexact alarm and the user never opens the app, the reminder can be marked MISSED instead of being caught up. | `ReconcileWorker`: `PeriodicWorkRequestBuilder(6, HOURS)`; grace window default 240 min. |

### Suspected — needs the new diagnostics on your phone to confirm or rule out

- **S1 — the alarm itself was delayed by Android.** Plausible causes:
  - the inexact delivery window; AOSP’s implementation allows inexact alarms a window that
    grows with the lead time, roughly 75% of it;
  - Doze maintenance windows;
  - App Standby buckets;
  - Samsung battery management.

  All of these can produce a delay of around 24 minutes with Precise timing off. This is the
  most likely explanation, but **not proven**: the build you tested did not record when the
  alarm broadcast arrived.
- **S2 — the alarm never fired, and the reminder was caught up when the app process started or
  was opened around 18:49.** The same log text would appear. It cannot be told apart from S1
  with the old build.
- **S3 — the silence came from the combination of Vibrate mode and channel vibration being off.**
  In Vibrate mode Android does not play notification sounds. AOSP can turn a sound into the
  default vibration in Vibrate mode, but Samsung’s behaviour is **not verified**. With channel
  vibration off (F4), a completely silent delivery is plausible. Pop-up was off (F4), so nothing
  appeared over the screen either.

Ruled out by inspection:
- Wrong time zone or UTC handling in the log. Events are stored as `Instant` and shown in the
  device zone; a one-time reminder is resolved with the same zone.
- A duplicate or extra post: the occurrence claim is compare-and-set.

## 2. Actual code changes

Commits on `ccr-016fbc2f-7v3dn7` after the tested build (`aaefdb7`): `ee82acd` (audit),
`4fff965` (observability), `04f155c` (health screen), `b7b1751` (hardening), `8bb7863` (docs),
`eca688f` (CI), plus the review/final commits listed in section 3.

### Observability (Phase 2) — answers F1, F2, F3
- **Notification time is now the real posting time.** The notification uses `setWhen(postedAt)`.
  The due time moves to the subtext: “Due 18:25”, or “Late · due 18:25” when late. A late
  reminder can no longer look on time in the shade.
- **New delivery ledger.** The `reminder_diagnostics` table (Room v3, `MIGRATION_2_3`) gets one
  row per lifecycle step:
  - `SCHEDULED`, `SCHEDULE_FAILED`, `ALARM_RECEIVED`, `ALARM_IGNORED`;
  - `RECONCILE`, `POST_REQUESTED`, `POSTED`, `POST_FAILED`;
  - `SUPPRESSED`, `MISSED`;
  - `SNOOZED`, `UNSNOOZED`, `CANCELED`;
  - `TEST_*` for the self-tests.

  Each row stores:
  - the planned time and the trigger time handed to AlarmManager;
  - approximate or precise mode;
  - the source (`alarm`, `reconcile:app_start`, `reconcile:app_resume`, `reconcile:periodic`,
    `reconcile:boot`, `mutation`, …);
  - lateness, outcome, and a reason or error code.
- **Real receipt time.** The receiver reads the clock first, so `ALARM_RECEIVED.at` is the
  receipt time even if the engine lock is busy.
- **Device state at posting time.** `POST_REQUESTED` stores an environment snapshot:
  - ringer mode, DND filter, notification volume;
  - the channel's importance, sound, vibration and DND bypass;
  - Doze, Battery Saver, battery-optimisation exemption, background restriction, standby bucket;
  - whether exact alarms are allowed.
- **`notify()` is not treated as seen.** `POSTED` means only that `notify()` returned
  (`notify_returned`); no row ever claims a person saw the reminder.
  - The self-tests also poll `getActiveNotifications()` for up to 2 s, because `notify()` only
    enqueues, and record `listed_active` / `not_listed_active`.
  - Coalesced reminders get `POSTED in_summary` only after the summary notification was actually
    posted.
- **The activity log says how late and why.** Examples:
  - “Reminder delivered 24 min late · alarm arrived late (approximate timing)”
  - “… · caught up when the app was opened or started”
  - “… · caught up by periodic check”
- **Privacy.** The ledger has no titles, notes or steps, and no foreign keys, so it survives
  task deletion. It keeps at most 3,000 rows or 45 days. The export uses shortened ids.

### Attention and self-test (Phase 3) — answers F4 and S3
- **Reminder health screen** (Settings → Reminder health; also the Today banner and a tap on the
  test notification).
  - **Verdict.** The pure `AlertAssessor` returns *blocked*, *probably silent*, *vibrate only*,
    *audible* or *uncertain*, with concrete reasons. Vibrate mode with channel vibration off
    gives “will most likely be silent”.
  - **What it shows:** permission, app notifications, channel importance, sound, vibration,
    pop-up, ringer mode, DND, notification volume, and approximate vs precise timing.
  - **Direct links:** the channel page, app notification settings, DND, “Alarms & reminders”, and
    app details for battery.
  - **Explanations** of Vibrate, Silent and DND, why pop-up is optional, and Samsung battery
    settings (Unrestricted, Sleeping apps).
  - **Measured late stats:** how many recent deliveries were more than 5 min late, and the worst
    lateness.
  - **Recent delivery log**, with Copy / Share diagnostics.
- **Send test notification now:** posts on the real reminder channel and reports whether Android
  lists it as active.
- **Schedule test reminder in 2 minutes:** uses a real AlarmManager alarm, the same mode as task
  reminders, and the same receiver. When it arrives it states how late it was and is logged.
- **Channel defaults.**
  - New installs: “Task reminders” uses `IMPORTANCE_DEFAULT` (sound, no forced pop-up),
    **vibration on**, and a short pattern.
  - Existing installs: the channel is never modified, deleted or recreated. Your “Vibrate off”
    stays until you change it; the health screen points at it.
- **Timing is labelled.** Task detail shows “approximate timing (may arrive late)” or “precise
  timing” next to the next reminder.

### Hardening (Phase 4) — answers F6
- **Hourly safety net.** The WorkManager reconcile now runs hourly (was 6 h), using
  `ExistingPeriodicWorkPolicy.UPDATE`, so existing installs pick up the new period. A dropped or
  deferred alarm is caught up well inside the 4 h grace window without opening the app. It is
  still not the reminder clock.
- **Precise timing applies at once.** On returning from “Alarms & reminders” with access granted,
  existing alarms are re-registered as exact. They are logged `SCHEDULED … outcome=mode_changed`.
- **Today banner** when the latest reminder arrived more than 10 min late on approximate timing.
- **Cancellation reasons** are logged precisely: done, paused, snoozed, schedule changed,
  superseded, …
- **The late banner and stats count only alarm-path lateness.** A catch-up delay (phone off,
  dropped alarm, force-stop) is shown separately and never blamed on approximate timing.
- **DND verdicts.** *Alarms only* and *Total silence* are reported as silent even if the channel
  may bypass DND; the bypass applies only to *Priority* mode.
- **Ledger accuracy.**
  - A refused re-registration of a due alarm is logged `SCHEDULE_FAILED`, not `SCHEDULED`.
  - The scheduled self-test falls back to approximate if exact access is revoked mid-call, like
    real reminders.
  - Uneventful hourly checks are hidden from the on-screen log; they stay in the export.
- The manifest's alarm receiver also accepts the test-alarm action; it is still `exported=false`.
- **Not added, on purpose:** foreground services, wake locks, `USE_EXACT_ALARM`, full-screen
  intents, battery-exemption requests, internet permission.

## 3. Test results and evidence

### Run in this container (Linux, JDK 21, Android SDK 36, no device or emulator)

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
```

Result at `104965c`:
- **BUILD SUCCESSFUL**;
- lint: **No issues found**;
- **185 JVM/Robolectric tests, 0 failures, 0 errors, 0 skipped** (176 before the code review,
  123 at the original M1C). Counts are taken from the JUnit XML in
  `app/build/test-results/testDebugUnitTest/`.

New or extended tonight (all passing):

| Class | Tests | What it proves |
|---|---|---|
| `DiagnosticsLifecycleTest` | 20 | See the list below the table. |
| `AlertAssessorTest` | 13 | S24 case (Vibrate + channel vibration off → silent); Silent; volume 0; DND Priority with/without bypass; DND alarms-only/total silence → silent; blocked states; pop-up only with high importance; compact environment string |
| `NotificationChannelAndContentTest` | 5 | New channel vibrates with default importance; an existing channel the user changed is **not** modified; notification `when` = posting time and subtext “Late · due 18:25”; test notification uses the reminder channel; environment probe reads ringer mode and channel vibration |
| `SelfTestTest` | 6 | Test notification now; blocked → reported as failed; listed only after a delay still counts; never listed → `not_listed_active`; 2-minute test alarm scheduled, received, lateness recorded, posts once; exact when precise timing is on and granted |
| `HardeningTest` | 7 | One-time reminder keeps absolute time across a zone change; hourly safety net catches a dropped alarm within the grace window; late banner only for recent late **alarm** deliveries on approximate timing, not for catch-ups or old rows; hours formatting; exact upgrade after the permission is granted |
| `MigrationTest` | 2 | v1→v2 and **v2→v3** (diagnostics table added, existing data intact) |
| `ReminderHealthUiTest` | 4 | Health screen flags Vibrate + channel vibration off as probably silent; approximate timing clearly indicated; “Send test notification now” posts once; “Schedule test reminder in 2 minutes” uses the alarm driver |
| `AndroidIntegrationTest` | 9 | Real `AlarmManager` → receiver → engine → `NotificationManager` under Robolectric; now independent of time of day (quiet hours) |

`DiagnosticsLifecycleTest` covers:
- scheduled with planned and trigger time and approximate mode;
- a late alarm records the **receipt** time and post time, not the planned time;
- a catch-up is attributed to `reconcile:app_resume`, with no alarm receipt;
- a cold-start race still records the alarm receipt;
- a duplicate broadcast is ignored and posts once;
- on-time wording;
- denied permission → SUPPRESSED with an environment snapshot;
- Done, pause and snooze cancellation reasons;
- `exact_unavailable` shown when precise timing is on but not granted;
- quiet-hours deferral note;
- boot recovery records re-registration and missed entries;
- repeated idempotent reconciles do not flood the ledger;
- coalesced rows are POSTED only after the summary is posted, and none if it failed;
- a refused re-registration → SCHEDULE_FAILED;
- routine-reconcile filter;
- export contains no task titles;
- pruning.

The existing scheduler suites are unchanged and still green:
- `ReminderEngineTest` (32) and `ReminderPlannerTest` (28);
- DST spring/fall;
- the canonical “Book train ride” 09:00 + 18:00 case;
- reboot, time-change and zone-change recovery;
- idempotency.

### Independent code review
A separate review pass over all of tonight's changes found no high-severity issues. Its seven
findings were all fixed in `104965c`, with the regression tests listed above (see section 2,
Hardening). Areas the reviewer checked and found clean:
- API 26 compatibility;
- deadlocks;
- refresh loops;
- PendingIntent identity;
- receiver export;
- privacy of the export;
- the migration SQL against the exported schema.

### Instrumented tests (emulator)
`InstrumentedReliabilityTest` adds:
- a fresh-install channel vibrates;
- the test notification is listed active;
- the 2-minute test alarm arrives through the real AlarmManager.

They compile here (`assembleDebugAndroidTest` OK) but could not run here, because no emulator
can run in this container. They **passed in GitHub Actions run #15** on an API 34 emulator; see
section 4.

### Not performed
**No physical-device test was performed.** Nothing in this report claims Samsung behaviour was
observed. See section 6.

## 4. Build and APK status

| Artifact | Path (in the build tree, gitignored) | Size | SHA-256 |
|---|---|---|---|
| Debug APK (`com.lifeadmin.autopilot.debug`) | `app/build/outputs/apk/debug/app-debug.apk` | 14.4 MB | `5b38bfcfcdf32987b8826de8c628ccbb34fabd0ea0d52a297e23530bc911b1f3` |
| Release APK (minified, **debug-signed**, personal use only) | `app/build/outputs/apk/release/app-release.apk` | 2.3 MB | `b9af8161edbbaaf40549afbeb293969840d30858af08fff29ac6be039d352204` |

- The debug APK installs over the build you tested (same package and signing key). The database
  migrates v2 → v3 on first start.
- It was sent to you in this chat. CI also uploads both APKs as the `apks` artifact.
- **CI:** `.github/workflows/android-ci.yml`.
  - Build job: lint, unit tests, APKs.
  - Emulator job: API 34, now with a cached AVD snapshot, because run #9 timed out while the
    emulator booted and ran no tests.
  - Runs #10–#13 were cancelled by newer pushes (`cancel-in-progress`).
  - Documentation-only pushes no longer trigger CI.
  - **Run #15 (`0137ab6`, all of tonight's code) — both jobs green:**
    - build job: lint, unit tests and APKs passed (08:30–08:38 UTC);
    - emulator job: **6/6 instrumented tests passed, 0 failed, 0 skipped**, on an API 34
      (Android 14) emulator (08:41–08:49 UTC). The emulator booted from the newly cached snapshot.
    - The 6 tests are the 3 earlier ones (real alarm → notification → Done, Room migration,
      Waiting dialog) plus the 3 new `InstrumentedReliabilityTest` cases:
      - fresh-install channel vibrates;
      - the test notification is listed active;
      - the 2-minute test alarm arrives through the real AlarmManager.
    - Link: https://github.com/HassanJaveed84/autopilot/actions/runs/39017536077
    - An emulator is not a Samsung phone: these results say nothing about One UI battery
      management or Vibrate-mode handling.
- **Draft PR: not created.** The repository has no `main` (or other default) branch, only this
  feature branch, so a PR has no base. Creating `main` was outside what I was authorized to do.
  To open one, create `main` from the commit you consider the baseline, then open a PR from
  `ccr-016fbc2f-7v3dn7`.

## 5. Remaining platform limitations

These are Android or Samsung behaviours the app cannot override. It now **reports** them instead
of hiding them.

1. **Approximate timing can be late.** It is the default (`setAndAllowWhileIdle`). Android may
   defer it; AOSP allows a window of about 75% of the lead time, and Doze rate-limits
   allow-while-idle alarms. A reminder set hours ahead can arrive tens of minutes late. Precise
   timing needs the user-granted “Alarms & reminders” access. Even exact alarms are not absolutely
   guaranteed under extreme battery management.
2. **Samsung battery management** (“Sleeping apps”, “Deep sleeping apps”, adaptive battery) can
   delay or drop alarms and kill the process. The app does not request exemptions. The user can
   set battery usage to *Unrestricted*.
3. **Channel settings belong to the user once the channel exists.** Sound, vibration, importance
   and pop-up cannot be changed by the app. Upgrading does not turn vibration on for an existing
   channel; you must enable it in the channel settings (one tap from Reminder health).
4. **Vibrate mode suppresses sound.** Whether Samsung converts a channel sound into a vibration
   is **not verified**. The assessor treats “channel vibration off + Vibrate mode” as silent.
5. **DND exceptions can't be read** without extra permission. With DND on and no channel bypass,
   the verdict is *uncertain*.
6. **Seen is not measurable.** `POSTED` / `listed_active` proves only that Android accepted and
   kept the notification, not that the device made a sound or that you noticed.
7. **Force stop** cancels all alarms until the app is opened again; the app then catches up
   within the grace window.
8. **The incident itself can't be re-diagnosed.** The build you tested did not record alarm
   receipt, so S1 vs S2 can only be decided with the new build (test 4 below).

## 6. Physical-device tests for you (S24 Ultra)

None of these were performed here; no device was attached. Install the new debug APK over the
old one (`adb install -r app-debug.apk`, or open the file). Your data is migrated to schema v3
automatically.

1. **Health screen.** Open Settings → *Open reminder health & tests*. The verdict should say reminders will
   probably be **silent** if the phone is in Vibrate mode and the channel Vibrate is still off.
   Note the verdict text.
2. **Fix vibration.** Tap *Open reminder channel settings* → turn **Vibrate** on → go back. The verdict
   should change to *vibrate only* (in Vibrate mode) and “Channel vibration” should read *On*.
   Leave pop-up as you prefer.
3. **Immediate test.** Tap **Send test notification now** with the screen on. Expect:
   - a vibration in Vibrate mode (or your Spaceline sound in Sound mode);
   - the “Last test” line “notification posted (listed active)”;
   - the shade time equal to *now*.
4. **Alarm test with the phone locked** — the key S1/S2 test.
   - Tap **Schedule test reminder in 2 minutes**, lock the phone, and leave it on the table.
     Don't open the app.
   - Note when it buzzes, then open Reminder health. The “Last test” card shows “alarm arrived
     +XmYYs”, and the log has a “Test alarm received” row.
   - Arrives within a few minutes: alarms are working in approximate mode.
   - Nothing arrives until you open the app: the alarm is being dropped. Report the log line.
5. **Real task reminder, approximate.**
   - Create “Test 2” with a one-time reminder 15 min ahead. Lock the phone, wait, and note the
     time it buzzes.
   - Then open Reminder health → *Copy diagnostics* and send it to me.
   - In the export, find that occurrence (`occ=` is the same on each line) and look at the
     `ALARM_RECEIVED … late=` line and the `POST_REQUESTED … via=` line.
   - `via=alarm` means the alarm fired, late by `late=` (S1).
   - No `ALARM_RECEIVED` and `via=reconcile:app_start`, `app_resume` or `periodic` means the alarm
     never came and the reminder was caught up (S2).
   - The `[ringer=… channel=…]` part shows the phone state at posting time.
6. **Precise timing.**
   - Settings → turn on **Precise timing** → grant “Alarms & reminders” → return.
   - The log should show “Scheduled · … · precise · … · mode changed” (export:
     `SCHEDULED … mode=exact … outcome=mode_changed`).
   - Repeat test 5; lateness should be under a minute.
7. **Samsung battery.** Settings → Apps → Life Admin Autopilot (debug) → Battery → *Unrestricted*.
   Repeat test 5 overnight with a reminder at a time you'll be up, or tomorrow 09:00.
8. **Notification behaviour.**
   - The shade shows the posting time with “Due HH:mm” (or “Late · due HH:mm”).
   - Swipe it away: the task stays open, and the next reminder is still listed.
   - **Snooze**: it disappears, and the log shows `SNOOZED`.
   - **Done**: the task is completed, and future reminders show `CANCELED task_done`.
   - Tapping the notification opens the right task.
9. **Canonical case.** “Book train ride”, daily 09:00 + 18:00. Leave it for a day and check
   both deliveries in the log (`ALARM_RECEIVED` lateness for each).
10. **Reboot.** With a reminder pending, reboot and don't open the app. It should still fire. The
    export shows `RECONCILE … via=reconcile:boot` and `SCHEDULED … outcome=recovered`.
11. **Silent mode / DND.** Switch to Silent or turn on DND and re-open Reminder health; the
    verdict should change to *silent* or *uncertain* with the right explanation.
