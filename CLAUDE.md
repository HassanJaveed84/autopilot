# CLAUDE.md — Life Admin Autopilot Development Guidance

This repository contains a native Android, local-first follow-through app. This file describes **standing engineering constraints and documentation practices**. It is **not an instruction to autonomously build the original MVP or to start future milestones**.

## Read first, then scope the current task

1. Read `docs/ROADMAP.md` for the **current product roadmap**, prioritized ideas and milestone states.
2. Read `docs/SPEC.md` for original requirements and non-negotiable technical/product constraints; it predates the expanded M3A/M3B/M3C/M3D breakdown.
3. Read `docs/ARCHITECTURE.md`, `docs/PROGRESS.md` and the report for the affected milestone. Inspect **actual current code and tests** rather than trusting an old progress report.
4. Follow the user's **explicitly authorized scope for this session**. A milestone merely appearing on the roadmap does not authorize implementing it. If a new feature is approved, document any differences from the roadmap.
5. Before editing, inspect `git status`, current branch, base commit and related open PRs. **Never modify another active session's branch or merge PRs without an explicit request.** Branch feature work from the appropriate committed base, and prefer a draft PR.

## Milestone coordination as of 2026-10-20

- M0–M1C offline MVP and M2 optional AI planning are implemented; Flight Plan UI redesign and stabilization have been merged. Automated and physical-device verification are separate gates.
- **M3A (Action Brief / consented selected-task web research) is merged into `main` as PR #4.** User reports installing and testing the latest APK; keep live-provider and migration evidence distinct from code/CI status. Read the merged report and current code before making future claims.
- **M3A.2 (Deep Research & Actionable Results)** is the proposed next priority: quality-oriented research, structured task-specific deliverables, flexible model/search budgets and usage-based cost estimates. It is not yet approved for implementation.
- **M3B (Autopilot Review)** is a **proposed major milestone after M3A.2**, not code already built and not permission to start implementation. The product vision is automatic local review of all pending tasks with optional, *separately consented*, multi-task AI analysis. See `docs/ROADMAP.md`.
- M3C (controlled external integrations), M3D (agent workflows), M4 (household sync) are later proposed/deferred work.
- Do not repeat legacy M0/M1 build instructions in this file; those milestones are historical. Their implementation details and test results live in `docs/PROGRESS.md`.

## Non-negotiable application invariants

- **Room is authoritative** for task data. Keep reminder delivery Android-native and independent of network/API availability.
- Keep reliable one-shot alarm occurrence scheduling, generation-based invalidation, idempotent receivers, Done/Snooze/Open notification actions, quiet hours, timezone/DST handling and boot/app-start reconciliation.
- Dismissing a notification never completes a task. `Done` is explicit and must cancel/reconcile future alarms as designed.
- Preserve schema migrations, stable app signing, existing data, notification channel IDs, backups/import-export compatibility and securely stored API credentials. Never use destructive migration fallbacks.
- Keep task, reminder, Capture, Today, Inbox and Settings functionality fully usable offline.
- Use dependency-injected seams for clock, timezone, alarm driver, network/provider and persistence; prefer deterministic, testable domain logic.
- No sensitive content in logs, diagnostic artifacts, backups or source control. API keys remain encrypted and out of screenshots, exports and test fixtures.

## AI, permissions and external information

- M2 AI planning is optional and opt-in, with review-before-write. Only the `ai/anthropic` adapter imports Anthropic SDK types.
- Web research must be separately authorized per selected-task request and use **real structured citation metadata** for attributed claims; retrieved pages are untrusted inputs. No invented links, availability, costs or successful actions.
- **An external AI review of multiple pending tasks is a new privacy boundary.** The user's consent to planning/research on one task never authorizes automatically uploading the full task list. Start with local deterministic review; multi-task AI needs explicit payload preview, per-field/task exclusions and separate opt-in.
- No automatic paid web searches, hidden background token spending or unconstrained polling. User-approved operations should have usage caps and understandable run history.
- Never silently edit tasks, reminders, deadlines or statuses due to AI suggestions. External messages, bookings, purchases, payments and third-party account changes require dedicated approval and capability-specific controls.
- Do not treat successful emulator tests as proof of live provider entitlement or real-device notification behavior.

## Workflow for an approved engineering task

1. Confirm exact scope, target base and branch; run baseline build/test where available. Document pre-existing failures.
2. Implement in coherent phases with focused commits, while preserving unrelated code. Make low-risk decisions autonomously inside the approved scope; don't add unrelated features.
3. Add unit, persistence, Android and provider/HTTP tests as appropriate, plus accessibility and migration cases. Use fakes/MockWebServer for paid API calls; do not require personal credentials in CI.
4. Run relevant gates (commonly `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`; emulator instrumentation and screenshots in CI where supported). Report commands **actually run**, results, links and limitations.
5. Produce a milestone report under `docs/` with changed components, acceptance criteria, failed/passing tests, open blockers and exact device/API checks.
6. Update `docs/ROADMAP.md` **on the feature's own branch or in a dedicated roadmap PR** when a milestone is completed, a product decision changes or a newly approved idea is added. Distinguish design, code, merge, CI, live API and physical-device verification.
7. Push and open a draft PR when authorized; leave merges, shipping, external actions and destructive changes to explicit user approval.

## Documentation roles

- `docs/ROADMAP.md` — living product roadmap and decision backlog.
- `docs/SPEC.md` — foundational technical and product requirements.
- `docs/PROGRESS.md` — implementation history; don't rewrite past evidence.
- `docs/ARCHITECTURE.md` and other specialty docs — current design contracts where still accurate.
- `docs/*REPORT.md` — milestone-specific implementation and test evidence.
- GitHub Issues — approved implementable slices and bugs. Creating issues is repository-wide and should be done deliberately, not as an accidental side effect of a documentation branch.

**Primary goal:** ship dependable, useful progress toward the approved feature without compromising local task data, reminders, user privacy or truthful reporting.
