# Research Online: AI-first production integration

**Status:** implemented on branch `ccr-e03bf7f8-hvk42n` (this session's assigned branch, fast-forwarded from the experiment branch `ccr-205def5b-4gv9qp` at `546bdd1`; no force-push). **Not merged.** Draft PR #8 (M4, frozen baseline `342db13`) and its branch are untouched.
**Date:** 2026-10-24. **Scope:** replace M4's Research Online core with the AI-first engine from `docs/experiments/ai-first-research/REPORT.md`, keep the rest of the app and all saved data.
**Not done in this session:** paid live calls (not authorized), physical-device testing. Document F (`docs/product-baseline/Document_F_Evaluation_Framework_v1.0_FROZEN.md`) is unchanged.

## 1. Production architecture

Claude owns language understanding, search strategy, source evaluation, synthesis and uncertainty. Kotlin owns the boundaries.

```
question ──▶ ResearchOnlineService.ask ── saves the turn, then ──▶ AfClarifier (Haiku, no search, ledgered)
                                                                      │ CLEAR / failed → READY
                                                                      └ ASK → CLARIFYING (person answers or "research as asked")
READY ──▶ card = engine.authorize(stored turn, switched-on context, config)      (pure; sends nothing)
Start ──▶ ResearchOnlineService.start(auth)  checks: same question/clarification, every context line still on offer,
          payload == what the fields + named history produce, gate, key, daily cap  ──▶ saves RUNNING ──▶
AiFirstResearch.run: one Messages call (web_search, max_uses = budget), pause_turn continued only within the
          remaining budget, spend-limit check before every call, ledger row per call, checkpoint after every call
          ──▶ COMPLETE / TRUNCATED / SEARCH_BUDGET_REACHED / SPEND_LIMIT_REACHED / REFUSED / FAILED / STOPPED
App start ──▶ recoverInterrupted: RUNNING turns (either format) → INTERRUPTED, in-flight ledger rows → usage unknown
Answer ──▶ "Suggest changes to the task…" (explicit, payload shown, Haiku no-search) ──▶ ProposalSet ──▶ Proposal Review
```

| Component | File | Role |
|---|---|---|
| Data model | `ai/aifirst/AiFirstModels.kt` | `AfAuthorization` (question, clarification, context lines, config, history turn ids, prompt version, exact payload + SHA-256, estimate), `AfTurn` (status, provider segments with source numbers, sources with the provider's cited text and page age, searches, unsupplied links, calls/searches/cost/floor) |
| Engine | `ai/aifirst/AiFirstResearch.kt` | `authorize` (pure), `mismatch` (refuses tampered or stale approvals), `run`, `AfRender` (provider citations → numbered sources), `AfPrompt` v2 |
| Small calls | `ai/aifirst/SmallCalls.kt` | `AfClarifier` (pre-card ambiguity check) and `AfTaskSuggester` (answer → proposal set) |
| Orchestration | `ai/aifirst/ResearchOnlineService.kt` | gate, defaults, context items, ask/clarify/decline, preview, start/stop, recovery, suggestions, association, deletion |
| Storage | `data/repository/AfThreadRepository.kt` | AI-first threads in the existing `investigations` / `investigation_turns` tables; unified list summaries for both formats |
| UI | `ui/investigation/*` | `InvestigationThreadScreen` (rewritten, simpler), `AnswerText` (light Markdown + `[n]` marks), `LegacyThread` (read-only), `InvestigationViewModels`, home list |

### Prompt changes (v1 → v2)

Two evidence-driven additions from the experiment's observed defects, plus one honesty line; nothing else:

1. Leave out side facts, nearby places, extras and background that were not asked for, unless they change what the person should do.
2. When a sentence is Claude's own inference, general knowledge or judgment rather than something a source states, say so plainly.
3. Search results are index snapshots: give the date or age when known, never say a price or availability is current or was "checked today".

The ambiguity line now says to name the reading answered (the clarifier handles genuine ambiguity before research). No second model-assisted wording check exists.

### Storage and compatibility

- **No schema change** (Room stays at v7; no migration, no destructive fallback). AI-first threads are marked by `meta.engine = "ai-first"` and turn rows of kind `AI_FIRST` with `AfTurn` JSON.
- **Legacy research stays readable, unchanged.** `InvestigationRepository` remains the read path for M4 investigations and the M3A.2 sessions migrated into them (`MIGRATION_6_7` and `LegacyInvestigations` are untouched). It ignores AI-first rows; `AfThreadRepository` ignores legacy rows and refuses to write turns into them. The legacy view shows the stored answer, caveats, sections, recommendation, cited sources and usage as saved, labels the status line "Recorded status", and offers no follow-up. M4 proposals that were stored on a turn can still be opened in the Proposal Review. M3A.2 sessions still open in their saved-result screen; M3A briefs are unaffected.
- **Nothing is reinterpreted or deleted.** Old claims are not converted into the new format.
- **Recovery:** the turn is saved before anything is sent and after every call. On app start, a turn still marked running (either format) becomes Interrupted with what its last checkpoint held; calls still "in flight" in the ledger become "usage unknown". Nothing is re-sent; continuing is a new question with its own approval.
- **Backups:** export is unchanged (rows as stored). Import validates AI-first threads by decoding every turn (one bad turn rejects the file, as before) and settles a running turn as interrupted.

## 2. Reused, removed, simplified

**Reused unchanged:** `AnthropicResearchCalls` and the SDK adapter (timeouts, error mapping, `pause_turn` hand-back), `ModelCall`/`CallResult`, `Research2Config` limits, `CostCalculator`/`PricingCatalog`, `UsageRecord`/`UsageLedger`/`RoomUsageLedger` and the AI usage screen, `ResearchError`, `ApiKeyStore`, `ResearchGate` and the one-time research explainer, `ContextItem` switches, the Proposal Review / `ProposalApplier` / provenance, the app shell and design components, the M3A/M3A.2 legacy screens, the Room v7 tables and migrations.

**Reused from the experiment, hardened:** `AiFirstResearch` and `AfRender`. Changes: the approval now carries the exact payload, its hash, the prompt version and the ids of the earlier turns it includes, and `run` refuses when they no longer agree (the experiment rebuilt the payload from the thread at run time); the spend check uses the **high** estimate of the next call (the experiment used the low end), and a continuation is estimated from what the previous call measured; answers are stored as provider segments so uncited text can be shown as such; turns are saved through Room instead of JSON files; cancellation, refusal and failure notes no longer contradict kept partial text.

**Removed (with their tests):** `InvestigationService`, `InvestigationParser` (claim grammar), `InvestigationPrompt` (18.8 kB contract), `ObjectivePreparer` (regex objective classification), `ClaimAssessor` (independence/freshness/scope heuristics), `ResultDeriver`, `AnswerGrounding` and `EvidenceText` (sentence-level lexical grounding and rewriting), `AnswerComposer`, `WordingCheck`, `TurnAssembler`/inheritance planning, `AuthorizationBuilder`, `ResearchPresets` (depth presets and per-stage search shares), the M4 parts of `ContextAllowList` (`InvestigationTurnBuilder`, payload DTOs, warnings), the M4 thread UI's claims/status/evidence machinery, the M4 debug fixtures, 24 M4 unit-test files, the M4 thread UI test, `LiveInvestigationEval` and the A/B harness `LiveAbEval`.

**Kept as frozen legacy format only:** `InvestigationModels.kt` (data classes needed to decode stored M4 turns; the derived `independentOrigins`/`currentClaims` helpers were removed), `LegacyInvestigations.kt`, `InvestigationProposalBridge.kt` (legacy proposals; its status wording is now a local copy of the old labels), `TurnConfig`/`FindingDisposition`, `ResearchDepth` (moved into the models file).

**Size:** `app/src/main` −4,454 / +1,865 lines across 35 files. The new research core (`ai/aifirst`, 4 files) is 1,083 lines, 844 non-blank non-comment, including orchestration, the clarifier and suggestions; the M4 core it replaces was 4,323 lines in `ai/investigation` alone plus its service wiring.

**Simplified UI:** the card is question (+ clarification), "How it researches" (model · searches · limit · effort, adjustable), an honestly labelled estimate, context switches, earlier answers of this thread (if any), "See exactly what's sent". The running view shows searches, calls, elapsed time and measured spend so far, with Stop (keeps what arrived). Each turn shows the answer, a legend for uncited text, unverified links, numbered sources (tap → title, host, the provider's cited excerpts, search-index age, URL, open in browser), task suggestions, copy, and "How this was researched" (approved config, prior estimate, measured calls/searches/cost incl. the check, queries with result counts, approval time, fingerprint, what was sent). No depth presets, claim lists, status badges, evidence sheets or stage lists.

## 3. Safeguards: how each requirement is met

| Requirement | Mechanism | Test |
|---|---|---|
| Payload matches what was authorized | `AfAuthorization.payload` is sent byte-for-byte; `mismatch` re-derives it from the approved fields and named history turns and checks hash and prompt version; `start` also requires the same question and clarification and that every context line is still on offer with the same value | `AiFirstResearchTest.theCallSendsExactlyTheApprovedPayloadAndSettings`, `aTamperedApprovalIsRefusedWithoutSending`; `ResearchOnlineServiceTest.startRefusesAnApprovalThatNoLongerMatchesTheQuestionOrTheTask`, `onlySwitchedOnContextIsSentAndTheTurnRecordsTheApproval`; UI `taskLinkedResearch…` |
| No run from constructing an authorization | `authorize`/`preview` are pure; only `start` launches | `buildingTheCardSendsNothingAndSavesNothing`, `askingRunsOnlyTheNoSearchCheckAndTheCardSendsNothing` |
| Clarification without web research | `AfClarifier` sends `maxSearches = 0` on Haiku with only the question (and this thread's earlier questions); a CLARIFYING turn has no card and cannot start | `SmallCallsTest`, `anAmbiguousQuestionIsClarifiedBeforeAnyResearch`, UI `ambiguousQuestionIsClarifiedBeforeAnyWebSearch`, emulator `t3` |
| Credentials | Unchanged `ApiKeyStore`; the key only authenticates calls; never in payloads, turns, backups or the live harness report (redacted, asserted) | existing `ApiKeySecurityTest` |
| Search limits | `max_uses` = remaining budget on every call; `pause_turn` continued only while budget remains (max 4 calls) | `pauseTurnContinuesWithTheRemainingBudgetOnly`, `pausedWithNoSearchesLeftStopsAndSaysSo` |
| Every billed call accounted | A ledger row is written before each call and priced after it from measured usage: complete, truncated, stopped (usage unknown), failed (searches seen as a floor) or rejected-before-processing ($0 assumed); the check and suggestion calls too | `everyCallIsLedgeredFromMeasuredUsageIncludingFailures`, `stoppingKeepsWhatArrived…`, `SmallCallsTest` |
| Honest estimates; no silent overrun | The card estimate is labelled "if all N searches are used and the answer runs long … not a guaranteed maximum"; before every call the spend so far plus the **high** estimate of that call must fit the limit, else nothing more is sent; a limit below one call disables Start | `theSpendingLimitIsCheckedAgainstAHighEstimateBeforeEveryCall` |
| Unavoidable overrun disclosed | Card, README and this report: a call already in progress cannot be cut short and can cost more than estimated | — |
| Save before sending, checkpoint after calls | `start` saves RUNNING with the approval; `run` saves after every call | `theTurnIsSavedBeforeSendingAndCheckpointedAfterEveryCall` |
| Partial results on failure, stop, interruption | Failure and stop keep the segments received; recovery keeps the last checkpoint | `everyCallIsLedgered…`, `stopKeepsThePartialAnswer…` (service, UI, emulator `t4`), `aRunInterruptedByProcessDeathIsRecoveredWithoutResending` |
| Context confined to the thread | History = this thread's answered turns only, frozen by id at approval; context is approved per turn, never inherited | `aFollowUpCarriesOnlyItsOwnThreadsApprovedHistory`, `aFollowUpCarriesThisThreadOnly` |
| Real citations only | Sources come only from `web_search_result_location` citations; model-written URLs that no search supplied are listed as unverified, never as sources | `citationsAreTheProvidersAndUnsuppliedLinksAreFlagged` |
| Task review safety | Suggestions only on request, after showing the exact payload; result is an OPEN `ProposalSet` with nothing pre-selected; applying happens only in the existing review; idempotent per turn | `SmallCallsTest.suggestionsBecomeAReviewOnlyProposalSet`, `taskSuggestionsAreReviewedNeverApplied`, UI and emulator `t1` |
| Legacy readability | Separate read path, read-only view, backup round trip of both formats, recovery of legacy running turns | `AfThreadRepositoryTest` (3), `legacyResearchIsReadOnlyAndUnknownToTheNewStore`, UI `legacyResearchOpensReadOnly`, existing `MigrationTest`, `BackupRepositoryTest`, `InvestigationRepositoryTest` |

Display: Claude's words are not rewritten. `AnswerLayout` only splits the provider's segments into headings, bullets, tables and paragraphs and strips `**` markers (`AnswerLayoutTest.everyWordOfTheAnswerIsShown`). Text whose provider block carried a citation is ink with `[n]` marks; text without one is a quieter ink with the legend "no source citation attached: Claude's own wording, inference or judgment, not checked against a page". A cited block is not proof that every word in it is supported (the provider's cited excerpts are ~150 characters); the source sheet says so.

## 4. Validation results

**Baseline before changes** (experiment head `546bdd1`, this session): `./gradlew lintDebug testDebugUnitTest` → 706 tests, 705 passed; the one failure (`MainActivitySmokeTest`) was environmental — Robolectric could not download an SDK jar because Maven Central rate-limited this container (HTTP 429). With a local Gradle init script pointing Maven Central and Robolectric at Google's Maven Central mirror (not committed), it passed.

**After changes, local** (same mirror setup; JDK 21, Android SDK 36 installed in the container):

```
./gradlew --stacktrace lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
BUILD SUCCESSFUL in 10m 18s
```

- Lint: no issues.
- Unit + Robolectric: **557 tests in 78 classes, 0 failures, 0 errors, 0 skipped.** The drop from 706 is 186 retired M4 tests offset by 37 new ones: `AiFirstResearchTest` 12, `ResearchOnlineServiceTest` 10, `SmallCallsTest` 5, `AfThreadRepositoryTest` 3, `AnswerLayoutTest` 3, `ResearchOnlineUiTest` 4 (Robolectric on the real app shell).
- After two small follow-up fixes (trimmed context comparison in `start`; sheet-closing side effects moved out of composition) the affected suites, `lintDebug` and `compileDebugAndroidTestKotlin` were re-run: passing.
- APKs built: `app-debug.apk` (30 MB) and `app-release.apk` (4.5 MB).
- Emulator instrumentation could not run locally (no KVM). It runs in CI: the `instrumented` job runs every androidTest class except the two on-demand screenshot tours, which includes the rewritten `ScreenshotTourInvestigation` (t1 task-linked flow to the review, t2 follow-up, t3 clarification before any search, t4 stop keeping the partial answer).

**CI:** see §8 (filled in from the run on the pushed head).

## 5. Live validation plan (not run; needs your authorization)

Harness: `app/src/test/java/com/lifeadmin/autopilot/liveeval/LiveResearchEval.kt`. It drives the production `ResearchOnlineService` (clarifier → card → start → follow-up) against Anthropic with Sonnet 5.5 and default effort. Excluded from every normal and CI run.

```
export LIVE_EVAL_ANTHROPIC_API_KEY=…    # never committed or logged
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalScenario=V1-fact \
    --tests 'com.lifeadmin.autopilot.liveeval.LiveResearchEval'
```

Guards: free Models API preflight; one run per scenario unless `-PliveEvalRerun=true`; each turn's approved estimate must sit under the scenario's limit; total measured spend of all validation runs plus this run's limits must stay under $15. Reports (`result.json`, `answer.txt`, redacted) go to `app/build/live-eval-research/` and are not committed.

| Id | Question | Budget / limit | What to judge |
|---|---|---|---|
| V1-fact | Street address of the British Library in London | 3 / $1 | Correct, cited to an authoritative page, no unrequested extras (prompt line 1) |
| V2-ambiguous | "What's the address of Union Station?" | 3 / $1 | Must ask which city **before any search**; the run stops at the clarification with only the Haiku check billed |
| V3-health | Vitamin D and fractures in healthy adults over 50 | 6 / $2 | Evidence strength and population stated, null/negative trials not overstated, no individual advice, inference marked |
| V4-technical | SQLite vs Postgres for a 50-user internal web app | 6 / $2 | Objective kept as asked (no invented constraint), judgment labelled with assumptions and flip conditions, cited premises |
| V5-follow-up | Robot vacuum, then: two cats, long-pile rug, budget now under $300 | 6 / $2 per turn | Follow-up honours the changed constraints, says what changed and why, re-searches only what's new; prices qualified as index results |

Expected spend: roughly $0.05–0.25 per research turn from the experiment's measurements, about $1–2 for all five; the guards cap it at $15. Judge by usefulness, factual grounding (against the provider's cited text and the pages), honest uncertainty and preserved user control, applying Document F's principles without editing Document F. Report failures as found; do not start prompt-tuning cycles from them.

## 6. Remaining risks

1. **Estimates are deliberately high and uncalibrated.** They assume every search is used (~11k input tokens each, a calibration guess) and the full 16k output allowance; the experiment's measured mean was ~$0.12 per turn against card estimates around $0.30–0.45 on Sonnet. A limit of $0.25 can therefore block a run that would have cost less. Calibrate from the ledger after real runs.
2. **A single call can exceed its estimate** (long pages, many searches inside one call). It cannot be interrupted mid-call; the limit only stops the *next* call. Disclosed on the card.
3. **Clarifier quality is unmeasured live.** Haiku may ask too often or miss an ambiguity; it fails open (a failed check shows "wasn't checked" and lets you decide). V2 exercises one case only.
4. **Follow-ups resend history as text** (earlier questions, answers and source lists). Long threads cost more per turn and earlier evidence is re-read through Claude's own summary. There is no history cap beyond the per-question limit.
5. **"Cited" is per provider text block**, not per claim; cited excerpts are ~150 characters. The app does not verify that a cited sentence is fully supported, and says so.
6. **Minimal Markdown rendering:** headings, bullets, numbered lists, tables, bold and rules. Nested lists, code blocks and inline links render as plain text (the words are never dropped).
7. **Legacy research is read-only.** M4 and M3A.2 threads cannot be continued; a new question starts a new thread. M4 turns that were never started show as "Not started".
8. **Downgrade / cross-build backups.** Installing the PR #8 (M4) build over this one would show AI-first threads with no readable turns (not deleted). A backup containing AI-first research is rejected by the M4 build's importer (it validates every turn in its own format); older `main` builds reject v7 backups anyway.
9. **Settings:** the research defaults reuse existing keys (Standard model override → default model, default searches, spending limit from AI usage; effort). There is no new settings screen; per-run choices are not saved as defaults.
10. **Effort support** is the app's reading of the documentation (Opus, Sonnet, Fable), not a live capability check. The web search tool stays `web_search_20250305` (no dynamic filtering), as in the experiment.
11. **Pricing table** lists Sonnet 5.5 cache reads at $0.10/MTok where current documentation says $0.20 (noted by the experiment). The AI-first path sends no cache control, so it is not affected; planning totals are.
12. Robolectric UI tests need explicit looper pumping (as did the M4 suite); the new UI test drains pending view-model work before closing its database.

## 7. Manual validation on your Android device

Install the debug APK from the CI run's `apks` artifact (signed with your personal key when the `AUTOPILOT_KEYSTORE_B64` secret is set, so it updates the installed app in place; otherwise uninstalling first would erase local data — check before installing). Make a backup first: Settings → Export.

1. **Upgrade and legacy.** Open Research (top-bar icon). Research saved by the M4 build shows "· earlier format"; open one: banner "Saved with the earlier research engine", the answer as stored, "Recorded status: …", sources, no follow-up box. A task with M3A.2 research still opens its saved result.
2. **Straight fact.** New research → "What is the street address of the British Library in London?" → Continue. Expect "Checking the question…", then the card with "Checked first: nothing needs clarifying" and the check's cost. Tap "See exactly what's sent" and read it. Start. Watch searches/calls/time; the answer arrives with `[n]` marks and a source list. Tap a source: cited excerpt, index age, URL, Open in browser.
3. **Ambiguity.** New research → "What's the address of Union Station?" → expect a question with city options and no card. In Settings → Plan with AI → AI usage, only a small "Research · understanding the question" call appears. Pick a city; the card shows "You clarified: …" and the payload contains it.
4. **Context and limits.** From a task with notes and steps: Research online → ask something → switch Task notes off and confirm the payload no longer contains them. Open Adjust: change model, effort, searches, limit; the estimate and payload sheet update. Set the limit to $0.25 with 10 searches on Opus: the "limit is below one research call" warning appears and Start is disabled.
5. **Stop and interruption.** Start a broader question (6+ searches); tap Stop → "Stop and keep what arrived". Whatever arrived stays with a "Stopped" banner. Start another and swipe the app away mid-run; reopen: the turn shows "Interrupted", partial text kept, and AI usage shows the in-flight call as usage unknown. Nothing restarts.
6. **Leave and return.** Start a run, go back to Today, then reopen the task or Research: the run continues (task detail shows "Researching now…").
7. **Follow-up.** On a finished thread, ask a follow-up that changes a constraint. The card lists "This thread's earlier answers"; the answer should address the change.
8. **Task suggestions.** On a task-linked answer → "Suggest changes to the task…" → read the exact payload → confirm → the Proposal Review opens with suggestions, none pre-selected. Apply one, check the task, then Undo. Nothing changes on the task unless you apply.
9. **Offline.** Airplane mode: every saved thread opens; Continue/Start show the offline banner.
10. **Costs.** Compare AI usage totals for these runs with the Anthropic Console.

## 8. CI on the pushed head

To be completed from the GitHub Actions run of the pushed head (lint, unit, APKs, instrumented on the API 34 emulator).
