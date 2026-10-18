# CLAUDE.md — Life Admin Autopilot: autonomous overnight build

You are the lead engineer responsible for implementing the native Android **Life Admin Autopilot** offline-first MVP. This session is intentionally **unattended**. The user may be asleep. Make reasonable low-risk engineering decisions without asking for intermediate sign-off. You are authorized to work **inside this app repository only**, for the stated MVP.

## Sources of truth and precedence

1. Read `docs/SPEC.md` IN FULL before implementing; it defines functional requirements, data model, architecture, Android limitations and acceptance tests.
2. This `CLAUDE.md` overrides the prior *step-by-step stopping cadence* from the earlier bootstrap: **do not stop after M0**. Continue automatically through M0, M1A, M1B and M1C, subject to real blockers, platform time/usage limits and safety constraints.
3. Make reasonable, documented choices if implementation details are unspecified; do not fabricate permission grants, credentials, functionality or test results.
4. Do not work on M2/M3/M4 (hosted AI, agents, external accounts, household sync) tonight. Leave extension points but build a usable **AI-free MVP**.

## Status update (M2)

M2 (optional AI capture and follow-through planning, bring-your-own Anthropic key) was implemented
later at the user's explicit request; see `docs/M2_AI_CAPTURE_REPORT.md`. Keep its guardrails:
- AI stays opt-in, user-triggered and review-before-write.
- Only `ai/anthropic` may import the Anthropic SDK.
- Never commit, log or export an API key.
- Core tasks and reminders must keep working offline.

M3/M4 (agents, external accounts, sync) remain out of scope until requested.

## Scope — required stack

- Kotlin, Jetpack Compose, Material 3, Room/SQLite, DataStore, coroutines/StateFlow, AndroidX Navigation or an equivalent maintainable navigation architecture.
- `AlarmManager` for time-sensitive one-shot reminder occurrences (with exact-alarm permission handling), `WorkManager` only for deferrable reconciliation/maintenance.
- Dependency-injected seams for clock, time-zone source, alarm driver, notifications and persistence; a deterministic scheduler that does not call AI or network resources.
- No signup, backend, cloud sync, billing, Google integrations, API keys, surveillance features or paid services.

## Autonomous execution loop — apply throughout

1. Inspect the repo/environment, Android SDK/JDK/Gradle, Git state and build/test tools. Check `.gitignore` and existing project files before creating new ones. Use compatible stable toolchain versions. If the Android SDK is missing, attempt reasonable documented installation/configuration **using official sources and permitted tools**; do not run unreviewed remote scripts or disable safety checks.
2. Implement **M0** fully (project setup, navigation skeleton, Room/DataStore scaffolding, build/test wiring). Run tests/build when possible; fix failures and commit.
3. **Without waiting for me**, proceed to **M1A** (real offline task CRUD, capture, status lifecycle, steps, history, Today/Inbox/Tasks/Settings, local import/export if in spec). Test and commit.
4. **Without waiting for me**, proceed to **M1B** (recurring policy with multiple times per day, durable occurrence ledger, one-shot scheduling, snooze, Done, notification channel/actions, quiet hours, notification permission, reboot/time-zone/time-change recovery and idempotency). Add scheduler and receiver tests; test and commit.
5. **Without waiting for me**, proceed to **M1C** (accessibility, permission/degraded-mode UX, schema migrations, export/import validation, diagnostics, app icons/theme polish, test report, release/debug APK if tooling supports it). Test and commit.
6. After each milestone, write/update `docs/PROGRESS.md` with exact completed items, build/test commands and results, blockers and verified limitations. Preserve real progress in Git commits. Push a feature branch and open a **draft PR** if repository credentials/tools allow; do not merge into main.
7. After M1C, do one code quality/test pass. Fix straightforward problems, rerun tests, update docs, commit and push. Then stop with a factual summary; do not start unrelated features.

## Absolute requirements for reliable reminders

- Reminder occurrences are persisted to Room and scheduled as unique, idempotent one-shot alarms; never rely on WorkManager periodic jobs for minute-exact delivery.
- A notification's dismissal DOES NOT mark a task complete. 'Done' is explicit, cancels future alarms and closes outstanding occurrences. 'Snooze' defers a particular reminder safely; opening a notification opens the correct task.
- Recalculate/reconcile on boot and relevant OS clock/time-zone changes, with permission-safe fallbacks and no crash loops. Implement allowed Android receiver and background rules.
- Prefer documented best-effort notification behavior over false claims of guaranteed exact delivery, especially with Doze, exact-alarm permission denial, notification permission denial and force-stop behavior.
- Treat local vs UTC time and DST correctly. Test spring/fall transitions, day/time slot crossing, clock changes and duplicate broadcasts using fake clock/time zone.
- Test canonical user case: `Book train ride`, follow up at 09:00 and 18:00 every day until explicitly completed. Make this real behavior, not sample/mock UI.
- Keep the app fully functional offline. No network permission is required by the MVP unless a documented build/test requirement needs access externally during development.

## Quality gates and guardrails

- Implement actual code. Don't stop at plans, TODO scaffolds or design mockups. No hardcoded successful test reports. Never claim a device test was performed if no real device/emulator was attached.
- Before marking a milestone validated, run the relevant compile/test/lint tasks **that are available**. If SDK/permissions prevent compilation, explain accurately, add automated GitHub Actions Android CI if supported, and continue with defensible code and pure JVM tests where possible. Distinguish attempted, passed, failed and not run.
- Work within this repository. Do not force-push, rewrite existing remote history, delete unrelated assets, change other repositories, deploy, publish to Play Store, create external accounts, spend money, disclose secrets, or perform uncontrolled external actions.
- You may install legitimate free build dependencies where permitted. Avoid shell commands destructive outside the workspace.
- Make architectural choices without needing approval. Ask a question **only** when a genuine hard blocker requires credentials, billing, access approval, destructive changes, or a product-policy decision that cannot be safely inferred. Otherwise document assumptions and proceed.
- If a permission prompt or usage limit stops the run, preserve work and leave an exact next-action note in `docs/PROGRESS.md`; do not invent continuation or pretend the run is still active.
- Build a clean, readable and polished but realistically scoped MVP. Prefer robust basics and tests over premature AI/autonomy features.

## Final deliverables to leave in repo

- Compilable Android project (if environment supports verification), functioning offline CRUD/reminders, automated tests, and onboarding/build instructions in README.
- `docs/ARCHITECTURE.md`, `docs/REMINDER_ENGINE.md`, `docs/TEST_MATRIX.md`, `docs/PROGRESS.md`.
- `docs/OVERNIGHT_REPORT.md`: checklist of M0/M1A/M1B/M1C; tests run with exact commands/results; known Android OS/device limitations; APK location if built; branch/commit/PR link if available; specific remaining physical-device tests and blockers.

**Begin immediately by inspecting the repository and reading the entire specification. Execute all milestones M0–M1C autonomously within the constraints above. Do not stop for a status check or ask permission between milestones.**
