# M3A.2 — Deep Research & Actionable Results: architecture plan

Status: implementation plan for branch `m3a2-deep-research` (against `main` @ a0419ea). The design
contract is the approved prototype `life-admin-autopilot-M3A2-research-assistant.html` (26 scenarios,
36-state grid, 14-section dev spec). This document says how that experience maps onto the existing
codebase; `docs/M3A2_IMPLEMENTATION_REPORT.md` records what was actually built and verified.

## 1. Retained components (unchanged or extended, never replaced)

| Component | Role in M3A.2 |
|---|---|
| `ai/anthropic/AnthropicResearchProvider` | Only SDK importer for research. Gains a generic structured call (`call(ModelCall)`) used by the new orchestrator; the M3A `research()` path stays for saved-brief compatibility. |
| `ai/research/Sources` | URL safety and government-only `OFFICIAL` tier. Reused as-is; no new hostname heuristics. |
| `ai/research/ResearchError`, `AnthropicErrors` | Error classes and their honest "billed?" lines. New states are added (`BudgetReached`, `WebSearchDisabled` already exists). |
| `ai/research/ResearchActions` + `TaskRepository.applyTaskEdit/undoAppliedEdit` hooks | Transactional, idempotent apply/undo. M3A.2 proposals go through the same hook with a session-scoped proposal id. Still never touches deadlines, reminders, priority or status. |
| `data/repository/ResearchRepository` (`research_briefs`) | Legacy M3A briefs remain readable, deletable and exportable; no data is migrated or reinterpreted. |
| `ui/research/*` components | `ResearchStamp`, `CitationChip`, `CitedText`, `EvidenceBadge`, `SourceRow`, `SourceSheet`, `ResearchBanner`, `ResearchResultState`, `ExplainerSheet`, `ProvenanceLegend`, `BriefSectionTitle` are reused by the new screens. Flight Plan primitives (`PrimaryButton`, `ChoiceChip`, `SwitchRow`, `SurfaceCard`, `AutopilotSheet`, `ButtonPair`) are reused for every new control. |
| `ResearchService` (M3A) | Kept for the old brief/run screens and its tests. New runs start from `DeepResearchService` (below); the task-detail "Research online" tile routes to the new preflight. |
| Settings, key store, consent, explainer | Unchanged gating order: AI on → consent → key → research explainer → online. |

## 2. New domain models (`ai/research2`, pure Kotlin, serializable)

- `DeliverableTemplate` A `QUICK_ANSWER`, B `SHORTLIST`, C `PICK_PER_ITEM`, D `HOW_TO`, E `CHECKLIST`.
- `Requirement(id, type ITEM|CONSTRAINT|OUTPUT|USER_ADDED, label, origin EXTRACTED|USER, confident)`; ids are
  `r-i0…` / `r-c0…` / `r-o0…` / `r-u0…`, assigned once in the preflight and never renumbered, so coverage,
  continuation and proposals refer to the same requirement forever.
- `RequirementStatus` `PENDING | ANSWERED | YOUR_CALL | NEEDS_CHECK | PARTLY | NOT_FOUND | RESOLVED_CONFLICT`;
  `Coverage(requirementId, status, summary, refs)`.
- `ProvenanceTag` `FACT | REVIEWS | COMMUNITY | JUDGMENT | CALC | UNKNOWN` on every attribute/claim. Parser rule:
  a tag that implies a source (`FACT`, `REVIEWS`, `COMMUNITY`) without a provider citation on that line is
  downgraded to `UNKNOWN`; `JUDGMENT`/`CALC` never carry refs as evidence of fact.
- `Candidate(id, name, variant, style, included, reason, attrs: List<Attribute>, refs)`,
  `Attribute(key, value, tag, refs, retrievedAtEpochMs?)`, `Price(amount, currency, retailer, size, status
  CONFIRMED|CONFLICT_RESOLVED|NOT_CONFIRMED|STALE, ref)`.
- `ItemPick(requirementId, status, pick, variant, labelFacts, alternative, forYou, why, refs)` (template C).
- `Claim(text, tag, refs)`, `Conflict(topic, sides, resolution, refs)`, `StepLine(text, tag, refs)`,
  `Callout(kind HEALTH|CROSS_ITEM|UNKNOWN_RECIPIENT|BUDGET|INFO, text)`.
- `Proposal(id, kind STEP|DECIDE|CHECK|NOTE|RESEARCH, title, note, requirementId?, refs, checkedAtEpochMs,
  default selected)`; proposal ids are `p-<hash of requirementId + title>` so edits keep identity.
- `ResearchSession` (persisted): `id, taskId, createdAt, updatedAt, title (generated, ≤60 chars, never the raw
  prompt), request (verbatim), template, mode (QUICK|THOROUGH|DEEP|CUSTOM), model, searchBudget, spendLimitCents?,
  outcome, requirements, coverage, answer (segments w/ refs), callouts, body (template-specific), claims, conflicts,
  unresolved, sources, queries, proposals, applied, runs (per run: id, kind INITIAL|CONTINUATION, stages, searches,
  usage summary, outcome), shared (plain-language scope)`.
- `SessionOutcome` `COMPLETE | PARTIAL | INCOMPLETE_EVIDENCE | INCOMPLETE_BUDGET | STOPPED | INTERRUPTED | FAILED`.
  Completeness is computed locally from coverage (d3): `COMPLETE` only when every `ITEM` and `OUTPUT` requirement
  is not `NOT_FOUND`/`PENDING` and the run ended normally; a run that ended because searches or the spend limit
  ran out is never `COMPLETE`.

## 3. Orchestration and state transitions (`DeepResearchService` + `ResearchOrchestrator`)

Stages are separate, locally planned model calls. The orchestrator decides locally when another call helps
("coverage assessment" is code, not a prompt), so raising `max_uses` alone is never the strategy.

```
Preflight (local, free)      RequirementExtractor → requirements (editable) → template → mode/model/budget → estimate
Run                           ┌─ GATHER     web_search(max_uses = share of budget) → structured contract → merge
 (per stage: budget check)    ├─ FILL_GAPS  only if coverage has NOT_FOUND/PARTLY items and searches remain
                              ├─ CROSS_CHECK Deep only: conflicting prices/claims on included candidates
                              └─ SYNTHESIZE Deep only, no search: rewrites answer/recommendation using existing
                                            source ids only (normalize, never manufacture)
Finish                        coverage → outcome → save session → usage reconciled
Continue (new run on session) requirements + coverage + known sources + queries already run → GATHER/FILL_GAPS
                              with a new approved budget; sources keep their ids; searches are not repeated
```

Mode presets (starting configs, every field editable in Adjust): Quick = Haiku 5.5, 3 searches, 1 stage;
Thorough = Sonnet 5.5, 10 searches, GATHER + FILL_GAPS; Deep = Opus 5.5, 25 searches, all four stages. Default per
template: A → Quick, others → Thorough; Deep is never pre-selected. Search budget 1–30. Model ids are the dateless
documented ids (`claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-opus-5-5`); an unavailable model is reported as
`BadRequest`/`Forbidden` and the user picks another.

Run state (`DeepRunState`): `Idle → Running(sessionId, runId, stage, event log, searchesSeen, estimateSoFar) →
Finished(sessionId, outcome)`. Events come only from real orchestration points: call sent, stage result received
(with the queries the server reports), stage skipped (why), pause_turn continuation, budget stop, user stop.
No counts or percentages are invented; "sources so far" is the registry size after each completed call.

Stop: cancels the in-flight HTTP call. The Stop sheet offers keep partial (everything merged so far is saved as
`STOPPED`), discard (nothing saved; usage still recorded), keep researching. Leaving the screen keeps the run
alive in app scope; process death leaves no resume: an in-flight usage record is marked `INTERRUPTED`, nothing is
re-sent.

`pause_turn` is continued once per stage within the stage's remaining budget (M3A behaviour kept for every
mode; the design's "Deep only" is a documented deviation in favour of finishing the stage).

## 4. Citation preservation

- Sources enter the registry only from `web_search_tool_result` blocks and `web_search_result_location`
  citations (unchanged from M3A); URLs typed in prose are stripped. Registry ids `s1…sn` are stable for the
  life of the session and are seeded from the saved session on continuation.
- The structured contract is line-based. A `## ATTR`, `## CLAIM`, `## STEP` or `## REQ` line's refs are the
  provider citations whose text blocks overlap that line (same span logic as `BriefParser`). Model-typed
  `src=s3` tokens are accepted only when `s3` already exists in the registry (needed for the no-search
  SYNTHESIZE stage and for continuation); unknown ids are dropped and the line is marked `UNKNOWN`.
- Prices: `CONFIRMED` only when the price line carries a citation to a page the registry holds, with the
  retrieval time taken from the run; otherwise `NOT_CONFIRMED`. Older than 24 h → `STALE` in the UI.
- `OFFICIAL` remains government-domain-only via `Sources.tier`; brand/retailer provenance is a model tag shown
  as "Brand/retailer page (as labelled)" and never as "Official".

## 5. Cost ledger (`ai/usage`)

Table `usage_records` (Room v5): `id, runId?, sessionId?, taskId?, feature PLANNING|RESEARCH|RESEARCH_CONTINUATION|
ASSIST, stage, model, startedAt, durationMs?, inputTokens?, outputTokens?, cacheWrite5mTokens?, cacheWrite1hTokens?,
cacheReadTokens?, webSearches?, outcome IN_FLIGHT|COMPLETE|TRUNCATED|STOPPED|FAILED_NOT_BILLED|FAILED_UNKNOWN|
INTERRUPTED, usageStatus COMPLETE|PARTIAL|UNKNOWN, estimatedMicros?, priceVersion, providerMessageId?`.
Never stored: prompts, task text, queries, keys.

Rules:
1. A record is inserted as `IN_FLIGHT/UNKNOWN` before dispatch and finalised after the response. On app start,
   leftover `IN_FLIGHT` rows become `INTERRUPTED/UNKNOWN` (process death) — honest, never `$0`.
2. One record per billable API call; a continuation's calls are new records with the same `sessionId` and a new
   `runId`, so totals never double count.
3. `providerMessageId` is unique-checked: a duplicate provider response updates the same record rather than
   adding one.
4. `webSearches` = `usage.server_tool_use.web_search_requests` when present, else the count of
   `web_search_tool_result` blocks; never the `max_uses` sent.
5. Rejected before processing (400/401/403/404, "web search not enabled", credit) → `FAILED_NOT_BILLED`,
   `$0.00 · not billed`. Timeout, network error, 5xx, cancel with no usage → `FAILED_UNKNOWN` or `STOPPED` with
   `usageStatus=UNKNOWN`, shown as "≥ $x · usage unknown" where x is the cost of searches seen.
6. `PricingCatalog` (version `2026-10`, from platform.claude.com/docs/en/about-claude/pricing on 2026-10-20):
   per MTok base input / 5m write / 1h write / cache read / output — Haiku 5.5 $0.10/$0.125/$0.20/$0.01/$0.50
   (≤100k prompt; >100k $0.50/$0.625/$1/$0.05/$2.50), Sonnet 5.5 $2/$2.50/$4/$0.10/$10, Opus 5.5 $4/$5/$8/$0.20/$20,
   Fable 5.1 $10/$12.50/$20/$0.25/$50; web search $10 per 1,000 searches, errored searches not billed.
   `CostCalculator` is a pure, tested function; unknown models return `estimate=null` (shown as "no price table").
7. Three numbers are always distinguished in the UI: local estimate (`~`), authorized budget, and Anthropic's
   bill (never shown; the dashboard says where to find it).
8. Dollar limits are soft: estimate before dispatch, check before each stage, trim `max_uses` to fit, reconcile
   after each response, stop launching further paid calls, explain the overshoot (one in-flight call).
9. Planning (M2) calls are recorded through the same ledger (`PlanningUsage` gains cache fields).

Prompt caching: the system prompt + contract (~2k tokens) is marked `cache_control` 5-minute ephemeral only when
a run has ≥2 stages (the second call reuses it within 5 min). Cache write/read tokens are recorded and priced;
no call is made only to warm a cache. Quality decisions never depend on caching.

## 6. Room migration and compatibility

- `AppDatabase.VERSION = 5`; `MIGRATION_4_5` creates `research_sessions` (FK → tasks CASCADE, indices taskId,
  createdAt) and `usage_records` (indices startedAt, sessionId). No existing table changes; `research_briefs`
  rows are untouched. Migration test from the exported v4 schema; `5.json` exported.
- Backup v1 file gains `sessions` and `usage` lists (older versions ignore them; import validates sizes, ids,
  task ownership and enum values exactly like briefs). Usage rows are restored only if absent (append-only ledger).

## 7. API request budgeting

Estimate (d5, visible in preflight, continue sheet and as an "estimate" during a run):
`searchCost = maxSearches × $0.01`, `inputTokens ≈ 6,000 + maxSearches × 11,000`, `outputTokens = mode budget`
(Quick 1.5k, Thorough 6k, Deep 10k), priced per model from the catalog; range = [0.7×, 1.3×] of the mid value.
Each stage receives `max_uses = min(remaining searches, stage share)`; a stage is skipped when its allowance would
be 0 or when the soft limit would already be exceeded by its low estimate. The daily session cap is raised to 25
(a cheap guard, not a product ceiling) and shown with the counts.

## 8. UI navigation and components (`ui/research2`)

Routes: `task/{taskId}/research/new?q=` → new preflight (replaces the M3A preflight entry);
`task/{taskId}/research2/run/{sessionId}` investigation; `task/{taskId}/research2/{sessionId}` result;
`…/proposals` editable proposals; `settings/ai/usage` dashboard; `…/usage/{sessionId}` run breakdown is a sheet.
Legacy routes for `research_briefs` stay.

New components (each a small Composable in `Research2Components.kt`): `RequirementRow` (type chip, status glyph,
editable), `ModeCard`, `AdjustSheet` (model radio with 1×/~20×/~40× labels, stepper 1–30, spend-limit chips
"No limit / $0.25 / $0.50 / $1 / $2 / Custom" with **no limit** as the default), `EstimateLine`, `CoverageRow`,
`CalloutCard`, `ProvenanceTagChip`, `PriceLine`, `CandidateCard`, `CompareTable` (sticky first column, horizontal
scroll), `PickHero`, `ItemCard`, `StepList`, `ContinueCard`/`ContinueSheet`, `StopSheet`, `ProposalRow`
(checkbox, editable title/note, kind chips), `UsageRunRow`, `UsageBreakdownSheet`. Font scale ≥1.3: footers
stack, grids become one column (same rule as M3A).

## 9. Key risks and mitigations

| Risk | Mitigation |
|---|---|
| Contract drift: the model ignores `## REQ/## CAND/## ATTR` headings | Lenient line parser; a free-text answer still becomes a template-A answer with citations; missing `## REQ` ⇒ `NOT_FOUND` per requirement (d3), outcome `INCOMPLETE_EVIDENCE`, no proposals invented. |
| Token estimate per search (11k) is a calibration guess | Shown as a range, labelled estimate; actual usage is recorded and the dashboard compares estimate vs recorded per run. |
| Multi-stage cost without visible benefit | Stages are skipped when coverage is already complete; Quick stays single-call; every skipped/run stage is listed in "How researched". |
| Process death mid-run | In-flight ledger row → `INTERRUPTED`; nothing re-sent; the session keeps what was merged before the last call only if the user had chosen keep-partial (otherwise nothing saved). |
| Spend limit overshoot | Documented as soft (one in-flight call); UI never says "cap". |
| Health/product suitability | Template C renders the fixed boundary callout; `forYou` text is always a question for a professional, never a dose; the prompt forbids individual dosing. |
| Regression of M3A | M3A tests stay green; legacy briefs open through the old screens; `ArchitectureBoundaryTest` still enforces the SDK boundary. |
