# Architecture

Single `:app` module, package `com.lifeadmin.autopilot`, minSdk 26 / target 36.

```
LifeAdminApp ──creates──> AppContainer (manual DI)
                            ├─ AppClock, ZoneProvider, IdGenerator        (core: injectable seams)
                            ├─ AppDatabase (Room, schema v2)              (data/local)
                            ├─ SettingsRepository (DataStore)             (data/repository)
                            ├─ AlarmDriver → AndroidAlarmDriver           (reminders)
                            ├─ NotificationPublisher → AndroidNotificationPublisher (notifications)
                            ├─ ReminderEngine  (implements ScheduleCoordinator)
                            ├─ TaskRepository  (all task mutations go through the coordinator)
                            ├─ TaskActions, BackupRepository, Diagnostics
UI (Compose, Material 3) ── ViewModels (StateFlow) ── repositories
Receivers (alarm / notification action / system) ── engine & repository
ReconcileWorker (WorkManager, 6 h) ── engine.reconcileAll + ledger prune
```

## Packages

| Package | Contents |
|---|---|
| `core` | `AppClock`, `ZoneProvider`, `IdGenerator` — the seams tests replace. |
| `domain/model` | Pure Kotlin models and enums (Task, TaskStep, ReminderPolicy, ReminderOccurrence, TaskEvent, AppSettings). |
| `domain/scheduling` | Pure time math: `LocalTimeResolver` (DST), `QuietHoursCalculator`, `PolicySlotGenerator`, `ReminderPlanner`, `SnoozeCalculator`, presets, describer. No Android imports. |
| `domain/usecase` | `TaskStateMachine`, `ScheduleCoordinator` interface, `TaskActions` (snooze defaults, previews, quick capture, example task). |
| `data/local` | Room entities, DAOs, converters, mappers, `Migrations` (1→2). Schemas exported to `app/schemas/`. |
| `data/repository` | `TaskRepository` (transactions + events + validation), `SettingsRepository` (DataStore + in-memory), `BackupRepository` (JSON). |
| `reminders` | `ReminderEngine`, `AlarmDriver` + Android impl, `IntentContract`, `ReconcileWorker`, `Diagnostics`. |
| `notifications` | `NotificationPublisher` + Android impl (channels, actions, deep links). |
| `receivers` | `ReminderAlarmReceiver`, `NotificationActionReceiver`, `SystemEventReceiver`. |
| `ui` | Root/navigation, Today, Inbox, Tasks, Task detail (+dialogs, policy editor), Capture sheet, Settings, Health panel, theme, shared components. |
| `di` | `AppContainer`. |

## Key decisions (and why)

- **Room is the only source of truth; alarms are a projection.** See `REMINDER_ENGINE.md`.
- **Manual DI instead of Hilt.** Fewer build plugins and annotation processors overnight, explicit
  seams, trivial test wiring (`AppContainer(...)` with fakes). Hilt can be introduced later without
  changing class boundaries.
- **One policy row per task, always present** (disabled = Off). Gives a single generation counter
  for policy, check-in and snooze-end occurrences and keeps `UNIQUE(policyId, plannedAt)` meaningful.
- **Engine mutex + compare-and-set claims.** Every mutation, delivery and reconcile is serialized
  in-process and runs on `Dispatchers.Default` (never the main thread); delivery is claimed with an
  `UPDATE … WHERE status = ? AND generation = ?`.
- **Toolchain pinned to AGP 8.13.2 / compileSdk 36.** The newest AndroidX releases (core 1.19,
  Compose UI 1.12) require compileSdk 37 + AGP 9.1; upgrading is a separate, deliberate step.
- **Robolectric for Android-framework tests** because the build container had no KVM/emulator;
  instrumented tests run on the CI emulator.
- **No destructive migration fallback.** A missing migration crashes loudly rather than silently
  deleting tasks; `MIGRATION_1_2` is tested on JVM and device.
- **Privacy**: `allowBackup=false` + data-extraction rules exclude everything; logs contain only
  exception class names; diagnostics contain counts only; event metadata is allowlisted.

## Extension points for later milestones (not implemented)

- M2 AI suggestions: would produce a *draft* `NewTask` + `ReminderPolicy` for the existing
  editors; persistence would still go through `TaskRepository` (validation, events) and the engine.
  `AppSettings.aiEnabled` exists and is always false.
- M3 agents / M4 sync: `TaskEvent` already gives an audit trail; ids are UUID strings suitable for
  merge; no network code exists today.
