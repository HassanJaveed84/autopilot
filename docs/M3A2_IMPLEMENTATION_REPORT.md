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

### Second review: evidence-integrity edge cases

Two edge cases and one targeted regression, each reproduced with a failing test first
(`EvidenceIntegrityTest`, 5 tests: all 5 failed on the unfixed code).

- **Budget on unconfirmed prices.** `CompletionPolicy.budget()` marked a strict budget Answered when
  every seen price was under the limit, even a model-typed price with no citation. Now compliance needs
  a confirmed (or conflict-resolved), same-currency price for every pick: missing, unconfirmed and
  other-currency prices keep the constraint at Needs a check with the reason named ("price not confirmed
  by a cited page for Vanilla Smoke ($79)"); a price seen *above* the limit is still flagged whatever
  its status (strict → Partly; soft → Your call only once everything else is confirmed, otherwise Needs
  a check with both notes). Consequence for benchmark B: after the first pass alone the run is now
  "Partially complete" (the $79 is unconfirmed); the fill-gaps pass that cites it makes it Complete.
  Tests that explicitly expected an unconfirmed price to satisfy the budget were corrected
  (`CompletionPolicyTest`, `SessionAssemblerTest`).
- **Restatements with changed values.** `ContractParser.restates()` compared words, so "89.00 USD ·
  100 ml · Big Retailer" could inherit the citation of "98.00 USD · 100 ml · Big Retailer" by
  similarity. Matching now canonicalises both lines (number words → digits; every value folded to
  amount+unit, with `$98`, `98.00 USD` and `98 dollars` agreeing) and requires the ordered list of
  values to be identical before any similarity is considered: a changed amount, size, dose, unit,
  currency, date, percentage, duration, or a value added or dropped, is a different claim and inherits
  nothing (the line becomes Restated / the price stays unconfirmed). Legitimate restatements with the
  same values still match.
- **Brand judged on identity.** A pick whose label or reason said "not from Thorne" was classified as
  Thorne because the brand check scanned explanatory text. Brand and exclusion checks now use only the
  product's own name and variant (`Named.identity`); label facts and reasons never count.

Limitations: units are compared literally ("hrs" vs "hours" is treated as a change, i.e. downgraded,
the conservative side); number words are recognised up to common ones (one…twenty, tens, hundred,
thousand, dozen); currencies other than USD/GBP/EUR/CAD/AUD compare as plain words.

Local validation of this head: `./gradlew --offline lintDebug testDebugUnitTest assembleDebug assembleRelease
assembleDebugAndroidTest` → BUILD SUCCESSFUL (7 m 14 s), lint "No issues found", **514 tests, 0 failures**,
debug 29.9 MB / release 4.3 MB / androidTest 1.2 MB.

CI, head `00059c3`:

| Workflow / job | Run | Result |
|---|---|---|
| Android CI · Lint, unit/Robolectric tests, APKs (push) | [39510062243](https://github.com/HassanJaveed84/autopilot/actions/runs/39510062243) | ✅ lint "No issues found"; **514 tests, 0 failures, 0 ignored**; `apks` artifact 32.9 MB |
| Android CI · Instrumented tests (emulator API 34) (push) | 39510062243 | ✅ **17 tests on emulator-5554, BUILD SUCCESSFUL** |
| Android CI · Lint, unit/Robolectric tests, APKs (pull_request) | [39510057311](https://github.com/HassanJaveed84/autopilot/actions/runs/39510057311) | ✅ same totals |
| Android CI · Instrumented tests (pull_request) | 39510057311 | ❌ **infrastructure, again**: `sdkmanager` "Error on ZipFile unknown archive" for the API 34 system image; the emulator never started and no test ran (same signature as run 39486127724 on `8876815`). The same job passed on the same commit in the push run. A re-run was attempted and refused by GitHub for the session's token ("Resource not accessible by integration"). |
| Screenshots · from request file (run 6) | [39510057310](https://github.com/HassanJaveed84/autopilot/actions/runs/39510057310) | ✅ 4/4 variants, 32 screens each; light/dark 1.0 committed under `docs/screenshots/m3a2/` |

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

CI, hardening head `bb0da9b`:

| Workflow / job | Run | Result |
|---|---|---|
| Android CI · Lint, unit/Robolectric tests, APKs (push) | [39499635955](https://github.com/HassanJaveed84/autopilot/actions/runs/39499635955) | ✅ lint "No issues found"; **509 tests, 0 failures, 0 ignored**; `apks` artifact 32.9 MB |
| Android CI · Instrumented tests (emulator API 34) (push) | 39499635955 | ✅ **17 tests on emulator-5554, BUILD SUCCESSFUL** |
| Android CI (pull_request), both jobs | [39499640832](https://github.com/HassanJaveed84/autopilot/actions/runs/39499640832) | ✅ same results |
| Screenshots · from request file (run 5) | [39499637962](https://github.com/HassanJaveed84/autopilot/actions/runs/39499637962) | ✅ 4/4 variants (light 1.0, dark 1.0, light 1.3, light 2.0), 32 screens each; light/dark 1.0 committed under `docs/screenshots/m3a2/` |

The committed coverage screen (s10) now shows the brand preference verified in code ("Every pick is
from Thorne or Pure Encapsulations") and the output wish honestly Partly ("Steps for 3 of 4 items")
under the "Incomplete · not enough evidence" banner.

## P0 — real-device failure: Deep Research timed out at 60 s (fixed)

**What happened.** First paid Deep Research test on a Galaxy S24 Ultra (Custom mode, Opus 5.5, 30
searches authorised): the GATHER stage sent one request allowed up to 18 searches; after ~60 s the app
showed "Research didn't finish — The request took longer than 60 s … CLIENT TIMEOUT (60 S)", with
0/30 searches reported and nothing saved. Handled error, no crash.

**Verified root cause.** `AnthropicResearchProvider` (the legacy M3A adapter) builds the SDK client with
`.timeout(ResearchConfig.REQUEST_TIMEOUT)` = 60 s. In the Anthropic Java SDK that single value becomes
OkHttp's *call* timeout and, because the read timeout defaults to the request timeout, its *read*
timeout too. `AnthropicResearchCalls` (M3A.2) obtained that same cached client through
`base.clientFor(apiKey)` and only wrapped the call in a 180 s coroutine timeout — which can never
extend a shorter HTTP timeout. A non-streaming research request sends no bytes until the whole
answer is ready, so OkHttp's 60 s read timeout fired, surfaced as `AnthropicIoException` →
`InterruptedIOException` → `PlanningError.Timeout` → `ResearchError.Timeout`, whose text was built
from the legacy 60 s constant whatever the pipeline. Reproduced with the real SDK client against
MockWebServer: legacy client 1 s, M3A.2 wrapper 5 s, reply after 2 s → `Failed(Timeout)` (now
`ResearchTimeoutHttpTest.deepResearchCallSurvivesAReplySlowerThanTheLegacyTimeoutButWithinItsOwn`,
which failed on the unfixed code).

**Exact failure path.** `DeepResearchService.runStage` → `AnthropicResearchCalls.call` →
`base.clientFor(key).messages().create(params)` (client built with 60 s) → OkHttp read timeout at 60 s
→ `mapError` → `ResearchError.Timeout` ("60 s") → ledger row `FAILED_UNKNOWN` (usage unknown, never
$0 — that part was already right) → `RunResult.Failed` → error screen, nothing saved because no call
had returned.

**Fix A — the real HTTP timeout.** Every M3A.2 call now carries its own limit (`ModelCall.timeout`)
and the adapter applies it **per request** through the SDK's `RequestOptions.timeout(...)`, which
OkHttp uses for that request's connect, read and call timeouts; the coroutine timeout is only a 5 s
backstop behind it. The shared client stays cached per API key (rebuilt on key change or `reset()`)
and its 60 s default remains the legacy M3A flow's limit; it can no longer shorten an M3A.2 call.
SDK retries stay at 0. `ResearchError.Timeout(seconds)` names the limit actually configured for the
call that timed out; HTTP 504 is a separate `ServerTimeout`; a dropped connection is `Network`
("Connection to Anthropic was lost"); Stop and process interruption keep their own errors.

**New timeout policy** (`Research2Config.callTimeout`): 60 s + 15 s per search the call may run +
output tokens ÷ 40 per second, clamped to 120–540 s, so every call stays inside Anthropic's
10-minute non-streaming limit:

| Mode (defaults) | Calls | Per-call limit |
|---|---|---|
| Quick · Haiku · 3 searches · 1.5k output | one call, ≤3 searches | 142 s |
| Thorough · Sonnet · 10 searches · 6k output | GATHER ≤6 · FILL_GAPS ≤3 | 300 s · 255 s |
| Deep · Opus · 25 searches · 10k output | GATHER 15 in batches of ≤6 · FILL_GAPS ≤6 then ≤1 · CROSS_CHECK ≤6 per call · write-up 0 | 400 s · 400 s / 325 s · ≤400 s · 310 s |
| The failed run: Custom · Opus · 30 searches | GATHER 18 as 3 batches of ≤6 (was one 18-search call behind a 60 s limit) | 400 s each, cap 540 s |

**Fix B — bounded batches, evidence saved as it arrives.** A stage allowed more than
`MAX_SEARCHES_PER_CALL` (6) searches now runs them as separate HTTP calls: each call is its own
billed request with its own timeout; after each call the merged evidence is **checkpointed** to the
store as an Interrupted session with a provisional run record; the next batch is sent only while
requirements are still open and the previous call actually searched, with the fill-gaps prompt
(known sources, queries not to repeat), at most `MAX_CALLS_PER_STAGE` (5) calls per stage.
`pause_turn` is still continued once per call within the same budget. Stages with ≤6 searches
(Quick, Thorough) behave exactly as before. Evidence gathering is therefore **split, not streamed**:
streaming would also give per-search progress, but the SDK's stream accumulator's handling of
citation deltas was not verified and the batch approach needed no new framework; it is the
documented next step if dropped idle connections show up on mobile networks.

**Fix C — paid work is not lost.** A timeout, 504 or dropped connection after a completed call ends
the run as "Partially complete" with everything merged so far (the checkpoint is finalised, not
re-created); on the very first call it saves nothing and says so. The timed-out call's ledger row is
`FAILED_UNKNOWN` with usage unknown (never $0); its searches, if the provider reported any, are a
floor. Nothing is ever resubmitted on its own: "Try again" sends the person back to the preflight,
and the error copy says a retry may be billed again. Stop → discard removes the checkpoint (a
continuation's session goes back to what it was); Stop → keep finalises it as Stopped. Process death
leaves the Interrupted checkpoint plus the in-flight ledger row, which the next app start settles as
Interrupted/unknown; the session offers Continue. The search budget holds across batches and
pause-turns (no call asks for more than the stage's remaining allowance); a soft spending limit is
checked before every batch.

**Fix D — status and error screens.** The counter reads "Searches reported"; a waiting line shows
the call number, its elapsed time and the limit actually configured ("Waiting for Anthropic's reply to
call 2 · 1:05 of up to 6:40. Search counts and sources arrive only when a call returns; Anthropic may
already be searching."); the spend line says when earlier evidence is already saved; events name
each batch ("…: 5 searches so far, evidence saved; still open: glucosamine — next batch of up to 6").
Error states: client timeout (with the configured limit and the retry-may-bill note), server timeout
(504), connection lost, stopped, interrupted. Flight Plan styling unchanged.

**Tests.** `ResearchTimeoutHttpTest` (real SDK client vs MockWebServer, injected short durations):
survives a reply slower than the legacy limit but within its own; times out at its own limit with the
right seconds in the message, one request only; legacy flow keeps its shorter limit and the same base
provider still serves a longer M3A.2 call; key change and reset rebuild the client without losing the
limit. `DeepResearchServiceTest`: per-call timeout policy and what the orchestrator hands each call;
Deep gather in bounded batches with a checkpoint after every call (store state inspected mid-run);
timeout after a completed batch keeps the evidence and never resends; timeout on the first call saves
nothing, names its limit, never resends; stop/discard removes the checkpoint, stop/keep finalises it;
process death leaves an Interrupted checkpoint and an unknown ledger row settled on next start; search
budget across batches and a pause-turn. `AnthropicResearchProviderHttpTest` (legacy) updated to the
typed Timeout.

**Validation.** Local: `lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` →
lint clean, 525 tests / 0 failures, all three APKs. CI, head `b47c685`: Android CI runs
[39520792363](https://github.com/HassanJaveed84/autopilot/actions/runs/39520792363) and
[39520801515](https://github.com/HassanJaveed84/autopilot/actions/runs/39520801515) — lint "No issues
found", **525 tests, 0 failures**, `apks` artifact, **instrumented tests green in both runs**; screenshot
run [39518965218](https://github.com/HassanJaveed84/autopilot/actions/runs/39518965218) on the P0 code
head `fde29c2` — 4/4 variants (the committed set; the investigation screen now shows "Searches reported",
the waiting line with the configured limit and the "waiting up to 5 min" event). One CI-only flake in
the new legacy-isolation test (an immediate "Connection lost" on the socket a timed-out request left in
the pool) led to a product change: both adapters now drop the cached client after a timeout or lost
connection so the next request opens a fresh connection.

**Remaining limitations.** Per-search progress still arrives only when a call returns (no streaming).
A single batch can still take up to 400–540 s on a slow network; the waiting line now says so. The
checkpoint shows as "Interrupted" in the task's research list while a run is in flight.

## Real-device bug — continuation returned "MALFORMED RESPONSE" (fixed)

**What happened.** On the Galaxy S24 Ultra, a saved Deep fragrance session (~69 sources, ~$0.89) was
continued with the note "Any chanel options" in Quick mode (Haiku 5.5, 3 searches). After ~12 s the
app showed "The answer couldn't be used — The response had no usable text (no usable blocks). Nothing
was saved. MALFORMED RESPONSE". The saved research was intact.

**Investigation.** `DeepResearchService` set `ctx.merged` only when the parsed reply held an ANSWER,
REQ, CAND, PICK, CLAIM or STEP block; everything else ended in the same `Malformed("no usable blocks")`
with no record of what had come back. Replies that land there: (1) `max_tokens` before any text block;
(2) a tool-only `pause_turn` when no searches were left (`left > 0` blocked the continuation and the
paused content was dropped); (3) an empty `end_turn`; (4) an UNRESOLVED-only reply (parsed, not counted);
(5) text whose REQ/CAND headings did not start with the exact id (dropped block by block, nothing kept).
The likeliest on the phone is (1): Claude Haiku 5.5 runs **adaptive thinking by default** when the
request omits `thinking`, thinking and tool calls count against `max_tokens`, and Quick allowed only
**1,500** output tokens for a prompt that carried five requirements, 40 source titles, ~25 earlier
queries and the new question. Twelve seconds is about what Haiku needs to spend 1,500 tokens on
thinking plus one search; the adapter ignored thinking blocks, so the orchestrator saw "no blocks".
The exact outcome on the device cannot be proven after the fact — which is finding A's point: every
call now leaves a privacy-safe record that would have said so.

**Reproduced first.** `ContinuationRegressionTest` (11 tests) seeds the saved fragrance session and
continues it with the same note; on the unfixed code all ten behavioural tests failed, five of them
with exactly `Malformed(detail=no usable blocks)` (cut off before text, empty reply, tool-only pause
with no budget left, UNRESOLVED-only, misformatted headings), the others on the follow-up prompt, the
overwritten answer and the missing run record. `ReplyShapeHttpTest` (3) drives the real SDK client
against MockWebServer with thinking + search + `max_tokens`, a tool-only `pause_turn` and an empty
`end_turn`.

**A — diagnostics.** The adapter now counts block kinds (`text`, `thinking`, `redacted_thinking`,
`server_tool_use`, `web_search_tool_result`, `tool_use`, other), text characters and cited text blocks
(`ReplyShape` on `CallResult.Done`). The orchestrator adds the parsed block counts, stop reason, tokens
in/out against `max_tokens`, searches run/allowed, duration, stage and a classification (`CallClass`:
STRUCTURED, FREE_FORM, TEXT_UNPARSED, UNRESOLVED_ONLY, NO_NEW_EVIDENCE, TOOL_ONLY, PAUSED_BEFORE_TEXT,
TRUNCATED_BEFORE_TEXT, EMPTY, REFUSED, HTTP_FAILED) into a `CallDiagnostic` per HTTP call, stored on
`StageRecord.callLog` and carried on `RunResult.Failed.diagnostics`. Never recorded: the key, prompt,
task text, reply text, queries or page content (`diagnosticsNeverCarryTheKeyThePromptTheTaskOrTheAnswer`
checks the shareable summary against all of them). The failure screen has "Technical details (safe to
share)" with Copy; "How this was researched" lists each call's line under its stage.

**B — paused turns.** A `pause_turn` is continued with the same tool and the stage's remaining searches
while any remain (as before); with none left it is **not** continued — the docs require the same tools
on the continuation, and `max_uses` would re-authorise searches past the budget — and the run says so:
"Research paused before an answer was written … 3 of 3 searches … not continued (no searches left in
the budget)", outcome Incomplete · budget used up, never MALFORMED. No hidden billable retry anywhere.

**C — the follow-up is the objective.** A typed note becomes a requirement of its own (`r-f1`,
`USER_ADDED`, `followUp = true`) for that run, so coverage, the outcome and the Continue sheet account
for it; it never gets a generated proposal and does not count toward "one step per item".
`Research2Prompt.continuation` with a note now leads with the exact words in quotes, names it the main
objective and the REQ id to report, restates the original constraints, keeps the original request
verbatim, lists existing candidates by id, name, variant and price (so new options are compared and
merged, numbering continues), refers to **cited sources only, at most 12** (was: 40 titles), the last 10
queries, and asks for a small contract: REQ for the follow-up, a 2–4 sentence ANSWER, new CAND/ATTR,
UNRESOLVED for anything unverifiable, "do not rewrite the earlier answer". A continuation's ANSWER and
TITLE are never merged over the saved ones; they become a `FollowUp` record (question verbatim, status,
cited answer, summary) shown as a "Follow-ups" card under the answer. Free text about the follow-up is
shown but marked Needs a check; an UNRESOLVED line settles it as Not found with the reason. A
focus-only continuation (no note) keeps the previous prompt shape and adds no follow-up requirement.

**Output room.** Quick 1,500 → **6,000**, Thorough 6,000 → 8,000, Deep 10,000 → 12,000 output tokens
per call (Claude 5.5 models think by default; thinking counts against `max_tokens`). Output tokens are
the cheap part: 6,000 Haiku 5.5 output tokens ≈ $0.003 against $0.03 for its three searches. The per-call
timeout follows (`callTimeout`: Quick 3 searches 142 s → 255 s; a Deep batch of 6 → 450 s, still inside
the 540 s cap).

**D — honest outcomes.** `ResearchError.NoFindings(why, detail, continuation)` replaces the generic
error on the M3A.2 path: headline per class ("The reply hit its output limit before any answer",
"Research paused before an answer was written", "Searches ran, but no answer came back", "Claude sent
back an empty reply"), the detail in plain words ("reached its 6000-token output limit before writing
any text (2 thinking blocks, 1 search, no text)"), then "No new findings were saved. Your previous
research is unchanged." for a continuation or "Nothing was saved." for a first run. Text that came back
is kept: a free-form reply is the follow-up's answer; text whose headings carry no usable id is kept as
text (heading lines stripped); an UNRESOLVED-only reply is saved as an honest Not found. REQ/CAND/ATTR/
PICK ids are now matched anywhere in the heading ("REQ · r-i0", "REQ: r-i0"). A failed continuation
saves the original content **unchanged** plus a FAILED (or Incomplete · budget) run record with the call
log, so the log and the ledger agree; a failed first run saves nothing. Usage accounting is untouched
(TRUNCATED / FAILED_UNKNOWN rows, search floors). The failure screen of a continuation leads back to the
saved result ("Back to your research") instead of the preflight.

**Tests.** `ContinuationRegressionTest` (11): cut off before text (error, message, unchanged findings,
run record, ledger, diagnostic fields); diagnostics privacy; empty reply; tool-only pause with no budget
left (one call, Incomplete · budget); tool-only pause with budget left (continued with the remainder,
answer saved as a follow-up); UNRESOLVED-only (saved, Not found with reason, offered for another
continuation); free-form cited text (follow-up answer, original untouched, Needs a check); misformatted
headings kept as text; HTTP 500 continuation (original intact, run recorded, search floor); follow-up as
the primary objective (verbatim quote, constraints, candidates, ≤ 12 source lines, REQ id, numbering,
no generated proposal, Complete); first run cut off before text; focus-only continuation unchanged.
`ReplyShapeHttpTest` (3). `DeepResearchServiceTest`: the continuation test now checks the follow-up
record and the kept answer; empty reply asserts `NoFindings(EMPTY)` with its diagnostic; timeouts and
the spend-limit trim follow the new output budgets. `Research2FlowUiTest` +2: typed follow-up answered
next to the original answer (card, coverage row, prompt, Complete); continuation cut off before text
(headline, "unchanged" message, no MALFORMED, technical record with `stop=max_tokens` and `thinking 2`,
question absent from the record, back to the unchanged result with the failed run on its log).
`ScreenshotTourResearch2`: s1 now types the follow-up and shows the answered card (s14b); new s6 shows
the cut-off failure screen, the technical record and the unchanged result (s30–s32).

**Validation.** Local: `lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`
→ lint clean, **542 tests / 0 failures**, debug + release APKs, instrumentation APK. CI on the pushed
heads (code in `42098c8` + `381066a`, docs `81f3172`, tour fix `b088211`):

| Check | Head | Result |
|---|---|---|
| Android CI · push [39534614692](https://github.com/HassanJaveed84/autopilot/actions/runs/39534614692) | `b088211` | ✅ lint "No issues found", **542 tests / 0 failures** (reports artifact), `apks` artifact, **instrumented tests green** (API 34 emulator) |
| Android CI · PR [39534619532](https://github.com/HassanJaveed84/autopilot/actions/runs/39534619532) | `b088211` | ✅ (same jobs) |
| Android CI on `81f3172` (runs 39533644509 / 39533648729) | `81f3172` | cancelled by the newer push before finishing (concurrency); code identical to `b088211` apart from the tour |
| Screenshot tour run 8 [39533644451](https://github.com/HassanJaveed84/autopilot/actions/runs/39533644451) | `81f3172` | ❌ 4/4 variants: two tour-script faults (a lazy-list item waited for by tag instead of scrolled to; Start looked up inside the preflight list) — no product failure; fixed in `b088211` |
| Android CI · push [39536349043](https://github.com/HassanJaveed84/autopilot/actions/runs/39536349043) and PR [39536353253](https://github.com/HassanJaveed84/autopilot/actions/runs/39536353253) | `0f865a2` (screens + tour renumbering) | ✅ |
| Screenshot tour run 8c [39536349125](https://github.com/HassanJaveed84/autopilot/actions/runs/39536349125) | `0f865a2` | ✅ 4/4 variants with the renumbered s33–s35 |
| Screenshot tour run 8b [39534614658](https://github.com/HassanJaveed84/autopilot/actions/runs/39534614658) | `b088211` | ✅ 4/4 variants (light/dark 1.0, light 1.3 and 2.0), 36 screens each incl. the follow-up card (s14b) and the cut-off continuation screens (s33–s35, captured as s30–s32 and renumbered); light/dark 1.0 committed under `docs/screenshots/m3a2/` |

**Real-device retest (needs the phone and a personal key).**
1. Install the debug APK over the previous build (same signing key; Room schema unchanged; saved
   sessions load — new fields default).
2. Open the saved Deep fragrance session → Continue researching → type "Any chanel options" → Quick ·
   Haiku 5.5 · 3 searches → Continue.
3. Expected: either a "Follow-ups" card under the recommendation with a cited Chanel answer (and any
   Chanel option as a new candidate next to the old ones, numbering continued), or an honest card
   "Not found" with the reason and the topic under "Still unresolved"; the original recommendation,
   candidates, sources and coverage unchanged; "Your request, point by point" shows the question as a
   row; "How this was researched" shows "Continuation 2 · Quick · Haiku 5.5" with one call line
   (`stop=end_turn · blocks: thinking n, server_tool_use n, text n …`).
4. If it fails again: the screen must name what came back (output limit / paused / searches without
   text / empty), say "No new findings were saved. Your previous research is unchanged.", and
   "Technical details (safe to share)" → Copy gives the redacted record to paste into the bug report.
   The original session must open unchanged with the failed continuation on its log.
5. Check the AI usage screen: one `RESEARCH_CONTINUATION` row for the attempt with its tokens and
   searches (or a "≥" floor).

**Remaining limitations.** The classification is inferred from the reply's shape; thinking depth
cannot be reduced without an effort setting (`output_config.effort`), which this change does not add
(a product decision: it changes research quality). The exact cause on the device is a diagnosis, not a
measurement; the next real run's call log settles it. A paused turn with no searches left still cannot
be finished without re-authorising searches; the person is told and can continue with a larger budget.

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
