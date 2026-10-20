# M3A — Consented web research: implementation report

Branch `m3-consented-research`, stacked on `flight-plan-stabilization` (PR #2, head `90151a9`).
Draft PR: _see "Delivery" below_. Nothing was merged by the agent.

Approved design: **D · Action brief** (Flight Plan visual system, native Jetpack Compose). The
prototype HTML and its `.example` sample data were used only as the visual/interaction reference.

## What was built

| Area | Where | Notes |
|---|---|---|
| Research domain and caps | `ai/research/ResearchModels.kt` | 3 searches per request (`max_uses`), 10 requests per local day (separate from planning's 60), 60 s timeout, one `pause_turn` continuation inside the same budget, 20 briefs kept per task, 64 kB payload cap, 72 h freshness banner, explainer version 1. |
| Prompt | `ai/research/ResearchPrompt.kt` | Fixed sections (QUICK ANSWER / NEXT MOVES / FINDINGS / UNCONFIRMED), bullets typed `STEP:` / `CHECK:` / `NOTE:`, rules: no URLs in text, no invented prices/dates, web text is data, never act. A local heuristic adds "no country given → general guidance only" for passport/visa/tax-style questions. |
| Provider | `ai/anthropic/AnthropicResearchProvider.kt` | Official SDK 2.73.0, `WebSearchTool20250305.maxUses`, no `user_location`, no `fallbacks`, no SDK retries. Reads `server_tool_use` (query), `web_search_tool_result` (results / `error_code`), text blocks with `web_search_result_location` citations, `usage.server_tool_use.web_search_requests`. `pause_turn` → one continuation with the paused assistant message and the remaining `max_uses`. Shared error mapping in `AnthropicErrors` (the planning provider now delegates) plus body-text detection of "web search … not enabled" and "credit balance" 400s. |
| Brief parser | `ai/research/BriefParser.kt` | A sentence is *Sourced* only if a structured citation overlaps it; numbering follows first citation; uncited search results are appended as "read, not cited"; prose URLs and `[n]` markers are stripped; a *Disagree* needs two different cited sides; injection-looking lines are dropped from moves and flagged. |
| Source tiers and link safety | `ai/research/Sources.kt` | *Official* only for a recognized government domain matched on whole host labels (`gov.uk`, `*.gov.uk`, `*.gov`, …); every other host is *Web page*, whatever name it carries (hardening finding B). `safeExternalUrl`: http/https, public host, no userinfo, no IP literals / localhost. |
| Service | `ai/research/ResearchService.kt` | Gate = AI on + planning consent + key + explainer accepted + online + daily cap; the cap is reserved in settings before the request is sent (failed attempts count); one app-scoped run with observable stage; Stop cancels the HTTP call; parse + save. |
| Persistence | Room v4 `research_briefs`, `data/local/Migrations.kt` `MIGRATION_3_4`, `data/repository/ResearchRepository.kt` | Parsed brief + applied moves as JSON; cascade on task delete; prune to 20 per task; oversized payloads trimmed (uncited sources, then findings); unreadable rows hidden instead of crashing. Schema exported to `app/schemas/…/4.json`. |
| Backup | `data/repository/BackupRepository.kt` | New `research` list (typed `BriefContent`, applied moves; never transcripts or the key). Orphan or malformed briefs reject the whole import; re-import is idempotent by id. Backup `schemaVersion` stays 1 so older builds still import (they ignore the field). |
| Apply / Undo | `ai/research/ResearchActions.kt`, `TaskRepository.undoAppliedEdit` | Selected moves → one `TaskEditCommand` (`addSteps`, `notes` append under "From web research:") through `applyTaskEdit` (transaction, `expectedUpdatedAt`, proposal id `research:<brief>:<selection hash>` so a selection applies at most once). CHECK moves become "Check: …" steps; duplicates of existing steps are skipped; deadlines, reminders, status and priority are never touched. Undo only while `updatedAt` is exactly what the apply wrote, steps still exist unticked and notes are unchanged. |
| UI | `ui/research/*`, `ui/detail/TaskDetailScreen.kt`, `ui/ai/AiSettingsScreen.kt`, `ui/navigation/Routes.kt`, `ui/LifeAdminRoot.kt` | Two "Move this forward" tiles; Research card (running / latest / history); preflight with scope switches, typed details, jurisdiction hint, cost card `RESEARCH TODAY n OF 10 · MODEL …`, "Nothing sent yet" pill and blockers; one-time explainer sheet; progress (Sent → Searching → Writing, indeterminate, Stop); result states (headline, explanation, numbered fixes where useful, the kept question, mono technical + billing line); brief (question, stamp, banners, quick answer with inline citation chips, legend, next moves, findings by provenance, sources with evidence badge, research log); source sheet (quote, dates, URL, Copy link, Open in browser via `ACTION_VIEW`); Add to your task (rows, skipped list, conflict banner, Apply, Undo); history with delete; AI settings "Web research" (usage, Delete all research). |

Routes: `task/{taskId}/research`, `…/research/new?q=`, `…/research/run/{runId}?q=`,
`…/research/{briefId}`, `…/research/{briefId}/apply?sel=`.

## Exact API behaviour (as implemented and tested against MockWebServer)

- Request: `POST /v1/messages` with `model`, `max_tokens = 4000`, `system`, one user message,
  `tools = [{type: "web_search_20250305", name: "web_search", max_uses: 3}]`. No `user_location`,
  no `anthropic-beta` header, no `output_config` (the brief is plain text so citations stay attached).
- Continuation after `stop_reason = pause_turn`: the same request plus the paused assistant
  `Message` appended via `addMessage`, with `max_uses = 3 − searches already run`; at most once; if
  the budget is spent the paused response is used as-is (no text → "cut off before any answer").
- Citations: only `TextBlock.citations[*].web_search_result_location { url, title, cited_text }`
  become sources. `WebSearchResultBlock { url, title, page_age }` from `web_search_tool_result`
  join the source list uncited (so "read, not cited" is visible). `encrypted_content` /
  `encrypted_index` are never stored.
- Usage: `input_tokens` + `output_tokens` summed across the continuation; searches =
  `max(usage.server_tool_use.web_search_requests, server_tool_use blocks seen)`.
- Errors (one HTTP request per attempt, never retried by the SDK or the app):
  400 with "web search … not enabled" → *Web search isn't enabled for this key*; 400 with
  "credit balance" → *Your Anthropic account needs credit*; other 400/404/422 → *The request was
  rejected*; 401 → *Your API key was rejected*; 402/403 → *This key can't do that*; 429 (+
  `retry-after`) → *Anthropic is busy*; 5xx/529 → *Research didn't finish*; client timeout (60 s +
  5 s slack) → *Research didn't finish*; DNS/connect → *Couldn't reach Anthropic*;
  `stop_reason = refusal` → *Claude couldn't research this*; `max_tokens` → brief saved and marked
  *cut short*; a `web_search_tool_result_error` with no answer text → *Web search failed (code)*;
  no citations at all → brief saved as *Unverified* with every finding labelled suggestion; user
  Stop → *Research stopped* with "n searches had already run and may be billed" (or "any searches
  that had already run may be billed" when the count is unknown).
- Progress without streaming: `Sent` on dispatch; `Searching(n)` + `Continuing` only when a
  `pause_turn` arrives (n = searches in the paused turn); `Writing` before parsing. The counters on
  the progress screen show "—" until the answer arrives; the app never invents intermediate counts.

## Tests

All tests run with fakes or a local MockWebServer. **No real API key and no paid request was used
by the agent or in CI.**

| Suite | Tests | Result |
|---|---|---|
| `BriefParserTest` (JVM) | 10 | ✅ |
| `AnthropicResearchProviderHttpTest` (MockWebServer) | 13 | ✅ |
| `ResearchServiceTest` (Robolectric, Room) | 10 | ✅ |
| `ResearchRepositoryTest` | 7 | ✅ |
| `ResearchActionsTest` | 6 | ✅ |
| `MigrationTest.migrateV3ToV4AddsResearchBriefsAndKeepsData` | 1 | ✅ |
| `BackupRepositoryTest.researchBriefsRoundTripAndBadOnesRejectTheWholeImport` | 1 | ✅ |
| `ResearchFlowUiTest` (Robolectric UI, real shell + fake provider) | 5 | ✅ |
| Full unit/Robolectric suite (`./gradlew lintDebug testDebugUnitTest`, local, final commit) | 395 (0 skipped) | ✅ 395 passed, lint 0 errors / 0 warnings |
| `ScreenshotTourResearch` (emulator, 6 tests / 23 screens) | CI screenshot workflow | _see Delivery_ |

Local commands run on the final commit (JDK 21, AGP 8.13.2):

```
./gradlew -q lintDebug testDebugUnitTest          # 395 tests, 0 failures; lint 0 errors, 0 warnings
./gradlew -q compileDebugKotlin compileDebugAndroidTestKotlin   # incl. ScreenshotTourResearch
```

The emulator-only parts (`ScreenshotTourResearch`, the existing instrumented suite) run in GitHub
Actions; their results are recorded under Delivery.

Acceptance scenarios from the handoff:

1. **Train ride** — `ResearchFlowUiTest.researchEndToEnd_explainerBriefSourcesApplyUndo`: start
   authorized (explainer first, nothing sent before acceptance), inspect source 1 in the sheet,
   review two pre-selected sourced moves (CHECK not pre-selected, duplicate step shown as skipped),
   apply once (no reminder created), undo, reopen the brief from the task card;
   `aiOffKeepsSavedBriefReadableAndOffline` reopens a saved brief with AI off and offline.
2. **Passport without jurisdiction** — `ResearchPrompt.looksJurisdictionBound` + preflight hint
   (`preflight_gap`) and the system prompt's "general guidance only" rule; `BriefParserTest` does not
   invent country-specific moves (moves come only from the model's NEXT MOVES section).
3. **Malicious page text** — `BriefParserTest.injectionLikeTextIsDroppedFromMovesAndFlagged`; the
   brief shows "A web page tried to give instructions"; no permission or scope can be changed by
   content (the parser never writes settings or tasks).
4. **Invented/unstructured URLs** — `BriefParserTest.proseUrlsAreStrippedAndNeverBecomeSources`,
   `uncitedResponseIsUnverifiedAndEverythingIsASuggestion`, `unsafeCitationUrlsAreIgnored`.
5. **AI off / airplane mode / cancel / credit / 429 / unsupported tool** — `ResearchServiceTest`,
   `AnthropicResearchProviderHttpTest.maps…`, `ResearchFlowUiTest.providerFailureKeepsTheQuestion…`,
   `stopAbortsTheRun…`, `offlinePreflightBlocksStart…`: question kept, task unchanged, one request
   per attempt.
6. **Upgrade from the installed app** — `MigrationTest.migrateV3ToV4…` (Room 3→4 with data), backup
   round trip, and the personal signing key already wired into CI (same key as the installed APK).
   Reminders, receivers, alarms, notifications, AI planning and the key store are untouched by this
   change set (no edits under `reminders/`, `notifications/`, `ai/security/`).

## Guardrails checked

- Only `ai/anthropic` imports the SDK (`ArchitectureBoundaryTest` still passes).
- The API key is read from the encrypted store per request and lives only in the SDK client; it is
  never in the prompt, logs, briefs, backups or screenshots. The explainer and preflight say that the
  key authenticates the request with Anthropic (not "never sent").
- No new permissions, receivers, workers or startup calls. Research runs only from a tap on Start.
- Web text is data: injection-looking lines never become moves; links open through `ACTION_VIEW`
  after `safeExternalUrl`; never a WebView.
- Research never writes reminders, status, priority or deadlines (`TaskEditCommand` carries only
  `addSteps` and `notes`); a changed task rejects the apply; the same selection applies once.
- Copy says "may be billed", shows counts only (requests, searches, tokens), never dollar estimates.

## Known limits and honest notes

- **Live BYOK verification is pending.** The adapter follows the SDK's types and the documented
  web-search response shape, but it has not been run against the real API with a real key. First
  live run to check: that `pause_turn` continuation is accepted with the paused `Message` appended
  as-is; that `web_search_result_location` citations arrive for Haiku 5.5 (the default); the exact
  wording of the "web search not enabled" and credit-balance 400 bodies (the app falls back to the
  generic *request was rejected* state if they differ).
- No streaming: the Searching stage is indeterminate. Queries and sources appear when the answer
  arrives. "Locking your phone stops research" is **not** promised; the copy says leaving the app
  does not stop a request already sent.
- Follow-up questions that extend an existing brief are not implemented (P2). "Research again"
  opens a new preflight with the question.
- Source tiers: *Official* (government domains only) / *Web page*; no *Secondary* / *Community*
  claims, and no ownership inferred from names in a host.
- The daily cap is per device (settings), not per key.
- Process death during a run: the run is never resumed; the progress screen shows "Research was
  interrupted. Nothing saved; your question is kept" with Research again.
- One emulator run of the screenshot tour at font 2.0 crashed in Compose's slot table during the
  test activity's teardown (see Delivery). Not reproduced on re-run or under Robolectric; cause
  unknown; the tour now records uncaught exceptions into its artifact.

## Post-implementation hardening (review findings)

PR #4 was retargeted from `flight-plan-stabilization` to **`main`** after #2 merged; `main` is
exactly the old base commit (`90151a9`), so the PR diff is unchanged and there was nothing to
resolve. Each finding below was first reproduced (or refuted) with a test before any code changed.

| Finding | Verified as | Root cause | Fix | Tests |
|---|---|---|---|---|
| **A** — citation accounting | Partly. Answer-only citations were *already* classified as verified (the per-line pass counted every section). Real defects: a cited block spanning two lines counted twice, and a DISAGREE line counted each side again. | `citedCount` was incremented per *line* overlapping a cited block (and again for disagreement sides) instead of per cited block. | `BriefParser.SourceRegistry` now counts once per cited text block in a pre-pass over the provider's segments (`countBlock`); `refsFor` only resolves. Numbering, quotes and the "read, not cited" results are unchanged; prose URLs still never register. | `CitationAccountingTest` (answer-only, no headings, across sections, block spanning lines, disagreement sides, duplicate URL in one block, wholly uncited) + existing `BriefParserTest` |
| **B** — unsafe *Official* | Confirmed: a host containing the typed operator name (≥ 4 letters) was labelled Official. | Name-in-host heuristic. | `Sources.tier(url)` labels *Official* only for recognized government suffixes matched on label boundaries (`gov.uk`/`*.gov.uk`; single-label `gov`/`mil` need a preceding label); IP literals and everything else are *Web page*. The operator detail no longer influences tiering; source-sheet copy says ownership is never inferred from a name. URL safety unchanged. | `SourcesTest` (government sites, suffix smuggled into subdomains, hyphen look-alikes, no label boundary, userinfo host, brand names), `BriefParserTest.sourceTieringIsConservative` updated |
| **C** — edit and bookkeeping not atomic | Confirmed: `applyTaskEdit` committed, then `recordApplied` ran as a second write (same for undo). | Two transactions. | `TaskRepository.applyTaskEdit(cmd, inTransaction)` runs a caller hook inside the same Room transaction with the new step ids; `undoAppliedEdit(…, inTransaction)` likewise. `ResearchActions` records/clears applied moves inside those hooks, so a failure anywhere rolls back steps, notes, history event and record together; any non-validation exception becomes a `Rejected` outcome with the task unchanged. Proposal-id and `updatedAt` guards are untouched; the apply view model ignores a second tap while one is in flight. | `ResearchActionsTest`: `bookkeepingFailureRollsBackTheTaskEditAndARetrySucceedsOnce`, `undoBookkeepingFailureRollsBackTheUndo`, `undoNeverOverwritesALaterUserEdit`, plus the existing idempotency / changed-task / done-task tests |
| **D** — delete vs running request | Confirmed: `deleteAllResearch()` deleted briefs while the job kept running and would save afterwards. | No cancellation. | `ResearchService.deleteAllResearch()` and new `deleteResearchForTask(taskId)` cancel the running job (all, or that task's), `join()` it, then delete; the job checks `ensureActive()` before parsing/saving so an answer in hand is dropped; the run ends as *Stopped* ("searches already run may be billed"). History and Settings dialogs say a running request is stopped. | `ResearchServiceTest`: `deleteAllWhileSearchingStopsTheRunAndNothingComesBack`, `deleteForAnotherTaskLeavesTheRunAlone`, `deleteForThisTaskWhileRunningCancelsAndSavesNothing`, `deleteDuringTheSaveItselfWinsOverTheLateResult` |

Also from the focused review:
- API-key confidentiality: deleting or replacing the key now also drops the research adapter's
  cached HTTP client (`ResearchService.forgetCredentials()`), so the old key does not linger in memory
  until the process dies. (The planning adapter already did this.)
- Checked and left as is: `pause_turn` budget (continued once, `max_uses = remaining`, never below
  the cap), outbound payload = exactly the preflight's switches, Room 3→4 and backup round trip,
  Stop/offline/interrupted paths, no new permissions or background entry points.

## Screens

`docs/screenshots/m3/INDEX.md` lists the 23 research screens, light and dark at font 1.0 (captured on
the CI emulator from `ScreenshotTourResearch` with the scripted provider and fictional `.example`
sources). The font 1.3 and 2.0 sets are CI artifacts of the screenshot workflow (links under Delivery).
Checked by eye at 2.0: footers stack (primary above secondary), the details grid is one column,
nothing is clipped; the preflight header pill moves under the title at ≥ 1.3× (fixed after the first
2.0 capture showed "Research online" wrapping mid-word).

## Delivery

- Branch `m3-consented-research`, draft PR **#4** → `flight-plan-stabilization` (stacked on #2).
  Not merged. Commits: `2796486` (B), `35428bc` (C), `0e86390` (D), `07936ec` (E), then tour/doc
  fixes `825bf27`, `70aee28`, `63cf33a` and the final commit (this one).
- Android CI (`lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`
  + the instrumented suite on an API 34 emulator): result and APK artifact (`apks`, signed with the
  personal key when the repository secrets are present) are recorded in the PR checks for the final
  head; see "Final CI" below once complete.
- Screenshot workflow runs on this branch:
  - 39401292261 (`07936ec`): all four variants red — the tour waited for the task *title* after
    returning to a scrolled task screen (the lazy list keeps its position, so the title is not
    composed) and typed into the operator field without scrolling at 2.0. Fixed in `70aee28`.
  - 39402883317 (`70aee28`): light 1.0, dark 1.0, light 1.3 green (23 screens each, `OK (6 tests)`);
    light 2.0 crashed once *inside Compose's slot table while the test activity was being torn down*
    (`ArrayIndexOutOfBoundsException` in `SlotWriter.moveSlotGapTo` during `CompositionImpl.dispose`,
    reached from `Activity.performDestroy`), right after the progress screenshot. The crash swallowed
    whatever ended the test.
  - 39403993768 (`63cf33a`, 2.0 only, with an uncaught-exception recorder in the tour): green, 23
    screens, no exception recorded. The Robolectric flow at 2.0 (`ResearchFlowLargeTextUiTest`) also
    passes. The one-off crash could not be reproduced; the recorder stays in the tour so a recurrence
    leaves its cause in the artifact. It is listed under known limits as an unexplained single
    occurrence rather than claimed fixed.

### Final CI (head `02b01ad`)

| Run | What | Result |
|---|---|---|
| [39405082133](https://github.com/HassanJaveed84/autopilot/actions/runs/39405082133) (push) and [39405088322](https://github.com/HassanJaveed84/autopilot/actions/runs/39405088322) (pull_request) | Android CI: lint, **397 unit/Robolectric tests**, debug + release APKs, **14 instrumented tests** on an API 34 emulator | ✅ both green, 0 failures, lint 0 errors |
| APK artifact | `apks` on run 39405082133 (artifact 12256991526, ~32 MB: `app-debug.apk`, `app-release.apk`, `app-debug-androidTest.apk`) — the personal keystore secret was present, so the debug APK installs over the user's current build without a data wipe | ✅ |
| [39405082136](https://github.com/HassanJaveed84/autopilot/actions/runs/39405082136) | Screenshot tour `ScreenshotTourResearch`, 4 variants (light/dark 1.0, light 1.3, light 2.0), 23 screens each | ✅ 4/4 green, `OK (6 tests)` each; artifacts `screenshots-phone-*` |

The committed `docs/screenshots/m3` set comes from run 39402883317 (`70aee28`); the only UI change
after it is the preflight pill position at ≥ 1.3×, which does not affect the 1.0 set.

### Still pending (needs the user)

- Live BYOK run against the real API (see "Known limits"): a first real question on a task, then
  check the brief's citations, the usage line and the console's billing for one request.
- Install the signed debug APK from the `apks` artifact over the current build (upgrade path:
  Room 3→4 migration runs on first open; verify tasks, reminders, AI key and backups are intact).
- Decide on merging #2 and then #4; the agent merges nothing.
