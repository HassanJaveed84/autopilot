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

## M3A.2: deep research with actionable results and a cost ledger (implemented)

- `ai/research2`: `Research2Models` (deliverable templates A–E, requirements with stable ids,
  coverage statuses, candidates/attributes/prices with provenance tags, proposals, `SessionContent`
  with per-run records and honest `SessionOutcome`), `RequirementExtractor` (deterministic, offline),
  `Research2Prompt` (line-based `## REQ / ## CAND / ## ATTR / ## PICK / ## CLAIM / ## PROPOSE` contract),
  `ContractParser` + `SourceRegistry2` (fresh provenance only from provider citations on a block's own
  lines; the registry keeps *cited* and merely *discovered* pages apart; a model-typed `src=sN` resolves
  only to a cited source and only as a pointer (`reusedRefs`) — it verifies nothing unless the line is a
  validated restatement of evidence already held, otherwise the line is tagged Restated; prices are
  confirmed only by a citation; source numbers stable across runs), `CompletionPolicy` (validates every
  requirement status against the structured result after each merge: items need cited evidence,
  budgets/brands/exclusions are checked against the candidates and picks themselves with soft vs strict
  constraints, output wishes are verified for real; Partly / Needs a check / Not found are never covered),
  `SessionAssembler` (merge with confirmed-beats-unconfirmed, coverage via the policy, outcome rules:
  Complete only when every requirement incl. constraints is covered; budget exhausted is never Complete),
  `ProposalBuilder` (one reviewable proposal per requested item), `DeepResearchService` (planned
  stages GATHER → FILL_GAPS → CROSS_CHECK → SYNTHESIZE, budget check before each HTTP call, soft dollar
  limit, `pause_turn` as a separate billed call — continued only while searches remain, never past the
  budget — Stop keep/discard, continuation on a saved session where a typed note becomes a follow-up
  requirement answered next to the saved answer, never over it; every HTTP call leaves a privacy-safe
  `CallDiagnostic` — block kinds and counts, parsed block counts, stop reason, tokens, searches,
  duration, classification — on the stage record, and a reply with nothing to save is reported as what
  it was (cut off before text, paused before text, searches without text, empty) rather than "malformed"),
  `Research2Actions` (edited proposals → one transactional, idempotent `TaskEditCommand`; Undo).
  Start/continue run under one atomic launch claim (no double launch, no double daily reservation);
  the final save runs non-cancellable. Each HTTP call carries its own timeout (`ModelCall.timeout`,
  from `Research2Config.callTimeout`: 60 s + 15 s/search + output tokens ÷ 40, clamped 120–540 s),
  applied per request at the SDK layer; a stage allowed more than 6 searches runs in bounded batches,
  each merged and checkpointed to the store before the next, so a slow or dropped call costs one batch.
- `ai/usage`: a failed call keeps whatever the provider reported (full usage priced in full; searches
  seen as a floor with tokens unknown; nothing → unknown, never $0); a request rejected before
  processing is "Rejected · $0 assumed" and counted as such; `UsageGrouping` is the pure grouping the
  dashboard reconciles against the ledger; restores never duplicate a provider message id.
- `ai/usage`: `PricingCatalog` (versioned, from the official pricing page), `CostCalculator` (pure),
  `UsageRecord`/`UsageLedger` (one row per API call, inserted in flight and finalised; unknown usage
  is never priced as $0; provider message ids de-duplicate). Planning (M2) calls record here too.
- `ai/anthropic/AnthropicResearchCalls`: generic Messages call for the orchestrator (5-minute prompt
  caching when a run has several stages, cache tokens and message id in usage). Still the only SDK importer.
- Persistence: Room v5 `research_sessions` (FK cascade, 30 per task, payload cap) and `usage_records`
  (no FK; outlives tasks); `MIGRATION_4_5`; backups carry sessions and the append-only ledger; legacy
  `research_briefs` untouched and still readable.
- UI: `ui/research2` (preflight with editable requirements, template chips, mode cards and Adjust;
  investigation with real stage events and the Stop sheet; result screen with the five templates,
  coverage rows, callouts, continue card/sheet, source sheet; editable proposals with Add anyway and
  Undo; AI usage dashboard with run breakdown and spending preferences). `task/{id}/research/new` now
  opens the M3A.2 preflight; M3A brief routes remain for saved briefs.

## Production kickoff M1: durable drafts, proposal sets, provenance, allow-listed payloads (2026-10-23)

Foundations for the approved UX (`docs/PRODUCTION_KICKOFF_AUDIT_AND_PLAN.md`). No screen changed in this milestone; the
data model and the write path did.

- **Room v6** (`Migrations.MIGRATION_5_6`, additive): `tasks.rev` (every user change and every applied proposal increments
  it), the three timing roles beside the deadline — `eventLocalDate`/`eventLabel`, `intendedLocalDate`,
  `targetLocalDate`/`targetBasis` (PLAN-040/041) — and `task_steps.origin`/`provenanceId` (INT-030). New tables, all
  without foreign keys so they outlive their origins: `drafts`, `proposal_sets`, `provenance_records`.
- **`domain/draft`** — `Draft`/`DraftContent` (verbatim input, steps with origin, decisions, retained details, timing
  roles, the encoded planning result). `DraftRepository` writes on every change: PLAN-010/013/014 storage.
- **`domain/proposal`** — one proposal model for planning and research, drafts and tasks (handoff §4.4): `ProposalSet`
  with `baseOriginal` (never changes) and `base` (rebased after a partial apply, OD-2), `ProposalItem` with
  `material`/`recommended`/`status` and research `src` (premises → findings → evidence keys), `ProvenanceRecord`
  (handoff §4.5). `ProposalReconciler` is pure: OK / NOTE / CONFLICT / BLOCKED per item (PLAN-073), done steps
  protected (PLAN-074), user-stated deadlines blocked (OD-6), finishing never proposed, "Select recommended" excludes
  material and conflicting items (OD-5).
- **`ProposalApplier`** — the only write path from a proposal to a task: one transaction, re-reconciled at apply time
  (an undecided conflict writes nothing), per-item provenance, `rev` + history event with the set id and both revisions
  (INT-054), reminders through the policy path with generation bump, undo only while `rev` is unchanged. Existing
  `applyTaskEdit` stays for M3A/M3A.2 until M2 moves them onto the applier.
- **`ai/context`** — `PlanningContextBuilder` and `InvestigationTurnBuilder` build `FrozenPayload`s (JSON + SHA-256 +
  plain lines) from explicit allow-lists; excluded context, excluded findings and anything about other tasks are not
  inputs, so they cannot appear. `AiPlanningService.snapshot` now goes through the same builder. The research builder
  is wired in M4.
- Backup format unchanged (schemaVersion 1) with optional `drafts`, `proposals`, `provenance` arrays and the new task
  and step fields; pre-v6 files import unchanged.

## Production M2: the one Proposal Review (2026-10-23)

Both AI features now propose through one path (handoff §1, §7; INT-042): a `ProposalSet` saved by a bridge, reviewed in
`ui/review/ProposalReviewScreen`, applied by `ProposalApplier`.

- **Bridges (pure mapping, nothing written to tasks).** `ai/PlanningProposalBridge` turns a validated follow-through
  `ProposalDraft` into a set: steps and the suggested next step are recommended additions; title/notes rewrites,
  reminders, status and dates are material; a date on a task that already has a deadline becomes a planning target with
  its basis (OD-6); priority is never proposed (D38). `ai/research2/ResearchProposalBridge` turns an M3A.2 session run's
  proposals into a set (`rps-<session>-<run>`, idempotent): sourced steps recommended, checks/research as
  verify items (`UNVERIFIED`), notes as appends, `src` from refs → sources (claim → finding → evidence key), legacy
  applied moves shown as already applied. `ResearchProposalSets.ensure` finds or creates the set for a session.
- **Review (`ProposalReviewViewModel` + screen).** Reconciles against the live task on every change; nothing selected on
  first open; "Select recommended" uses `ProposalReconciler.recommendedSelection`; material items in their own section
  with an Accept control; current value against proposed value; inline text edit and date change before acceptance
  (editing never selects); conflicts need Apply anyway / Keep yours (Keep yours declines through
  `ProposalApplier.decline`); a stale banner says what moved without rewriting the proposal; Apply → summary
  (applied / kept / still pending) with Undo while `rev` is unchanged; selection, edits and decisions persist in
  `SavedStateHandle`. Explanations (advice, assumptions, questions) stay collapsed.
- **Entry points.** Task detail shows one banner per open set and a provenance marker on applied steps that opens
  `ProvenanceSheet` (origin, applied time, model, findings with status and as-of, evidence keys, link to the research).
  The research session screen's "Review N changes" hands off through `ResearchProposalSets`; "Plan next step" on a
  saved task hands off from `PlanViewModel` and leaves the plan screen (`Routes.REVIEW`).
- **Retired.** M3A.2 `ProposalsScreen`/`ProposalsViewModel`/`Research2Actions` and `ProposalMapper.toEdit`. The M3A brief
  apply (`ResearchActions` → `TaskRepository.applyTaskEdit`) remains the only other write path until the M3A entry
  points retire in the Research Online milestone; `createPlannedTask` (saving a new task from a draft) is not a proposal
  apply and stays.

## Production M4: the Research Online Investigation Thread (2026-10-23)

Objective-led research replaces the M3A.2 requirement/template engine as the source of the question
and the shape of the answer (RES-010/011, RES-080–089). Code: `ai/investigation`, `ui/investigation`,
Room v7. The M3A.2 call loop, provider adapter, usage ledger and checkpointing were kept and moved
behind the new model; the M3A and M3A.2 run, preflight and apply paths were removed.

- **Objective first** (`ObjectivePreparer`): a free, offline objective from the person's own words
  (ask kind from the request's wording, never its subject; constraints echoed; a question with no
  subject asks first). Optional: one small no-search call under general AI consent, ledgered as
  `OBJECTIVE_PREP`, sending the question alone; an instruction-carrying reply falls back to local.
  A follow-up is read against the earlier objective (changed constraints replace their kind).
- **Authorization before any paid call** (`AuthorizationBuilder`, M1 `InvestigationTurnBuilder`):
  the record holds the objective and constraints, each context item with its switch, each earlier
  finding with its disposition (reused / checked fresh / replaced / not sent / excluded by you), the
  configuration and preset name, the estimate, warnings, and the exact frozen payload with its
  SHA-256. `InvestigationService.authorize` writes it to the turn before the first byte leaves.
- **Claims, not coverage** (`InvestigationModels`, `InvestigationParser`, `ClaimAssessor`,
  `ResultDeriver`): each claim has type, scope, as-of, supporting and opposing evidence; provider
  citations verify, model-typed `src=` tokens only point; status is derived from evidence
  (established / indicative / contested / not established / depends on you); judgments inherit their
  weakest premise and are never cited; the turn's result state is derived from load-bearing claims.
- **Composition** (`AnswerComposer`, `LeakageScan`): the answer leads; sections appear only when the
  ask calls for them (steps, comparison rows, conditions, changes); every load-bearing gap is a caveat
  in the main answer; absence of evidence is never worded as "false"; no internal ids, markers or
  status labels reach the text.
- **Turns** (`InvestigationService`, `TurnWork`): stages from the R48 preset (Quick / Standard /
  Thorough / Deep + Custom; model, effort, searches and limit independent); at most 6 searches per
  call; a verify pass only for open load-bearing claims; a reconcile pass only for contested ones;
  a checkpoint of the turn after every merged call; the spend limit checked before each batch. Stop
  keeps findings and usage (open claims become "not checked"); discard drops findings but keeps the
  turn and usage; a failure keeps earlier evidence; `recoverInterrupted()` turns a dead process's
  running turn into Interrupted with its checkpoint. A continuation inherits only what its
  authorization sent; exclusions persist on the thread (OD-4); `CHANGED` supersedes a claim; earlier
  proposal sets resting on a superseded claim become obsolete.
- **Proposals** (`InvestigationProposalBridge`): one canonical `ProposalSet` per turn with
  claim-level `src`; established basis → recommended change (a correction of the person's step is
  material); indicative/contested basis → labelled "Check:" verification item; unchecked or
  unsupported basis → held back and counted; a standalone thread proposes nothing.
- **Room v7** (`MIGRATION_6_7`): `investigations` (standalone or task-linked; `ON DELETE SET NULL`)
  and `investigation_turns` (one record per turn, unique per index); every `research_sessions` row
  became one legacy investigation with a turn per run and its content kept byte-for-byte; the old
  table was dropped after the copy; `usage_records.effort`. Backups carry `investigations` plus a
  legacy `sessions` copy for older app versions; session-only backups import as threads.
- **UI**: research history (`research?task=`), new thread (`research/new`), one thread
  (`research/thread/{id}`) from Today's header (standalone) and the task's "Research online" tile
  (task-linked). Thread screen: question → objective card (edit, clarify, add details) → authorization
  card (depth, adjust, context switches, earlier findings, exact payload) → progress → turn cards
  (answer, caveats, sections, judgment, evidence list and sheet, sources, how it was researched,
  requested vs used configuration, review hand-off, recovery surface) → follow-up. Legacy M3A briefs
  and M3A.2 sessions stay readable, read-only.

## Extension points for later milestones (not implemented)

- M3 agents (research, email/calendar) would reuse the proposal → review → command pattern with
  per-capability permissions; nothing in M2 performs external actions.
