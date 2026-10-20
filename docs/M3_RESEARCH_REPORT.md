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
| Source tiers and link safety | `ai/research/Sources.kt` | *Official* only for recognized government suffixes or a host that contains the operator/service the user typed (≥ 4 letters); else *Web page*. `safeExternalUrl`: http/https, public host, no userinfo, no IP literals / localhost. |
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
- Source tiers: *Official* / *Web page* only; no *Secondary* / *Community* claims.
- The daily cap is per device (settings), not per key.
- Process death during a run: the run is never resumed; the progress screen shows "Research was
  interrupted. Nothing saved; your question is kept" with Research again.

## Delivery

Filled in after push and CI (see the end of this file).
