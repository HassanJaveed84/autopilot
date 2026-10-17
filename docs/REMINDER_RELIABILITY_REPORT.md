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
- **`notify()` is not treated as seen.** After `notify()` the app checks
  `getActiveNotifications()` and records `listed_active` or `not_listed_active`. It never records
  that a person saw the reminder.
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
  existing alarms are re-registered as exact. They are logged `SCHEDULED … mode_changed`.
- **Today banner** when the latest reminder arrived more than 10 min late on approximate timing.
- **Cancellation reasons** are logged precisely: done, paused, snoozed, schedule changed,
  superseded, …
- The manifest's alarm receiver also accepts the test-alarm action; it is still `exported=false`.
- **Not added, on purpose:** foreground services, wake locks, `USE_EXACT_ALARM`, full-screen
  intents, battery-exemption requests, internet permission.

## 3. Test results and evidence
_(filled in after the final run)_

## 4. Build and APK status
_(filled in after the final run)_

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

1. **Health screen.** Open Settings → Reminder health. The verdict should say reminders will
   probably be **silent** if the phone is in Vibrate mode and the channel Vibrate is still off.
   Note the verdict text.
2. **Fix vibration.** Tap *Open channel settings* → turn **Vibrate** on → go back. The verdict
   should change to *vibrate only* (in Vibrate mode) and “Channel vibration” should read *On*.
   Leave pop-up as you prefer.
3. **Immediate test.** Tap **Send test notification now** with the screen on. Expect:
   - a vibration in Vibrate mode (or your Spaceline sound in Sound mode);
   - the “Last test” line “notification posted (listed active)”;
   - the shade time equal to *now*.
4. **Alarm test with the phone locked** — the key S1/S2 test.
   - Tap **Schedule test reminder in 2 minutes**, lock the phone, and leave it on the table.
     Don't open the app.
   - Note when it buzzes, then open Reminder health → the log. `TEST_ALARM_RECEIVED` shows the
     lateness.
   - Arrives within a few minutes: alarms are working in approximate mode.
   - Nothing arrives until you open the app: the alarm is being dropped. Report the log line.
5. **Real task reminder, approximate.**
   - Create “Test 2” with a one-time reminder 15 min ahead. Lock the phone, wait, and note the
     time it buzzes.
   - Then open Reminder health → *Copy diagnostics* and send it to me.
   - For that occurrence, compare `ALARM_RECEIVED` with the planned time, and check
     `POST_REQUESTED source=` (`alarm` or `reconcile:…`).
6. **Precise timing.**
   - Settings → turn on **Precise timing** → grant “Alarms & reminders” → return.
   - The log should show `SCHEDULED … mode_changed exact=true`.
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
    log shows `RECONCILE source=reconcile:boot` and `SCHEDULED … recovered`.
11. **Silent mode / DND.** Switch to Silent or turn on DND and re-open Reminder health; the
    verdict should change to *silent* or *uncertain* with the right explanation.
