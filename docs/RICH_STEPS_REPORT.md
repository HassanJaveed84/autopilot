# Rich Steps (v0.2.1): Implementation Report

Date: 2026-10-28 · Branch: `claude/autopilot-rich-task-steps-sec9oe` (from `main` @ `f54e9df`, Release v0.2.0) · Draft PR, not merged

**Scope (approved):** the hybrid A+B design from the interactive prototype and the `ap=v2` handoff from the feasibility study
(`docs/experiments/rich-steps-handoff/REPORT.md`). Every step can have an optional plain-text description and labelled web links,
stored in Room and backups, editable by hand, and sent back from ChatGPT for review. Nothing else changed: no ChatGPT
conversation bookmarks, no new network access, no backend, no API key, no JSON or compressed payloads.

## 1. What the user sees

| Where | Behaviour |
| --- | --- |
| Checklist (Task Detail → Steps) | Unchanged for plain steps. A step with links shows its **main link** as an outlined pill with the destination domain (`Product page · chanel.com`), one tap to open. A quiet hint line says what else is there (`Description · 1 more link`). Completed steps keep their line-through, drop the pill and are **not** folded away |
| Tap targets | The 48 dp box only ticks; the title area only opens the step's sheet; the pill only opens the link. Nothing is nested. The existing provenance marker stays on the row |
| Step sheet | Title, its own done box, the main link as a large button with `domain · opens your browser`, the description (selectable text), *More links* with domains, *From ChatGPT · where this came from*, and **Edit step** (or *Add a description or link* on a plain step). Read-only on a done, cancelled or archived task, links still open |
| Step editor | Step wording, *Description (optional)*, and links (web address + optional label; the first is the main link, an up-arrow makes another one main, a bin removes one; *Add link* up to 10). `www.example.com` is accepted as `https://…`; anything that isn't an http(s) web address is refused with a reason |
| Manual creation | Still the one *Add a step* field: type and press Done. Details are added afterwards from the step's sheet |
| Links | Opened only by a tap, in the browser (`ACTION_VIEW` + `CATEGORY_BROWSABLE`). Opening a link never ticks or changes the step. Import and review never open anything |
| Proposal Review | A rich step from ChatGPT is **one item**: title, description (3 lines, *Read all*) and every link with its domain and a *Main* tag. **Edit** opens the same step editor, so the description and individual links can be changed or removed before applying. Details for a step already on the task show as *Step details*: the user's wording and done state are kept, and only the new paragraphs and links are added. When ChatGPT's step name matches **more than one** step, the item asks *Which step gets these details?* and can't be applied until the user picks one |

## 2. Changes

| Area | Files | Change |
| --- | --- | --- |
| Model and rules | `domain/model/StepLink.kt` (new), `domain/model/Task.kt` | `StepLink(url, label)`; `TaskStep.description`, `TaskStep.links`. `WebLink.check`: http/https only, an ASCII host with a dot, no user info, no whitespace, ≤ 2,000 characters. Only recognised tracking parameters are removed (`utm_*`, `gclid`, `gbraid`, `wbraid`, `dclid`, `fbclid`, `msclkid`, `mc_cid`, `mc_eid`, `igshid`, `yclid`, `twclid`, `ttclid`); every other query parameter keeps its exact bytes and order. `StepDetails`: description ≤ 2,000 characters, ≤ 10 links, labels ≤ 80, same-page duplicates dropped, append-only merging |
| Room v8 → v9 | `data/local/Entities.kt`, `Migrations.kt`, `Mappers.kt`, `AppDatabase.kt`, `schemas/…/9.json` | `task_steps.description TEXT` and `task_steps.links TEXT` (a JSON array), both nullable, added with `ALTER TABLE`. Existing steps keep every value and get neither. No destructive fallback |
| Backup v2 | `data/repository/BackupRepository.kt` | `SCHEMA_VERSION` 1 → 2. `StepDto.description` and `StepDto.links` (`LinkDto(url, label)`). Schema 1 files import unchanged. A file newer than 2 is refused, and so is a step link that fails validation, with nothing written. Without the bump, 0.2.0 (which ignores unknown fields) would import a 0.2.1 file and silently drop details; with it, 0.2.0 refuses the file |
| Preserving details | `ProposalModels.kt` (`StepSnapshot`, `ApplyRecord.detailStepIds`), `ProposalApplier.kt`, `TaskRepository.kt` | **Fixed:** Proposal Review's undo rebuilt every step from a title-only snapshot, which would have wiped every step's details. Snapshots now carry description and links. Undo restores them for the steps the apply enriched and leaves every other step's details exactly as they are, including after an apply recorded by 0.2.0. New `TaskRepository.updateStep(id, title, description, links)` keeps done state, order, origin and provenance. Rename, tick and move already copy the row and keep details; tests cover them |
| Handoff `ap=v2` | `share/applink/HandoffLink.kt`, `ImportLink.kt` | v1 grammar plus `sd` (one description paragraph, ≤ 4 per step, ≤ 300 characters), `sl` (`<label> <address>` or `<address>`, ≤ 5 per step) and a required final `end=1`. Details must come directly after their `s`. A raw `=` inside a v2 value is accepted, because it is unambiguous: ChatGPT's `?utm_source=chatgpt.com` citations are accepted and then stripped. Refused as a whole, never cut down: a missing end marker (`INCOMPLETE`), a detail out of place, a bad address (`BAD_LINK`), a raw `&` or `#` inside an address, oversize. `ap=v1` is parsed exactly as before. `ImportLinkParser.MAX_URL` 4,096 → 8,192; the prompt prefix is now `ap=v2` |
| Proposals | `ProposalModels.kt`, `ProposalReconciler.kt`, `HandoffProposalBridge.kt` | `ItemValue.Step` gains `description` and `links`. New `ItemKind.ADD_STEP_DETAILS` / `ItemValue.StepDetails(stepId, choices, stepTitle, description, links)`. Steps are matched by name only to find their **id**. With exactly one match, the item carries that id. With several, `stepId` is empty and the reconciler asks the user to choose (never bulk-selected). Only paragraphs and links not already on the step are proposed, and they are re-checked at apply time. A ticked step needs *Apply anyway* and stays ticked. Adding details is not material, because the user's wording is untouched. The set id is unchanged for plain steps, so v1 and v2 repeats still reopen one review |
| Review | `ui/review/ProposalReviewScreen.kt`, `ProposalReviewViewModel.kt`, `ReviewPresenter.kt` | Rich preview, step editor for ChatGPT steps and details, the step chooser. Checks run against the user's edits, both on screen and at apply time |
| ChatGPT prompt | `share/chatgpt/ChatGptPrompt.kt` | Adds: details right after their step; `&sd=` / `&sl=` (label, a space, the full address); use only addresses actually found, without tracking parameters; to enrich an existing step, repeat its exact name; finish with `&end=1`; encode web addresses too; whole link under 4,000 characters |
| Import screen | `ui/handoff/HandoffImportViewModel.kt`, `HandoffImportScreen.kt` | The validated step details survive rotation and process death (never the raw URL); the *too big* message no longer quotes the old per-item limit |
| UI | `ui/components/StepLinks.kt`, `StepEditor.kt` (new), `ui/detail/StepSheet.kt` (new), `TaskDetailScreen.kt`, `TaskDetailViewModel.kt` | Hybrid rows, sheet, editor; only Flight Plan tokens and components (ink for actions, route + dashed for *from AI*, `AutopilotSheet`, `ButtonPair`, `LinkButton`) |
| Tests | see §4 | |
| Release | `app/build.gradle.kts` | `versionCode` 2 → 3, `versionName` 0.2.0 → 0.2.1 |

### Differences from the feasibility report

- **Address limit 2,000** instead of 1,000. One shared limit serves the editor, backups and the link; long product variants fit.
- **Ambiguous names:** the report preferred an open step over a done one. The implementation never chooses between two steps with the same name: the user picks.
- **Provenance marker:** kept on the row as before (an existing control with tests), and also in the sheet.
- **Paste a URL into *Add a step*** (in the prototype, not in the approved requirements) is not implemented. Creation stays one plain field.

## 3. Acceptance criteria

| Requirement | Evidence |
| --- | --- |
| Compact checklist; main link one tap; title opens the sheet; box only ticks | `RichStepsUiTest.theChecklistStaysCompactWithTheMainLinkOnTheRow`, `theBoxOnlyTicksTheTitleOnlyOpensAndTheLinkOnlyOpensTheBrowser` |
| Description and multiple labelled links per step; manual creation fast | `RichStepsUiTest.theEditorChangesDetailsAndKeepsDoneStateAndOrder`, `manualStepCreationIsStillOneField`; `RichStepsTest.theStepEditorSavesDetailsAndKeepsDoneStateOrderAndOrigin` |
| Completed steps not folded | `theChecklistStaysCompactWithTheMainLinkOnTheRow` |
| Room v8 → v9, older versions, existing data kept | `MigrationTest.migrateV8ToV9AddsStepDetailsAndKeepsEveryStepAsItWas`, `aSchemaV1DatabaseMigratesAllTheWayToTheCurrentVersionWithItsSteps` (plus the existing 1→2 … 7→8 tests, which now open at v9); device: `InstrumentedDatabaseTest.migrate8To9AddsStepDetailsAndKeepsSteps` (MigrationTestHelper against `9.json`) |
| Backup v2; old backups; newer refused | `BackupRepositoryTest.stepDetailsSurviveExportAndImport`, `aSchema1BackupFrom020ImportsWithStepsAndNoDetails`, `aNewerBackupOrABadStepLinkIsRefusedAndChangesNothing` |
| No title-only path drops details; undo restores every field | `RichStepsTest.undoRestoresEveryFieldAndLeavesUnrelatedDetailsAlone`, `anApplyRecordFromBeforeRichStepsDoesNotWipeDetailsOnUndo`, `theStepEditorSavesDetailsAndKeepsDoneStateOrderAndOrigin` |
| `ap=v2` descriptions, labelled links, end marker; v1 unchanged; malformed or truncated refused whole | `RichHandoffTest` (7 tests), `HandoffFragmentTest`, `ImportLinkTest` |
| Long URLs and query strings | `RichHandoffTest.longAddressesWithQueryStringsFitAndTheWholeLinkIsCapped`, `StepDetailsTest.onlyRecognisedTrackingParametersAreRemovedAndVariantsKeepTheirExactBytes` |
| Rich step reviewed as one item, editable, applied | `RichHandoffBridgeTest.aNewRichStepIsOneItemWithItsDescriptionAndLinks`, `RichStepsUiTest.aRichStepFromChatGptIsReviewedEditedAndAppliedAsOneUnit`; device: `HandoffImportDeviceTest.aRichV2LinkAppliesItsStepWithDescriptionAndLinks` |
| Details for an existing step keep wording and done state; nothing overwritten | `RichStepsTest.addingDetailsKeepsTheUsersWordsMainLinkAndDoneStateAndOnlyAddsWhatsNew`, `RichHandoffBridgeTest.detailsForTheOneMatchingStepCarryOnlyWhatIsNewAndItsStableId` |
| Ambiguous match: explicit choice | `RichHandoffBridgeTest.anAmbiguousNameWaitsForTheUserToChooseTheStep`, `RichStepsTest.anAmbiguousMatchIsNeverGuessedAndAppliesOnlyToTheChosenStep`, `RichStepsAmbiguousUiTest` |
| Duplicate links; repeat import and apply idempotent | `StepDetailsTest.linksAreCleanedDeduplicatedAndCapped`, `RichStepsTest.repeatedImportsAndRepeatedApplicationAreIdempotent`, `RichHandoffBridgeTest.aNameRepeatedInTheLinkBecomesOneUnit` |
| http/https only; domain shown; never auto-opened; opening never completes | `StepDetailsTest.onlyHttpAndHttpsWebAddressesAreAccepted`, `theDomainIsShownWithoutWww`, `theBoxOnlyTicks…` |
| Light/dark, large text | `RichStepsUiTest.darkThemeRendersTheSameControls`, `largeTextKeepsEveryTargetReachable`; screenshot tour `r_richSteps` (§5) |
| Process death on the import screen | `HandoffImportViewModelTest.richStepDetailsSurviveProcessDeathAndReachTheSameSet` |

## 4. Verification

Local SDK installed in the session (`/opt/android-sdk`, cmdline-tools checksum matched Google's manifest). Gradle went through Google's
Maven Central mirror (an untracked `~/.gradle/init.d` script) because Maven Central rate-limited the container.

| Command | Result |
| --- | --- |
| Baseline `./gradlew testDebugUnitTest` at `8463ca0` (clean worktree) | BUILD SUCCESSFUL, 459 tests, 0 failures |
| `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` on the final code (before the version bump) | BUILD SUCCESSFUL in 6m 13s. Lint: *No issues found*. **501 unit/Robolectric tests, 0 failures** (459 at baseline; 42 new). Debug, release and androidTest APKs built |
| `./gradlew assembleRelease` after the bump + `.github/scripts/verify-release-apk.sh … 3 0.2.1` | Every check OK: application ID, versionCode 3, versionName 0.2.1, no `INTERNET`, `/autopilot/import` present, no `/autopilot/test`, signature verifies. **Exit 2 as expected:** signed with this container's debug key, not the personal certificate, so no locally built APK is offered for the phone |
| Emulator (`connectedDebugAndroidTest`) and the screenshot tour | Not runnable here (no emulator); CI only |

Existing tests changed only where the behaviour changed on purpose:
- `ap=v2` is now a supported version, so the "unsupported version" cases use `ap=v3`.
- The link cap is 8,192, so the oversize cases are longer.
- The prompt prefix is `ap=v2` and ends with `&end=1`.

CI evidence (GitHub Actions) is in the PR description.

## 5. Phone acceptance (owner, S24 Ultra)

Install the CI release artifact `autopilot-0.2.1-release` over 0.2.0 (same signing key). Export a backup first.

1. **Upgrade:** existing tasks, steps, reminders and saved research are all there; steps look as before.
2. **Manual rich step:** on any task, add a step by typing, tap its title, *Add a description or link*. Add a description with two lines and two links, one typed as `www.…` and one with `?utm_source=x&size=50ml`. Save. Expected:
   - the row shows the first link's pill with the domain;
   - the address was saved as `https://…`, keeps `size=50ml` and loses `utm_source`.
3. **Tap targets:** the box ticks only; the title opens the sheet; the pill opens the browser and the step stays unticked. Back returns to the app.
4. **Completed steps:** tick the rich step. It stays in place with a line-through and no pill; its sheet still opens and its links still work.
5. **Undo safety:** with that task's details in place, apply any ChatGPT change, then *Undo* in the review. The details of every step are unchanged.
6. **ChatGPT, new rich step:** *Discuss in ChatGPT* on a gift task, then ask: *"Send this to Autopilot: a step to buy Bleu de Chanel EDP 50 mL with the estimated price, gift-wrap instructions and the card text as description, and the product page as a link."* Tap *Open in Autopilot*. Expected:
   - one *New step* with its description and link;
   - *Edit* can remove the link before applying;
   - apply.
7. **ChatGPT, details for an existing step:** on a task with a step *Get a passport photo*, ask ChatGPT to add the photo requirements and one link to that step. Expected:
   - the item reads *Step details*;
   - after apply, your wording, done state and existing description are kept, and the new text follows a blank line.
8. **Research-heavy:** ask for one step with up to five official links, one with a query string. Expected: the link opens; if ChatGPT left a raw `&` in an address, Autopilot says *didn't come through right* and nothing changes.
9. **Ambiguous:** create two steps named *Call the office*, then ask ChatGPT to add a note to *Call the office*. Expected: the review asks which step, and *Select all* doesn't pick it.
10. **Repeat:** tap the same *Open in Autopilot* link again. It reopens the same review, and applying twice adds nothing.
11. **Old link:** a link from a conversation started on 0.2.0 (`ap=v1`) still opens its review.
12. **Backup:** export, then import on the same phone. Descriptions and links are intact.
13. **Large text / dark:** system font 1.3 and dark theme. Pill, sheet and editor are readable and every control is reachable.

## 6. Limits and open items

- The ChatGPT app's handling of a 3–4 KB link, and how reliably ChatGPT encodes addresses with query strings, are only provable on the phone (§5 steps 6–8).
- Emulator results come from CI; they don't prove real-device notification behaviour or App Link verification. This change touches neither.
- Descriptions and links are not sent *to* ChatGPT by *Discuss in ChatGPT*; only step titles are shared, as before (privacy default from the feasibility study).
