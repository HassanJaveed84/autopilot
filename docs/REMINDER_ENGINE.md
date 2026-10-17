# Reminder engine

> **Invariant: the Room database is authoritative; OS alarms are a disposable projection of it.**

Code: `domain/scheduling/*` (pure math), `reminders/ReminderEngine.kt` (state + reconciliation),
`reminders/AndroidAlarmDriver.kt`, `notifications/AndroidNotificationPublisher.kt`, `receivers/Receivers.kt`,
`reminders/ReconcileWorker.kt`.

## Data model

| Entity | Role |
|---|---|
| `ReminderPolicy` (1 per task) | Schedule *definition*: `enabled`, `kind` (ONCE / DAILY_TIMES / EVERY_N_DAYS), `startLocalDate`, `dayInterval`, `zoneMode` (+`zoneId`), optional quiet-hours override, `generation`. |
| `ReminderTimeSlot` | One row per wall-clock time (09:00 and 18:00 are two rows of the *same* policy). |
| `ReminderOccurrence` | Delivery *ledger*. Unique `(policyId, plannedAt)`. Status `PLANNED → SCHEDULED → DELIVERED / SUPPRESSED / MISSED / CANCELED`. `kind` = POLICY / CHECK_IN / SNOOZE_END. |

Every task always has a policy row (disabled = "Off"), so policy reminders, Waiting check-ins and
snooze-end reminders share one generation counter and one uniqueness key. `plannedAt` is the
*effective* delivery instant (after quiet-hours deferral) and `slotAt` the nominal slot; using the
effective instant as the dedupe key is what makes quiet-hours **coalescing** automatic (21:30 and
23:00 both deferred to 08:00 → one row).

## Planning (`ReminderPlanner.next`, pure)

Inputs: task, policy, settings, `now`, device zone, and the set of already-settled planned instants.

1. DONE / CANCELED / PAUSED / archived → nothing.
2. Candidate streams, merged in time order:
   * ACTIONABLE or INBOX with an enabled policy → policy slots. While snoozed, slots before
     `snoozedUntil` are suppressed; slots at/after it resume.
   * WAITING → only the explicit `waitingCheckInAt`.
   * Active snooze with "remind me when it ends" → `snoozedUntil`.
3. Slots ≤ now are skipped. Slots in quiet hours are deferred to the end of the window
   (DEFER_AND_COALESCE, default) or skipped (SKIP); per-policy override wins over the global setting.
4. Instants already DELIVERED / SUPPRESSED / MISSED are skipped (idempotent across clock changes).
5. The first remaining candidate is *the* next reminder. Only one future occurrence per task exists.

### Time semantics
* Absolute history uses `Instant` (UTC). Recurring slots are `LocalTime` + zone.
* `FOLLOW_DEVICE` (default) uses the current device zone; `FIXED_ZONE` pins an IANA zone.
* **Spring-forward gap:** a nonexistent local time resolves to the first valid time after the gap
  (02:30 → 03:00), not `ZonedDateTime`'s shifted 03:30.
* **Fall-back overlap:** a repeated local time resolves to the earlier offset and fires once per day.
* Quiet hours are evaluated in the **device** zone (they are about the user's sleep).
* EVERY_N_DAYS is anchored to `startLocalDate`; delivery delays never shift the cadence.
* Date-only deadlines never create reminders.

## Engine operations (all serialized by one `Mutex`, run on `Dispatchers.Default`)

### Mutation (`mutateTask`)
Every schedule-affecting repository write (create, status change, reopen, snooze, policy edit,
archive, delete, import) runs inside `mutateTask`: snapshot active occurrences → run the Room
transaction (which bumps `policy.generation`) → optionally dismiss the task's notification →
reconcile that task. Delete uses the pre-mutation snapshot to cancel alarms whose rows cascaded away.

### Reconcile one task
1. Active occurrences whose time has come:
   * no longer eligible (task done/paused, policy changed, snooze moved…) → CANCELED;
   * overdue ≤ 60 s → assumed in flight, left for the receiver;
   * overdue > grace window (default 4 h) → MISSED (logged);
   * otherwise delivered late (or collected for coalescing during a full reconcile).
2. Plan the next occurrence; cancel any other active future occurrence.
3. Upsert the planned row (reusing a CANCELED/stale row with the same `(policyId, plannedAt)` and
   stamping the current generation) and hand it to `AlarmDriver`. If the OS refuses (e.g. alarm
   limit), the row stays **PLANNED** and is retried by the next reconcile.

### Alarm broadcast (`onAlarm(occurrenceId, generation)`)
Re-reads Room (intent extras are never trusted):
* missing row → ignore; status not PLANNED/SCHEDULED → **duplicate**, ignore;
* generation ≠ row generation → **stale**, ignore (and make sure the current alarm is registered);
* fires > 60 s early (wall clock moved back) → re-plan, don't deliver;
* re-check eligibility and grace window;
* blocked (in-app toggle off, permission denied, app/channel notifications off, daily cap) →
  **SUPPRESSED** with a reason, logged, never claimed as delivered;
* otherwise compare-and-set `→ DELIVERED` (exactly one caller wins), post notification; if posting
  fails, downgrade to SUPPRESSED. Then immediately plan the next occurrence.

### Full reconcile (`reconcileAll(reason)`)
Triggered by: app start, app foreground (debounced 60 s), `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`,
`TIME_SET`, `TIMEZONE_CHANGED`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, settings changes,
import, the hourly WorkManager safety net, and the "Re-check schedule" button.
Late-but-within-grace reminders after downtime: ≤ 3 are delivered individually (labelled late);
more are **coalesced into one summary notification** (no storm). Missed ones are counted in the
summary. Because only one occurrence per task is ever materialised, a multi-day outage produces at
most one missed row per task. The result is written to settings (`lastReconcileAt`, summary) and
shown in the health panel. Reconcile is idempotent (tested 3× in a row → same single alarm).

## Notification actions
* **Done** → `TaskRepository.complete` (transaction, generation bump) → future alarms canceled →
  notification dismissed → event logged.
* **Snooze** → default target (per-policy minutes, else global preset; default "tomorrow 09:00")
  with a snooze-end reminder; intervening reminders are suppressed.
* **Open / tap** → `MainActivity` with a validated task id → task detail.
* **Swipe away** → nothing. The task stays open and the next reminder remains scheduled.

## Android delivery modes
* Default: `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP)` — best-effort, may be batched/delayed
  (minutes, more in Doze/Battery Saver), still fires in idle.
* Precision mode (opt-in in Settings): `setExactAndAllowWhileIdle` **only if**
  `canScheduleExactAlarms()`; otherwise automatically inexact and reported as such. We declare
  `SCHEDULE_EXACT_ALARM` (user-granted special access), never `USE_EXACT_ALARM`.
* No foreground services, full-screen intents, or battery-optimisation exemption requests.
* PendingIntents are `FLAG_IMMUTABLE`; identity comes from a per-occurrence data URI
  (`lifeadmin://occurrence/<id>`), so distinct occurrences never share a PendingIntent.
* Receivers for alarms and notification actions are `exported=false`; the system receiver only
  handles protected broadcasts and performs an idempotent reconcile.
* WorkManager is used only for the periodic reconcile/prune safety net, never as the reminder clock.

## Known OS limitations (not bugs)
* **Force stop** cancels all alarms; nothing fires until the user opens the app again (reconcile on
  start then catches up within the grace window).
* OEM battery managers (e.g. Samsung "Sleeping apps") may delay or drop alarms; the health panel
  explains how to set battery usage to Unrestricted. We do not request exemptions.
* Revoking exact-alarm access cancels exact alarms; the permission-change broadcast and app start
  reschedule them as inexact.
* Inexact alarms can be several minutes late; "09:00" means "around 09:00" unless precision mode
  is on and granted.

## Delivery diagnostics (added 2026-10-17)

Every step of a reminder's life is written to the `reminder_diagnostics` table (schema v3). It
has no foreign keys, so history survives task deletion. It holds ids and timestamps only, never
titles, notes or steps, and keeps at most 3,000 rows / 45 days.

| Type | Written when | Key fields |
|---|---|---|
| `SCHEDULED` | alarm handed to AlarmManager: `new`, `updated`, `mode_changed` (approximate↔precise), `recovered` (after boot/time change), `reregistered_due` | `plannedAt`, `triggerAt`, `exact`, notes such as `quiet_hours_deferred_from=…`, `exact_unavailable` |
| `SCHEDULE_FAILED` | AlarmManager refused (including re-registering a due alarm) | error class |
| `ALARM_RECEIVED` | the alarm broadcast reached the receiver; the time is captured **before** any locking | `at` = real receipt time, `lateness` = receipt − planned |
| `ALARM_IGNORED` | duplicate / stale generation / early / not found | outcome |
| `RECONCILE` | each full reconcile | `source=reconcile:<reason>`, counts, environment |
| `POST_REQUESTED` | about to call `notify()` | `source` (`alarm` / `reconcile:app_resume` / `mutation` …), lateness, mode, **environment snapshot** |
| `POSTED` | `notify()` returned | `notify_returned` (reminders); `in_summary` (coalesced, written only after the summary was posted). The self-tests also check `getActiveNotifications()` for up to 2 s, because `notify()` only enqueues, and record `listed_active` / `not_listed_active` |
| `POST_FAILED`, `SUPPRESSED`, `MISSED` | not shown, with the reason | reason code, environment |
| `SNOOZED`, `UNSNOOZED`, `CANCELED` | user actions / cancellations | reason (`task_done`, `task_paused`, `snoozed`, `superseded`, …) |
| `TEST_*` | self-tests from the Reminder health screen | lateness of the test alarm |

The environment snapshot records:
- ringer mode and Do Not Disturb filter;
- notification volume;
- the reminder channel's importance, sound, vibration and DND bypass;
- Doze, Battery Saver, battery-optimisation exemption, background restriction and App Standby
  bucket;
- whether exact alarms are allowed.

`POSTED` is deliberately **not** called “delivered to the user”. It only proves Android accepted
the notification.

The task activity log now states how a reminder was delivered, for example:
- “Reminder delivered 24 min late · alarm arrived late (approximate timing)”
- “… · caught up when the app was opened or started”

Settings → Reminder health → *Copy / Share diagnostics* exports the ledger plus device state as
text.

### Notification time
The notification's timestamp (`setWhen`) is the **actual posting time**. The due time is shown as
the subtext (“Due 18:25” or “Late · due 18:25”). Before this change the shade showed the planned
time, which hid late posting.

### Channel policy
- New installs create “Task reminders” with `IMPORTANCE_DEFAULT` (sound, no forced heads-up),
  **vibration on** and a short vibration pattern.
- If the channel already exists, the app does not touch it: Android reserves sound, vibration,
  importance and pop-up for the user. The app never deletes and recreates a channel to override
  them, and Android would restore the old settings anyway.
- The Reminder health screen detects the settings and links straight to the channel page.
- Pop-up (“Show as pop-up” / `IMPORTANCE_HIGH`) is an optional user preference.

### Safety net
The WorkManager reconcile now runs **hourly** (it was every 6 h, longer than the 4 h grace
window). A dropped or long-deferred inexact alarm is therefore caught up even if the app is never
opened. It is still deferrable work and never the reminder clock.

### Alert assessment
`AlertAssessor` (pure, unit-tested) turns permission, channel and environment into one of:
BLOCKED, SILENT, VIBRATE_ONLY, AUDIBLE or UNCERTAIN, plus concrete issues. Example: Vibrate mode
with channel vibration off is “will probably be silent”. Do Not Disturb without a channel bypass
makes the result UNCERTAIN, because DND exceptions can't be read without extra permission.
