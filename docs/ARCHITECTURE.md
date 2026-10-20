# Architecture

Single `:app` module, package `com.lifeadmin.autopilot`, minSdk 26 / target 36.

```
LifeAdminApp ──creates──> AppContainer (manual DI)
                            ├─ AppClock, ZoneProvider, IdGenerator        (core: injectable seams)
                            ├─ AppDatabase (Room, schema v3)              (data/local)
                            ├─ SettingsRepository (DataStore)             (data/repository)
                            ├─ AlarmDriver → AndroidAlarmDriver           (reminders)
                            ├─ NotificationPublisher → AndroidNotificationPublisher (notifications)
                            ├─ ReminderEngine  (implements ScheduleCoordinator)
                            ├─ TaskRepository  (all task mutations go through the coordinator)
                            ├─ TaskActions, BackupRepository, Diagnostics
                            ├─ ApiKeyStore → EncryptedApiKeyStore (Keystore)  (ai/security, M2)
                            ├─ TaskPlanningProvider → AnthropicTaskPlanningProvider (ai/anthropic, M2)
                            ├─ AiPlanningService (gates, caps, context, validation; never writes)
UI (Compose, Material 3) ── ViewModels (StateFlow) ── repositories
Receivers (alarm / notification action / system) ── engine & repository
ReconcileWorker (WorkManager, 1 h) ── engine.reconcileAll + ledger prune
```

## Packages

| Package | Contents |
|---|---|
| `core` | `AppClock`, `ZoneProvider`, `IdGenerator` — the seams tests replace. |
| `domain/model` | Pure Kotlin models and enums (Task, TaskStep, ReminderPolicy, ReminderOccurrence, TaskEvent, AppSettings). |
| `domain/scheduling` | Pure time math: `LocalTimeResolver` (DST), `QuietHoursCalculator`, `PolicySlotGenerator`, `ReminderPlanner`, `SnoozeCalculator`, presets, describer. No Android imports. |
| `domain/usecase` | `TaskStateMachine`, `ScheduleCoordinator` interface, `TaskActions` (snooze defaults, previews, quick capture, example task). |
| `data/local` | Room entities, DAOs, converters, mappers, `Migrations` (1→2→3). Schemas exported to `app/schemas/`. |
| `data/repository` | `TaskRepository` (transactions + events + validation), `SettingsRepository` (DataStore + in-memory), `BackupRepository` (JSON). |
| `reminders` | `ReminderEngine`, `AlarmDriver` + Android impl, `IntentContract`, `ReconcileWorker`, `Diagnostics`. |
| `notifications` | `NotificationPublisher` + Android impl (channels, actions, deep links). |
| `receivers` | `ReminderAlarmReceiver`, `NotificationActionReceiver`, `SystemEventReceiver`. |
| `ui` | Root/navigation, Today, Inbox, Tasks, Task detail (+dialogs, policy editor), Capture sheet, Settings, Health panel, theme, shared components. |
| `ai` | M2, optional: provider-neutral `TaskPlanningProvider` / `PlanningRequest` / `RawPlan` / `PlanningError`, `PlanSchema`, `PlanningPrompt`, `PhraseResolver`, `ProposalValidator`, `ProposalDraft`, `ProposalMapper`, `AiPlanningService`. |
| `ai/anthropic` | The **only** package that imports `com.anthropic.*` (enforced by `ArchitectureBoundaryTest`). |
| `ai/security` | `ApiKeyStore`: Keystore AES-GCM encrypted BYOK key, app-private, excluded from backup/export. |
| `ui/plan`, `ui/ai` | Plan with AI / Move this forward (input → review → approve) and AI settings. |
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

## M2: optional AI planning (implemented)

- **Planning is advisory.** The model returns a draft (`RawPlan`) that `ProposalValidator` turns
  into an editable `ProposalDraft`. Nothing is written until the user approves; approval maps the
  draft to plain domain commands (`PlannedTaskCommand`, `TaskEditCommand`) that `TaskRepository`
  applies in **one** `mutateTask` + Room transaction, so the existing engine schedules alarms once.
- **Idempotency without a migration.** A new task's id *is* the proposal id; an edit records
  its proposal id in the activity-log metadata. Repeated approval creates nothing new.
- **Provider boundary.** Core task/reminder code never imports `ai`; only `ai/anthropic` imports
  the Anthropic SDK. An on-device or proxy provider implements `TaskPlanningProvider`.
- **BYOK for this personal build** (the M2 brief's explicit choice; SPEC §7.1 allows it for
  personal experiments): a public release would need an authenticated server-side proxy.
- See `docs/M2_AI_CAPTURE_REPORT.md` for details, tests and device steps.

## M3: consented web research (implemented)

- `ai/research`: `ResearchConfig` (caps), `ResearchRequest`/`ResearchScope` (exactly what leaves the
  device), `ResearchProvider` (seam), `BriefParser` (provenance from structured citations only),
  `ResearchService` (gating, daily cap reserved before sending, one app-scoped run, Stop) and
  `ResearchActions` (selected moves → one `TaskEditCommand`, applied through the same transactional,
  idempotent path as AI planning; Undo only while the task is exactly as the apply left it).
- `ai/anthropic/AnthropicResearchProvider`: the only place that knows the SDK's web-search types.
  Requests `web_search_20250305` with `max_uses`, no `user_location`; continues `pause_turn` once
  within the same budget; reads `server_tool_use` / `web_search_tool_result` / cited text blocks.
- Persistence: Room v4 `research_briefs` (parsed brief as JSON, applied moves, status), cascade on
  task delete, pruned to 20 per task, payload size cap; included in backups as typed content.
- UI: `ui/research` (preflight + explainer, progress, brief + source sheet, apply, history); task
  detail gains two "Move this forward" tiles and a Research card. No SDK type reaches Compose.
- Guardrails: research is user-triggered only (no WorkManager, no receiver, no startup call); web
  text is data (injection-looking lines never become moves); links go through `Sources.safeExternalUrl`
  and `ACTION_VIEW`, never a WebView; reminders/status/deadlines are never written by research.

## Extension points for later milestones (not implemented)

- M3 agents (research, email/calendar) would reuse the proposal → review → command pattern with
  per-capability permissions; nothing in M2 performs external actions.
