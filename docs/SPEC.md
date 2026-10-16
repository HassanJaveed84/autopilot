# Life Admin Autopilot — Product & Technical Specification

**Status:** Approved technical direction / implementation-ready design  
**Version:** 1.0  
**Date:** 2026-10-16  
**Initial audience:** Single user, personal-use Android app  
**Primary implementation workflow:** Claude Code, milestone-gated development

## 0. Executive decision

Build a **native Android, local-first follow-through assistant**. The offline reminder and task engine is the product's dependable core. AI is **optional and OFF by default**, initially suggesting plans and later operating with explicitly scoped permissions. No login, hosted backend, cloud account, or paid API is required for the MVP.

**Core promise:** Capture an intention in seconds; keep it visible and follow up on the user's chosen schedule until the user resolves it. A dismissed notification is **not** a completed task.

**Do not promise:** guaranteed minute-perfect reminder delivery under every Android power/permission/device state; automated purchases; silently executing real-world tasks; full offline AI.

## 1. Product requirements

### 1.1 Personas and goals

- Initial persona: busy technical Android user who accumulates personal commitments (book tickets, complete registration, arrange a repair, follow up on a request).
- Optimize for: quick capture, proactive but configurable follow-through, easy snooze/complete, low notification fatigue, offline usability, task trustworthiness, privacy.
- Future persona: household partners collaborating on shared administrative tasks. **Not in MVP.**

### 1.2 Key user stories

1. **Persistent follow-up:** “Book the train ride; remind me at 9 AM and 6 PM every day until it’s done.” Create one task, one policy with two wall-clock slots, and ongoing occurrences until completed/canceled/paused.
2. **Quick capture:** “Need to renew car registration next month.” Save as Inbox; don't invent an exact deadline. User can add dates and policy later.
3. **Waiting:** “Waiting for the mechanic to call.” Mark WAITING; ask for a follow-up/check-in date. Do not send repeated “call mechanic now” notifications by default.
4. **Snooze:** Tap “Snooze until tomorrow” from a notification; keep the task open but suppress/reschedule intervening reminder occurrences.
5. **Completion from notification:** Tap Done; mark task done transactionally, cancel future alarms, log the event, and dismiss the task's notifications.
6. **Offline operation:** With airplane mode on, tasks can be added, viewed, edited, completed, and alerted locally, subject to system notification/alarms restrictions.
7. **Device reboot:** Outstanding reminder policies are reconstructed following boot/unlock when the operating system permits receivers to run.
8. **Optional AI planning (milestone 2):** Natural-language input can generate editable task/checklist/reminder proposals. AI must not silently persist uncertain dates or alter reminders.
9. **Future research agent:** After explicit approval, a tool can research official parking/ticket pages and return cited findings; no purchasing/sending until a separate approval.

### 1.3 MVP functional scope (must ship)

- Create/edit/archive/delete task with title, notes, optional date-only deadline or precise date/time, priority and status.
- Inbox, actionable, waiting, paused, done, canceled categories; Today overview.
- Optional checklist steps with completion tracking.
- Reminder policy per task: OFF, once at timestamp, daily at one or more explicit local times, every N days at configured time; support twice daily (e.g., 09:00 and 18:00). Multiple times must belong to the **same task**, not create duplicate tasks.
- Gentle/daily/persistent **presets** merely prepopulate editable reminder policies; user controls actual schedule.
- Global quiet hours default 21:00–08:00, timezone handling, notification channels, and maximum notification count. Reminder suppressed by quiet hours should be deferred to next allowed time or skipped according to explicit setting (default: defer and coalesce).
- Notification actions: DONE, SNOOZE (quick default), OPEN. Notification tapping deep-links to relevant task.
- Snooze until preset or custom time; pause/resume without losing policy.
- Task event history and reliable updating/canceling of scheduled alarms.
- Settings with permission diagnostics, delivery mode explanation, notification channel link, default follow-up policy, quiet hours, export/import.
- Local-only operation, no account creation, no model API dependency.
- Automated unit, database, Compose UI, and Android instrumentation tests; manual real-device test checklist.

### 1.4 Deliberately deferred

- AI parsing / suggestions: milestone 2.
- Online browsing, external AI agents, email/calendar OAuth, cloud compute: milestone 3.
- Cross-device sync and household collaboration: milestone 4.
- Recurring *task regeneration* (different from repeating reminders on one open task): after MVP unless extremely simple and adequately tested.
- Always-on listening, accessibility automation of third-party apps, SMS/notification scraping, billing, payment automation, elaborate gamification, widgets, wearables, autonomous browser sessions.

## 2. UX and UI specification

### 2.1 Visual direction

- Premium but restrained Android-native Material 3 design with dynamic color support, dark/light themes, spacious typography, polished motion, and no distracting gamification.
- Surface an **attention dashboard**, not a shame-inducing endless overdue list.
- No fake AI buttons or empty tabs shipped before functionality exists.

### 2.2 Navigation

1. **Today** (default tab): summary counts for Needs Action / Waiting / Upcoming, next few actionable tasks, overdue grouped clearly, one-tap Done/Snooze/Open.
2. **Inbox:** rapid review of tasks captured without complete details; tap to schedule or classify.
3. **Tasks:** sections/filters (All, Actionable, Waiting, Paused, Completed); search by title/notes; task detail and checklist.
4. **Quick Capture:** prominent floating action button + text input; optional richer fields; fast save even without reminder. Future share-intent capture / push-to-talk.
5. **Settings:** policy defaults, quiet hours, notification health, precision mode, local export/import, AI disabled indicator until stage 2.

Task detail is a destination, not a bottom tab: title, notes, priority, optional deadline, status actions, checklist, next reminder, policy editor, activity log, and (later) Agent suggestions.

### 2.3 Onboarding

- First launch: explain local-first behavior and configurable reminders; create a demo task **only on explicit user action**, not automatically.
- Request `POST_NOTIFICATIONS` at a contextual moment when user creates their first reminder (API 33+). If denied, show persistent notification health warning; allow task storage and in-app visibility.
- Only offer exact-alarm special access for narrowly justified precise-reminder scenarios and after a plain-language explanation; see §5.
- Never demand contacts, SMS, location, microphone, email, or calendar permission in MVP.

### 2.4 Interaction requirements

- Save task: at most 2 taps from Quick Capture for basic title and default policy.
- Task completion should be reversible via Reopen in history (and possibly short Undo snackbar).
- Snooze confirmation displays the exact next eligible date/time.
- In-app health panel shows: notification permission, channel enabled/disabled, exact alarm access (if requested), battery restriction caveat, last scheduler reconciliation, next scheduled reminder.
- Task status and notification status must never contradict the durable Room state.

## 3. Technology decisions

- **Language/UI:** Kotlin, Jetpack Compose, Material 3, ViewModels, coroutines/StateFlow.
- **Architecture:** single Android `:app` module initially, clear package boundaries: `ui`, `domain`, `data/local`, `data/repository`, `reminders`, `notifications`, `receivers`, `di`. Avoid premature multimodule complexity.
- **Data:** Room as the sole read/write source of truth. DataStore for typed small preferences. `java.time` for scheduling calculations.
- **Dependency injection:** Hilt if Gradle/KSP/toolchain supports it cleanly; keep abstractions injectable for tests. Do not tie core scheduling arithmetic to Android classes.
- **OS scheduler:** AlarmManager for user-facing alarm delivery; WorkManager for **deferrable maintenance/reconciliation**, not exact-minute reminders.
- **Dependencies:** use contemporary stable, mutually compatible Android Gradle Plugin, Kotlin, Compose BOM, Room, WorkManager, and SDK versions. Confirm from official release docs/build output; don't invent versions, avoid alpha/canary by default.
- **Build:** Gradle Kotlin DSL + version catalog; single app id, no remote service or mandatory Google sign-in.
- **Testing:** JUnit for pure domain calculations; Room DAO instrumented tests; Compose UI tests; scheduler fake/adapter tests; manual device tests.

## 4. Domain model and persistence

Use UUID string primary keys, nullable timestamps where appropriate, creation/update metadata, indexes for task status and scheduled occurrences. Use type converters for `Instant`, `LocalDate`, `LocalTime`, and enums; store time zone ID explicitly where needed. Room migrations must preserve existing tasks (no destructive fallback in release builds).

### 4.1 `Task`

- `id: UUID`, `title: String`, `notes: String?`, `status: TaskStatus`, `priority: Priority`.
- `createdAt: Instant`, `updatedAt: Instant`, `completedAt: Instant?`, `deletedAt: Instant?` (soft-delete/tombstone optional in v1).
- `deadlineType: NONE | DATE_ONLY | EXACT`, `deadlineLocalDate: LocalDate?`, `deadlineInstant: Instant?`, `deadlineZoneId: String?`.
- `snoozedUntil: Instant?` (orthogonal to task status).
- `waitingReason: String?`, `waitingCheckInAt: Instant?`.
- Invariants: completion and canceled are terminal until explicit Reopen. Inbox/Actionable/Waiting/Paused do not imply whether a policy is enabled.

### 4.2 `TaskStep`

- `id`, `taskId` FK, `sortOrder`, `title`, `isDone`, `completedAt`.
- Steps don't complete the parent automatically without explicit user choice (or an opt-in setting in the future).

### 4.3 `ReminderPolicy`

- `id`, `taskId` FK, `enabled: Boolean`, `kind: ONCE | DAILY_TIMES | EVERY_N_DAYS`.
- `startLocalDate`, `zoneMode: FOLLOW_DEVICE | FIXED_ZONE`, `zoneId` if fixed, `dayInterval` if N-day.
- `quietHoursBehavior: DEFER_AND_COALESCE | SKIP` (prefer global override if not set), `snoozeDefaultMinutes`.
- Optional once-only `atInstant`; `lastEditedAt`, `generation` integer to invalidate stale receiver deliveries.
- **Separate** `ReminderTimeSlot` table: `id`, `policyId`, `localTime`, `sortOrder`; supports 09:00 + 18:00 without awkward CSV strings.
- One policy per task in MVP. Recurring *notifications* continue for the same task until resolved; do not regenerate the task daily.

### 4.4 `ReminderOccurrence`

- `id`, `policyId`, `taskId`, `plannedAt: Instant`, `scheduledAt: Instant?`, `deliveredAt: Instant?`, `status: PLANNED | SCHEDULED | DELIVERED | SUPPRESSED | MISSED | CANCELED`, `generation`.
- Unique index on `(policyId, plannedAt)` to stop duplicate occurrence creation.
- Stable PendingIntent identity based on occurrence ID (never reuse same request code for distinct active occurrences); immutable where appropriate.
- Past occurrences are a **delivery ledger**; not the source of the schedule definition.

### 4.5 `TaskEvent`

- `id`, `taskId`, `at`, `eventType`, `summary`, `structuredMetadata` (small allowlisted JSON). Log create, edit, status transition, snooze, policy edit, reminder delivered, permission/reconciliation effects. Avoid storing secrets or full LLM prompts.

### 4.6 DataStore settings

- `quietHoursStart=21:00`, `quietHoursEnd=08:00`, `defaultSnooze=tomorrow morning`, `defaultReminderPreset`, `allowReminderNotifications`, optional privacy-on-lock-screen, notification volume/channel preferences managed through Android, manual backup preferences, AI consent false.
- Persist data schema version for import/export.

### 4.7 Future-only entities

- `AgentProposal`: id, taskId, source, plan, requiredCapabilities, riskLevel, proposed changes, status, expiry, createdAt.
- `AgentExecution`: id, proposalId, approvedScope, approver timestamp, tool call history, status, result summary, idempotency key.
- `ExternalConnectorGrant`: provider, granular scopes, token reference (never raw token in Room), expiry, revokedAt.
- `TaskDependency` and `HouseholdMembership` only when requirements demand them.

## 5. Task state machine and reminders

### 5.1 Task status

`INBOX -> ACTIONABLE -> DONE` is the primary flow. From ACTIONABLE users may set WAITING or PAUSED; WAITING and PAUSED may return to ACTIONABLE. Any nonterminal task may be CANCELED or DONE explicitly; reopening a terminal task logs a new event and recalculates schedules. Snooze is **not** a task status; `snoozedUntil` overlays an active status.

- `DONE` / `CANCELED`: never generate future reminders.
- `PAUSED`: no reminders; resume only by user action or explicit resume time if supported later.
- `WAITING`: do not use action reminder schedule; send only an explicitly configured check-in reminder.
- `INBOX`: no default reminder until user accepts a schedule; user can optionally configure a reminder on inbox items.
- `ACTIONABLE`: reminder policy can generate occurrences unless user-snoozed.

### 5.2 Scheduler invariant

**Database is authoritative; OS alarms are a disposable projection of it.**

Pseudo-flow:

```text
on task/policy/settings change:
  transactionally persist change, increment relevant policy generation
  cancel outstanding PendingIntents for stale occurrence(s)
  reconcile task's desired next occurrence(s)

reconcile(policy, now, zone, permissions):
  if task not actionable or policy disabled or paused:
    cancel future alarms; return
  calculate the next valid wall-clock time AFTER now
  account for snoozedUntil, global quiet hours, and coalescing
  upsert planned occurrence using unique(policyId, plannedAt)
  schedule one next delivery through AlarmManager adapter
  log scheduling mode (exact eligible or best-effort inexact)

on alarm broadcast(occurrenceId, generation):
  read current Room state; reject stale generation, canceled task, duplicate delivery
  if too late, apply documented missed-notification policy
  in DB transaction claim occurrence idempotently and record delivery eligibility
  post notification if permissions/channel permit
  record actual post result / suppression reason
  immediately schedule next occurrence

on Done / Snooze / Pause:
  update task atomically, invalidate/cancel outstanding alarms
  dismiss existing notification as appropriate
  recalculate only if still eligible
```

Implementation detail: keep scheduler interfaces injectable (`Clock`, `ZoneProvider`, `AlarmDriver`, `NotificationPublisher`, `TaskRepository`) so scheduling math and state transitions can be tested without a device. Use concurrency-safe compare-and-set / Room transactions around delivery claims; never rely on a broadcast receiver executing exactly once.

### 5.3 Time semantics

- `Instant` (UTC) for absolute event history and deliveries; `LocalTime` + `ZoneId` for recurring user-facing reminder slots.
- Default `FOLLOW_DEVICE` zone for personal daily reminders; `FIXED_ZONE` option for things tied to a specific region. Changing device timezone recomputes pending alarms.
- Date-only deadlines do **not** imply midnight notifications. Explicitly choose reminder time.
- Daylight saving: spring-forward nonexistent local slot moves to the first valid local time following the gap; fall-back repeated local slot fires once per local calendar day, at the earlier valid offset. Document and unit test.
- Overdue after reboot/offline: default configurable grace window (e.g. 4h) for individual late notifications; older missed occurrences should not cause a notification storm. Coalesce into one summary and schedule future instances.
- When quiet hours affect a scheduled reminder, defer/coalesce to next allowed time by default, and maintain idempotency against duplicate notifications.

### 5.4 Android API / permission choices

- Android 13+ requires runtime `POST_NOTIFICATIONS` for normal notifications; handle denied permissions and disabled channels.
- `SCHEDULE_EXACT_ALARM` is **not** granted by default for most fresh installations on Android 14+. Do not use `USE_EXACT_ALARM` merely to bypass user permission; it is restricted by Play policy to qualifying alarm/calendar categories.
- Default: best-effort **inexact** alarms. For useful reminders during Doze, selectively use `AlarmManager.setAndAllowWhileIdle()` while respecting rate limitations and battery cost. Prefer `setWindow()` / `set()` for more flexible time windows.
- If a narrowly justified precision mode is supported, check `canScheduleExactAlarms()`, explain why special access is requested, then use `setExactAndAllowWhileIdle()` only if access is granted. If denied/revoked, automatically fall back to inexact scheduling and make precision status visible.
- Use `WorkManager` only for deferrable reconciliation and cleanup; periodic WorkManager is **not** a punctual reminder clock.
- Manifest boot receiver + `RECEIVE_BOOT_COMPLETED`; also recover on package replace, relevant time/timezone changes, exact-alarm permission change, and app startup/resume. Actual alarm scheduling after boot occurs once app storage is available.
- OS forcibly stopped app / disabled notifications / manufacturer power restrictions may block or delay delivery. Detect or explain relevant conditions where possible; do not claim impossible guarantees.
- Do not use unrestricted background foreground services, full-screen intents, or blanket battery-optimization exemptions as a workaround.
- PendingIntents, notification receivers, and deep links must have appropriate exported/mutability settings and validate IDs/actions from external intents.

### 5.5 Schedule policy examples

- `09:00, 18:00 daily`: next eligible wall-clock time; continue while ACTIONABLE.
- `Tomorrow 09:00 once`: one occurrence only, no automatic repeat.
- `Every 3 days at 10:00`: anchor to the user-chosen start date; no drift from actual delivery timing.
- `Waiting — check in next Tuesday`: explicit check-in independent of main ACTIONABLE policy.
- `Snooze till tomorrow 09:00`: suppress intervening notifications, then resume the configured policy; optionally fire a one-time resumption reminder as a separate choice. Don't silently create additional notifications.

## 6. Notifications

- Create documented channels, e.g. `action_reminders` (normal priority configurable in Android), `summary_updates` (lower priority). Don't impersonate OS critical alerts.
- Message includes task title and brief contextual next action. Lock-screen redaction optional.
- Actions: Done, Snooze (snooze default from settings), Open. Receivers must execute quickly and offload slow computation safely without relying on a long-running background service.
- PendingIntent action IDs must be unique and tamper-resistant enough for in-app changes; read state from Room and never trust notification extras alone.
- On notification disablement: keep tasks and policies intact, record suppression and show in-app health guidance, no repeated permission nagging.
- Notification dismissed/swiped away: do nothing to completion state; next configured occurrence remains.
- User action Done: transactional task completion, cancellation + dismissal + event log.

## 7. AI architecture and agent permissions — milestones 2–3

### 7.1 Boundaries

AI is a **planning/advisory boundary**, never the authoritative scheduler. At milestone 2 add an opt-in AI interface to a small authenticated FastAPI service (or other secure gateway). Do not embed an application-wide LLM secret into APK source, resources or shipped binary. If experimenting with bring-your-own-key locally, ask user to enter their own key, protect it with Android Keystore, disclose exposure/tradeoffs, and do not include such a key in shared builds.

The app remains fully usable while the AI API is down or AI is turned off. LLM output must be schema-validated, grounded in supplied text/context and reviewed before mutating local tasks.

### 7.2 Milestone 2: structured draft proposal

Example input:

```json
{"text":"Book the train ride soon. Remind me twice a day until done.","deviceZone":"America/Chicago","now":"2026-10-16T14:00:00-05:00"}
```

Example output (**proposal only**, not an action):

```json
{
  "title":"Book family train ride",
  "suggestedStatus":"ACTIONABLE",
  "deadline":{"kind":"UNKNOWN"},
  "proposedReminder":{"kind":"DAILY_TIMES","times":["09:00","18:00"]},
  "suggestedSteps":["Find dates and availability","Check boarding and parking","Purchase and save confirmation"],
  "assumptions":["No exact purchase deadline was provided"],
  "questions":[]
}
```

- Parse ambiguous dates with user's timezone/date passed explicitly; label unresolved relative language (“soon”, “next month”) as ambiguous and prompt once when truly necessary.
- Show a preview of proposed title, checklist, and reminder schedule; apply locally only after confirmation.
- LLM may not assign deadline solely based on reminder slot.

### 7.3 Milestone 3: controlled agents

Capability classes and defaults:

| Capability | Default | Permission model |
|---|---|---|
| Read current task and user-selected notes | Allow for selected task only after AI opt-in | Per-task context |
| Suggest checklist or reminder change | Allow to draft | User confirms before writing |
| Web research | Deny until requested | Approval per research request / scoped session |
| Read email/calendar | Deny | OAuth plus granular integration scopes; user-selected resources |
| Draft message/event | Deny until requested | User reviews preview |
| Send message/create external event | Deny | Explicit per-action confirmation and idempotency |
| Purchase/pay/submit sensitive form | Not supported in initial agent | Requires a later dedicated security/product review |

Agent execution rules:

- Persistent `AgentProposal` -> `APPROVED` -> `RUNNING` -> `SUCCEEDED | FAILED | NEEDS_REVIEW` state machine.
- Tool allowlist, server-side authorization, action-specific approval, expiration, scope and idempotency keys. Only a server execution engine invokes external integrations; model text never directly bypasses authorization.
- Research results and retrieved email/web content are **untrusted inputs** and must not be able to change tool scopes (prompt-injection defense).
- Clear execution history: what tool, when, inputs limited to necessary metadata, result, and user permission.
- No blanket approval for spending money or sending messages. No hidden data exfiltration; minimize PII and redact logs.
- If a task is “done” according to a tool, verify external success signal before auto-closing. Otherwise report as “possibly completed — needs confirmation.”
- Autonomous background monitoring requires server scheduling and opt-in, not pretending Android background execution is always available.

## 8. Privacy and security

- No account, analytics SDK, tracking SDK, or embedded ad SDK in MVP.
- All core task contents remain in private app storage. Android device-level encryption and sandboxing provide baseline protection; don't claim full end-to-end encryption without a separate design.
- Default `android:allowBackup=false` or explicit backup rules to prevent unexpected cloud backup. Provide user-initiated JSON export/import through Android Storage Access Framework, with **clear warning** that plaintext export contains private task details. Consider password-encrypted backups in a later release. Tradeoff: without export there is no recovery after device loss.
- Do not request contacts, location, SMS, call logs or microphone privileges in MVP.
- Never log full task notes, user PII, tokens, model responses, or external email contents to production logcat / crash reports.
- Validate imported JSON schema and sizes; rollback import on failure; de-duplicate by IDs; avoid path traversal / file system writes outside app-controlled destinations.
- Secure Android component exports and PendingIntents; no unprotected exported command receiver.
- All future network connections use TLS and authenticated endpoints, with tokens in appropriate secure storage and revocation.

## 9. Milestone-by-milestone Claude Code plan

### M0 — Bootstrap and architecture

**Deliver:** Buildable Compose app, navigation scaffold, Room entities/DAOs, repositories, DataStore, dependency injection, theme, basic ViewModels, fake clock and fake alarm abstractions, basic CI checks/readme.

**Definition of done:** Project syncs/builds, launches on emulator/device, local unit tests run, no placeholder network dependency, schemas exported, no warnings hidden by suppressions.

### M1A — Task lifecycle and UI

**Deliver:** Today/Inbox/Tasks/Quick Capture/Settings, CRUD, task details, checklist, status transition use cases, search/filter, event log and import/export.

**Definition of done:** Save tasks offline, kill and relaunch process, no lost tasks, no orphaned steps, deterministic task state transitions and passing UI tests.

### M1B — Reminder engine and notifications

**Deliver:** Per-task policy UI, local time slot recurrence, durable occurrence ledger, AlarmManager adapter, notification channels/actions, snooze/Done, boot/timezone/permission reconciliation, scheduler health UI and battery-aware fallback.

**Definition of done:** Train-ticket use case succeeds on actual Android device; Done from notification stops future reminders; reboot and timezone changes recover expected schedules; no duplicate bursts; notification-denied state is honest. This is the **real MVP release gate**.

### M1C — Hardening and packaging

**Deliver:** Extensive tests, error states, import/export roundtrip, migration test, performance baseline, accessibility checks, installable debug/release APK for personal use and instructions. Review actual Android special permissions and distribution policy if ever publishing.

**Definition of done:** No release blocker in §10; verified on target physical Android phone and at least one emulator API level.

### M2 — Optional AI plan suggestions

**Deliver:** AI opt-in, authenticated model gateway, structured proposed task parser, user review/confirmation UI, failure fallback, Pydantic validation + tests, AI disable/delete controls.

**Definition of done:** With AI OFF app works identically; AI guesses never silently rewrite deadlines or reminders; unexpected output cannot crash/persist invalid records.

### M3 — Research agent

**Deliver:** Explicit research capability, approval workflow, server jobs (only when needed), audit log, model/tool guardrails, credible source links, retry/idempotency states. Introduce external provider OAuth only as a separate scoped feature.

**Definition of done:** Agent can research a selected task and bring back findings. It **cannot** make a payment/send communications without explicit, specific prior authorization; malicious web/email instructions do not expand its permissions.

### M4 — Optional household sync

**Deliver:** identity, user-owned vs shared scopes, encrypted transport, conflict handling, server database, shared membership and notification ownership. **Only when requested.**

## 10. Testing strategy and exact acceptance matrix

### 10.1 Pure domain / unit tests (fake `Clock` and zone)

- Daily 09:00/18:00 recurrence computes next upcoming slot from arbitrary instants.
- Repeated reminder **never** creates another Task.
- Once-only reminder stops after one occurrence.
- Every-N-days schedule remains anchored to original date; delivery delays don't cause interval drift.
- DST spring gap / fall duplicate occurrence; half-hour-offset zones; timezone change.
- Quiet-hours crossing midnight; defer/coalesce; no double notification at 08:00 when several prior slots were suppressed.
- Snooze across next configured slot and across deadline; resume behavior explicitly tested.
- Waiting/Paused/Done/Canceled suppress normal task reminders.
- Reopen terminal task creates new generation; stale broadcasts ignored.
- Missing/ambiguous deadline treated as unknown, not silently inferred.
- Idempotent duplicate broadcasts; concurrent Done and delivery; transaction crash/retry.
- Older missed alarms coalesced, no storm after reboot; next alarm still scheduled.

### 10.2 Room and app tests

- Create/edit/delete/status changes survive process death.
- DB foreign keys and cascading behavior; unique occurrence index prevents duplicates.
- Migration from each schema version retains task text and policy times.
- Export/import roundtrip and malformed import rejection without partial corruption.
- UI tests: capture, complete, reopen, waiting, snooze, policy editing, permission warnings, accessibility semantics.
- Notification receiver action tests verify correct task IDs and authorized internal commands.
- Import validation rejects oversized/corrupt payloads; no secrets in logs.

### 10.3 Physical Android device/manual cases

1. Grant notifications. Create “Train tickets” with 2 daily slots (for testing, select upcoming times). Background/lock phone, verify delivery and action buttons.
2. Swipe a notification away; task remains open and later reminders continue.
3. Tap Done in notification; task completes, remaining alarms are canceled; reopen reschedules per policy.
4. Snooze until next day; intervening alarms absent and future policy resumes correctly.
5. Force Doze via adb on dev device; observe best-effort behavior and note timing deviation.
6. Deny `POST_NOTIFICATIONS`; app remains usable and displays warning, no false “delivered” claim.
7. Reboot; verify scheduler rehydration after boot/unlock and policy consistency.
8. Change device time zone, wall clock, daylight-saving test via fake clock plus device where possible.
9. Toggle alarm special access where relevant; verify exact-to-inexact fallback and reconstruction.
10. Force-stop from Android settings; document expected OS limitation (don't claim delivery until relaunch).
11. Test Samsung battery settings and long-idle behavior on target device, including Do Not Disturb/channel blocks.
12. Turn on airplane mode; create/edit task and receive local alarms.
13. Reinstall and import export; confirm readable data and schedules without duplicate floods.
14. Test 100 tasks and 200+ reminder slots for startup responsiveness and sane alarm count.

**Manual pass evidence:** Keep a simple test log with device/Android version, exact permissions and app battery mode, planned vs actual notification time, screenshot, result, and known deviation. Do not claim universal 100% on-time guarantees.

### 10.4 Quality gates

- Gradle assemble debug succeeds; unit tests pass; instrumentation tests pass on available device/emulator.
- No known duplicate notifications for same occurrence ID, no reminders for Done/Canceled, no lost tasks after process restart, no unnecessary permissions.
- Workflows understandable without AI or internet.
- Every feature visible in UI is functional; no false placeholders or demo claims.
- README includes feature list, build/install steps, permission explanations, limitations, tests and troubleshooting.

## 11. Repo and code organization

```text
life-admin-autopilot/
  README.md
  CLAUDE.md                  # repo-specific agent instructions
  docs/
    SPEC.md                  # this specification
    ARCHITECTURE.md
    REMINDER_ENGINE.md
    TEST_MATRIX.md
  app/
    src/main/java/<package>/
      App.kt
      MainActivity.kt
      data/local/            # Room entities, DAOs, DB, migrations
      data/repository/
      domain/model/
      domain/usecase/
      domain/scheduling/     # pure recurrence, quiet hours, time zone math
      reminders/             # Android alarm adapter, reconciliation
      notifications/
      receivers/
      ui/today/
      ui/inbox/
      ui/tasks/
      ui/capture/
      ui/settings/
      di/
    src/test/
    src/androidTest/
  gradle/
  .github/workflows/       # optional local/CI checks
```

### Contribution rules for Claude Code

- Make and keep the app runnable after **each** milestone. No 20-file unverified changes without a build.
- Start by inspecting the repo and environment before choosing toolchain versions or permissions.
- Prefer small well-defined interfaces and domain logic tests over large god-classes, especially for scheduler logic.
- No fake network data, pretend reminder implementations, unfinished TODO markers represented as completed features, or hard-coded tokens.
- Commit milestone boundaries (if Git configured) and produce a changelog and known limitations.
- If Gradle, SDK, or device verification is unavailable, state explicitly what remains unverified; don't claim test success.
- Do not proceed automatically to a new milestone if current acceptance tests fail; resolve issues and document tradeoffs.
- Avoid adding AI dependencies until the offline MVP is functioning.

## 12. Product risks and mitigations

| Risk | Mitigation |
|---|---|
| Android/OEM delays notifications | Correct AlarmManager use, permission health, inexact fallback, clear user expectations, real-device tests |
| Duplicate or stale alarms | generation tokens, unique occurrence index, idempotent receivers, reconcile and cancel |
| Notification fatigue | per-task policies, quiet hours, snooze, frequency caps, coalescing, Waiting state |
| AI invents dates | schema validation, assumptions, review-before-commit, user time zone context |
| AI overreaches | per-tool allowlist, per-action approval, audit, default deny, external content untrusted |
| Privacy of personal task data | app sandbox, minimal permissions, no telemetry, deliberate backup behavior |
| Overengineering | single app module, no backend/MCP/OAuth until milestones demand them |
| Device lost, no cloud backup | user-initiated export/import, explicit warning; encrypted backup later |

## 13. Authoritative technical references (verified 2026-10-16)

- Android alarms: https://developer.android.com/develop/background-work/services/alarms
- Android 14 exact-alarm changes: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Notification permission: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Doze and app standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- Android offline-first data layer: https://developer.android.com/topic/architecture/data-layer/offline-first
- Android architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Room persistence: https://developer.android.com/training/data-storage/room/index.html
- Play exact-alarm permission policy: https://support.google.com/googleplay/android-developer/answer/18258653
- WorkManager testing: https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing

## 14. Initial instruction to Claude Code

Read the full SPEC, then implement **only M0 first** and validate. After M0 passes, proceed to **M1A**, then **M1B**, then **M1C** with explicit acceptance evidence at each gate. Do not start M2 or M3 until the on-device reminder MVP is dependable. If the user explicitly directs one-shot implementation, the complete scope is M0–M1C, not an untested autonomous AI agent. Preserve everything necessary for M2–M3 extension without prematurely implementing it.
