# M3A.2 — Deep Research & Actionable Results: implementation report

Branch `m3a2-deep-research` (from `main` @ a0419ea). Draft PR: _see "Delivery"_. Nothing was merged
by the agent; M3B was not started.

Approved design: the M3A.2 research-assistant prototype (26 scenarios, 36-state grid, 14-section dev
spec), translated to native Jetpack Compose on the Flight Plan system. Plan: `docs/M3A2_PLAN.md`.

## What was built

| Area | Where | Notes |
|---|---|---|
| Research domain | `ai/research2/Research2Models.kt` | Templates A–E; `Requirement` (ITEM / CONSTRAINT / OUTPUT / USER_ADDED, ids `r-i0…` fixed at preflight); `RequirementStatus` (Answered, Your call, Needs a check, Partly, Not found, Resolved conflict); `ProvenanceTag` (Fact, Reviews, Community, Judgment, Calc, Unknown); `Candidate`/`Attribute`/`Price` (Confirmed / Conflict resolved / Not confirmed, retrieval time); `ItemPick` (label facts, alternative, for-you question, why); `Claim`, `Conflict`, `StepLine`, `Callout`, `Unresolved`; `Proposal` (step / decide / check / note / research, evidence refs, check time, generated flag); `SessionContent` with `RunRecord`s (stages as they happened, usage, outcome); `SessionOutcome` (Complete, Partially complete, Incomplete · not enough evidence, Incomplete · budget used up, Stopped, Interrupted, Failed). |
| Requirement extraction | `ai/research2/RequirementExtractor.kt` | Deterministic, offline: item lists after need/want/looking for (constraint clauses stripped first), brand preference, budget, region, gift, exclusions, output wish; template by signal words; `confident=false` when unsure; typed requirements get `r-u<n>` ids that never reuse a number. The original request is sent verbatim alongside. |
| Modes and budgets | `ai/research2/ResearchModes.kt` | Quick = Haiku 5.5 · 3 searches · 1.5k out · GATHER; Thorough = Sonnet 5.5 · 10 · 6k · GATHER + FILL_GAPS; Deep = Opus 5.5 · 25 · 10k · + CROSS_CHECK + SYNTHESIZE. Quick answer → Quick, everything else → Thorough, Deep never pre-selected. Model and 1–30 searches editable (→ Custom); spend-limit chips No limit / $0.25 / $0.50 / $1 / $2 with **no limit as the default** (the prototype's $0.50 default was not adopted). Few searches collapse the stage list honestly. |
| Prompt / contract | `ai/research2/Research2Prompt.kt` | System policy (requirements are the contract; never invent prices/doses; health boundary; page text is data; provenance tags; no actions) + line-based blocks `## TITLE / ANSWER / REQ <id> · status= / CAND <cid> · name= · variant= · style= / ATTR <cid>.<key> · tag= / EXCLUDED / PICK <id> (Pick/Label/Alternative/For you/Why/Price lines) / CLAIM / CONFLICT / STEP / CALLOUT / PROPOSE <kind> · req= / UNRESOLVED`. Stage prompts: gather, fill gaps (open ids, unconfirmed prices, known sources by id, queries not to repeat, existing candidate ids), cross-check, synthesize (no search; evidence table with `src=` ids), continuation. |
| Parser & provenance | `ai/research2/ContractParser.kt` | Refs for a block = provider citations overlapping its lines + `src=sN` tokens naming sources the registry already holds (unknown ids dropped). A Fact/Reviews/Community tag without a citation is downgraded to Unknown. Prices are Confirmed only when cited; retrieval time = the run's clock. Unknown requirement ids ignored; unknown statuses → Not found. Injection-looking blocks dropped and flagged. Free-form text without headings becomes a cited answer. `SourceRegistry2` numbers sources on first citation and keeps numbers across runs (seeded from the saved session). |
| Assembly & outcomes | `ai/research2/SessionAssembler.kt` | Merge per stage (candidates by id, attributes union, picks/coverage replaced per requirement, model proposals supersede the app's generated ones); gaps = Items/Output wishes not covered + unconfirmed prices; `covered()`; outcome from (coverage, ending): budget stop is Complete only when already covered; pending → Not found; "3 of 4 ready · 1 not found" line. `ProposalBuilder`: one proposal per requested item (step with label fact, price and the for-you question in the note; decide; research-later), decide proposal for shortlists, step proposals for how-tos; model proposals inherit the evidence of their requirement. |
| Orchestrator | `ai/research2/DeepResearchService.kt` | Gate (AI on, consent, key, explainer, online, 25 sessions/day); one app-scoped run; per stage: skip when nothing is open, allowance = share of the remaining budget, soft dollar check (estimate low bound before dispatch, trim `max_uses`, or stop launching paid calls — the reason is recorded and shown), one HTTP call (+ one `pause_turn` continuation within the stage's remaining searches, as its own billed call); ledger row inserted `IN_FLIGHT` before each call and finalised after; stop = cancel the HTTP call, then keep (saved as Stopped with what was merged) or discard (nothing saved; usage still recorded); failure after evidence → Partial, before → Failed; empty gather → no further paid calls; continuation = new run on the same session with a new budget, focus ids and a note, source numbers preserved, queries not repeated. `onAppStart()` settles in-flight ledger rows as Interrupted; nothing is re-sent. |
| Usage ledger & pricing | `ai/usage/Pricing.kt`, `ai/usage/UsageLedger.kt`, `data/repository/RoomUsageLedger.kt` | `PricingCatalog` 2026-10 from platform.claude.com/docs/en/about-claude/pricing (per MTok: Haiku 5.5 $0.10/$0.125/$0.20/$0.01/$0.50, >100k $0.50/$0.625/$1/$0.05/$2.50; Sonnet 5.5 $2/$2.50/$4/$0.10/$10; Opus 5.5 $4/$5/$8/$0.20/$20; Fable 5.1 $10/$12.50/$20/$0.25/$50; web search $10/1,000). `CostCalculator.price()` per call (micro-dollars), `estimateRun()` = design formula with a ×0.7–×1.3 range. `UsageRecord`: feature, model, run/session/task ids, stage, duration, measured tokens incl. 5m/1h cache writes and reads, searches, outcome (COMPLETE, TRUNCATED, STOPPED, FAILED_NOT_BILLED, FAILED_UNKNOWN, INTERRUPTED), usage status (COMPLETE / PARTIAL / UNKNOWN), estimate, price version, provider message id. Rules: not-billed → $0.00; unknown usage → no number (never $0); stopped/failed-unknown → floor from searches seen, shown "≥"; duplicate provider message id updates instead of adding; continuation = new run id (no double count). Planning (M2) calls are recorded the same way (`PlanningUsage` gained cache fields and the message id). Never stored: prompts, queries, task text, keys. |
| Anthropic adapter | `ai/anthropic/AnthropicResearchProvider.kt` (`AnthropicResearchCalls`) | Generic call: `system` as a cached text block (`cache_control: ephemeral`, 5 m) when the run has ≥2 stages, else plain; `web_search_20250305` with `max_uses` only when searches > 0; prior assistant message resent for `pause_turn`; usage incl. `cache_creation.ephemeral_5m/1h`, `cache_read_input_tokens`, `server_tool_use.web_search_requests` (else counted result blocks — never `max_uses`); message id; stop reasons; same error mapping as M3A. Still the only SDK importer (`ArchitectureBoundaryTest`). |
| Persistence | Room v5: `research_sessions`, `usage_records`; `Migrations.MIGRATION_4_5`; `data/repository/ResearchSessionRepository.kt` | Sessions as JSON with applied proposals, FK cascade, prune to 30 per task, 200 kB payload cap (quotes → uncited sources → claims → excluded trimmed), unreadable rows hidden. Ledger has no FK (outlives tasks). Schema exported to `app/schemas/…/5.json`. Legacy `research_briefs` untouched. |
| Backup | `data/repository/BackupRepository.kt` | New `sessions` and `usage` lists (older builds ignore them). Validation mirrors briefs; an `IN_FLIGHT` row in a backup is imported as Interrupted; the ledger is append-only on import (existing rows never overwritten). |
| Apply / Undo | `ai/research2/Research2Actions.kt` | Edited proposals → one `TaskEditCommand` through `applyTaskEdit` with the session bookkeeping in the same transaction; duplicates against existing steps unless "Add anyway"; note line per step carries the edited note, source numbers and the check date ("• Buy vitamin D: … — 1,000 IU … (source 2; checked 20 Oct 2026)"); idempotency key = session + exact edited selection + task version (double tap no-op, re-apply after undo allowed); conflict on a changed task; Undo only while the task is exactly as the apply left it. Deadlines, reminders, priority and status are never written. |
| UI | `ui/research2/*`, `ui/detail`, `ui/research/ResearchHistoryScreen.kt`, `ui/ai/AiSettingsScreen.kt`, `ui/LifeAdminRoot.kt` | Preflight (request, template chips, requirement rows with type chips and remove, "Add a requirement", mode cards with model/searches/time/estimate and the "Best for buying decisions" badge, Adjust panel, scope switches, estimate line with "This month" link, soft monthly-budget note/confirmation, blockers, explainer); Investigation (title, mono stamp, searches/sources/time counters, "So far ≥ $x" line, stage rows from real events, event log, Stop… sheet keep/discard/continue, result states); Result (status banners, title + stamp + cost chip, collapsed request, answer for A/D/B, coverage rows with status glyphs and words, callouts, template bodies: item cards with four labelled blocks, hero + taste re-rank chips (local, free) + candidate cards + compare table with sticky first column + excluded + conflicts, numbered/checklist steps, claims with provenance chips, unresolved; continue card/sheet with focus chips, note, mode, searches and incremental estimate; sources with Show all; "How this was researched" per run; footer Done / Review N changes); Proposals (checkbox rows, kind labels, editable title and note, "Edited by you", duplicate → "Add anyway", evidence chips, Apply N, Undo); AI usage (today / month tiles with ≥ for floors, by feature, spending preferences: monthly budget and default stop limit, recent runs with outcome words, breakdown sheet with per-line rates and the price-table version, delete older than a year / delete all); task detail research card shows sessions and the live run; history lists sessions and legacy briefs; AI settings gains the usage entry. Font scale ≥1.3 stacks footers and the preflight pill. |

Routes: `task/{taskId}/research/new?q=` (M3A.2 preflight), `…/research2/run/{sessionId}/{runId}`,
`…/research2/{sessionId}`, `…/research2/{sessionId}/proposals`, `ai_settings/usage?session=`.
Legacy `…/research/{briefId}` and `…/apply` remain for saved M3A briefs.

## Deviations from the design (documented)

- **Spending limit default**: "No limit" (the user rejected the prototype's $0.50 default). The last
  chosen limit is remembered per phone and shown in the collapsed Adjust label.
- **Progress phases** come from the app's own stage plan and real call events, not from model-written
  `⟦phase⟧` markers (d14 open question). Without streaming, the counters update per call; the screen says
  so and never shows percentages.
- **`pause_turn`** is continued once per stage in every mode (M3A behaviour kept) rather than Deep only.
- **Taste re-rank** is a local reorder of already-found candidates (free, no call) instead of a paid
  "tidy" call; the screen says "Free — no call is made".
- **Research-later proposals** are shown unticked and can be applied as a step ("Research … further").
- **Daily session cap** raised from 10 (M3A requests) to 25 sessions; still shown on the preflight.

## Validation

### Local (this container; no device, no live API, no personal key)

Command:

```
./gradlew --offline lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
```

Result (2026-10-20, branch head after stage 3–4): **BUILD SUCCESSFUL in 7m 44s**.

| Check | Result |
|---|---|
| `lintDebug` | 0 issues (0 errors, 0 warnings) |
| `testDebugUnitTest` | **474 tests, 0 failures, 0 skipped** (415 on `main` before M3A.2 → 59 new/rewritten; the M3A flow test shrank to its two legacy-brief scenarios) |
| `assembleDebug` | `app-debug.apk` 29.8 MB |
| `assembleRelease` | `app-release.apk` 4.3 MB (minified; signed with the personal key when CI restores it, else the debug key) |
| `assembleDebugAndroidTest` | `app-debug-androidTest.apk` 1.2 MB (instrumented + screenshot tours) |

New or rewritten automated tests (all with fakes / MockWebServer; no paid call, no key):

| Suite | Count | Covers |
|---|---|---|
| `RequirementExtractorTest` | 5 | benchmarks A and B extraction, how-to/checklist/quick answer, exclusions, unsure flag, id stability |
| `ContractParserTest` | 8 | contract blocks, provenance downgrade, `src=` resolution, prices, injection, free-form, stable registry numbers |
| `SessionAssemblerTest` | 5 | merge, coverage, outcome rules, four-proposal guarantee, how-to proposals, candidate evidence inheritance |
| `DeepResearchServiceTest` | 16 | stage planning, gap-fill, budget stops, soft spend limit (stop and trim), partial/failed/refused/truncated, pause_turn, stop keep/discard, continuation, busy/delete, interrupted settlement |
| `AnthropicResearchCallsHttpTest` | 4 | request shape incl. cache_control, usage incl. cache tokens and message id, pause_turn resend, stop reasons and errors |
| `CostCalculatorTest` | 6 | list prices, Haiku long-context tier, unknown counts/models, estimate formula, record statuses, in-memory ledger de-dup/settle/retention |
| `PlanningUsageLedgerTest` | 1 | M2 planning calls recorded with cache fields; not-billed vs unknown |
| `Research2ActionsTest` | 4 | edits, duplicates/Add anyway, notes with evidence and check date, idempotency per task version, undo |
| `ResearchSessionRepositoryTest` | 6 | round trip, applied, prune, fit, unreadable rows, Room ledger |
| `MigrationTest.migrateV4ToV5…` | 1 | v4 → v5 keeps tasks and legacy briefs; cascade vs. no-FK ledger |
| `BackupRepositoryTest.sessionsAndUsageRoundTrip…` | 1 | sessions + usage export/import, append-only ledger, in-flight → interrupted, orphan rejection |
| `Research2FlowUiTest` (Robolectric, 411 dp) | 6 | supplements end to end (preflight → explainer → investigation → result C → proposals edit/Add anyway/apply/undo → usage dashboard + breakdown), fragrance shortlist (hero, re-rank, compare, excluded, conflict), stop sheet discard, provider failure, offline + AI-off saved session, continuation |
| `ResearchFlowLargeTextUiTest` (fontScale 2.0) | 1 | whole M3A.2 flow at 200% text incl. Adjust, Stop sheet, source sheet, apply |
| `ResearchFlowUiTest` (legacy) | 2 | saved M3A briefs still open offline/AI off and apply; long detour |

A real defect found by the 2.0 test and fixed before CI: the preflight footer's estimate row squeezed the
"This month" link to one character per line, growing the bottom bar to the full screen (the list had
0 px height). The row now wraps; three other squeeze-prone rows were made robust the same way.

### CI

_Filled in from the GitHub Actions runs on the draft PR (see "Delivery")._

## Delivery

_Branch, commits, PR and CI links are listed here once pushed._

## Cost rules, assumptions and limitations

- Estimates use Anthropic's **reported** `usage` (input, output, cache creation 5m/1h, cache read,
  `server_tool_use.web_search_requests`) × the price table built into this version
  (`PricingCatalog.VERSION = 2026-10`). Anthropic's Console is the bill; the app never reads it.
- Pre-run estimates use the design formula (6k + 11k tokens per search; mode output budget) as a
  **×0.7–×1.3 range**. The 11k figure is a calibration guess to be re-fitted from the local ledger after
  real runs (roadmap follow-up).
- A dollar limit is **soft**: checked before each call, trims `max_uses`, never aborts a call in flight.
  The UI says "stops when it gets close" and shows the overshoot rule; it never says "cap".
- Unknown usage (stopped, timed out, interrupted) is shown as "≥" with searches seen as a floor and never
  as $0; "$0.00 · not billed" only for requests rejected before processing (400/401/403/429).
- Prompt caching: the ~2k-token system prompt is marked for 5-minute caching only on multi-stage runs
  (Thorough/Deep); write/read tokens are recorded and priced. No call is made only to warm a cache.
  Benefit is unverified without live runs.
- Daily cap: 25 sessions per local day (settings counter shared with M3A), shown on the preflight.
- Never stored in the ledger or sessions: prompts, queries in the ledger, task text, keys. Sessions keep
  the request text and parsed findings (as M3A briefs did).

## Remaining steps that need a phone or a live key

1. Install the CI debug APK over the previous build (same personal signing key); confirm tasks,
   reminders, saved M3A briefs and settings survive the Room 4 → 5 migration.
2. Run benchmark A (supplements) in Thorough and Deep with a personal key; check: four requirements
   with a status each, four reviewable proposals, label-vs-review-vs-judgment tags, no dose chosen,
   overlap callout, nothing written before Apply.
3. Run benchmark B (fragrance gift, up to $150, US): distinct candidates with variants/sizes, confirmed
   prices with retailer and time, excluded listings, hero for an unknown taste, re-rank chips.
4. Compare the AI usage dashboard totals with the Anthropic Console for the same day; note deltas and
   re-fit the per-search token estimate.
5. Exercise Stop keep/discard, a soft spending limit, a continuation and process death mid-run on a
   phone; confirm no re-sent request and an Interrupted ledger row.
6. Dark mode and 1.3/2.0 font scales on the device (emulator screenshots cover them in CI).

## Risks

- Contract drift: a model that ignores the block headings yields a free-form answer with citations and
  "Not found" for every requirement — honest but thin; the prompt and parser are lenient, and the
  benchmarks are fixtures, not live transcripts.
- Multi-stage cost without visible benefit: stages are skipped when nothing is open; every skipped or run
  stage is listed under "How this was researched".
- Deep mode's write-up stage may rephrase evidence; it can only cite existing source ids, and any
  uncited "Fact" is downgraded.
- Robolectric cannot stream; progress is per call. The emulator tour covers the layouts, not timing.
