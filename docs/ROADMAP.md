# Product Roadmap — Life Admin Autopilot

Updated: 2026-10-25

This is the living **product-direction** document. It records milestone status, priorities, scope, decisions, open questions and evidence. It does not authorize implementation. The technical baseline remains in `docs/SPEC.md`; historical engineering results remain in `docs/PROGRESS.md` and milestone-specific reports.

## Vision

**Capture a commitment → remember it reliably → recognize what deserves attention → research next steps → help complete the work, with explicit approvals.**

Autopilot is an Android-native, local-first follow-through assistant. Tasks and reminders should remain useful offline. AI is optional, transparent, privacy-conscious and bounded by user consent, cost controls and permission boundaries.

## Milestones

| Milestone | Scope | Status (2026-10-20) | Next gate |
| --- | --- | --- | --- |
| M0–M1C | Native Android app, local task lifecycle, reminders, notification actions, diagnostic and recovery tools | Implemented | Continue physical-device validation |
| M2 | Opt-in AI-assisted capture, review-before-write planning and per-task suggestions | Implemented | Live provider and phone validation separate from mock CI |
| Flight Plan UI | Native redesign and stabilization | Merged | Field usability/upgrade checks |
| **M3A** | **Action Brief: selected-task web research, real source attribution, saved research briefs, reviewed task updates** | **Merged into `main`** | [PR #4](../pull/4) (squash `4477ee6`); final automated CI green; user reports installing/testing latest APK; exact upgrade and paid live-API validation evidence tracked separately |
| **M3A.2** | **Deep Research & Actionable Results: flexible investigation depth, model selection, evidence coverage, exact requested deliverables, cost intelligence** | **Implemented on branch `m3a2-deep-research` — draft PR open, not merged** | Code, Robolectric and MockWebServer tests, CI and screenshots on the branch (`docs/M3A2_IMPLEMENTATION_REPORT.md`); live paid-API runs and phone install not performed by the agent |
| **Production UX (approved)** | **Plan with AI Living Draft, Research Online Investigation Thread, one Proposal Review, integration — per the frozen specs A–D/J and `UX-implementation-handoff.md` v1.1** | **Kickoff 2026-10-23: audit + plan in `docs/PRODUCTION_KICKOFF_AUDIT_AND_PLAN.md`; M1 foundations (`prod-ux-m1-foundations`, PR #6), M2 Proposal Review (`prod-ux-m2-proposal-review`, PR #7) and M4 Research Online Investigation Thread (`prod-ux-m4-investigation-thread`, PR #8) implemented as stacked draft PRs, none merged. 2026-10-24: Research Online core replaced by the AI-first engine (A/B experiment on `ccr-205def5b-4gv9qp`, integration on `ccr-e03bf7f8-hvk42n`, not merged; PR #8 frozen as the M4 baseline)** | Owner-approved order (G6): M2 canonical Proposal Review → M4 Investigation thread → M3 Living Draft → M5 integration/spending → M6 hardening (see the plan and `docs/DECISION_LOG_ADDENDA.md`) |
| **M3B** | **Autopilot Review: automatically review open tasks and detect overlooked obligations** | **Proposed — after the production UX milestones** | Focused design, privacy specification, phased implementation |
| M3C | Scoped calendar/email/service integrations and user-approved external actions | Proposed | Design permissions, OAuth, provider integrations and auditability |
| M3D | Multi-step agent workflows with per-action approval and verified outcomes | Proposed | Reliable execution, scope, idempotency and recovery |
| M4 | Optional household collaboration and sync | Deferred | Explicit approval and identity/sharing architecture |

**Coordination:** M3A landed in `main` as PR #4. M3A.2 was implemented at the user's explicit request on `m3a2-deep-research` (draft PR against `main`, not merged). The production UX implementation was kicked off at the user's explicit request on 2026-10-23 (frozen specs A–D/J + handoff v1.1 are the authority; design exploration is closed); M1 lives on `prod-ux-m1-foundations`. M3B remains proposed; no implementation is authorized by this document.

## M3A — Action Brief: consented task research (merged)

Implemented in [PR #4](https://github.com/HassanJaveed84/autopilot/pull/4): the user opens **one selected task**, specifies a research question and explicitly authorizes sharing the chosen context and incurring a bounded web-search API request.

The desired experience includes a concise quick answer, uncertain findings clearly labeled, credible structured citations and sources, actionable next moves, offline access to prior research, and **selective** task updates applied only with approval. Avoid fabricated source links and any claim that simulated prototype data is real research.

M3A does **not** scan all tasks, autonomously conduct paid searches, send messages, book tickets, purchase items or change reminders without approval. The hardened code landed via PR #4; reported CI included 415 unit/Robolectric tests and 14 instrumented tests with the latest head's CI also green. The user reports installing and testing the APK, but specific migration-path and real-provider results should still be recorded explicitly rather than inferred.

## M3A.2 — Deep Research & Actionable Results (next product priority)

**Reason:** Real-world tests of supplement research and a $150 US perfume gift search showed that a fixed three-search, under-350-word Action Brief can produce shallow comparisons and generic task steps instead of fulfilling the requested deliverable. The priority is **useful, trustworthy research**, with transparent user-controlled spending rather than prematurely minimizing API cost.

**Desired capabilities:**

- **Intent-aware output:** Distinguish factual lookup, shopping comparison, multi-category recommendation, procedural research and step creation. Identify exactly what the user asked to receive; do not silently drop categories or requirements.
- **Adaptive investigation:** Plan research questions, gather corroborating sources, verify evidence gaps, resolve material contradictions when possible, and synthesize findings. Allow configurable Quick / Thorough / Deep modes with flexible per-run search and model budgets; stop on evidence sufficiency, not an arbitrary target search count.
- **Model fit:** Support per-request model choice, with stronger models available for difficult synthesis; benchmark them instead of assuming the most expensive model is always best.
- **Task-specific deliverables:** Product comparisons should preserve candidate name, variant/concentration/size, independently supported pros/cons, pricing with retailer/time, source references, and uncertainty. Multi-category requests should produce one reviewable proposed step per requested category when safe, including explicit unresolved fields.
- **Evidence integrity:** Preserve provider-issued citation metadata; avoid model-invented URLs, false 'Official' badges and confident assertions without enough sources. Distinguish official vendor claims from independent evaluation and current commercial pricing. Important health/product suitability questions deserve especially careful qualification.
- **Research result vs. action separation:** Produce a substantive answer first, then a separately structured and editable list of proposed task changes. Never directly mutate tasks because a model recommended it.
- **Cost intelligence:** Locally capture usage metadata and estimated token/search cost per run, plus a daily/monthly ledger and spend settings; mark failures/cancellations with unknown or partial usage honestly. Never persist API secrets or full task text in diagnostics. Do not embed Anthropic organization admin credentials in the APK.
- **User-controlled spend:** Provide preflight disclosure, sensible optional spending ceilings, visible model/search choices and a continuation path for unresolved questions. No hidden recurrent paid browsing.
- **Quality benchmarks:** Supplement example must preserve four categories (multivitamin, vitamin D, magnesium, glucosamine) and the preferred brand constraint in four proposed category steps. Perfume example must yield a diverse, evidence-backed gift shortlist under a stated $150 US budget, including specific variants and comparable verified prices where available.

**Boundary:** Planned enhancement only. Preserve existing M3A functionality, offline history, provenance, review-before-apply and capped background behavior until the new permissions/budgets are explicitly designed and approved. Do not automatically execute M3A.2 just because this roadmap lists it.

## M3B — Autopilot Review: identify what the user might miss

**Product opportunity:** Automatically scan pending commitments and surface a few meaningful insights about tasks needing attention, rather than requiring the user to open every task individually.

Possible findings:
- A deadline is approaching, but necessary checklist steps are not completed.
- A waiting task has passed its follow-up/check-in date.
- A task has been repeatedly snoozed or has not had meaningful progress.
- A task lacks a next step, decision, follow-up or reminder.
- Two tasks might conflict or depend on each other; any inferred relationship must be labeled tentative.
- Information seems missing: offer an **explicitly authorized** M3A research request, not automatic paid research.

### M3B.1 — Privacy-preserving on-device review

The first deliverable should use deterministic, testable local rules with no network access or API key required. Include an on-demand **Review now** and an optional daily/weekly review. Use WorkManager for best-effort deferrable processing, without promising exact times or execution after a force-stop.

Show a short prioritized digest in the existing Flight Plan experience, explaining **why** each insight was identified and offering concrete user actions. Persist stable finding identifiers and the state of dismissed/snoozed observations. Do not resurface unchanged findings endlessly. Begin with in-app review; optional digest notifications require a separate opt-in, frequency cap and quiet-hours respect.

Treat waiting, snoozed, paused and completed tasks according to their distinct states. Never silently edit tasks, change alarm policies or start research.

### M3B.2 — Optional AI review across multiple tasks

Reviewing **all pending tasks with an external model is a separate consent boundary** from M2 planning and M3A selected-task research.

Start with a manual **Analyze my open tasks** action. Display the exact task count and fields to be sent; support per-task exclusion and reasonable size limits. Send no unrelated task history, contacts, credentials, exact device location or notification diagnostics.

Provide AI-identified cross-task patterns as *hypotheses grounded in the selected task records*, not facts or source-verified claims. Require user approval for all local mutations and separate approval for any web research. If optional scheduled AI analysis is introduced later, it requires fresh recurring consent, explicit cadence, bounded billable usage and a visible run history. AI-off mode must remain fully useful.

### M3B.3 — Relevance improvements

Collect optional feedback (useful, dismiss, not relevant, do not repeat) to prioritize findings while suppressing noisy repetition. Do not mistake dismissal for task completion. A proposed dependency graph requires user confirmation.

### M3B acceptance criteria

1. Open tasks are reviewed automatically on the user-selected approximate cadence and on demand, offline.
2. Every finding has a clear trigger and a specific next action; state changes and deadline timing are correctly handled.
3. Identical observations do not spam users; notification frequency, permissions and quiet hours are honored.
4. No multi-task data leaves the phone without an explicit separate AI consent and payload preview.
5. Neither local nor AI review automatically performs paid searches, edits tasks, sends messages or alters reminders.
6. Unit, persistence, Android UI, accessibility and field tests cover deduplication, dismissal, task state changes, timezone and reboot/deferred-work behavior.

**Design step:** Run a focused Claude Design exploration for the Autopilot Review screens and controls after the M3A UI is available. This is an extension of Flight Plan, not an app-wide redesign.

**Open decisions:** Daily versus weekly cadence; when to show notifications; which actions count as meaningful progress; model payload exclusions; maximum task count; and whether a separate navigation tab is justified.

## M3C — Controlled integrations (future)

Potential user-approved calendar/email reading, drafting communications/events and narrowly scoped external actions. Require separate provider connections, revocable permissions, reviewable previews, idempotency, audit history and source/prompt-injection defenses. Drafting is not sending. Payments and purchases require a later, explicit product and security decision.

## M3D — Approval-gated agent workflows (future)

Coordinate research, proposals, narrowly authorized external tools, outcome verification and follow-up using persistent execution states and clear stop/retry controls. A model's text alone must never be treated as proof that an external action succeeded.

## M4 — Household sync (deferred)

Optional sharing, identity, task ownership, member permissions, cross-device conflicts, encrypted transport and reminder ownership. Preserve the simple single-device offline experience until deliberately changed.

## Backlog and known follow-ups

| Topic | Status |
| --- | --- |
| Deep research with actionable outputs and usage/cost ledger | **Implemented 2026-10-20 as M3A.2 (draft PR); hardened the same day after an independent code review (completion policy in code, provenance pointers vs citations, failed-call cost floors); awaiting review, live-API and device verification** |
| Production UX implementation (Living Draft, Investigation Thread, Proposal Review) | **Approved and started 2026-10-23.** M1 (Room v6: drafts, proposal sets, provenance, task `rev` + timing roles; conflict-aware atomic applier; allow-listed payload builders) implemented with unit, Room and backup tests on `prod-ux-m1-foundations`. Documents E/F/G/H (received 2026-10-23) reconciled in the kickoff document §9: M4 must retire the M3A.2 keyword/template layer as the objective source (RES-010/011 are P0 Invariants); Stage 0 mapping in `docs/TEST_MATRIX.md`; two owner decisions surfaced (milestone order per Document G §3; research preset names, OQ-C1). M1 reconciliation pass fixed a real concurrent double-apply race and the draft text truncation. Prototype suites pass (reference only). Phone and live-API verification not performed by the agent |
| Research Online AI-first replacement | **Implemented 2026-10-24 on `ccr-e03bf7f8-hvk42n` (not merged).** Owner decision after the A/B experiment (`docs/experiments/ai-first-research/REPORT.md`): replace M4's deterministic research core; keep authorization, limits, provider-only citations, ledger, persistence and the one review. Adds a no-search clarification check before authorization and explicit task suggestions. Local lint, 557 unit/Robolectric tests and all APK builds pass; CI instrumented results in `docs/AI_FIRST_RESEARCH_INTEGRATION_REPORT.md`. Live paid validation (plan V1–V5 in the report) and phone testing not yet performed |
| Task-first audit: nine everyday tasks, capture to completion | **Audit 2026-10-24 on `ccr-2b291a9b-kqjl8r` (`docs/TASK_FIRST_AUDIT.md`), base `ccr-e03bf7f8-hvk42n`; no code changed.** Finds one-tap capture ignores timing, bedtime nudges are deferred to morning by default quiet hours, routines have no "done for today", deadlines do not drive nudges. Proposes a small local "Right-time nudges" milestone; awaiting owner choice, not approved |
| Right-time nudges (audit §5 milestone) | **Approved and implemented 2026-10-25 on `right-time-nudges` (base `ccr-2b291a9b-kqjl8r`), not merged** (`docs/RIGHT_TIME_NUDGES_REPORT.md`). Offline timing at capture (tonight/times, tomorrow/weekdays, before bed, every night/day, "by Monday 5pm"), editable bedtime (21:30), explicit per-task quiet-hours exception, "Done for tonight" for confirmed routines (Room v8, additive), Gentle default for new installs. Unit/Robolectric local; emulator via CI; phone verification outstanding. Next proposed slice: deadline-aware nudges (not started) |
| ChatGPT share handoff (feasibility PoC) | **Experiment 2026-10-26 on `ccr-69eac4c4-5vsk4s` (base `right-time-nudges`), not merged, no PR** (`docs/CHATGPT_SHARE_HANDOFF_POC.md`). Verdict **CONDITIONAL GO**: a public `chatgpt.com/share/<id>` link returned the conversation via an anonymous GET; text is read from undocumented React Router loader data (fails closed); OpenAI's terms on programmatic extraction need an owner decision. Share target + explicit Fetch + read-only `autopilot.handoff.v1` preview; no task writes, not wired to `ProposalApplier`. Fallback: share the response text itself (no network). Phone check from the real ChatGPT app outstanding; next slice (handoff → Proposal Review) not approved |
| ChatGPT → Android App Link (spike) | **Experiment 2026-10-26 on `ccr-594b2954-khysx3` (base `ccr-69eac4c4-5vsk4s`), not merged, no PR** (`docs/CHATGPT_APP_LINK_SPIKE.md`). One verified-ready route `https://hassanjaveed84.github.io/autopilot/test#message=…` opens a display-only test screen; no task writes, no AI calls. Site files prepared in `applink-site/`; **not published** (needs owner approval to create the `HassanJaveed84.github.io` Pages repository), so domain verification and the ChatGPT phone test are outstanding. The real `autopilot.handoff.v1` link payload is not approved |
| Automatic pending-task review | **Captured 2026-10-20; prioritized as M3B after the production UX milestones** |
| Focused M3B design | Planned after M3A UX exists |
| Real-device installation and reminder/keyboard checks | Verification outstanding |
| Live Anthropic planning/research tests (incl. M3A.2 multi-stage runs, cache usage, real price reconciliation against the Console) | Verification outstanding |
| Calibrate the 11k-tokens-per-search estimate from the local usage ledger after real runs | Open (M3A.2) |
| Light-mode outline contrast polish | Small deferred UI issue |
| Widgets, richer capture, recurring task regeneration | Idea bank, not scheduled |
| Paid background research and financial transactions | Not approved |

## Tracking and update rules

1. Treat this as the **product roadmap**; `docs/SPEC.md` remains the architecture/requirement baseline and `docs/PROGRESS.md` remains technical history.
2. Record design completion, code implementation, merge, CI verification, live provider verification and physical-device testing as **different states**.
3. Update a status only with supporting commits, PRs, test links/reports and known limitations. Never label an in-progress unattended session complete.
4. New product ideas go into the backlog with user value, consent/privacy implications, dependencies and unresolved questions.
5. Future Claude Code sessions should read this roadmap, produce milestone reports and **propose changes on their own branch**, without altering an unrelated active feature branch.
6. GitHub Issues can track approved implementation slices and bugs once chosen. They are repository-wide and are not created as part of this documentation-only change.
7. Preserve notification channel identity, alarm scheduling, stable signing, Room migrations, backup compatibility and local-first task behavior.

## Next checkpoint

**2026-10-24:** the Research Online core is being replaced by the AI-first engine (integration branch `ccr-e03bf7f8-hvk42n`, awaiting owner review; not merged). Next for it: owner review, the five-scenario live validation in `docs/AI_FIRST_RESEARCH_INTEGRATION_REPORT.md` §5 once authorized, and a phone run of §7. The M4 description below is historical.

M1 was accepted by the owner on 2026-10-23; the open points were decided (G6 order M2 → M4 → M3 → M5 → M6; R48 presets Quick/Standard/Thorough/Deep + Custom; D52 no automatic draft expiry) and recorded in `docs/DECISION_LOG_ADDENDA.md` for incorporation into Documents E/G/H v1.1. M2 (canonical Proposal Review, PR #7) is complete. M4 (Research Online Investigation Thread, PR #8, stacked on M2) is implemented: objective-led research with claim-level evidence, derived completeness, turn-by-turn threads with explicit authorization, Room v7, R48 presets, proposals through the one review; the M3A and M3A.2 run, preflight and apply paths are retired and their saved results stay readable. Deterministic tests only: no live provider run and no device run by the agent. Next per G6 is M3 (Living Draft), not started. The M3A.2 draft PR #5 remains the base: install its CI APK, run the benchmarks against the real API with a personal key, compare the ledger with the Console, and record findings in `docs/M3A2_IMPLEMENTATION_REPORT.md`. M3B stays proposed.
