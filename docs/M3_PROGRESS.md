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

- **A** (done): branch, baseline, this file.
- **B** (in progress): domain models, prompt, brief parser, Anthropic research provider, service.
