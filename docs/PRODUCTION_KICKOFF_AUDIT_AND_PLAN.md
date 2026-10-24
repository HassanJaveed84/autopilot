# Production Implementation Kickoff — Repository Audit, Gap Analysis, Architecture and Phased Plan

**Status:** Phase 1 deliverable (audit + plan). Phase 3 Milestone 1 starts on this branch after this document.
**Branch:** `prod-ux-m1-foundations`, created from `m3a2-deep-research` @ `f5eb868` (the M3A.2 head; PR #5 is still a draft and is not merged). Draft PR to follow; nothing is merged by this work.
**Date:** 2026-10-23 (reconciled against Documents E/F/G/H the same day; see §9)
**Baseline assessed against:** frozen product baseline **v1.0** (Documents A–G, frozen 2026-10-22; Document H v1.0 change log), per Document E change-control rule 4.

## 0. Sources, precedence and what is missing

| Precedence | Source | Supplied | Notes |
|---|---|---|---|
| 1 | Document A (principles/boundaries) v1.0 | yes | Read in full |
| 1 | Document B (Plan with AI PRD) v1.0, PLAN-010…PLAN-113 | yes | 65 requirements; priorities and Invariant/Capability tags read per ID |
| 1 | Document C (Research Online PRD) v1.0, RES-010…RES-161 | yes | 89 requirements |
| 1 | Document D (Integration spec) v1.0, INT-010…INT-072 + INT-023 consent table | yes | 32 requirements |
| 1 | Document J (UX design handoff brief) v1.0 | yes | |
| 1 | Documents E (decision log), F (evaluation framework, Stage 0 fixtures), G (roadmap), H (v1.0 freeze change log) | **yes (received after M1, 2026-10-23)** | Read in full; reconciliation in §9. The original kickoff package held only A–D and J. |
| 2 | `UX-implementation-handoff.md` v1.1 incl. OD-1…OD-12, §13 mandatory native work, §14 native acceptance tests | yes | Treated as the authority on behaviour the frozen docs leave to UX |
| 3 | Design boards Flow / Main / Plan / Task (`.dc.html`) | yes | Flow wins on behaviour; Plan/Main/Task give full surfaces (§0.3) |
| 4 | Prototype acceptance scripts (`tests/*.js`, Playwright 1.56) | yes | Behavioural reference only; **not** production acceptance |

**Documents E/F/G/H** arrived after M1 was implemented. §9 reconciles this plan, the roadmap and the test matrix against them; the Stage 0 acceptance mapping lives in `docs/TEST_MATRIX.md`. Nothing here reopens a confirmed decision; where a decision is still open in Document E it is named as open.

## 1. Repository audit (as of `f5eb868`)

### 1.1 Architecture
- Single-module Kotlin/Compose Android app, `compileSdk 36`, `minSdk 26`, navigation-compose 2.9.8, Room (v5 schema), DataStore settings, WorkManager (periodic reminder reconcile only), kotlinx.serialization for JSON payloads, Anthropic Java SDK 2.73.0 confined to `ai/anthropic`.
- Manual DI in `di/AppContainer.kt`: clock, zone provider, id generator, alarm driver, notification publisher, API key store, planning/research providers, network status and an app-wide `CoroutineScope(SupervisorJob())` are all injected seams. View models are created from the container.
- Reminder engine (`reminders/`, `domain/scheduling/`) is Room-authoritative, alarm-driven, generation-invalidated, with boot/app-start reconciliation. Untouched by this work except for the new task fields it must ignore.

### 1.2 Persistence (Room `AppDatabase.VERSION = 5`, exported schemas `app/schemas/.../1.json`–`5.json`, hand-written migrations 1→2→3→4→5 with tests in `MigrationTest`)

| Table | Owner | Relevant facts |
|---|---|---|
| `tasks` | engine | title, notes, status, priority, created/updated/completed/archivedAt, deadline (type/localDate/instant/zoneId), snooze, waiting. **No** `rev`, event date, intended date, planning target, origin. |
| `task_steps` | engine | id, taskId (FK cascade), sortOrder, title, isDone, completedAt. **No** provenance/origin. |
| `task_events` | engine | id, taskId, at, type, summary, `structuredMetadata` (string map). Apply writes `proposalId` into metadata; used as the idempotency check. |
| `reminder_policies`, `reminder_time_slots`, `reminder_occurrences`, `reminder_diagnostics` | engine | unchanged by this plan |
| `research_briefs` | M3A (legacy) | JSON payload per brief; FK to tasks |
| `research_sessions` | M3A.2 | JSON `payload` = `SessionContent` (requirements, coverage, answer, claims, conflicts, unresolved, sources, queries, proposals, runs, followUps, shared, …) + `applied` list. **FK to tasks with CASCADE**, so a session cannot exist without a task. |
| `usage_records` | shared ledger | feature (PLANNING / RESEARCH / RESEARCH_CONTINUATION / ASSIST), model, tokens, cache tokens, web searches, outcome, usageStatus, estimatedMicros, priceVersion, providerMessageId. Good base for PLAN-102 / RES-143 / RES-154. |

Backup/export (`BackupRepository`, format `life-admin-autopilot-backup`, schemaVersion 1) carries tasks, steps, policies, events, briefs, sessions and usage with validation on import. Every new table needs a DTO, validation and a round-trip test.

### 1.3 Navigation and UI surfaces (`ui/navigation/Routes.kt`, graph in `ui/LifeAdminRoot.kt`)
Today / Inbox / Tasks tabs; Settings as header action; `task/{taskId}` detail; `plan?text&auto` and `plan/task/{taskId}` (M2 Plan with AI); M3A brief routes (`…/research`, `/new`, `/run`, `/{briefId}`, `/apply`); M3A.2 routes (`…/research2/run/{sessionId}/{runId}`, `/research2/{sessionId}`, `/research2/{sessionId}/proposals`); `ai_settings`, `ai_settings/usage`, `health`, `licenses`.
There is **no** draft surface on Today, no standalone research route (every research route is under a task), and **two** research review UIs (M3A `ResearchApply` and M3A.2 `ProposalsScreen`) plus a third proposal UI for planning (`ui/plan/PlanReview.kt`). The handoff requires one canonical Proposal Review.

### 1.4 Features and their state

| Component | Where | What exists | Verdict |
|---|---|---|---|
| Capture | `ui/capture` | In-memory `CaptureDraft`; Save without AI; "Plan with AI" hands text to the Plan route | Keep; drafts must become durable (PLAN-010) |
| Plan with AI (new draft and follow-through) | `ai/AiPlanningService`, `ProposalValidator`, `ProposalMapper`, `ui/plan/*` | Prompt → `RawPlan` → validated `ProposalDraft` (provenance EXPLICIT/INFERRED/SUGGESTED per field, notices, questions, assumptions, advice) → `PlannedTaskCommand` / `TaskEditCommand`; draft only in `SavedStateHandle`; follow-through sends one `TaskSnapshot` (title, notes, status, steps, deadline, reminders, age, snoozes, waitingFor); planning calls ledgered as PLANNING | Reuse the validator/mapper and prompt knowledge; replace the draft store and the review UI; remove priority proposals (handoff §7 rule 8) |
| Research Online, generation 1 | `ai/research`, `ui/research` | M3A single-call briefs with structured citations, selective apply/undo | **Retire the entry points** once the Investigation thread lands; keep saved briefs readable (data preserved) |
| Research Online, generation 2 | `ai/research2`, `ui/research2` | Staged deep research with requirements, claim-level evidence (`Claim`, `Conflict`, `Unresolved`, `SourceRef2`, provenance tags), completion policy, per-call diagnostics, continuation with follow-up as objective, bounded search batches with checkpoint saves at call boundaries, stop/keep, spend limit, mode presets (Quick/Thorough/Deep/Custom), proposals with apply/undo, usage dashboard | **Reuse as the engine** of the Investigation thread; the session/turn/authorization/payload model around it is new |
| Ledger | `ai/usage`, `data/repository/RoomUsageLedger` | Per-call records, pricing catalog, cost calculator, monthly soft budget (note at 80 %, confirmation at 100 %) | Reuse; add warning + hard-stop thresholds (RES-142) and "unknown in-flight usage" markers (RES-154) |
| Settings | `AppSettings` | aiEnabled/consent, aiModel, per-mode research models, spend limit, monthly budget | Extend: reasoning effort, Auto, saved research defaults, draft policy |
| Key storage / privacy | `ai/security/ApiKeyStore` (encrypted), no secrets in logs/diagnostics (tested) | Reuse |

### 1.5 Task-update path today
`TaskRepository.applyTaskEdit(TaskEditCommand)` is one Room transaction, idempotent by `proposalId`, rejects terminal tasks, and rejects the **whole** edit if `tasks.updatedAt` moved since the snapshot. That is stricter than PLAN-073 ("unrelated activity never invalidates a compatible change") and coarser than it (no per-item conflict detection, no step rename/tick/delete handling, no done-step protection beyond "steps are only added"). `undoAppliedEdit` exists for M3A.2 applies. There is no snapshot of the prior task version (INT-054 asks for byte-identical history), and no `rev`.

### 1.6 Process-death behaviour today
- Planning: `PlanViewModel` keeps text, phase and the encoded draft in `SavedStateHandle`. That survives configuration change and *some* process deaths (while the task is in recents) but **not** a swipe-away, reboot or Android reclaiming saved state. A paid planning result can therefore be lost (PLAN-014 gap).
- Research: `DeepResearchService` runs inside the app scope; a session row is written as `INTERRUPTED` at start and checkpointed at every call boundary; relaunch shows the interrupted session and never restarts (RES-152 holds). The resume surface does not yet list preserved / unresolved / proposed work with "remaining budget unknown" (RES-153 partial).
- Capture: in-memory only (PLAN-010 gap).

### 1.7 Test infrastructure
- 74 JVM test files (JUnit4 + Robolectric 4.16.1 for Compose and Room; MockWebServer for provider HTTP; deterministic clock/zone/ids), 6 instrumentation classes incl. DB, reminders, reliability, UI, keystore and the screenshot tour; Compose UI tests at font scales 1.0/1.3/2.0 and 320 dp width; 48 dp touch-target tests; dark/light contrast tests.
- CI: `android-ci.yml` (lint, unit, debug/release/androidTest APKs signed with the personal key from a secret, emulator API 34 instrumentation) and `screenshots.yml` driven by `.github/screenshots-request.json`.
- Prototype suites: see §6.

## 2. Gap analysis against the frozen requirements and handoff §13

Legend: **Met** = code and tests exist; **Partial** = behaviour exists but a stated clause is missing; **Gap** = absent.

### 2.1 Durable state and recovery (handoff §13 row 1)
| ID | State | Evidence / gap |
|---|---|---|
| PLAN-010 | Gap | Capture draft is in memory; Plan text in SavedStateHandle only |
| PLAN-011, PLAN-012 | Met | Save without AI exists; planning only on tap; no auto calls (tested) |
| PLAN-013 | Gap | No draft object, no resume surface, no save/discard disposition, no shown expiry policy |
| PLAN-014 | Partial | Result kept only in SavedStateHandle |
| INT-013 | Gap | Research cannot start from an unsaved draft (routes and FK require a task) |
| RES-152 | Met | No automatic restart after kill (session saved INTERRUPTED; tests exist) |
| RES-153 | Partial | Interrupted state shown; preserved/unresolved/proposed listing and "budget unknown" missing |
| RES-154 | Partial | Ledger rows exist for interrupted calls with usageStatus; "in-flight usage unknown" not surfaced |

### 2.2 Timing semantics
| ID | State | Gap |
|---|---|---|
| PLAN-040 / PLAN-041 / INT-053 | Gap | Task has only a deadline. Event date, intended action date and planning target (with basis) do not exist; the validator maps every date to `DraftDeadline` |
| PLAN-042 | Partial | Validator leaves times unset when none stated; needs the "authorized default, labeled" path |
| PLAN-045 | Met | Reminders apply only when the user ticks them; engine owns firing |

### 2.3 Proposals, apply and history (handoff §13 row 6)
| ID | State | Gap |
|---|---|---|
| PLAN-071 | Partial | Selective approval exists in three different UIs; one canonical review required |
| PLAN-072 | Partial | Material changes are shown but can be bulk-selected in M3A.2 (`selectedByDefault = true` for sourced steps); planning pre-selects EXPLICIT fields (conflicts with OD-5) |
| PLAN-073 | **Gap** | Whole-task `updatedAt` check rejects compatible changes after unrelated activity; no per-item conflict classes (ok / note / conflict) |
| PLAN-074 | Partial | Steps are only added, so done steps are never edited; no explicit per-change accept model for done-step changes |
| PLAN-070 | Partial | Planning follow-through proposes priority; must stop |
| INT-050, PLAN-077 | Met | No write path without an accept; no scheduled planning |
| INT-054 | Gap | Events exist, but no prior-version snapshot (`baseOriginal`) and no `rev` pair in history |
| OD-1 / OD-2 / OD-7 | Gap | No proposal set object: research sets cannot stay open and be re-validated; no item-level rebase; no retargeting from draft to task |

### 2.4 Provenance (INT-030…033, OD-9)
Gap. Steps carry no origin. Research applies write a note line and record `AppliedProposal(proposalId, at, title, stepId)` **on the session**, so provenance dies with the investigation and is not attached to the content. No as-of/status-at-incorporation on the element.

### 2.5 Privacy-safe context (handoff §13 row 5)
| ID | State | Gap |
|---|---|---|
| PLAN-080 / PLAN-081 | Met in spirit | Payload is built from one task snapshot; no other tasks. Not yet an explicit, fixture-inspected allow-list builder |
| INT-022 | Met (by absence) | Planning never sees research findings today; the *selected-findings* path must be added without persistence of the selection |
| RES-021 / RES-161 | Partial | Preflight shows scope and plain-word "shared" lines; **no exact payload preview**, no per-item exclusion for inherited findings (OD-4), no frozen payload per turn |
| RES-160 / INT-021 / INT-064 | Met | Continuations send only the same session's content |
| RES-087 | Partial | Internal ids (`r-a1`, `c3`) are rendered in the technical log only; needs a test that user-facing text never shows them |

### 2.6 Spending, configuration and ledger
| ID | State | Gap |
|---|---|---|
| RES-140 / RES-141 / RES-144 | Met | Estimate before run; soft limit trims searches; wording says "estimate" |
| RES-142 / PLAN-101 | Partial | Monthly budget is soft; needs warning + hard-stop thresholds covering planning + research |
| PLAN-090…094 / RES-120…123 | Partial | Manual model per feature/mode; "used model" is recorded; **no** Auto with disclosed resolution, **no** reasoning effort (PLAN-093 / RES-135) |
| RES-130…134 / OD-3 | Partial | Presets exist; budget "follows depth" vs. independent manual budget, saved defaults + per-run override, exist only partly |

### 2.7 Research lifecycle and standalone
| ID | State | Gap |
|---|---|---|
| RES-150 / RES-151 / OD-11 | Met | Stop keeps supported evidence; outcome STOPPED/INCOMPLETE; no manufactured answer (regression tests) |
| OD-8 | Partial | Proposals from partial runs are not filtered to fully-checked premises; no "held back" count |
| RES-110 / RES-112 / INT-045 / INT-062 / INT-063 | Gap | Research requires a task; no association surface |
| RES-024 / RES-034 | Met | Continue = new preflight approval |
| RES-092 | Met | Run records are immutable; continuation never overwrites the original answer |

### 2.8 Other mandatory native work
- AI-off/offline (PLAN-083): Met for tasks/reminders and gating; add the "nothing queued on reconnect" test.
- Accessibility: good base (font-scale, touch-target, contrast tests); handoff adds TalkBack order, non-colour status meaning and focus management for sheets. Prototype's 44 px targets are **not** followed; 48 dp is.
- Prototype-only controls (OD-12): none exist in the app; the plan adds none.

## 3. Proposed architecture

### 3.1 Principles
1. **Room is authoritative for everything the user could lose**: drafts, proposal sets, provenance, investigations/turns and ledger rows are tables or JSON columns in Room, written before any paid call and checkpointed at call boundaries. `SavedStateHandle` keeps only transient UI state.
2. **One domain, three sources**: planning and research both produce a `ProposalSet` with `ProposalItem`s; one `ProposalReview` component renders and reconciles them; one `ProposalApplier` writes them. The M3A.2 engine stays the research engine, wrapped by an `Investigation` thread with `Turn`s.
3. **Allow-list builders**: each paid operation has a pure `Payload` builder (`NewDraftPlanningPayload`, `TaskPlanningPayload`, `InvestigationTurnPayload`) that takes an explicit list of included items and returns the frozen JSON that is both shown ("See exactly what's sent") and sent. Tests inspect the frozen payload.
4. **Conflict-aware atomic apply**: apply runs in one transaction against `tasks.rev`; each item is classified ok / note / conflict against the current task; chosen non-conflicting items apply; the task `rev` increments; history records set id, item ids, `rev` before/after and a reference to the `baseOriginal` snapshot.
5. **Nothing silent**: no default selection, no priority proposals, deadlines never changed by AI, event dates and targets stored in their own fields, reminders only on explicit accept.

### 3.2 Data model (Room v6 in Milestone 1; v7 in Milestone 4)

**Milestone 1 (v5 → v6)**
- `tasks` + `rev INTEGER NOT NULL DEFAULT 0`, `eventLocalDate TEXT`, `eventLabel TEXT`, `intendedLocalDate TEXT`, `targetLocalDate TEXT`, `targetBasis TEXT`, `origin TEXT NOT NULL DEFAULT 'USER'`.
- `task_steps` + `origin TEXT NOT NULL DEFAULT 'USER'`, `provenanceId TEXT` (nullable).
- `drafts` (id, createdAt, updatedAt, status DRAFT|SAVED|DISCARDED, text, title?, savedTaskId?, payload JSON = `DraftContent`{decisions, steps with provenance, retained details, planning result, refinement history}, lastModelResultAt?). No FK to tasks.
- `proposal_sets` (id, source PLANNING|RESEARCH, targetKind DRAFT|TASK, targetId, investigationId?, turnId?, madeAt, status OPEN|CLOSED|OBSOLETE, baseOriginal JSON, base JSON, items JSON = list of `ProposalItem`{id, kind, target, value, why, recommended, material, status PENDING|APPLIED|DECLINED|OBSOLETE, src}). Indexed by targetId.
- `provenance_records` (id, taskId?, draftId?, elementKind STEP|NOTE|DEADLINE|TARGET|EVENT|REMINDER|STATUS, elementId?, source, proposalSetId, itemId, appliedAt, editedByUser INTEGER, src JSON). No FK on investigation (must survive its deletion).
- Backup schemaVersion stays 1 with new optional arrays; import of older files unchanged.

**Milestone 4 (v6 → v7)**: rebuild `research_sessions` as `investigations` (nullable `taskId`, nullable `draftId`, `origin`, `turns` JSON) via create-copy-drop-rename; migrate every existing session into one investigation with one or more turns; `research_briefs` kept read-only.

### 3.3 Services
- `DraftRepository` — create on first keystroke (debounced), update, markSaved(taskId), discard(disposition). Observed by Today.
- `ProposalSetRepository` + `ProposalReconciler` (pure: item vs. current task → ok/note/conflict with reason) + `ProposalApplier` (transaction: validation, rev check per item, done-step protection, provenance records, history, `rev++`, undo token).
- `PayloadBuilders` (pure, allow-listed, serializable, with redaction tests).
- `InvestigationService` (M4) wrapping `DeepResearchService`: turn authorization record, frozen payload, inherited-finding dispositions, stop/partial derivation, held-back proposals, resume surface model.
- `PlanningService` adaptation (M2/M3): draft-targeted and task-targeted planning return `ProposalSet`s; selected findings passed per request only.

### 3.4 UI
Canonical components per handoff §3: Living Draft (Today), Proposal Review (one screen, any source/target), Investigation thread (authorization / running / result / follow-up turns), Provenance sheet, Evidence sheet, Configuration sheet, Disclosure sheet, Research history + ledger. Existing `ui/plan/PlanReview.kt`, `ui/research/ResearchApply*`, `ui/research2/ProposalsScreen.kt` are replaced by Proposal Review in M2/M4 (routes kept until removed; data remains readable).

## 4. Phased implementation plan (dependency order)

| # | Milestone | Depends on | Requirement IDs (primary) | Exit criteria |
|---|---|---|---|---|
| **M1** | **Foundations** — Room v6 (drafts, proposal sets, provenance, task rev + timing fields, step origin), `ProposalReconciler`, `ProposalApplier` with per-item conflicts and done-step protection, allow-listed payload builders for the three operations, backup DTOs, migration tests, deterministic unit tests | — | PLAN-010/013/014 (storage), PLAN-040/041/042 (fields), PLAN-071/072/073/074, INT-030/031/033, INT-050/054, PLAN-110, RES-160, PLAN-080/081, INT-012/020/021/022 (builders), OD-2/OD-5/OD-6 | Migration 5→6 keeps all data (test), apply fixtures from PLAN-073/074 pass, payload fixtures show exactly the allow-listed content, lint/unit/assemble green |
| **M2** | **Proposal Review (canonical) + planning on saved tasks** — one review screen, nothing pre-selected, Select recommended, material items separate, inline edit, conflict choices, Apply via `ProposalApplier`; planning follow-through emits a `ProposalSet`; priority removed; disclosure sheet with activity-signal switches; migrate M3A.2 apply to the same screen | M1 | PLAN-070…077, PLAN-081/082, INT-042/043/044, OD-1/OD-2/OD-5, handoff §7 | Robolectric tests for §14 item 7; M3A.2 proposals apply through the new path |
| **M3** | **Living Draft** — durable capture → draft on Today, resume/save/discard with disposition, planning result persisted with the draft, refinement actions on the current state, interpretation callout, timing roles, one unsolicited offer, "Ways I can help", save keeps step ids and provenance | M1, M2 | PLAN-010…014, PLAN-020…036 (prompt/validator work), PLAN-040…046, PLAN-050…054, PLAN-060…063, PLAN-083, INT-013 (retargeting), OD-7, OD-10 | Kill-mid-planning test recovers input and result with no new ledger row; no task appears until Save |
| **M4** | **Investigation thread** — v7 investigations (task, draft or standalone origin), turn model with authorization card, exact payload, per-item context exclusion, inherited-finding dispositions (OD-4), configuration sheet (model / effort / depth / budget / limit, Auto with disclosed resolution), stop/partial derivation with held-back proposals (OD-8), resume surface with "budget unknown", research-derived proposal sets with `src`, evidence/provenance sheets, association surface, M3A brief entry points retired | M1, M2 | RES-010…035, RES-040…047 (engine already), RES-090…107, RES-110…113, RES-120…135, RES-140…145, RES-150…154, RES-160/161, INT-040…045, INT-051…053, INT-060…065, INT-071/072, OD-3/4/8/9/10/11 | §14 items 1, 2, 4, 5, 8, 9 as native tests; MockWebServer runs for stop/failure/pause paths |
| **M5** | **Integration glue and spending** — research offers from gaps (readiness rule), draft-origin research, findings ticked per planning request (never persisted), combined warning/hard-stop budget, ledger rows for objective preparation, model-used everywhere, reasoning effort via `output_config.effort` where the model supports it | M3, M4 | INT-010…014, INT-022, RES-023, RES-142, PLAN-090…103, RES-120…123 | Payload fixtures prove no findings without ticks; budget thresholds stop new paid calls with the reason |
| **M6** | **Hardening and acceptance** — process-death instrumentation tests, accessibility (TalkBack order, 48 dp, 200 % scale, non-colour status), offline "nothing queued" test, screenshot tour update, report, roadmap update, legacy route removal | all | handoff §13 all rows, §14 all items | CI green incl. emulator; report with commands actually run |

Each milestone ships on its own branch stacked on the previous one with a draft PR, a report section in this document's companion report, and no merges.

**Execution order (owner decision G6, 2026-10-23, `docs/DECISION_LOG_ADDENDA.md`):** M1 → **M2 → M4 → M3** → M5 → M6. Milestone numbers above are kept for traceability; the Investigation Thread (M4) is built before the Living Draft (M3), and draft-origin research (INT-013) lands with M3. The research presets are Quick / Standard / Thorough / Deep + Custom (R48) and drafts never expire automatically (D52).

## 5. Blockers, contradictions and recorded assumptions

1. **Documents E/F/G/H** were missing at kickoff and are now reconciled (§9). Two owner decisions are surfaced there (milestone order vs. Document G §3; research preset names vs. Document F's "Standard" level, OQ-C1).
2. **PLAN-073 vs. current apply** (contradiction with existing code, resolved by M1): whole-task `updatedAt` rejection is replaced by per-item reconciliation against `rev`; the previous behaviour is kept only as the "task deleted/completed" guard.
3. **OD-5 vs. current pre-selection** (resolved by handoff): nothing pre-selected; `selectedByDefault` and EXPLICIT pre-selection are removed from review; "Select recommended" is the one-tap path that satisfies PLAN-071's acceptance.
4. **Priority proposals** (behaviour change from M2): the handoff forbids proposing priority; planning may still *read* priority. New drafts let the user set priority manually.
5. **Research without a task** needs a table rebuild (M4). Existing sessions are migrated 1:1; saved M3A briefs stay readable but gain no new features.
6. **Draft expiry (OQ-B1)**: decided by the owner on 2026-10-23 (D52): drafts never expire automatically; the user saves or discards them; the policy is stated on the draft surface (PLAN-013).
7. **Reasoning effort**: supported by the Anthropic SDK (`output_config.effort`); Claude 5.5 models run adaptive thinking by default and Sonnet/Opus 5.5 cannot disable it, so "effort" is exposed only where the provider supports it and recorded per turn (PLAN-093/RES-135). Implemented in M5, not M1.
8. **Two research generations in code**: the plan retires the M3A brief UI in M4 for the "one canonical implementation" rule. Data is kept. This is an engineering consolidation, not a product change.
9. **Prototype suites are not production acceptance**: they are run (see §6) only to confirm the behavioural reference is intact.

## 6. Prototype acceptance suites (behavioural reference only)

Run in this container with Node 22.22.0, Playwright 1.56.0 and the pre-installed Chromium, from an isolated copy of the handoff package. Results are recorded in §6.1 below as they complete. A passing browser suite proves the prototype still behaves as the handoff describes; it proves nothing about the Android app.

### 6.1 Results (run 2026-10-23 in this container; `node <suite>.js ../runnable <shots> <width>`)

| Suite | 360 px | 412 px |
|---|---|---|
| `integration-acceptance.js` | 76/76 passed | 76/76 passed |
| `living-draft-acceptance.js` | 31/31 passed | 31/31 passed |
| `proposal-review-acceptance.js` | 41/41 passed | 41/41 passed |

`npm install` fetched Playwright 1.56.0 only (exit 0); no page errors and no horizontal overflow were reported by any suite. Screenshots were written to the session scratchpad and are not committed.

## 7. Native acceptance tests to implement (from handoff §14 and the requirement acceptance lines)

| # | Test (native) | Milestone |
|---|---|---|
| T1 | Payload builders emit exactly the included context; excluded items absent; hand-edited objective naming an excluded item warns | M1 (builders), M4 (UI) |
| T2 | No research call or ledger row without an approval record; follow-up and continue each have their own | M4 |
| T3 | Proposal premises resolve to findings; findings to source keys; applied provenance stores them unchanged | M1 (storage), M4 (research src) |
| T4 | Continuation payload contains only sent findings and fresh checks; exclusions persist; nothing from other investigations | M4 |
| T5 | Obsolescence only on checked premises; obsolete items stay visible | M4 |
| T6 | Draft Save retargets research and pending proposals, applies nothing, keeps step ids and provenance; Discard keep/delete | M3 |
| T7 | Review: nothing pre-selected; material never bulk-selected; conflicts need explicit choices; Apply changes only chosen items; history records source and revs; original snapshot preserved | M1 (applier), M2 (UI) |
| T8 | Config payload and ledger reflect every dimension and requested → actual model; overrides do not alter defaults | M4/M5 |
| T9 | Budget / limit / Stop → derived partial state, supported kept, unsupported marked, usage incl. unknown in-flight, proposals only from established, Continue needs approval | M4 |
| T10 | User-stated deadlines never altered; targets stored separately | M1 (applier), M3 |
| T11 | Process death: drafts, results, investigations, pending proposals and ledger survive; nothing restarts | M3/M4/M6 |
| T12 | Offline / AI off: capture, save and every task feature work; nothing queued; reconnect produces no calls or ledger rows | M6 |

## 8. Milestone 1 — scope started in this session

Delivered on `prod-ux-m1-foundations` (see the M1 report section appended to this document when the milestone closes):
- Room v6 migration with schema export and migration test; existing rows keep every value.
- Domain: `Draft`/`DraftContent`, `ProposalSet`/`ProposalItem`/`ItemSrc`, `ProvenanceRecord`, task timing fields and `rev`, step origin.
- `ProposalReconciler` (pure) and `ProposalApplier` (transactional) with the PLAN-073/074 fixtures, provenance writes, history with revs and base snapshot, undo.
- Allow-listed payload builders for new-draft planning, task planning (with optional selected findings) and investigation turns, with redaction/leak tests.
- Backup DTOs and round trip for the new tables.
- No UI change beyond what compiles; no behaviour change for users until M2 wires the review.

---

# Milestone 1 report — foundations (branch `prod-ux-m1-foundations`)

## What changed

| Area | Files | Notes |
|---|---|---|
| Room v6 | `data/local/Entities.kt`, `Daos.kt`, `Migrations.kt`, `AppDatabase.kt`, `app/schemas/.../6.json` | `tasks.rev`, `eventLocalDate`/`eventLabel`, `intendedLocalDate`, `targetLocalDate`/`targetBasis`; `task_steps.origin`/`provenanceId`; tables `drafts`, `proposal_sets`, `provenance_records` (no foreign keys). Additive migration; no destructive fallback. |
| Domain | `domain/model/Enums.kt` (`ContentOrigin`), `domain/model/Task.kt`, `domain/draft/DraftModels.kt`, `domain/proposal/ProposalModels.kt`, `ProposalReconciler.kt`, `ProposalApply.kt` | One proposal model for planning and research, drafts and tasks; pure reconciler; `TargetSnapshot`, `ItemSrc`, `ProvenanceRecord`, `ApplyRecord`. |
| Repositories | `data/repository/DraftRepository.kt`, `ProposalSetRepository.kt`, `ProposalApplier.kt`, `TaskRepository.kt` | Every task write bumps `rev`; a user rename of a provenance-bearing step marks the user as latest editor without erasing the origin; the applier is the single proposal → task write path with undo. |
| Privacy | `ai/context/ContextAllowList.kt`, `ai/AiPlanningService.kt` | `PlanningContextBuilder` (new draft, existing task with switchable signals and per-request findings) and `InvestigationTurnBuilder` (turn payload with included context, sent findings, counts for excluded/not-sent, fresh checks, config, warnings). The planning snapshot now comes from the builder; `previewFollowThrough` exposes the exact payload for the disclosure surface. |
| Backup | `data/repository/BackupRepository.kt` | New optional arrays and task/step fields; pre-v6 files import unchanged; a set for a missing target rejects the import. |
| DI | `di/AppContainer.kt` | `draftRepository`, `proposalSets`, `proposalApplier`. |
| Docs | `docs/ARCHITECTURE.md`, `docs/TEST_MATRIX.md`, `docs/ROADMAP.md`, this file | |

**No screen changed.** Users see no difference until M2 wires Proposal Review to the applier. Existing M3A/M3A.2 apply paths keep working through `applyTaskEdit`.

## Requirement coverage delivered in M1 (storage and write-path level)

PLAN-010/013/014 (storage only; the Today surface is M3), PLAN-040/041/042 (fields and apply rules), PLAN-071 (selective apply), PLAN-072 (materiality), PLAN-073 (per-item reconciliation), PLAN-074 (done steps), PLAN-080/081 (allow-listed snapshot), PLAN-110 (atomic), INT-012/020/021/022 (builders), INT-030/031/033 (provenance records), INT-050 (no write without accept), INT-053 (targets apart from deadlines), INT-054 (history with both revisions, original snapshot), RES-160 (turn payload), OD-1, OD-2, OD-5 (recommended selection rule), OD-6.

## Tests added

`ProposalReconcilerTest` (9), `ContextAllowListTest` (7), `ProposalApplierTest` (12), `DraftRepositoryTest` (3), `MigrationTest.migrateV5ToV6…` (1), `BackupRepositoryTest` (+2). All deterministic (fake clock, sequential ids, in-memory or file Room under Robolectric).

## Gates actually run

Run locally in this container on the M1 tree (2026-10-23):

```
./gradlew -q lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest   # EXIT 0
```

| Gate | Result |
|---|---|
| `lintDebug` | passed (`abortOnError = true`); the lint report lists no issues |
| `testDebugUnitTest` | 577 tests in 74 classes, 0 failures, 0 skipped (incl. the 34 new M1 tests) |
| `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest` | APKs built (29.9 MB / 4.4 MB / 1.2 MB); local debug key only, so not installable over the phone's CI-signed build |
| Emulator instrumentation, screenshots | not run locally; Android CI on the draft PR (results recorded in the PR once available) |
| Prototype suites (reference only) | 76/76, 31/31, 41/41 at 360 px and 412 px |

## Limitations and what M1 does not claim

- No UI uses the new tables yet; drafts are not created from Capture until M3, and the research turn builder is not wired until M4. PLAN-010 is therefore **not yet met for users**; the storage it needs is.
- `ProposalApplier` targets tasks only. Draft-targeted apply (and retargeting on Save, OD-7) is M3; `ProposalSetRepository.retarget` exists but is unused.
- Research `src` for proposal items will be populated from the Investigation turn model in M4. Nothing converts today's `SessionContent.proposals` into `ProposalSet`s yet; the M3A.2 proposals screen is unchanged.
- No live provider call, no device install and no emulator run were performed by this session for M1; CI results are recorded above when available.
- The fixtures above were derived from the B/C/D acceptance lines and handoff §14 before Documents E/F/G/H were available; §9 maps them to Document F Stage 0 and records what changed.

---

# 9. Reconciliation against Documents E, F, G and H (2026-10-23)

All four documents were read in full. The frozen documents were not modified. Findings are listed with the frozen IDs they rest on; "changed" means this plan or the test matrix changed, never the baseline.

## 9.1 Material discrepancies and what changed

| # | Finding | Source | Effect on this plan |
|---|---|---|---|
| 1 | **The M3A.2 template layer cannot be the objective source.** RES-010/011 ("explicit objective; never inferred from topic, keyword or template") and RES-087 are **P0 Invariants** in Document G §2. Today `RequirementExtractor` derives requirements from keywords and `DeliverableTemplate` (A–E) shapes the result; Document F §9 classifies a category-keyed structure that dictates the answer as a *template defect*, and Document G §8 names this exact fork ("if its structured-contract design is the root of Problems 2 and 9, replacement is the cheaper path"). | G §2, §8; F §9; E R1, R14, R45 | **Changed.** M4 keeps the M3A.2 gather / evidence / checkpoint / ledger machinery and **retires** the keyword extractor and deliverable templates as the source of the objective and the shape of the answer: objective established under general consent and ledgered (RES-023, R32), objective-led composition (RES-080–084). §1.4's "reuse as the engine" is narrowed accordingly. |
| 2 | **Stage 0 is a continuous fixture harness and the first implementation slice.** Document G §7 step 5: "the Stage 0 fixture harness for Invariants is the first slice"; Document F §2.3 lists its contents. | F §2.3; G §7 | **Changed.** M1 is recorded as that slice. The Stage 0 mapping (which bullet is covered by which test now, which later) is in `docs/TEST_MATRIX.md` and summarised in §9.2. |
| 3 | **Deferred-capability rule (G4).** Recurrence proposals (PLAN-047) are P1 and deferred (D-R3); PLAN-045 was extended at the freeze so recurrence is never silently enabled; the Stage 0 fixture asserts no recurrence rule without confirmation; absence expectations are always graded. | H §1; E G4, D-R3; G §4 | **Changed.** Test-matrix rows depending on PLAN-047 are marked *(conditional on PLAN-047)*. The app has no task recurrence; the absence fixture is covered now by `nothingButAChosenReminderItemEverTouchesReminders` (no reminder policy is written unless a reminder item is explicitly chosen) and by the existing "no silent reminders" tests. |
| 4 | **Research effort levels.** Document F runs Stage 1/2 at the "Standard" level with "low" and "high" variants; R31 names presets *provisionally* Quick/Standard/Thorough/Deep; OQ-C1 (names and count) is open and assigned to UX; handoff OD-3 leaves it open too. The app has Quick/Thorough/Deep/Custom. | F §2.3, §7; E R31, OQ-C1 | **Decided 2026-10-23 (R48, `docs/DECISION_LOG_ADDENDA.md`):** Quick / Standard / Thorough / Deep + Custom; saved settings keep their preset, model, budget and limit exactly; Standard is a new choice. Implemented in M4. |
| 5 | **Milestone order vs. Document G §3.** G §2 says Invariants hold "from the first build" (supports M1 → M2 first). G §3's *suggested* Capability order puts research objective, evidence and continuation (items 1–3, "where the known failures live", first Stage 1 target) before planning output and timing (items 5–6). | G §1–3, §7 | **Decided 2026-10-23 (G6, `docs/DECISION_LOG_ADDENDA.md`):** M2 → M4 → M3 → M5 → M6. Numbers kept for traceability. |
| 6 | **AI-proposed priority is deferred/out of scope** (D38; G §6), not merely a handoff rule. | E D38; G §6 | Confirms the plan: `ItemKind` has no priority kind; the M2 review never proposes priority; the request sends priority read-only. No change. |
| 7 | **Binary gates and serious failures** (F §2.1, §6; E3, E5) define release acceptance: Authorization, Preservation, Task safety, Leakage; any §6 failure fails the run; applies to the current release candidate (G5). | F §2.1, §2.2, §6; E E3, E5, G5 | **Changed.** §9.2 maps M1 work to the gates it serves. Two M1 fixes came out of this pass: the double-apply race (Task safety / Preservation) and silent draft truncation (Preservation, "loss of user input"). The Leakage gate adds an RES-087 scan of user-facing text to M4 (the current M3A.2 technical log shows internal ids such as `r-a1`; it is a diagnostics surface, but the scan must confirm nothing reaches result text). |
| 8 | **OQ-B1 (draft expiry, limits, surfacing)** was an open UX question; the handoff did not close it. | E "Unresolved questions"; H §5 | **Decided 2026-10-23 (D52, `docs/DECISION_LOG_ADDENDA.md`):** v1 drafts never expire automatically; users save or discard. Count/surfacing details remain UX (M3). |
| 9 | **Document G §7 sequence** placed a read-only gap analysis (step 2) before UX design and implementation. | E G2; G §7; H §7 | No change. The owner's kickoff brief explicitly authorised implementation after the UX handoff; Phase 1 of this document is the step-2 assessment, recorded against baseline v1.0. |
| 10 | **Stage 1–3 are paid live benchmarks** (about twenty investigations for Stage 1; 60–80 runs for Stage 2). | F §2.3 | No change to code. Not run by the agent; they need the owner's explicit authorisation and key. |
| 11 | **Configuration tests** (F §7) require the ledger to record reasoning effort and requested → actual model per stage, and "unavailable model → no ledger entry for a substituted run". | F §7; E D40/D44, R28/R29/R43 | Confirms M5 scope; the ledger lacks an `effort` field today (noted in §2.6). |

Everything else in E/F/G/H agrees with the plan as written: Invariant vs. Capability tagging (E7), the gap taxonomy kept internal, the four timing roles (D14, D43), conflict-based reconciliation (D18), completed-work protection (D19), durable draft with explicit save (D16, D26), thread-scoped memory (R13), explicit association (R24, I5), selected-findings disclosure (I7), discard disposition (I6), and the three independent ceilings (R41).

## 9.2 Document F Stage 0 — mapping summary

| Stage 0 bullet (F §2.3) | Covered now | Remaining |
|---|---|---|
| Draft persistence and recovery; no task / notification / recurrence without confirmation (PLAN-010/013/014/045) | Storage and absence: `DraftRepositoryTest` (5), `nothingButAChosenReminderItemEverTouchesReminders` | User-facing recovery surface and kill-mid-planning test: M3 |
| Consent/authorization events vs. provider calls (PLAN-012/054/100; RES-021/023/025; INT-014) | Existing M2/M3A.2 gating and ledger tests (partial) | Authorization record per turn and RES-025 boundary: M4/M5 |
| Outbound payload inspection (PLAN-080–081; RES-093/160; INT-012/020–022/064) | `ContextAllowListTest` (7) on the builders | Wiring the turn builder and the selected-findings path: M4/M5 |
| Ledger completeness incl. effort and partial/unknown markers (PLAN-102; RES-123/143/154) | Existing `CostCalculatorTest`, `PlanningUsageLedgerTest`, ledger tests | `effort` field, per-turn requested → actual model: M5 |
| Ceiling precedence (RES-030/141) | Existing `DeepResearchServiceTest` spend-limit trimming (partial) | Named stopping reason and combined budget: M4/M5 |
| Status derivation, same evidence → same status, caveat guard, negative claims, contradictions, independence (RES-060/062/063/064/070/045) | Existing `CompletionPolicy`/`SessionAssembler`/`ContractParser` tests (partial, M3A.2 semantics) | Re-derived on the claim model after the template layer is retired: M4 |
| Citation-to-claim linkage (RES-044/085/101) | Existing provenance-pointer tests (partial) | M4 |
| Reconciliation logic (PLAN-073/074) | `ProposalReconcilerTest` (9), `ProposalApplierTest` (16), `ProposalApplierConcurrencyTest` (1) | UI choice surface: M2 |
| Provenance and as-of on incorporated findings; report immutability (INT-030/033; RES-092; INT-054) | `ProposalApplierTest` provenance, history and snapshot tests; existing continuation-never-overwrites tests | Research `src` population: M4 |
| Leakage scan of result text (RES-087) | — | M4 |
| Timing-field mapping (PLAN-040–041) | Fields and apply rules: `ProposalApplierTest`, `ProposalReconcilerTest` | Language → role mapping in the validator: M3 |

---

# M1 reconciliation pass — report (2026-10-23)

## Concurrent double-apply: real, reproduced, fixed

`ProposalApplier.apply` read the proposal set **before** taking the per-task lock and the Room transaction; only the task was re-read inside. Two Apply requests issued while the set was still OPEN both proceeded from the stale PENDING snapshot. `ProposalApplierConcurrencyTest` reproduces this deterministically: a gating coordinator holds both callers until each has read the set, then serialises them exactly as the production `ReminderEngine` lock does. On the unfixed code the APPEND_NOTE item was applied **twice** (two note lines, two provenance records, two history events; the test failed with "exactly one apply succeeds expected:<1> but was:<2>"). With an ADD_STEP in the same request the second apply happened to be stopped only by the duplicate-wording conflict, which is why the defect was not visible in the earlier tests.

**Fix (minimal):** the set is re-read inside the locked transaction; status must still be OPEN and each chosen item still PENDING; otherwise the request is refused with "These changes were already applied" and nothing is written. A mixed request applies only what is still pending. `undo` re-reads the set under the lock the same way (its `rev` check already made a double undo safe). Partially applied research sets keep their behaviour: unchosen items stay pending, `base` is rebased, `baseOriginal` is untouched (`partialApplyThenTheRestKeepsTheOriginalSnapshotAndRecordsTwoApplies`).

## Draft text: verbatim contract at the limit

`DraftRepository.create`/`update` silently truncated text to 20,000 characters. Now: text is stored exactly as given (whitespace, newlines, non-BMP characters included); input longer than `MAX_TEXT` (20,000 UTF-16 units, as `String.length` counts) is rejected with a `ValidationException` naming the limit and the actual length, and nothing is written; an over-long edit leaves the draft exactly as it was. Boundary tests: exactly 20,000 accepted verbatim; 20,001 rejected on create and on update. The M3 capture surface must show the same limit before the user reaches it.

## Other invariant findings

- **Deadline removed since the snapshot:** a proposed deadline against a task whose user-stated deadline was removed after the snapshot is now a CONFLICT ("You removed your deadline since this was proposed") rather than silently compatible. A task that still has a deadline stays BLOCKED (OD-6).
- Reviewed and unchanged (tests already cover them): per-item reconciliation, completed-step protection, explicit per-item choice (no bulk concept at the applier), idempotency (now also under concurrency), undo refused after an unrelated revision, provenance kept through a user rename, history snapshot preserved across a second apply, allow-list fingerprints.

## Tests added or modified in this pass

`ProposalApplierConcurrencyTest` (new, 1), `ProposalApplierTest` (+3: partial-then-rest with snapshot preservation and undo of the last apply only; re-apply of a settled item refused; no reminder written without a chosen reminder item), `DraftRepositoryTest` (+2: verbatim storage; exact-limit / over-limit on create and update), `ProposalReconcilerTest` (deadline-removed conflict).

## Gates actually run

Run locally in this container on the reconciled tree (commit `46d54b4`, 2026-10-23):

```
./gradlew -q lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest   # EXIT 0
```

| Gate | Result |
|---|---|
| `lintDebug` | passed; the lint report lists no issues |
| `testDebugUnitTest` | 583 tests in 75 classes, 0 failures, 0 skipped (6 new since the M1 run; the race test failed on the unfixed code and passes on the fixed one) |
| `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest` | APKs built (30.1 MB / 4.4 MB / 1.2 MB); local debug key only |
| Android CI (PR #6) | runs 118 and 119 green on the M1 heads (`638a187`, `0756670`); runs 120/121 on `46d54b4` recorded in the PR when complete |
| Emulator instrumentation, physical device, live provider | not run by the agent |

---

# Milestone 2 report — the one Proposal Review (branch `prod-ux-m2-proposal-review`, stacked on M1)

Baseline: v1.0 + addenda A1–A3 (`docs/DECISION_LOG_ADDENDA.md`). Acceptance authorities: Document F (Task safety and Preservation gates, §5.1 P10, §5.3 I-1 handoff rules) and `docs/TEST_MATRIX.md` ("Production M2").

## What changed

| Area | Files | Notes |
|---|---|---|
| Review UI | `ui/review/ProposalReviewScreen.kt`, `ProposalReviewViewModel.kt`, `ReviewPresenter.kt`, `ProvenanceSheet.kt`, `Routes.REVIEW` | Nothing selected on open; Select recommended; plain vs material sections; current → proposed; inline edit / date change; conflicts with Apply anyway / Keep yours; stale banner; Apply → summary with Undo; Reject / Reject all; explanations collapsed; selection, edits and decisions in `SavedStateHandle`. |
| Bridges | `ai/PlanningProposalBridge.kt`, `ai/research2/ResearchProposalBridge.kt` (+ `ResearchProposalSets`) | Pure mapping into `ProposalSet`; nothing written to tasks. |
| Entry points | `ui/detail/TaskDetailScreen.kt` / `TaskDetailViewModel.kt` (banner per open set, provenance marker + sheet on steps), `ui/research2/ResearchSessionScreen.kt` / `Research2ViewModels.kt` (hand-off), `ui/plan/PlanViewModel.kt` / `PlanScreen.kt` (follow-through hand-off), `LifeAdminRoot.kt`, `AppContainer.kt` | |
| Domain | `ProposalSet.title/advice/assumptions/questions`, `ProposalApplier.decline` | Backward-compatible fields with defaults. |
| Retired | `ui/research2/ProposalsScreen.kt` (+ `ProposalsViewModel`), `ai/research2/Research2Actions.kt`, `ProposalMapper.toEdit`, route `RESEARCH2_PROPOSALS`, `Research2ActionsTest` | Replaced only after the new tests covered the same behaviour. Stored M3A.2 reports and their `applied` history remain readable: legacy applied moves show as "Applied earlier". |

## Old proposal paths: migrated or retired

| Path | Before | Now |
|---|---|---|
| M3A.2 research proposals | `ProposalsScreen` → `Research2Actions.apply` → `TaskRepository.applyTaskEdit` (note lines with evidence) | "Review N changes" → `ResearchProposalSets.ensure` → canonical review → `ProposalApplier` (provenance records instead of note lines) |
| Planning on a saved task | `PlanReview` on the plan screen → `ProposalMapper.toEdit` → `applyTaskEdit` | `PlanningProposalBridge` → set saved → canonical review → `ProposalApplier`; the plan screen leaves the stack |
| M3A brief moves (legacy) | `ResearchApply` → `ResearchActions` → `applyTaskEdit` | **Unchanged** — the last parallel write path; retires with the M3A entry points in the Research Online milestone (M4) |
| New task from a draft | `ProposalMapper.toCreate` → `createPlannedTask` | Unchanged (a save, not a proposal apply; Living Draft milestone) |

## Acceptance mapping

See `docs/TEST_MATRIX.md` → "Production M2" (17 rows) and the M1 rows it builds on. Document F: Task-safety gate (no change without an explicit accept; material changes individually accepted; conflicts flagged, never overwritten) and Preservation gate (prior reports, ledger and task history intact; undo restores) are exercised by `ProposalReviewUiTest`, `ProposalReviewViewModelTest`, `Research2FlowUiTest` and the M1 applier tests. P10 turn 2 ("user ticks step 3 during review → remaining proposal applies; touched item flagged") is `ProposalReviewUiTest.aStepTickedMidReviewIsFlaggedAndKeepYoursDeclinesWithoutWriting`.

## Gates actually run

Local, on `bd6b367` (the head after the test-synchronisation fixes below), `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --continue`: **exit 0**.

| Gate | Result |
|---|---|
| `testDebugUnitTest` | 595 tests, 0 failures, 0 errors (whole suite; includes the new review, bridge and migrated flow tests) |
| `lintDebug` | "No issues found." |
| `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest` | all three APKs built |
| Emulator instrumentation + screenshot tour | CI only (`Instrumented tests (emulator API 34)` job); not run locally |

Earlier heads on this branch were red in CI and locally for test-synchronisation reasons, each fixed in its own commit rather than by loosening the assertion: the end-to-end review test asserted before the reopened review had loaded (`5ae6e37`); the view-model apply test read a message left over from the previous apply, and the research 200 % flow test still walked the retired proposals screen (`6d8520d`); under full-suite load the apply summary can arrive before the observed task and set carry the new revision, so the view-model test now waits for Undo to be offered (`bd6b367`). One product change came out of this: deciding a flagged change (Apply anyway / Keep yours) now clears the "Decide on the flagged change first" message.

**Android CI:** runs 134 (push) and 135 (pull_request) on `34e556c` — **green**, both jobs ("Lint, unit/Robolectric tests, APKs" and "Instrumented tests (emulator API 34)", 18 instrumentation tests). Before that: run 131 on `bd6b367` was **green** in "Lint, unit/Robolectric tests, APKs" and **red** in "Instrumented tests (emulator API 34)": `ScreenshotTourResearch2.s1_…` waited for the literal "Apply 2 changes" after Select recommended, but the tour's follow-up run adds a sourced glucosamine step, so the count differs from the Robolectric flow test; the tour now waits for any "Apply N changes" (fixed in the following commit; the other 17 emulator tests, including the AI-review tour j1–j4 through the new review, passed). Earlier heads (`b81372d` cancelled by the next push, `4802301`, `5ae6e37`, `6d8520d`) were **red** in the "Lint, unit/Robolectric tests, APKs" job for the test-synchronisation reasons above, each fixed by the following commit.

## Remaining risks and deferred work

- The M3A brief apply path still exists (see above); two write paths remain until M4, both transactional.
- Research `src` is derived from the M3A.2 contract (refs → sources, coverage status → finding status). The claim-level model and the retirement of the keyword/template layer are M4; `src` will be re-derived there.
- Dates are editable in review through a date picker; reminder schedules and status values are accept/reject only in M2 (the planning validator already resolved the schedule; a reminder editor in review is deferred).
- Draft-targeted sets (`TargetKind.DRAFT`) are stored but the review opens task sets only until the Living Draft milestone (M3).
- Emulator instrumentation and screenshots run in CI only; no physical device and no live provider call in this milestone.


---

# Milestone 4 plan — Research Online Investigation Thread (branch `prod-ux-m4-investigation-thread`, stacked on M2)

Baseline: v1.0 + addenda A1–A3. Authorities: Documents A, C, D, F (Stage 0 fixtures and §5.2 cards), G §1–3, H, J; `UX-implementation-handoff.md` v1.1 (§3 components, §4.3 investigation, §5 states, §6 authorization, §8 inheritance and configuration, §13 production obligations, OD-3/4/8/11); `docs/TEST_MATRIX.md`. Written before any M4 code change, as the brief requires.

## M4.1 Component disposition (read before this was written: every file under `ai/research`, `ai/research2`, `ui/research`, `ui/research2`, the Room schema, backup, usage ledger, M1 builders, M2 review)

| Disposition | Component | Why |
|---|---|---|
| **Retain** | `ModelCall`, `CallResult`, `CallStop`, `ReplyShape`, `ResearchCallProvider`; `AnthropicResearchCalls` (adapter) | Provider seam is objective-neutral. Gains one field: reasoning effort via `output_config.effort` (SDK 2.73.0 exposes it; RES-135). |
| **Retain** | `SourceRegistry2`, `SourceRef2`, the citation-overlap / `src=` pointer / restatement mechanics of `ContractParser` | Provenance rules (provider citations verify; model-typed pointers never do) are exactly RES-044/085/101. Moved into `ai/investigation/EvidenceText.kt` and reused by the new parser. |
| **Retain** | `CallDiagnostic`, `CallClass`, `StageRecord`, `StageKind`, `RunUsage`, `Research2Config` limits (per-call timeout policy, bounded batches, caps), `ResearchError` catalogue | Privacy-safe diagnostics and the real-device fixes (P0-1/P0-2) carry over unchanged. |
| **Retain** | Usage ledger (`UsageRecord`, `UsageLedger`, `RoomUsageLedger`, `CostCalculator`, `PricingCatalog`), `UsageScreen` | Adds `UsageFeature.OBJECTIVE_PREP` (RES-023 separate ledger entries) and a per-row `effort`; everything else as is. |
| **Retain** | `InvestigationTurnBuilder` / `TurnInput` / `FrozenPayload` (M1), `ProposalSet` model, `ProposalApplier`, `ProposalReconciler`, Proposal Review (M2) | The frozen payload and the one write path were built for this milestone. |
| **Retain (read-only)** | `SessionContent` and its sub-types, `ResearchBriefEntity` + `ResearchBriefScreen` | Legacy results stay readable exactly as produced (RES-092; the brief: never relabel historical conclusions). The types are kept as the legacy decoder; nothing new is written in that shape. |
| **Refactor** | `DeepResearchService` → `InvestigationService` + `TurnRunner` | The gather / bounded-batch / checkpoint / spend-limit / stop / ledger loop is kept; what the loop asks for and how it judges "open" moves from requirement coverage to load-bearing claims. Stage plan: GATHER → VERIFY (open load-bearing claims, Standard and up) → SYNTHESIZE (Deep), each a ceiling. |
| **Refactor** | `ResearchModes` → `ResearchPresets` | R48: Quick / Standard / Thorough / Deep + Custom. Quick, Thorough and Deep keep their exact model, budget and stage definitions; Standard is new (Sonnet 5.5, 6 searches, gather + verify). Saved settings map 1:1; `researchStandardModel` is a new, blank-by-default setting. |
| **Refactor** | `ResearchProposalBridge` → `InvestigationProposalBridge` | The transitional coverage → "established" mapping is replaced by claim-level `src`: every item names the claims it rests on; each claim names its findings, status, as-of and evidence keys. Established → correction (may mark an assumption superseded); indicative/contested → labelled verification item; not established → no proposal; partial/stopped turns hold back anything resting on an unchecked claim (OD-8). |
| **Refactor** | `ResearchSessionRepository` → `InvestigationRepository`; `research_sessions` → `investigations` + `investigation_turns` (Room v7) | Nullable task, nullable draft, origin, one immutable row per turn. Every existing session becomes one investigation (same id, so ledger rows still join) with one legacy turn per recorded run; the table is then dropped. |
| **Refactor** | Backup | New `investigations` and `turns` arrays; files that still carry `sessions` import through the same conversion. |
| **Refactor** | Task detail research card, AI settings research section, screenshot tour | Entry points for the thread (task-linked, standalone, association); legacy briefs listed read-only. |
| **Retire** | `RequirementExtractor`, `DeliverableTemplate`, `Requirement` / `Coverage` as drivers, `CompletionPolicy`, `SessionAssembler`, `ProposalBuilder`, `Research2Prompt`, the REQ/CAND/ATTR/PICK/EXCLUDED/CALLOUT contract | RES-010/011 (Invariants): the objective, the investigation and the answer's shape come from the objective, never from a keyword or a template; RES-080: components present, never drive; RES-087: no requirement ids near user-facing text. `ProposalBuilder` manufactured a proposal per requested item — INT-041 forbids manufactured checklist actions. |
| **Retire** | `ui/research2/*` (Preflight2Screen, InvestigationScreen, ResearchSessionScreen, Research2Components, Research2ViewModels), routes `RESEARCH_NEW`/`RESEARCH2_RUN`/`RESEARCH2_SESSION` | Replaced by `ui/investigation/*` (one thread screen with authorization / running / result / stopped / interrupted turns). |
| **Retire** | M3A run and apply path: `ResearchService`, `ResearchActions`, `ResearchPrompt`, `BriefParser`, `AnthropicResearchProvider.research()`, `ResearchPreflightScreen`, `ResearchProgressScreen`, `ApplyResearchScreen`, `ResearchHistoryScreen`, routes `RESEARCH_HISTORY`/`RESEARCH_RUN`/`RESEARCH_APPLY`; `TaskRepository.applyTaskEdit` research-only undo | One canonical implementation (handoff §0.3). Saved briefs remain readable from the task (`RESEARCH_BRIEF`, read-only). |

Not a wrapper: the keyword extractor and the deliverable templates are deleted, not hidden behind the new screens.

## M4.2 Objective and evidence architecture (`ai/investigation`)

**Objective (RES-010–013, 020, 025).** `Objective { question (verbatim), text (what is being learned or decided), askKind ∈ {FACT, PROCEDURE, COMPARE, RECOMMEND, EXPLAIN, FORECAST, DECIDE}, satisfies[], constraints[], evidenceNeeds[], unknowns[{text, blocking}], exclusions[], consequential, established, clarification? {question, options[]}, recommendedDepth + reason }`. `ObjectivePreparer` has two paths behind one interface: a local pass (verbatim objective; ask-kind from the sentence's own request words; ambiguity only from unresolved referents such as a bare "is it worth it?"; never from topic) and the provider pass (one no-search call, cheapest model, under general AI consent, ledgered as `OBJECTIVE_PREP` and never as investigation spend). With general consent off, no call is made and the local objective is shown for correction. Not established → a clarification card before any authorization surface exists (R-J, R-N(b)); the user's answer re-prepares. Established with a missing detail → authorization with the gap disclosed; the detail may be supplied under that authorization only before the first web search (RES-025, OQ engineering note).

**Claims and evidence (RES-040–047, 070–072, 101, 107).** `Claim { id, text, type ∈ {FACT, CALCULATION, INFERENCE, JUDGMENT, RECOMMENDATION}, scope, asOf, loadBearing, volatile, dependsOnUser, support[EvidenceRef], against[EvidenceRef], status, statusReason, inheritedFromTurn?, supersededBy? }`; `EvidenceRef { sourceN, excerpt, scopeNote, retrievedAtMs, originKey }`. `ClaimAssessor` derives every status in code from the evidence attached to that claim: ESTABLISHED needs at least one provider-cited source in scope and not stale and no un-reconciled opposing evidence; opposing evidence on the same claim → CONTESTED (one claim, two positions); only pointers or restatements → INDICATIVE; nothing → NOT_ESTABLISHED; `dependsOnUser` → DEPENDS_ON_INPUT; JUDGMENT and RECOMMENDATION never carry a status of their own and inherit the weakest premise. Independence counts origins (`originKey` = publisher host), never pages. The same evidence yields the same statuses whatever model or depth produced it (RES-064): the assessor takes no configuration input.

**Result state (RES-060–063).** `ResultDeriver` maps the load-bearing claims to ANSWERED / ANSWERED_WITH_CONDITIONS / PARTIALLY_ANSWERED / NOT_ESTABLISHED / NEEDS_INPUT. A condition is a `dependsOnUser` claim; an unresolved decisive fact (NOT_ESTABLISHED / CONTESTED / INDICATIVE on a load-bearing claim) is blocking → partial, never "with conditions" (RES-062). Stopped, budget-limited and failed turns derive from what was kept and are additionally marked. The model's own completeness words are parsed as text, never as state (RES-065).

**Composition (RES-080–089).** The contract is objective-led: TITLE, ANSWER (prose with `[cN]` markers on factual premises), CLAIM blocks (type, scope, as-of, load-bearing, evidence lines with provider citations, AGAINST lines), CONDITION, NEEDS_INPUT, CHANGED / CONFIRMED (continuations), STEPS / COMPARE / RECOMMEND only when the ask kind calls for them, PROPOSE (task-linked only; must cite claim ids), UNRESOLVED. `AnswerComposer` validates: markers only on fact / calculation / inference claims (RES-085); a leakage scan removes or rejects internal ids and status words (RES-087); every load-bearing claim that is not established gets its caveat in the main answer when the model left it out (RES-086); "does not exist / is false" wording on a NOT_ESTABLISHED claim is rewritten as "no support was found" (RES-063); nothing is added because it was found (no price table unless the objective asks about price).

**Thread (RES-090–094, 100–107; INT-021, 064; OD-4).** `Investigation { id, origin, taskId?, draftId?, title, turns[] }`; `Turn { id, index, kind, state, objective, authorization {includedContext, exclusions, inheritedDispositions, config (requested → actual model, effort, depth, budget, limit, preset), payloadSha, approvedAtMs}, payload (frozen JSON), result (immutable), usage, stop/failure/interruption record }`. A continuation keeps the prior objective and constraints, exposes a constraint change, inherits claims by relevance with dispositions SENT / EXCLUDED / NOT_SENT / REPLACED / FRESH (volatile claims are checked fresh, never reused), carries exclusions forward and never reinserts them, and records CHANGED (supersession, with the old claim marked superseded and never current support) or CONFIRMED (only when asked). Nothing from another investigation or another task is an input to the payload builder.

**Authorization (RES-021–025, 030, 140–144, 150–154).** A turn row with its authorization record and frozen payload is written before any provider call; the runner refuses to start without one (Stage 0: no web-search call without an authorization event). Preparation under general consent is the only call allowed before it. Stop keeps established claims and marks the rest "not checked" in the main answer; failure midway does the same with the failure stated; process death marks the turn INTERRUPTED on next start, settles its ledger rows as unknown, restarts nothing, and the resume surface lists preserved / unresolved / proposed with remaining budget "unknown". Continue and Resume are new turns with new authorizations.

**Configuration (R48; RES-120–123, 130–135, 141).** Independent dimensions: model, reasoning effort (sent as `output_config.effort` only for models the app lists as supporting it; otherwise the control is disabled with the reason; the setting is recorded either way), depth (preset), search budget (follows the preset until set), spending limit. Requested and actual model are both recorded per turn. No silent substitution: an unavailable model is an error with alternatives, never a swapped run.

## M4.3 Slices (one reviewable PR, separate commits)

1. Domain models, assessor, deriver, composer, parser, preparer, presets — deterministic tests incl. Stage 0 fixture sets and five cross-domain objectives.
2. Room v7 (`investigations`, `investigation_turns`, `usage_records.effort`), migration 6→7 with seeded sessions, repository, backup round trip and legacy import.
3. `InvestigationService` + `TurnRunner`: prepare, authorize, run, stop, failure, interruption/resume, association; fake-provider tests for authorization, payload exclusion, isolation, ceilings, preservation.
4. Continuation: inheritance dispositions, exclusion persistence, supersession/changed-since, obsolescence of earlier proposals.
5. Compose UI (`ui/investigation`): home/history, thread, configuration sheet, evidence sheet, association sheet, task-detail entry points; Robolectric at default and 320 dp / 200 %.
6. Proposals through the canonical review; retirement of the research2 UI and the M3A run/apply path; legacy read-only access kept.
7. Regression, gates, CI, screenshot tour, docs, report.

Known boundaries recorded up front: draft-origin research and the Living Draft hand-offs are M3 (G6); the combined planning + research period budget with warning and hard-stop thresholds is M5 (RES-142) — M4 keeps the existing monthly soft note and the per-turn spending limit; objective-preparation calls are ledgered in M4 (the "necessary minimum" the brief allows) and the RES-142 combined threshold is not applied to them until M5.

---

# Milestone 4 report — Research Online Investigation Thread (branch `prod-ux-m4-investigation-thread`, stacked on M2; draft PR #8)

Everything below is deterministic evidence: scripted call provider, in-memory or file Room, Robolectric Compose. **No live provider call was made and no physical device was used.** Emulator instrumentation runs in CI only.

## Commits

| Slice | Commit | Content |
|---|---|---|
| Plan | `76b7dca` | M4.1–M4.3 above, written before code |
| 1 | `96e1d20` | Objective and evidence domain: `Objective`, `Claim`, `EvidenceRef`, `ClaimAssessor`, `ResultDeriver`, `AnswerComposer` + `LeakageScan`, `InvestigationParser`, `ObjectivePreparer`, `ResearchPresets` (R48) |
| 2 | `53fa7e8` | Room v7 (`investigations`, `investigation_turns`, `usage_records.effort`), `MIGRATION_6_7`, `InvestigationRepository`, backup arrays and legacy import |
| 3 | `c7e6e09` | `InvestigationService`: prepare / clarify / authorize / run / stop / failure / interruption / association; `AuthorizationBuilder` (frozen payload + SHA-256) |
| 4 | `54f4a0d` | Objective-led composition across domains; continuation (inheritance dispositions, exclusions, supersession, CHANGED / CONFIRMED) |
| 5 + 6 | see PR | Native thread UI and home; proposals through the canonical review; retirement of the M3A and M3A.2 run/apply paths. **Committed together**: the new routes, task-detail entry points and `AppContainer` wiring replace the retired screens in the same files, so neither half compiles alone. |
| 7 | see PR | Regression fixes, screenshot tour, gates, docs and this report |

## Components: what actually happened versus M4.1

| Plan | Outcome |
|---|---|
| Retain provider seam, `SourceRegistry2`, diagnostics, ledger, M1 builders, M2 review | As planned. `ModelCall` gained `effort`; the ledger gained `UsageFeature.OBJECTIVE_PREP` and a per-row `effort`. |
| `DeepResearchService` → `InvestigationService` + `TurnRunner` | **Deviation:** one class, `InvestigationService`. The run loop (GATHER → FILL_GAPS → CROSS_CHECK → SYNTHESIZE, each a ceiling) lives in it; a separate `TurnRunner` added no seam the tests needed. |
| `ResearchModes` → `ResearchPresets` | New runs use `ResearchPresets` only. `ResearchModes` stays because the read-only legacy session screen labels old runs with it. |
| `ResearchProposalBridge` → `InvestigationProposalBridge` | New bridge written (claim-level `src`). The old bridge stays only so a legacy M3A.2 report's pending proposals can still open in the canonical review. |
| `research_sessions` → `investigations` + turns | As planned: same ids, one legacy turn per recorded run, table dropped. |
| Retire `RequirementExtractor`, `DeliverableTemplate`, `CompletionPolicy`, `SessionAssembler`, `ProposalBuilder`, `Research2Prompt` | **Partly a deviation.** Removed from every run path: no new run reads a keyword, a template or a requirement. `RequirementExtractor` and `Research2Prompt` are deleted from the app (`RequirementExtractor` survives as a test fixture helper). `DeliverableTemplate`, `CompletionPolicy`, `SessionAssembler`, `ContractParser` and `ProposalBuilder` remain as the **decoder and display code for stored M3A.2 reports** (the read-only session screen and the legacy converter call them); their existing tests still run. Deleting them would make stored reports unreadable, which RES-092 forbids. |
| Retire `ui/research2/*` and routes `RESEARCH_NEW` / `RESEARCH2_RUN` | `Preflight2Screen` and `InvestigationScreen` deleted; route `RESEARCH2_RUN` removed. **Deviation:** `ResearchSessionScreen` is kept as the read-only view of a legacy report (no continue, no run) because a legacy turn's "Open the original report" goes there. |
| Retire the M3A run/apply path | `ResearchService`, `ResearchActions`, `ResearchPreflightScreen`, `ResearchProgressScreen`, `ApplyResearchScreen`, `ResearchHistoryScreen` and routes `RESEARCH_HISTORY` / `RESEARCH_RUN` / `RESEARCH_APPLY` deleted. The M3A brief screen is read-only (its "apply moves" path is gone, so there is now **one** task write path for research: the canonical review). **Retained, unused by any screen:** `BriefParser`, `ResearchPrompt` and `AnthropicResearchProvider.research()`; the adapter's HTTP tests still exercise them, and removing the adapter method is a separate clean-up. |

Not a wrapper: the new thread never calls the keyword extractor, a deliverable template or the coverage policy. The answer's shape comes from the objective's ask kind (`AnswerComposer`), and statuses from evidence (`ClaimAssessor`).

## Architecture as built

`ObjectivePreparer` (local pass; provider pass only under general AI consent, one no-search call, ledgered as objective preparation) → turn in CLARIFYING or AUTHORIZING → `AuthorizationBuilder` (objective, context switches, inherited findings with dispositions, configuration, estimate, frozen payload JSON and its SHA-256) written onto the turn → `InvestigationService` run loop with bounded calls, a checkpoint after every call, the search budget and the spending limit as ceilings → `InvestigationParser` → `ClaimAssessor` → `ResultDeriver` → `AnswerComposer` → immutable turn result → `InvestigationProposalBridge` (task-linked turns only) → canonical Proposal Review → `ProposalApplier`. The thread UI (`ui/investigation`) renders every turn state from the stored turn, so a restart shows the same thread.

## Room v7 migration

`MIGRATION_6_7` creates `investigations` (task FK `ON DELETE SET NULL`, so deleting a task keeps the research) and `investigation_turns` (unique `investigationId + turnIndex`, cascade on investigation delete), adds `usage_records.effort`, converts every `research_sessions` row through `LegacyInvestigations.fromStored` with the same id (ledger rows still join), then drops the old table. No destructive fallback. Tested by `MigrationTest.migrateV6ToV7…` on a seeded v6 database, `InvestigationRepositoryTest` and the backup cases (files with `sessions`, files with `investigations`, a running turn in a backup imports as interrupted).

## Document F Stage 0 — M4 rows

| Stage 0 bullet | M4 coverage |
|---|---|
| Authorization events vs. provider calls (RES-021/023/025) | `InvestigationServiceTest.preparingAndOpeningMakeNoProviderCall…` asserts the authorization and payload hash are on the turn when the first call is made; clarification blocks authorization (`…aClarificationBlocks…`) |
| Outbound payload inspection (RES-093/160; INT-020–022/064) | Same test: an unticked context item is listed as not sent and its text is absent from the payload and the prompt; excluded findings stay out of later turns (`InvestigationContinuationTest`, `InvestigationThreadUiTest.aFollowUpCanExcludeAnEarlierFinding…`) |
| Ledger completeness incl. effort and partial/unknown | Effort per row; stopped and interrupted turns settle as floor / unknown (`InvestigationServiceTest` stop and recovery cases) |
| Ceiling precedence (RES-030/141) | `InvestigationServiceTest` spending-limit cases (added in slice 7, see below) and the search-budget endings |
| Status derivation, same evidence → same status, negative claims, contradictions, independence | `ClaimAssessorTest`, `ResultDeriverTest`, `AnswerComposerTest` — now on the claim model |
| Citation-to-claim linkage (RES-044/085/101) | `InvestigationParserTest` (provider citations verify; typed pointers never do), `AnswerComposerTest` (markers only on citable claims) |
| Provenance on incorporated findings; report immutability | `InvestigationProposalBridge` writes claim-level `src`; turns are immutable rows; continuations never edit an earlier turn |
| Leakage scan (RES-087) | `LeakageScan` + `AnswerComposerTest` across five unrelated objectives |

## Cross-domain results

`InvestigationContinuationTest.fiveUnrelatedObjectivesGetFiveShapesFromTheirAskAndNoneLeaksInternals` runs five unrelated objectives through the same service, parser, assessor and composer:

| Objective | Shape produced | Derived state |
|---|---|---|
| Address fact (Union Station, DC) | Lead only; no sections; no recommendation | Answered |
| Passport renewal procedure | Steps + a condition the person resolves; no table | Answered with conditions |
| Multivitamin comparison | Comparison table on criteria; optional pick; "Sources disagree" caveat in the main answer | Partially answered |
| Engineering decision (Snowflake direct vs. replicate to Postgres) | Recommendation whose reasons cite claims; the recommendation itself uncited; flip condition | Answered with conditions |
| Unverifiable product claim (filter removes 99.99 % of microplastics) | An "unresolved" section; nothing proposed | not asserted in this test |

All five pass the leakage scan and contain no template vocabulary. The fragrance follow-up (`aFollowUpChangingTheBudget…`) adds a sixth domain for continuation: the new budget replaces the old constraint, volatile prices are checked fresh and the earlier pick is superseded.

## Authorization and payload isolation

Nothing paid runs before Start. Preparation, opening the thread and changing settings make no call. The payload is built only from the current task's allow-listed fields the person left switched on, the objective, and the inherited findings of the **same** investigation with their dispositions; nothing from another investigation or another task is an input. The exact JSON the run will send is viewable before Start ("See exactly what will be sent") and its SHA-256 is stored on the turn.

## Stop, interruption and recovery

Stop offers keep or discard. Keep marks unchecked load-bearing claims "Not checked before it stopped" in the main answer; discard drops the findings but keeps the turn and its usage. A failure midway keeps checkpointed evidence and states the failure. Process death leaves a RUNNING turn; on next start `recoverInterrupted()` marks it INTERRUPTED, settles its usage as unknown and restarts nothing. Resume is a new turn with a new authorization and lists what was preserved and what is unresolved.

## UI and accessibility

One thread screen for every state: question, objective card (editable), clarification, missing details, authorization (depth presets, Adjust panel with model / searches / limit / effort, context switches, inherited findings, payload sheet, estimate), running (progress, Stop), result turns (answer, caveats, sections, recommendation, evidence and sources sheets, "How this was researched"), stopped / failed / interrupted turns with resume, declined turns, legacy turns with "Open the original report", follow-up composer. A home list shows all investigations, task-linked and standalone, with link / unlink / delete. Task detail, Today and the review screen link into it. Robolectric tests cover the flows at default size and at 320 dp with 200 % text; the bottom-bar footer shortens at large font scales so Start stays reachable.

## Slice 7 findings (regression pass)

- **Depth or configuration tap could be lost (product bug, fixed).** The thread view model rebuilt the authorization preview from several coroutines at once. A slower rebuild that began before a tap could finish last and overwrite the configuration, so Start could run a different depth than the one chosen. Rebuilds are now serialised, always read the latest choices and write nothing if the choices changed mid-rebuild. Start resolves its configuration from the current choices directly. Seen in `InvestigationThreadUiTest.aFollowUpCanExcludeAnEarlierFinding…`, where a two-stage run was sent instead of Quick.
- **A spending limit below the first call reported "the reply was empty" (product bug, fixed).** Nothing had been sent. The turn now fails with "Your spending limit is below the first call" and nothing sent. A turn whose ceiling left nothing gathered is FAILED, not DONE. New tests: `InvestigationServiceTest.aSpendingLimitBelowTheFirstCall…` and `…aSpendingLimitTrims…`. These restore the ceiling coverage the retired `DeepResearchServiceTest` had.
- **Robolectric test harness.** Waiting on service or database state with Compose's `waitUntil` does not run the main looper when the UI is idle. A coroutine resuming on Main after a database hop could then sit unrun, and the stop test timed out. The thread UI tests now use a looper-pumping wait for non-UI conditions and the file-backed test database (WAL, as in production). This is a test-environment fix; it changes no app code.

## Gates actually run

Local, on the slice 7 working tree (committed as the head of this branch), `./gradlew --continue lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`: **exit 0**.

| Gate | Result |
|---|---|
| `testDebugUnitTest` | 596 tests in 83 classes, 0 failures, 0 errors, 0 skipped |
| `lintDebug` | "No issues found." |
| `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest` | all three APKs built (30.8 MB / 4.5 MB / 1.2 MB); local debug key only |
| Thread UI + service tests, repeated | `InvestigationThreadUiTest`, `InvestigationThreadLargeTextUiTest`, `InvestigationServiceTest`: three consecutive green runs (6 + 15 tests) |
| Emulator instrumentation + `ScreenshotTourInvestigation` | CI only (`Instrumented tests (emulator API 34)`); not run locally. The androidTest APK compiles. |

Earlier runs on this branch, recorded rather than overwritten: the first full run on the slice 5 + 6 tree (`e760258`) had 594 tests with 1 failure (`stoppingKeepsWhatWasFound…`, the harness wait above). The second had 596 tests with 1 failure (same test). In isolation that run also exposed the lost-tap race in the follow-up test. Both were fixed as described above.

Android CI on the pushed head is recorded in PR #8.

## Limitations and dependencies

- **No live provider run.** The prompts, contract and parser are tested against scripted replies only. A live Anthropic run (with web search) needs your explicit approval and your key.
- **No physical device.** Emulator instrumentation and the screenshot tour run in CI only.
- Legacy decoder code (see the components table) is retained for reading stored reports; removing it requires a decision to stop displaying them in their original form.
- `AnthropicResearchProvider.research()`, `BriefParser` and `ResearchPrompt` are unused by any screen and should be removed in a clean-up PR.
- Draft-origin research and the Living Draft hand-offs are **M3** (not started). The combined planning + research period budget with warning and hard-stop thresholds is **M5**; M4 keeps the monthly soft note and the per-turn spending limit, and objective-preparation calls are ledgered but not yet counted against a combined threshold.

---

# M4 evidence-correctness review (RES-042–046, 070–072, 107), 2026-10-23

A narrow pass on PR #8, requested before M3. It traced the evidence path from the reply to the answer: the parser reads EVIDENCE and AGAINST lines, the turn work merges them, the claim assessor sets each status, the result deriver sets the state, then the composer and the proposal gate run. Each finding was reproduced as a failing test on `d01894a` before any change (8 of 15 new cases failed).

## Findings

| Concern | Verdict | Evidence on `d01894a` |
|---|---|---|
| Any one non-pointer citation without opposing evidence establishes a claim (RES-043) | **Confirmed.** Nothing scaled the evidence needed with the claim or its consequences. A health-benefit claim resting on one blog post and one small study was established. | `aConsequentialBenefitClaimOnLowQualityEvidence…`, `aConsequentialClaimWithNoDeclaredStandard…` failed |
| Evidence about another variant, place, population or date establishes a claim (RES-044) | **Confirmed.** The prompt told the model not to cite such evidence, but code never checked. `EvidenceRef.scopeNote` existed and was never set. | `aSourceAboutAnotherVariant…` failed |
| Freshness from a fixed seven-day window on fetch time (RES-046) | **Confirmed.** Every evidence item was stamped with the current call's time, so the window never fired within a turn. The claim's own `asof` was parsed and never used. An eleven-month-old price fetched today was established. | `anOldPrice…`, `aFastChangingClaimWithNoDate…`, `aRuleTheEvidenceSaysChangesYearly…` failed |
| Opposing evidence from an earlier period or another seller (RES-071) | **Confirmed.** It made the claim contested. | `opposingEvidenceAboutAnotherScopeOrAnEarlierPeriod…` failed |
| Replaced findings as current support (RES-107) | **Confirmed, in three places.** The answer kept citation markers to a replaced claim. A judgment resting on it kept its status. A proposal resting on it passed the gate. | `aSupersededFindingIsNeverCurrentSupport` failed |
| Already protected | Pointer-only citations never verify. Repeats of one origin count once. Judgments and recommendations take their premises' status and are never cited. In-scope opposing evidence makes one contested claim. The result deriver and the outgoing payload ignore replaced claims. Volatile claims are never reused on a continuation. An old stable rule was already treated as current. One authoritative source already established a narrow fact. | Existing tests plus the 7 new cases that passed before the change |

## What changed (smallest changes, no domain templates)

- **Contract.** A CLAIM header may state `needs=direct|corroborated|review` (the evidence this claim needs, from its consequences) and `fresh=<7d|3m|1y>` (how long the fact stays true). An EVIDENCE or AGAINST line may begin with `(kind=primary|review|study|secondary · asof=… · about=…)`. The prompt explains these in general terms, with no category list. Prompt version 2.
- **Parser.** Reads those attributes into the evidence and the claim and strips them from the excerpt. An `about` that restates the claim's own scope is no mismatch. A government host is only a fallback for `kind` when the reply gives none.
- **Assessor.**
  - Evidence counts only when it is in scope and fresh for this claim.
  - Freshness uses the fact's own date: the evidence's, else the claim's, never the fetch time alone.
  - The window is the claim's own. The seven-day default applies only to a volatile claim that states none. A stable fact with no window never goes stale.
  - The standard is the claim's own. When a consequential objective's load-bearing claim states none, it needs the issuing source, a review, or independent agreement. Independent agreement is one route among three, never a minimum count.
  - Opposing evidence about another scope or an earlier period no longer makes a claim contested.
  - A judgment resting on a replaced finding is not established.
  - Reasons say what fell short in plain words.
- **Composer and proposal gate.** No citation marker to a replaced claim. Proposals resting on one are held back. The change itself is still shown, as history.
- **UI.** Evidence rows show "About …" or "As of …" when the reply states it.
- **Fixtures.** The supplement-comparison replies now state `kind=` the way the new contract asks. The old seven-day unit test now uses dated evidence. It had treated fetch time as the fact's date, the confirmed flaw.

## Still dependent on live evaluation

The code now enforces these signals, but a live model must supply them correctly: what a source is about, the date the page gives, the kind of source, the claim's standard and its freshness window. A model that labels a maker's marketing claim as `kind=primary`, or omits `about=`, can still overstate a claim. Signals the code can only use to lower a status cannot catch that. Confirming this needs live runs on Document F's live scenarios: R-A benefit claims, R-C prices, R-D the Chanel variants, R-E an official rule, and R-K price history. Those runs are paid and were not performed here.

## Gates actually run

Local, `./gradlew --continue lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`: **exit 0**.

| Gate | Result |
|---|---|
| Unit and Robolectric tests | 612 in 84 classes, 0 failures. That is 16 more than M4's 596: the new `EvidenceCorrectnessTest` class. |
| Lint | "No issues found." |
| APKs | debug, release and androidTest all built |

Android CI on the pushed head is recorded in PR #8. No live provider call was made and no physical device was used.

---

# M4 live evaluation R-N(a) and its fixes, 2026-10-23

This was the first paid live run, scored against the canonical frozen Document F at `docs/product-baseline/Document_F_Evaluation_Framework_v1.0_FROZEN.md`. The scorecard and the defect investigation are in `docs/live-eval/R-N-a-2026-10-23.md`, and the index is `docs/live-eval/README.md`.

**Verdict: fail.** The address was correct and the state ANSWERED was justified. However:

- **Gates:** the Leakage gate failed.
- **Essential dimensions:** objective fidelity 2, evidence grounding 1, status honesty 1.
- **Other dimensions:** efficiency 0.
- **Serious failures (§6):** two, a misleading claim about completeness and a misattributed quotation.

The run cost $0.09611 and used 2 of 3 searches.

**Confirmed defects, each fixed generally and reproduced first from the run's own replies:**

| Defect | Root cause | Fix |
|---|---|---|
| E: second stage run on a settled fact | An undated stable fact with a freshness window was judged stale | Undated stable evidence is current while it was read within the window |
| A: false missing-source limitation | The merge replaced same-turn evidence. The composer passed a later call's narration about its own search. The prompt named no held evidence | Same-turn evidence is kept. A later call's search narration is left out. Prompt v3 lists held evidence and why each claim is open |
| C: paraphrase shown as a quote | Only the model's line was stored on the evidence row | `EvidenceRef.quote` holds the verbatim cited text; the model's line is labelled a summary |
| D: wrong scope reason | The model used `about=` to describe the page, and the reason stated the label as fact | The prompt defines `about=`; the reason reports a label |
| Leakage: "held as s1" | Unresolved items were not scanned | Scanned; bare source numbers removed |
| Independence overstated | Origins are hosts | The reason says "sources on different sites" |

The `official` flag (government host only) was harmless and has been documented. Prompt contract version 3.

**Stored data:** `EvidenceRef.quote` is a new nullable field with a default. Stored turns and backups without it decode unchanged, and the Room schema is unchanged.

**Still dependent on live evaluation:** prompt v3 behaviour, the labels models supply (`kind`, `volatile`, `fresh`, `asof`), origin independence by publisher, an unestablished side claim stated in the lead, and R-N(b)'s reliance on the provider objective pass. The replays are not a pass. Document F §2.2 requires the scenario to be rerun live on the candidate build.

---

# M4 final correctness pass, 2026-10-24

A narrow pass requested before the next paid call. It covered three issues raised in review. Each was reproduced first: 12 new test cases failed on `dd9de39`. No paid call was made. The historical R-N(a) record and scorecard are unchanged.

| Issue | Confirmed? | Root cause | Fix |
|---|---|---|---|
| 1. A weak claim stated as plain fact in the answer | **Yes.** In the R-N(a) replay, "The station fronts Columbus Circle…" read as fact although c2 was not established. | Answer segments were whole paragraphs carrying every marker in them, so the composer could not tell which sentence stated which claim. It only guarded negative wording. | The parser records each sentence with its own markers (`AnswerSegment.sentences`). `StatusQualifier` keeps each sentence and labels the ones whose claims are weak. A mixed sentence names its weak part. Already-hedged and "nothing was found" sentences are left alone, and the absence guard (RES-063) runs per sentence. Paragraphs, chips and caveats are unchanged. |
| 2. Different sites counted as independent agreement | **Yes.** Two sites of one publisher, three pages repeating one announcement, two subdomains, and two sites of unknown independence all established a `needs=corroborated` claim. | `meets()` counted distinct hosts. | Evidence is grouped into origins (registrable site, stated publisher `origin=`, a stated publisher naming the other page's site, a shared passage of 12+ words). Agreement corroborates only between origins whose independence is known. The issuer and review routes are unchanged. Prompt contract version 4 asks for `origin=` on each evidence line. |
| 3. R-N(b): "address of Union Station" established with no city | **Yes.** The local preparer saw ambiguity only in a question with no subject. The app's default flow (preparation call off) offered authorization. | No local rule for a place-dependent ask that names no place. | A place-dependent ask (address, hours, directions, phone, nearest) that names no place is not established and asks which place, locally, with no call. A named place goes straight to authorization. With the optional preparation call on, the provider may settle a unique place, ledgered as `OBJECTIVE_PREP` under general consent. If that call fails, the place is still asked for. |

**Stored data:** `AnswerSegment.sentences` and `EvidenceRef.origin` are new fields with defaults. Stored turns and backups decode unchanged. Composed answers keep no sentence list, and the Room schema is unchanged.

**Known limits:**
- Sentence splitting is English-oriented, with an abbreviation list.
- Registrable sites use a short list of second-level suffixes, not the full public-suffix list.
- Origin independence still depends on the reply stating publishers. Without that, agreement never corroborates, which can leave a claim only indicated where a careful reader would accept it.
- The local place rule cannot tell a unique landmark from a common name, so it asks; the optional preparation call can settle it instead.

---

# M4 live rerun R-N(a), 2026-10-24

This was one owner-approved paid rerun on the fixed engine (`41dc471`, prompt contract v4), run through the harness at `9cc17fe`. That commit changes only the development-only harness: each run gets its own directory and database, so nothing is overwritten. Report: `docs/live-eval/R-N-a-2026-10-24-rerun.md`.

**Verdict: fail.**
- **Gates:** the Leakage gate failed.
- **Dimensions:** objective fidelity 2, evidence grounding 2, status honesty 2, presentation 0, efficiency 2.
- **Serious failures:** none.

**What went right:** one billed call, one search of 3, $0.051416. FILL_GAPS was skipped on sufficiency. The single claim rests on the station operator's own page, with all three citations verbatim. There was no side claim and no false process limitation in the answer.

**New defects** (diagnosed, not fixed, no rerun):

| Defect | Cause | Effect |
|---|---|---|
| **N1** | `EvidenceAttrs.split` cannot read an attribute block containing parentheses. The model wrote `origin=… (Union Station Redevelopment Corp.)` and `asof=2026 (page about 168 days old …)`. | `kind`, `origin` and `asof` were lost on every line. The raw block reaches the evidence sheet's summary line, which is not leakage-cleaned. |
| **N2** | The GATHER prompt shows only "searches allowed on this call". | The unresolved section described that per-call allowance as the investigation's limit. |

The proposed fixes are in the report (§6).

---

# M4 fix pass for N1, N2 and source dates, 2026-10-24

This pass was approved after the second R-N(a) live run. Each defect was first reproduced on `0d4b900` from that run's own sanitized reply (`fixtures/live/R-N-a-2026-10-24-rerun.replies.json`): 9 new cases failed there, and 3 characterization cases passed. No paid call was made in this pass. Both historical scorecards and their sanitized JSON are unchanged.

| Defect | Root cause | Fix |
|---|---|---|
| **N1:** a label block containing parentheses was ignored, and the raw block was shown as Claude's summary | `^\(([^()]*)\)` cannot match nested parentheses. A block that failed to match was silently treated as having no labels, so evidence counted as in scope. | A balanced-parenthesis reader splits fields only outside parentheses. Anything it cannot read safely marks the evidence "labels unreadable": the citation and quote are kept, but it is not counted as support (an unreadable AGAINST still counts). The displayed summary never carries labels. |
| **N2:** the per-call allowance was described as the investigation's limit | The prompt gave only "searches allowed on this call". The model's remark reached the unresolved section. A run that used its whole budget with a gap open ended as "Finished". | The prompt gives each call's share against the authorized budget. Allowance remarks are removed from the answer and from unresolved items. The turn log states searches used against authorized, and the engine's stop reason. That case now ends as "Search budget reached". |
| **Dates:** `asof=` came from search-result page age | Any `asof=` counted as the fact's date | `EvidenceRef.asOfBasis` (QUOTED or STATED) and `asOfNote`. Only a date the cited text shows dates the fact; a stated date can only make evidence older. |

Prompt contract **version 5**: `asof=` must be a date the page states, attribute values must be plain words, and each call is told its share of the budget.

**Fixture changes:** four fixtures with volatile prices whose cited text gave no date (fragrance seller pages, an X9 retailer and museum tickets) now carry the date in the cited text. Their tests are about conflict and continuation, not dating. `ClaimAssessorTest` constructs dates the page shows.

**Consequence for live price scenarios (R-C, R-K):** a seller page that shows no date can no longer have its price called current on the strength of a date the model supplies. Such prices will be "only indicated" unless the page dates them. This is intended: the search index can serve old page content.

---

# Live R-N(a) third run on `afb9cad` (prompt v5), 2026-10-24

The full scorecard is `docs/live-eval/R-N-a-2026-10-24-run3.md`. **Verdict: FAIL.** Every gate passes and there is no serious failure, but evidence grounding scores 1, and that dimension is essential.

**What the fix pass achieved live:**
- one billed call, one search of 3, $0.053328, FILL_GAPS skipped on sufficiency;
- every label block was parsed;
- no contract syntax or allowance remark was shown;
- no date was claimed beyond what the pages show;
- the log states the searches used and the stop reason.

**New findings** (diagnosed, not fixed, no rerun):

| Finding | Cause | Effect |
|---|---|---|
| **N3** | `AnswerComposer` shows WORTH_KNOWING for any ask whose claims are current. It has no materiality check. | An unrequested Columbus Circle item was shown. |
| **N4** | The status qualifier checks each sentence's claim **statuses**, not its content against the claims' cited text. | "where Massachusetts Avenue NE meets First Street NE" was shown unqualified, although no cited text supports it. |

The proposed fixes are in the report (§5).

---

# M4 answer-grounding pass: N3, N4, R-C review and R-N(b) harness, 2026-10-24

Approved after the third R-N(a) live run. Every new case was first run on `ddf6b44`: 11 of 15 failed there. The 4 that passed are guards the fix must keep: a relevant exception, a safety warning, a supported paraphrase and qualified uncertainty. No paid call was made in this pass. All three historical R-N(a) scorecards and their sanitized JSON are unchanged.

## N3: objective relevance

**Root cause.** `AnswerComposer.compose()` showed every WORTH_KNOWING item whose claims were current. The prompt asked for materiality, but nothing enforced it.

**Fix (`Relevance`).**
- The contract (prompt v6) asks each item to state how it bears on the answer: `bears=risk|exception|limitation|prerequisite`, with `on=cN` naming the answer claim it qualifies.
- A risk is always kept: omitting a warning could do harm.
- An exception, limitation or prerequisite is kept only when it is tied to the answer (`on=` names an answer claim, or the item states a load-bearing claim) and its words actually restrict it ("only", "closed", "unless", "must", "not" and similar).
- Anything else is omitted, not disclaimed.
- For a narrow fact ask, a main-answer sentence that states only claims the reply marked `load=no`, and neither restricts nor warns, is background and is also left out.
- No topic, category or template is consulted.

## N4: complete factual grounding

**Root cause.** The composer judged a sentence only by its claims' *statuses*. Nothing compared the sentence, or the claim's own wording, with the cited page text.

**Design.**
1. **Atomic attribution.** Prompt v6 says a sentence may state only what its cited claims state, and a claim only what its cited text says. Any further detail needs its own claim and evidence, or is left out.
2. **Lexical grounding (`Grounding`, every objective, free).**
   - **Corpus.** Each sentence is checked against the corpus of the claims it names: verbatim cited text, page title, page address and site name. A judgment uses its premises. The claims' own wording, the reply's evidence summaries and the reply's labels never count.
   - **Checkable details** must appear in the corpus: numbers with the unit that follows them ("1000 IU" is not supported by "1000 mcg"), names, places and dates.
   - **Normalization** covers abbreviations (Ave, D.C., NE/Northeast), ISO dates, leading zeros, decimals and number words.
   - **Certainty and universality words** ("prevents", "always", "for every", "at any scale") must be the source's own.
   - **Hedges.** Hedged research evidence (study or review: "may", "limited", "associated with") must not be stated flatly.
   - **Claims.** A fact claim whose own wording adds a detail its cited text does not show is only indicated: "The cited text does not show 500 mg".
3. **What happens to an unsupported part.**
   - A separable clause (", where …", ", which …", ", also …", a parenthesis) holding the unsupported detail is removed.
   - Contrastive clauses (", but …", ", unless …") are never removed.
   - Otherwise the sentence is kept and the unsupported part is named: "(the cited sources do not show: 18:00)". A sentence whose claims are already weak keeps its status label instead.
   - In optional material, an item that still says something unsupported is omitted, except a risk, which is kept and qualified.
4. **Model-assisted check (`WordingCheck`, consequential objectives only).**
   - **Why it is needed.** String matching cannot tell whether a paraphrase means the same thing: "increased lean mass" is not "builds muscle strength". For health, legal, financial and safety answers, an overstated sentence is a Document F §6 serious failure.
   - **How it runs.** The authorization includes one extra call, without search (`CHECK_WORDING`), and the estimate includes it. The card says so.
   - **What it sees.** The verifier sees only the sentences and their verbatim cited text.
   - **How its findings are used.** Findings only remove or qualify. A span that is not the sentence's own words is not trusted: the whole sentence is then named as not shown. A "supported" verdict never overrides the lexical checks. Claim statuses never change because of it.
   - **If it cannot run** (failure, refusal or spending limit), the answer says so and the lexical checks still apply.
   - **R-N(a)** is not consequential, so it stays one call.
   - **Limits.** It is still a model's judgment, not proof.

**Tests:**
- `ObjectiveRelevanceTest` (6);
- `AnswerGroundingTest` (10, including steps and comparison cells);
- `WordingCheckServiceTest` (6, Robolectric, scripted replies);
- run-3 replay: the Columbus Circle item is omitted and the address answer is unchanged.

Five existing tests were updated:
- three because a non-load-bearing side sentence is now omitted rather than shown qualified, which is the N3 decision;
- two whose fixtures cited pages without a title, which real citations always carry.

## R-C price freshness: review

**Question.** Can R-C ("Answered only with fresh seller-sourced prices; Partially if some unverifiable") be satisfied when a current seller listing has no explicit date?

**Finding.** Web search returns the index's copy of a page, captured at a time the engine is not told. So none of the following dates a price:
- the time of the search (`retrievedAtMs`);
- the result's page age (search metadata, often a page-modified time that can be newer than the indexed text);
- a date the reply works out (`DateBasis.STATED`).

An undated seller listing is therefore only indicated. The status reason now says why: the cited text is the search index's copy, shows no date, and the price was not seen on the live page. A seller page that shows its date (for example "Price as of 23 October 2026") within the claim's window establishes the price.

**Consequence.**
- R-C is not impossible to pass: when listings are undated, *Partially* is the state Document F says is justified.
- *Answered* is reachable only when seller pages show their date.

**Not implemented (needs approval).** An *observed* current price would need a live page fetch whose retrieval time the provider reports, for example Anthropic's web fetch tool. That time would be recorded as an observation ("seen on the seller's page at T"), never as the price's effective date for undated index text. It adds a new outbound capability with its own consent, cost and injection considerations, so it is left for a separately approved slice.

Tests: `PriceFreshnessTest` (4).

**Risk noted, not changed.** A price from an aggregator can still be established if the reply labels it `needs=direct`. The prompt defines the seller as the primary source for its own price, but that labelling is the model's.

## R-N(b) clarification recording

The live harness now takes `-PliveEvalScenario=R-N-a|R-A|R-N-b`. R-N-a is the default; each paid scenario has its own guard.

R-N-b records the clarification flow through the real service. It needs no key and has a provider that refuses every call. The report records:
- that "address of Union Station" is prepared locally as not established and asks which place;
- that no authorization exists before the answer;
- that no provider call and no ledger row exists;
- that after "Washington, DC" the objective is established and an authorization can be built, but is never authorized.
