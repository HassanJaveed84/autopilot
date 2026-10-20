# M3 progress log (consented web research)

Branch `m3-consented-research`, created from `flight-plan-stabilization` at **90151a90c0781ee2329ff3dc80e1fe4278ef4f84**
(PR #2's head at the time; it carries every stabilization fix). Stacked draft PR targets
`flight-plan-stabilization`. Nothing here is merged by the agent.

Design inputs: `Life Admin Autopilot — Redesign(1).html` (Claude Design export; boards: prototype,
concepts, 30-state gallery, components, spec), `M3_DESIGN_INTEGRATION_HANDOFF.md`,
`Life_Admin_Autopilot_M3_Overnight_Claude_Code.md`. Approved UX: **D · Action Brief**.

## Baseline (Phase A)

- The base commit is the head of PR #2; its code was verified green by CI run 39383228842
  (lint clean, 342 unit tests, 8 emulator tests) and screenshot run 39383190839 (4 variants, 82 screens
  each, nothing skipped). The only commits after that run on the branch are documentation.
- SDK: `com.anthropic:anthropic-java` 2.73.0 ships `WebSearchTool20250305` (also 20260209 / 20260318),
  `ServerToolUseBlock`, `WebSearchToolResultBlock` (results or `WebSearchToolResultError`),
  `TextBlock.citations()` → `CitationsWebSearchResultLocation { url, title?, citedText, encryptedIndex }`,
  `Usage.serverToolUse().webSearchRequests`, `StopReason.PAUSE_TURN`. Web search is requested with
  `MessageCreateParams.Builder.addTool(WebSearchTool20250305)` and `maxUses`.
- Design contract for the implementation (from the spec board, applied to the real provider):
  - Only structured citations become sources. URLs the model writes in prose are stripped; the
    sentence is labelled a suggestion.
  - Caps: `max_uses = 3` per request, 10 research requests per local day (separate from M2's 60),
    one continuation after `pause_turn` within the same 3-search budget.
  - Scope: question + task title always; notes and date/language on by default; steps off. Never:
    other tasks, Inbox, history, diagnostics, contacts, precise location, the API key (sent only as
    the HTTP credential). `user_location` is not used.
  - No streaming in M3A: stages are shown as Sent → Searching (indeterminate) → Writing and the
    queries/sources come from the final response. No invented counters.

## Phase log

- **A** (done, this file): branch, baseline, design contract.
- **B** (done, commit `2796486`): `ai/research` models + caps, `ResearchPrompt`, `BriefParser`
  (provenance only from structured citations, prose URLs stripped, conservative source tiers,
  injection lines dropped), `ai/anthropic/AnthropicResearchProvider` (web_search_20250305 with
  `max_uses`, no `user_location`, one `pause_turn` continuation inside the budget, citations and
  search results from content blocks), shared `AnthropicErrors`, `ResearchService` (gating, explainer
  version, daily cap reserved before sending, single app-scoped run, Stop), `ResearchRepository` +
  Room v4 `research_briefs` (migration 3→4). Tests: `BriefParserTest`, `AnthropicResearchProviderHttpTest`
  (MockWebServer), `ResearchServiceTest`, `ResearchRepositoryTest`, `MigrationTest.migrateV3ToV4…`.
- **C** (done, commit `35428bc`): briefs in backup export/import (typed content, no transcripts or
  keys; orphan/malformed briefs reject the import), `ResearchActions` (selective transactional apply
  via `TaskRepository.applyTaskEdit`, dedupe, idempotent proposal id per selection, conflict on a
  changed task, Undo only while the task is exactly as the apply left it). Tests: `ResearchActionsTest`,
  `BackupRepositoryTest.researchBriefsRoundTrip…`.
- **D** (done, commit `0e86390`): native Compose screens in `ui/research` (preflight + explainer,
  progress, brief + source sheet, apply, history), task-detail tiles + research card, AI settings
  "Web research" section, routes. Test: `ResearchFlowUiTest` (5 end-to-end Robolectric scenarios).
- **E** (commits `07936ec` … `02b01ad`): `ScreenshotTourResearch` (23 screens, scripted provider,
  `.example` sources), screenshot request (light/dark 1.0, light 1.3, light 2.0),
  `docs/M3_RESEARCH_REPORT.md`, README, architecture and test matrix updates, `docs/screenshots/m3`,
  draft PR #4 (base `flight-plan-stabilization`). Final head `02b01ad`: Android CI green (397 unit
  tests, 14 instrumented, lint clean, signed APKs), screenshots 4/4 green. Tour fixes along the way:
  wait for the task *list* after returning (it keeps its scroll), scroll to the operator field at
  large text, record uncaught exceptions in the artifact; one unexplained Compose teardown crash at
  font 2.0 on the emulator, not reproduced (see the report).

## Deliberate scope notes

- Follow-up questions ("Ask · up to 3 more searches" extending the brief) are **not** built in M3A;
  "Research again with this question" opens a fresh preflight instead. The brief format leaves the
  section order intact for a later follow-up section.
- Source tiers ship with the conservative rule set from the spec's open questions: *Official* only for
  recognized government suffixes or a host containing the operator name the user typed; everything
  else is *Web page*. No *Secondary*/*Community* claims.
- Deadlines: research never proposes a deadline; anything date-shaped arrives as a CHECK move.
- Progress stages are not streamed; see the report for what the UI does and does not claim.
