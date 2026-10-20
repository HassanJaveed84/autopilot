# Product Roadmap — Life Admin Autopilot

Updated: 2026-10-20

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
| **M3A** | **Action Brief: selected-task web research, real source attribution, saved research briefs, reviewed task updates** | **In progress in a separate Claude Code session** | Await implementation PR/report, automated tests and live API/phone checks |
| **M3B** | **Autopilot Review: automatically review open tasks and detect overlooked obligations** | **Proposed — next priority after M3A** | Focused design, privacy specification, phased implementation |
| M3C | Scoped calendar/email/service integrations and user-approved external actions | Proposed | Design permissions, OAuth, provider integrations and auditability |
| M3D | Multi-step agent workflows with per-action approval and verified outcomes | Proposed | Reliable execution, scope, idempotency and recovery |
| M4 | Optional household collaboration and sync | Deferred | Explicit approval and identity/sharing architecture |

**Coordination:** M3A is in progress on a separate development session. This document-only roadmap branch does not modify M3A, declare it complete or request M3A changes.

## M3A — Action Brief: consented task research

Allow the user to open **one selected task**, specify what to research and explicitly authorize sharing the chosen task context and incurring a bounded web-search API request.

The desired experience includes a concise quick answer, uncertain findings clearly labeled, credible structured citations and sources, actionable next moves, offline access to prior research, and **selective** task updates applied only with approval. Avoid fabricated source links and any claim that simulated prototype data is real research.

M3A does **not** scan all tasks, autonomously conduct paid searches, send messages, book tickets, purchase items or change reminders without approval. Completion requires real implementation and regression evidence; mock API success is not live API validation.

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
| Automatic pending-task review | **Captured 2026-10-20; prioritized as M3B** |
| Focused M3B design | Planned after M3A UX exists |
| Real-device installation and reminder/keyboard checks | Verification outstanding |
| Live Anthropic planning/research tests | Verification outstanding |
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

Review the M3A implementation PR and its source-attribution/tests/real-device limitations. Then schedule M3B's focused product design and engineering brief, leaving future features unimplemented until explicitly requested.
