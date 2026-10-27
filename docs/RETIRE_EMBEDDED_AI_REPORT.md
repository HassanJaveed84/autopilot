# Retire Built-In AI: Implementation Report

Date: 2026-10-27 · Branch: `retire-embedded-ai` (from `chatgpt-roundtrip-polish` @ `082ba59`) · No PR opened · Nothing merged

**Decision (approved by the owner).** *Autopilot manages commitments. ChatGPT handles intelligence.*
Capture offline → optionally **Discuss in ChatGPT** → return suggestions through the verified
`/autopilot/import` App Link → review and selectively apply them in the one Proposal Review →
Autopilot handles reminders, nudges and completion. The app no longer maintains a competing AI
system of its own.

## 1. Status

| Gate | State |
| --- | --- |
| Dependency map and removal | **Done** (§2–§4) |
| Saved research read-only, backups and migrations unchanged | **Done** (§5, §6) |
| Local gate (lint, unit/Robolectric, debug/release APKs, androidTest APK) | See §8.1 |
| CI on this branch (incl. emulator instrumented tests) | See §8.2 |
| Phone check (upgrade over an install with saved research and a stored key) | **Not done** by the agent |

## 2. Dependency map

Traced from the UI entry points down to providers and Room before deleting anything.

| Layer | Exclusively the retired AI | Shared with ChatGPT round trip / Proposal Review / saved history |
| --- | --- | --- |
| UI | Capture *Plan with AI* button and in-sheet *Turn on AI planning?* setup; Inbox *Plan with AI* tile; task detail *Move this forward* card (*Plan next step*, *Research online*); Today header *Research* button; `ui/plan` (Plan with AI, Move this forward); `ui/ai` (AI settings: key, consent, model, test connection, delete); `ui/research2/UsageScreen` (AI usage and budget); thread composer, clarification, authorization card, model/effort/search/limit adjust panel, run progress, Stop, *Suggest changes to the task*, link/unlink; brief *Research again* | `ui/review` (Proposal Review, provenance sheet), `ui/handoff`, `ui/detail/DiscussInChatGpt.kt`, the read-only parts of `ui/investigation`, `ui/research`, `ui/research2` (answers, sources, how it was researched, what was sent, legacy proposals) |
| Domain / services | `AiPlanningService` (+ `NetworkStatus`, `AiAvailability`), `PlanningPrompt`, `PlanSchema`, `PhraseResolver`, `ProposalDraft`, `ProposalMapper`, `ProposalValidator`, `PlanningProposalBridge`, `ResearchOnlineService`, `AiFirstResearch` (+ `AfRender`, `AfPrompt`), `AfClarifier`, `AfTaskSuggester`, `ContextAllowList`, `BriefParser`, `ResearchPrompt`, `ContractParser`, `CompletionPolicy`, `SessionAssembler`, `ResearchModes`, `ModelCall` | `ProposalValidator.normalizeKeyPublic` was used by the handoff importer and bridge → moved to `domain/proposal/MatchKey` (same normalisation) |
| Provider | `ai/anthropic/*` (planning, research, research calls, error mapping) and the `com.anthropic:anthropic-java` dependency | — |
| Credentials | `ApiKeyStore` / `EncryptedApiKeyStore` / `AndroidKeystoreCipher` | A key an earlier build stored → `LegacyApiKey` (detect and remove only) |
| Persistence | `RoomUsageLedger` + `UsageLedger` interface (writes, totals, grouping) | Every Room table, DAO, entity and migration; `ResearchRepository`, `ResearchSessionRepository`, `InvestigationRepository`, `AfThreadRepository`, `DraftRepository`, `ProposalSetRepository`, `ProposalApplier`, `BackupRepository`; stored JSON models in `ai/research`, `ai/research2`, `ai/investigation`, `ai/aifirst` |
| Settings | `aiEnabled`, `aiConsentAt`, `aiModel`, AI and research day counters, spend limit, monthly budget, research model/depth/search/effort overrides, research explainer version | DataStore keys left in place, unread (§6) |
| Build / CI | Anthropic SDK, MockWebServer, the `liveEval` Gradle switch, the Anthropic ProGuard note, `INTERNET` and `ACCESS_NETWORK_STATE`, three AI screenshot-tour classes | CI workflow, signing, App Link site |
| Dev harness | `src/test/.../liveeval` (paid live evaluation) and its reply fixtures; `src/debug` research fixtures | `docs/live-eval` and experiment records kept as history (marked retired) |

## 3. What was removed

- **Plan with AI**: the Capture button and its in-place AI setup sheet, the Inbox triage tile, the
  plan screen, view model, review and diff UI, and every planning prompt/schema/validator/mapper.
- **Move this forward**: the AI card on task detail (*Plan next step* and *Research online* tiles,
  *Set up AI planning*).
- **Research Online execution**: new threads, follow-ups, the clarification check, the
  authorization card, model/effort/search/spend choices, running/Stop, *Suggest changes to the
  task*, link/unlink, the Today header research button, *New research* in the history, *Research
  again* on M3A briefs, and the engine behind them.
- **Anthropic integration**: `ai/anthropic`, the SDK dependency, MockWebServer, the provider
  ProGuard rule, the encrypted key store and every model-selection and connection-test path.
- **AI settings, usage and billing UI**: Settings → *AI planning · optional*, the AI settings
  screen, the AI usage dashboard and monthly budget, cost chips that linked to it.
- **Network access**: `INTERNET` and `ACCESS_NETWORK_STATE` are no longer requested (WorkManager
  still merges the latter, which grants no network access). The source viewer no longer checks
  connectivity; opening a source is left to the browser.
- **Live evaluation harness**: `src/test/.../liveeval`, its three reply fixtures and the
  `-PliveEval` Gradle switch. Debug-only research fixtures in `src/debug`.
- **Tests that only verified retired code** (31 files, plus `ResearchFlowUiTest` replaced by `SavedResearchUiTest` and `KeystoreApiKeyStoreTest` by `LegacyApiKeyDeviceTest`): planning approval, NL scenarios,
  validator, planning bridge and ledger, provider HTTP tests (planning, research, research calls,
  reply shape, timeouts), brief parser, citation accounting, contract parser, completion policy,
  evidence integrity, provenance hardening, session assembler, cost/usage accounting, AI-first
  research, Research Online service, small calls, AI settings UI, plan screen and view model UI,
  Research Online UI, and the AI fakes.
- **Navigation**: routes `ai_settings`, `ai_settings/usage`, `plan`, `plan/task/{id}`,
  `research/new`.

Size: main source went from 29,941 to 21,378 Kotlin lines; the change is 142 files,
+1,401 / −15,870 lines including tests and docs.

## 4. What was deliberately preserved, and why

| Kept | Why |
| --- | --- |
| Capture, task editing, steps, notes, statuses, deadlines, reminders, nudges, quiet hours, notifications, health screen | Untouched apart from removing the AI button; capture's 2,000-character limit moved to `CaptureViewModel.MAX_INPUT_CHARS` |
| **Discuss in ChatGPT**, `/autopilot/import`, `HandoffImporter`, `HandoffProposalBridge`, the import activity | The approved AI path; only their duplicate-check helper moved (`MatchKey`, identical normalisation) |
| Proposal Review, `ProposalSetRepository`, `ProposalApplier` (atomic apply, conflicts, duplicate handling, undo), provenance and its sheet | The one write path from any suggestion to a task |
| `ProposalSource.PLANNING` / `RESEARCH` labels ("From Plan with AI", "From research") | Applied changes keep their provenance; pending sets from earlier versions can still be reviewed and applied |
| Room schema v8, every migration (1→8) and exported schema; `research_briefs`, `investigations`, `investigation_turns`, `usage_records`, `drafts`, `proposal_sets`, `provenance` | No user data is deleted because the feature that created it is retired; no new migration was needed |
| Stored-format models in `ai/research`, `ai/research2`, `ai/investigation`, `ai/aifirst`; `InvestigationProposalSets`, `ResearchProposalSets` | Decode saved research for the read-only viewers, backups and migrations; turn legacy proposals into reviewable sets |
| Read-only research viewers (`ui/research`, `ui/research2`, `ui/investigation`) | Saved research stays accessible: from its task (*Saved research* card) and from Settings → *Saved research* (shown only when there is some). Delete stays available per thread |
| `ai/usage` enums and the price table | Backup import validates usage rows with the enums; saved sessions show their recorded cost |
| `AfThreadRepository.settleInterrupted` (was `ResearchOnlineService.recoverInterrupted`) | A turn left *Running* by a dead process still becomes *Interrupted* at app start, as before |
| `DraftRepository` | Its table is in backups; nothing in the UI wrote drafts even before this change |

## 5. Does any unused AI code remain?

No provider, prompt, network, engine or AI-setup code remains, and nothing imports an AI SDK
(`ArchitectureBoundaryTest.noCodeImportsAnAiProviderSdk`, `noCodeOpensNetworkConnections`). What
remains under `ai/` (~2,000 lines, down from ~9,000) is **data, not AI**: the serializable formats
of research saved by the retired features, the two bridges that make its saved proposals reviewable,
the price table and enums for saved usage rows, and display names for models those records name.
They are used by the read-only viewers, backup import/export and the v6→v7 migration. Some
serializable fields are never read by the viewers; they are kept so stored JSON and backups
round-trip unchanged.

Small, deliberate remnants:
- `AfThreadRepository.create` / `saveTurn` keep the AI-first format writable for backup import,
  start-up settling and tests; nothing in the app creates research.
- Repository `deleteAll` / `observeCount` helpers that only the removed AI settings called are still
  there (tiny, harmless; can go in a later cleanup).
- `SettingsRepository` no longer reads or writes the `ai_*` / `research_*` keys; they remain in the
  DataStore file (not deleted, see §6).
- `LegacyApiKey` only detects and removes an old key (§6).

## 6. Database, backup and settings implications

- **Room**: unchanged (v8). No migration added, none removed, no table dropped. Upgrading keeps
  every task, step, reminder, proposal set, provenance record, draft, usage row and saved research
  thread/brief/session.
- **Backups**: export and import code paths are unchanged apart from comments. Exports still
  carry research briefs, investigations (+ the legacy `sessions` copy), usage rows, drafts,
  proposal sets and provenance; backups from earlier versions import as before (covered by
  `BackupRepositoryTest`, `AfThreadRepositoryTest`).
- **Saved research**: readable offline; a running turn from an old process is settled to
  Interrupted; existing suggestion sets open in the Proposal Review; nothing new is proposed.
- **Settings (DataStore)**: the AI and research keys are no longer read or written but are not
  removed, so a downgrade would still find them. They hold no secrets.
- **Stored API key**: an encrypted Anthropic key from an earlier build is **not** deleted
  automatically. Settings shows **Remove the old Anthropic API key** only while one exists; on
  confirmation it clears the `ai_secrets` preferences file and deletes the Keystore entry. The key
  is never read, decrypted or sent. It was never in cloud backup, device transfer or the JSON export.

## 7. UX

- Capture: one primary button (*Save & nudge* / *Save to Inbox*); the AI hint line is gone.
- Inbox triage: *Nudge me · Waiting on… · Done already* and Delete.
- Today: no research button in the header; the onboarding line no longer mentions AI planning.
- Task detail: *Discuss in ChatGPT* is the one AI entry point; a *Saved research* card appears only
  when the task has research from earlier versions.
- Saved research thread: a *Saved research · read-only* banner, answers, sources, *How this was
  researched*, *What was sent*, *Review N suggested changes* (when a set exists), *Copy answer*,
  *Delete*.
- Settings: Follow-up, Privacy, Appearance, Your data (+ *Saved research* and the key-removal row
  only when relevant), About (*no network access*). No empty AI section.
- No replacement screens or configuration were added.

## 8. Tests and results

Tests added or rewritten:

| Test | Covers |
| --- | --- |
| `ui/SavedResearchUiTest` (8, Robolectric, replaces `ResearchFlowUiTest`) | No retired entry point anywhere (capture, inbox, detail, settings, history, thread); Discuss in ChatGPT on detail; saved brief readable with sources openable; long-detour regression kept; saved thread opens read-only from Settings and from its task; delete removes only the thread; key-removal row only on request |
| `data/LegacyApiKeyTest` (2, Robolectric) and `LegacyApiKeyDeviceTest` (emulator, replaces `KeystoreApiKeyStoreTest`) | Detection; removal clears the blob and the real Keystore entry (test-only names) |
| `data/AfThreadRepositoryTest.appStartSettlesATurnLeftRunningAndNeverTouchesFinishedOnes` | Start-up settling for both formats |
| `ArchitectureBoundaryTest` (rewritten) | No AI SDK imports; no network code; core packages don't import `ai` |
| `ManifestContractTest` (updated) | `INTERNET` / `ACCESS_NETWORK_STATE` no longer requested |
| `BackupRepositoryTest`, `MigrationTest`, `ResearchSessionRepositoryTest` (fixture change) | Unchanged assertions; the M3A.2 session they use is now a committed JSON fixture (`src/test/resources/fixtures/legacy/m3a2-supplements-session.json`) generated once from the retired engine at `082ba59`, and usage rows are inserted as stored rows |
| `ScreenshotTour.eq_savedResearch` | Screenshots of the saved-research card, read-only thread and Settings row |

### 8.1 Local gate

Baseline at `082ba59` (same container): `./gradlew testDebugUnitTest` → **708 tests, 0 failures**.

On this branch (same container, Android SDK 36, JDK 21, Gradle 8.14.3; Maven Central through a
session-only mirror outside the repo):

```
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --continue --rerun-tasks
BUILD SUCCESSFUL
```

| Check | Result |
| --- | --- |
| `lintDebug` | **No issues found** |
| `testDebugUnitTest` | **485 tests, 0 failures, 0 errors** (708 at baseline; the difference is the tests that only covered retired code, net of the 12 added here) |
| `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest` | **Built**; release APK 3.1 MB with no `com.anthropic` classes |
| Merged release manifest | `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, plus WorkManager's `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and the AndroidX dynamic-receiver permission. **No `INTERNET`** |

One flake was found and fixed during the run: `SavedResearchUiTest` scrolled to the Settings row
before the saved-research count had loaded; it now waits for the row.

### 8.2 CI

_Filled in after the push._

## 9. Verification that no obsolete entry point remains

- Source search: no `capture_plan_ai`, `inbox_plan`, `nav_research`, `detail_ai_card`,
  `action_move_forward`, `action_research`, `open_ai_settings`, `home_new`, `thread_prepare`,
  `auth_start` test tags or routes in `app/src/main`; `SavedResearchUiTest` asserts their absence
  on every screen it visits.
- No `com.anthropic` import, no Anthropic dependency in `gradle/libs.versions.toml`, no `INTERNET`
  permission in the manifest.

## 10. Remaining risks and open items

- **Phone upgrade not run by the agent.** Install over an existing build that has saved research,
  pending research suggestion sets and a stored key: research should open read-only, sets should
  still review/apply, Settings should offer the key removal, reminders and the ChatGPT round trip
  should be unaffected.
- **Merged manifest.** WorkManager still merges `ACCESS_NETWORK_STATE` (no network access); no
  library merges `INTERNET` in the release build (§8.1).
- **Downgrade.** Installing an older build later still works (schema unchanged); its AI settings
  would be as they were, except a key removed here is gone.
- **History labels** ("From Plan with AI", "Research Online") remain on applied changes and old
  proposal sets by design.
- **Docs**: `docs/ARCHITECTURE.md` sections for M2–M4 and AI-first are marked historical; older
  milestone reports are unchanged as evidence.
