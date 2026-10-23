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

## 5. Blockers, contradictions and recorded assumptions

1. **Documents E/F/G/H** were missing at kickoff and are now reconciled (§9). Two owner decisions are surfaced there (milestone order vs. Document G §3; research preset names vs. Document F's "Standard" level, OQ-C1).
2. **PLAN-073 vs. current apply** (contradiction with existing code, resolved by M1): whole-task `updatedAt` rejection is replaced by per-item reconciliation against `rev`; the previous behaviour is kept only as the "task deleted/completed" guard.
3. **OD-5 vs. current pre-selection** (resolved by handoff): nothing pre-selected; `selectedByDefault` and EXPLICIT pre-selection are removed from review; "Select recommended" is the one-tap path that satisfies PLAN-071's acceptance.
4. **Priority proposals** (behaviour change from M2): the handoff forbids proposing priority; planning may still *read* priority. New drafts let the user set priority manually.
5. **Research without a task** needs a table rebuild (M4). Existing sessions are migrated 1:1; saved M3A briefs stay readable but gain no new features.
6. **Draft expiry (OQ-B1)**: assumption for M3 — drafts never expire automatically; the user deletes them; the policy is stated on the draft surface. This is a "clearly established policy" per PLAN-013.
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
| 4 | **Research effort levels.** Document F runs Stage 1/2 at the "Standard" level with "low" and "high" variants; R31 names presets *provisionally* Quick/Standard/Thorough/Deep; OQ-C1 (names and count) is open and assigned to UX; handoff OD-3 leaves it open too. The app has Quick/Thorough/Deep/Custom. | F §2.3, §7; E R31, OQ-C1 | **Owner decision needed before M4** (non-blocking now): adopt the provisional four names, keep three plus Custom and map "Standard" to one of them for Document F runs, or decide otherwise. This plan does not map silently. |
| 5 | **Milestone order vs. Document G §3.** G §2 says Invariants hold "from the first build" (supports M1 → M2 first). G §3's *suggested* Capability order puts research objective, evidence and continuation (items 1–3, "where the known failures live", first Stage 1 target) before planning output and timing (items 5–6). This plan's M3 (Living Draft) precedes M4 (Investigation thread). | G §1–3, §7 | **Owner decision surfaced.** Recommendation: swap M3 and M4 so the Investigation thread (research objective, composition, continuation, configuration, standalone) follows M2 directly, then the Living Draft, then integration. Draft-origin research (INT-013, I-3) would land with the Living Draft either way. The dependency graph allows both orders; the frozen roadmap's priority favours research first. |
| 6 | **AI-proposed priority is deferred/out of scope** (D38; G §6), not merely a handoff rule. | E D38; G §6 | Confirms the plan: `ItemKind` has no priority kind; the M2 review never proposes priority; the request sends priority read-only. No change. |
| 7 | **Binary gates and serious failures** (F §2.1, §6; E3, E5) define release acceptance: Authorization, Preservation, Task safety, Leakage; any §6 failure fails the run; applies to the current release candidate (G5). | F §2.1, §2.2, §6; E E3, E5, G5 | **Changed.** §9.2 maps M1 work to the gates it serves. Two M1 fixes came out of this pass: the double-apply race (Task safety / Preservation) and silent draft truncation (Preservation, "loss of user input"). The Leakage gate adds an RES-087 scan of user-facing text to M4 (the current M3A.2 technical log shows internal ids such as `r-a1`; it is a diagnostics surface, but the scan must confirm nothing reaches result text). |
| 8 | **OQ-B1 (draft expiry, limits, surfacing) is an open UX question**, explicitly non-blocking; the handoff did not close it. | E "Unresolved questions"; H §5 | **Changed wording.** The M3 assumption "drafts never auto-expire; the user deletes them; the policy is shown" is recorded as a *proposed* UX decision to be accepted by the owner through change control, not as a settled rule. |
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

_Full gate run on this tree in progress at commit time; results recorded in the next commit._

