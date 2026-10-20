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
- **Start button label**: "Start · Thorough" at normal text (the search count is in the Adjust panel and
  the estimate line) and "Start research" at font scale ≥ 1.3; the prototype's "Start · Thorough · 10
  searches" does not fit a half-width button on a 411 dp phone.

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

### CI (GitHub Actions, draft PR #5)

Head `8876815` (the last code change before this report was finalised):

| Workflow / job | Run | Result |
|---|---|---|
| Android CI · Lint, unit/Robolectric tests, APKs (push) | [39486121354](https://github.com/HassanJaveed84/autopilot/actions/runs/39486121354) | ✅ lint "No issues found"; **474 tests, 0 failures, 0 ignored**; `apks` artifact (debug + release + androidTest, 32.8 MB) |
| Android CI · Instrumented tests (emulator API 34) (push) | 39486121354 | ✅ `connectedDebugAndroidTest`: **17 tests on emulator-5554, BUILD SUCCESSFUL** |
| Android CI · Lint, unit/Robolectric tests, APKs (pull_request) | [39486127724](https://github.com/HassanJaveed84/autopilot/actions/runs/39486127724) | ✅ same totals |
| Android CI · Instrumented tests (pull_request) | 39486127724 | ❌ **infrastructure**: `sdkmanager` reported "Error on ZipFile unknown archive" for the API 34 system image and the emulator never started; no test ran. The same job passed on the same commit in the push run above. |
| Screenshots · from request file (`ScreenshotTourResearch2`) | [39486121391](https://github.com/HassanJaveed84/autopilot/actions/runs/39486121391) | ✅ 4/4 variants (light 1.0, dark 1.0, light 1.3, light 2.0), 32 screens each |

Earlier heads on the branch, for the record:

- `f46c50a` — Android CI green; screenshot run 39481735738 failed in all four variants at scene s12: the tour
  tapped the item-card column instead of its clickable header. Fixed in `c52821d` (test-only change).
- `c52821d` — Android CI green in both runs (39483390318, 39483400451); screenshot run 39483390264 passed
  light 1.0, dark 1.0 and light 1.3 and failed light 2.0 right after s01: the soft keyboard stayed open and
  left the preflight list about 100 px tall, so the next scroll found no hierarchy. Fixed in `8876815`
  (tour dismisses the keyboard; the Start button also got a compact label at large text).

Head `a23586a` (report, committed screenshots, Start label): Android CI runs
[39488460739](https://github.com/HassanJaveed84/autopilot/actions/runs/39488460739) and
[39488469117](https://github.com/HassanJaveed84/autopilot/actions/runs/39488469117) — lint/unit/APKs ✅ and
instrumented ✅ in both; screenshot run
[39488460868](https://github.com/HassanJaveed84/autopilot/actions/runs/39488460868) ✅ 4/4 variants. The
committed set under `docs/screenshots/m3a2/` (light and dark at 1.0, with `INDEX.md`) comes from that run,
so it shows the code in the PR head.

## Delivery

- Branch `m3a2-deep-research`, based on `main` at `a0419ea` (M3A hardening merged as PR #4).
- Draft PR **#5**: https://github.com/HassanJaveed84/autopilot/pull/5 (against `main`; not merged).
- Commits: `f0097dc` plan · `3a9eba7` stage 1 (domain, extractor, parser, orchestrator, pricing) ·
  `fd74364` stage 2 (Room v5, ledger, backup, actions) · `77e8a1d` stages 3–4 (UI, wiring) ·
  `f46c50a` stage 5 (test sync, validation) · `c52821d`, `8876815` (screenshot-tour hardening) ·
  then this report/screenshot commit.
- Diff against `main` before the final commit: 68 files, +8 968 / −313 lines.
- APKs: the `apks` artifact of each Android CI run (`app-debug.apk` ≈ 29.8 MB, `app-release.apk` ≈ 4.3 MB,
  `app-debug-androidTest.apk`); nothing was installed on a phone by the agent.

## Hardening pass (2026-10-20, second session): findings A–C and a focused review

An independent source-code review raised three correctness findings. Each was reproduced first with a
temporary test against head `7562009` (all six assertions failed on the unfixed code), then fixed with
permanent regression tests. Numbers in this section are from the final head; see "Validation (hardening)".

### Finding A — requirements counted as complete without being satisfied

- **Reproduced.** `RequirementStatus.covered` was true for everything but Pending/Not found, so Partly
  counted as covered; `SessionAssembler.covered()` skipped Constraint requirements; every status came
  from the model's `status=` token. A $210 shortlist pick with `status=answered` on the $150 budget was
  Complete; a `status=answered` with no citation anywhere was Complete.
- **Root cause.** Completeness was a property of the model's words, not of the structured result.
- **Fix.** `ai/research2/CompletionPolicy.kt` validates every status in code after each merge (called
  from `SessionAssembler.merge`), with explicit semantics documented on `RequirementStatus`:
  Answered / Resolved conflict / Your call are covered; Partly, Needs a check, Not found and Not
  researched are open. Items marked Answered or Your call need cited evidence (coverage line, pick,
  shortlist candidate, step or claim) or become Needs a check. Constraints take part: budgets are
  checked against every included candidate and pick that has a price (strict → Partly, soft → Your
  call with the exceptions named, no price seen → Needs a check); brand preferences are checked
  against every pick's name, variant and label facts (a preference is soft, so an off-brand pick is a
  named exception; "only"/"must" makes it strict); exclusions are checked against names and reasons.
  Output wishes are verified for real ("one step per item" counts items with a step or a pick; a
  shortlist needs ≥ 2 candidates and a recommendation; how-to/checklist need steps). Constraints the
  app cannot check keep the model's status, except Answered needs some cited evidence in the session.
  `Requirement.soft` is set by the extractor ("Brand preference", "around $150") and can be worded in
  by the person ("(exceptions ok)", "hard limit"). Outcome: Complete only when every requirement is
  covered; a normal ending with something Not found is "Incomplete · not enough evidence", with
  everything investigated but some Partly / Needs a check it is "Partially complete"; budget stops are
  never Complete. Gaps (the banner, the fill-gaps prompt) now include constraints.
- **Regression tests.** `CompletionPolicyTest` (15): partly, unanswered, over-budget shortlist, budget
  verified by code, unpriced pick, user-permitted exception, brand preference broken (soft) and strict,
  exclusion present, requested output missing/partial, your-call with and without options, false
  Answered, outcome ⇔ on-screen gaps agreement, extractor soft/strict flags. Existing expectations that
  encoded the bug were corrected: the Thorough run whose second pass finds glucosamine "partly" is now
  Partially complete, not Complete (`DeepResearchServiceTest`); the supplements gaps include the
  "one step per item" wish (`SessionAssemblerTest`).
- **Side finding fixed.** The extractor's brand regex swallowed the next sentence's first word
  ("…Pure Encapsulations. Need" was the brand list shown on the preflight); it now stops at the sentence.
- **Limitations.** Region and gift constraints cannot be checked deterministically; they keep the
  model's status (capped by the evidence rule). Budget checks compare amounts in the same currency
  only. Sessions saved before this change keep their stored outcome; they are not re-judged on read.

### Finding B — model-written source ids could appear to verify unsupported claims

- **Reproduced.** `src=sN` resolved to any registry entry, including pages merely returned by a search
  and never cited; a model-typed `src=s1` made a claim a Fact and a price Confirmed.
- **Root cause.** One registry held discovered and cited pages alike, and a pointer counted as a citation.
- **Fix.** `SourceRegistry2` keeps the two kinds apart (`isCited`); `src=` resolves only to sources the
  provider cited (now or in an earlier run) and only as a pointer (`reusedRefs`, kept separately from
  `refs` on every evidence-bearing type). Fresh verification comes only from provider citations that
  overlap a block's own lines (a citation on a neighbouring heading no longer leaks). The one accepted
  reuse is a validated restatement: the line repeats a cited line the session already holds
  (`SessionAssembler.evidenceLines`, matched by `ContractParser.restates`) and points at one of its
  sources — then it keeps that evidence; a new statement with an old id is tagged **Restated**
  (`ProvenanceTag.SYNTHESIS`, a new chip) and never Fact, and a model-typed price is never Confirmed.
  Uncited answer sentences with pointers are judged sentence by sentence. `SessionAssembler.merge`
  never lets an unconfirmed price or an unverified attribute replace a confirmed/cited one.
- **Regression tests.** `ProvenanceHardeningTest` (9): discovered-only result, pointer on a cited
  source (price, reviews, claim), provider citation still confirms, validated restatement vs new fact
  (claims and answer sentences), unknown ids, citation on a neighbouring heading, evidence preserved
  across runs with confirmed-beats-unconfirmed, one identity per page (fragment/slash), restatement
  matcher strictness. `ContractParserTest` updated to the pointer semantics.
- **UI.** Restated chip (dashed, ink) in cards and the compare legend. Pointers are not drawn as citation
  chips in answer text: a chip means a provider citation.
- **Limitations.** Restatement matching is textual (normalised equality/containment or ≥ 0.6 word
  overlap with ≥ 4 shared words); a heavy paraphrase of real evidence becomes Restated rather than
  Fact — the conservative side.

### Finding C — failed requests lost known search costs

- **Reproduced.** `CallResult.Failed.searchesSeen` was ignored: a failure after two observed searches
  was recorded with no usage and no estimate (unknown), understating the floor by $0.02.
- **Fix.** `DeepResearchService.runStage` keeps observed usage on failure: full provider usage when
  reported (priced in full, status Complete), otherwise the searches seen as a floor with token counts
  unknown (status Partial, `tokensUnknown`); the run usage, remaining search budget and the in-flight
  spend floor follow. A rejected request (401/402/403/429, invalid request incl. web search disabled,
  offline) carries no usage and is recorded as "Rejected · $0 assumed": Anthropic's docs state only that
  errored *web searches* are not billed, not a rule for errored requests, so the dashboard names it an
  assumption and counts such calls (`UsageTotals.assumedZero`) instead of presenting $0 as confirmed.
  Server errors, timeouts and transport failures stay "usage unknown", never $0. `UsageRecord.priced`
  now derives the status from the counts, not the outcome. Grouping moved to a pure
  `UsageGrouping.groups` so dashboard totals can be reconciled with the ledger in a test.
- **Regression tests.** `UsageAccountingTest` (6): floor from observed searches, failure with complete
  usage, rejected = assumed zero, nothing reported = unknown, stopped with searches, dashboard groups
  reconcile with the ledger (micros, searches, calls, unknown and assumed counts, partial flags, cache
  tokens priced once). `DeepResearchServiceTest`: failure before dispatch leaves no row, failure after
  observed searches (first call and after a good pass), failure with full usage, stop during a
  `pause_turn` continuation keeps the first call's searches, rejected request has no usage.
  `ResearchSessionRepositoryTest`: Room restore never duplicates a provider message id;
  `BackupRepositoryTest`: an import with the same response under a new id is skipped.
- **Limitations.** With the non-streaming SDK call a failed HTTP request yields no partial message, so
  `searchesSeen > 0` on failure can only come from a provider that reports it; the path is exercised by
  the scripted provider. The 4xx classification is the app's assumption, labelled as such.

### Focused review (section 5 of the brief)

| Item | Result |
|---|---|
| Simultaneous Start / quota reservation | **Fixed.** `start()` and `continueSession()` run under one atomic claim (`AtomicBoolean`): the Busy check and the daily reservation cannot interleave; test `twoSimultaneousStartsLaunchOneRunAndReserveOneSession`. |
| Delete-all vs active run | Reviewed, no change: `deleteAll`/`deleteForTask` cancel and **join** the job before deleting, so a save cannot land after the delete. |
| Cancellation during persistence | **Fixed.** `finish()` (settle, proposals, save) runs under `NonCancellable`, so Stop/delete during the save cannot leave a half-saved session or re-enter `finish`. |
| `pause_turn` and remaining budget | Reviewed: the continuation gets the stage's remaining allowance after the first call's measured searches; not continued when nothing remains. New test covers Stop during the continuation. |
| Search budgets across calls | Reviewed: `remaining` is decremented by measured searches (provider count, else result blocks), coerced at 0; failed calls now decrement too. |
| Source identity / citation counts | **Fixed:** fragments and trailing slashes no longer create duplicate sources; citation counts stay per cited block. |
| Requirement extraction and manual edits | **Fixed** the brand-sentence overrun; softness can be edited in by wording. |
| Proposals inheriting evidence | **Fixed.** A model proposal borrows citations only when its title/note names the requirement's pick or a shortlist candidate; otherwise it stays uncited and unselected (`modelProposalsInheritEvidenceOnlyForTheProductTheyName`). |
| Apply/Undo idempotency | Reviewed, existing tests hold (`Research2ActionsTest`). |
| Ledger dedupe and backup restore | **Fixed** restore by provider message id (Room ledger and backup import). |
| Migration 4→5 with user data | Reviewed: `MigrationTest.migrateV4ToV5…` seeds a v4 database with a task and a brief and checks both survive and new tables work; unchanged. |
| Long runs, network loss, process death | Reviewed: network loss → "usage unknown" (never $0); process death → in-flight rows settled as Interrupted on app start; nothing is re-sent. |
| Prompt cache accuracy | Reviewed: cache write 5 m / 1 h and cache read tokens priced at their own rates, once (`UsageAccountingTest` checks the arithmetic). |

Not changed, documented for a later pass: the Deep mode write-up stage still cannot cite new pages
(by design); sessions saved before this pass keep their stored outcomes; pointers in answer text are
not shown as chips.

### Validation (hardening)

Local, this container (no device, no live API, no personal key), final hardening head:

```
./gradlew --offline lintDebug testDebugUnitTest            → BUILD SUCCESSFUL (4 m 13 s): lint "No issues found"; 509 tests, 0 failures (474 before the pass)
./gradlew --offline assembleDebug assembleRelease assembleDebugAndroidTest → BUILD SUCCESSFUL (6 m 23 s): app-debug.apk 29.9 MB, app-release.apk 4.3 MB, app-debug-androidTest.apk 1.2 MB
```

Reproduction evidence: a temporary `HardeningReproTest` (6 assertions of the correct behaviour) was run
against the unfixed code first — all 6 failed ("PARTLY must not count as covered", "expected:<[]> but
was:<[1]>" for the uncited search result, "expected:<NOT_CONFIRMED> but was:<CONFIRMED>" for the
model-typed price, "the floor is $0.02, not unknown", and the two false-Complete cases) — then deleted
once the permanent tests covered the same ground.

CI results for the hardening head are listed under "CI (GitHub Actions, draft PR #5)" below once the
runs complete.

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
