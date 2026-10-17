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
_(filled in as phases complete — see below)_

## 3. Test results and evidence
_(see below)_

## 4. Build and APK status
_(see below)_

## 5. Remaining platform limitations
_(see below)_

## 6. Physical-device tests for you
_(see below)_
