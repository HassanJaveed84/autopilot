# Text Selection Handoff Spike (`ACTION_PROCESS_TEXT`)

Date: 2026-10-26 · Branch: `process-text-spike` (from `ccr-69eac4c4-5vsk4s` @ `f08e928`) · No PR · PR #8 untouched

**Question:** can text selected in a ChatGPT Android response go straight into Autopilot through
Android's documented text-processing contract, with no copy/paste and no public share link?

**Status:** the Autopilot side is built and verified in automated tests. **Whether ChatGPT shows
the action is unknown until the real-device test below is run.** Android supporting the API does not
mean ChatGPT's own selection menu lists third-party actions.

## What changed (small, on top of the shared-link PoC)

| File | Change |
| --- | --- |
| `AndroidManifest.xml` | A second `<intent-filter android:label="@string/process_text_label">` on the existing `ShareImportActivity`: `android.intent.action.PROCESS_TEXT`, category `DEFAULT`, `text/plain`. The `ACTION_SEND` filter is unchanged. |
| `res/values/strings.xml` | `process_text_label` = **Send to Autopilot**, the label selection menus show. The share sheet keeps "Autopilot". |
| `ui/share/ShareImportActivity.kt` | `handle()` routes `ACTION_PROCESS_TEXT` to the view model. It reads `EXTRA_PROCESS_TEXT` via `getCharSequenceExtra` (as plain text; styling and link spans dropped) and `EXTRA_PROCESS_TEXT_READONLY`. A malformed bundle or wrong extra type reads as "no text". **No result is ever set**, so the sender's text is never replaced, read-only or not. Other actions behave as before. |
| `ui/share/ShareImportViewModel.kt` | New states `Selection(text, receivedChars, readOnly)` and `EmptySelection`. The same selection again is a no-op; a new one replaces the screen. Selected text and a later share link replace each other. Text is capped at **20 000 chars** kept (the full count is still shown), without splitting a surrogate pair. For process recreation it sits in `SavedStateHandle`, in-memory saved state. Never Room, never logs. |
| `ui/share/ShareImportScreen.kt` | Title **Selected text** (vs "Import from ChatGPT"). Shows "Received through the text-selection menu", the character count, read-only/editable status, the line "This is not a shared link: nothing was downloaded, saved or sent, and your tasks are unchanged.", a 2 000-char preview, and **Close**. Close finishes the activity, which ran in ChatGPT's task, so it returns to ChatGPT. |

Not done, by design: no URL fetch, no AI call, no handoff parsing, no task writes, no new activity,
and no change to the shared-link importer.

## Tests

| Requirement | Tests |
| --- | --- |
| Manifest registration | `ManifestContractTest.shareTargetAcceptsOnlyPlainTextAndTheLauncherIsUnchanged` (exactly one SEND and one PROCESS_TEXT filter, both `DEFAULT` + `text/plain`, label only on PROCESS_TEXT), `processTextLabelIsSendToAutopilot`; `ProcessTextActivityTest.theSystemOffersSendToAutopilotForSelectedText` (resolver returns our activity for both actions); `ShareImportDeviceTest.theSystemListsSendToAutopilotForSelectedText` (emulator, CI: real `PackageManager` reports the label **Send to Autopilot**; Robolectric reports the activity label instead, so the label is only asserted there) |
| `EXTRA_PROCESS_TEXT` extraction | `selectedTextIsShownExactlyWithItsCount`, `styledTextArrivesAsPlainText` |
| Missing / malformed input | `missingOrBlankTextShowsNothingArrived` (absent, empty, whitespace, wrong extra type), `ShareImportViewModelTest.blankSelectionsAreEmpty` |
| Oversized input | `oversizedTextIsCountedAndCapped`, `ShareImportViewModelTest.aSelectionIsCappedWithoutSplittingACharacter` |
| Read-only behavior | `theSenderNeverGetsReplacementText` (read-only true / false / absent → `RESULT_CANCELED`, no `EXTRA_PROCESS_TEXT` returned), `anEditableSelectionIsLabelledAsLeftUnchanged` |
| Repeated intents, warm launch, recreation | `repeatedAndNewSelectionsAndRecreation`, `ShareImportViewModelTest.aSelectionReplacesALinkPreviewAndSurvivesProcessRecreation` |
| Unexpected actions | `anUnexpectedActionIsNotTreatedAsSelectedText` |
| `ACTION_SEND` intact | `aShareAfterASelectionStillOpensTheLinkFlow` plus every existing `ShareImportActivityTest` / `ShareImportViewModelTest` / `ShareImportDeviceTest` case |
| No task writes, no network | `selectedTextIsShownExactlyWithItsCount` (task count unchanged), `ShareImportViewModelTest.selectedTextIsShownWithoutAnyNetworkAndCanNotBeFetched` (transport call count 0) |

Results are in §Results below.

## Real-device test (Samsung Galaxy S24 Ultra)

1. Install the APK from this branch's CI run. It is signed with the personal key, so it installs over the current app.
2. **Control test first (proves registration):** open Chrome or Samsung Notes, long-press a word, and
   open the selection toolbar's **⋮** overflow. **Send to Autopilot** should be listed. Tap it: the
   Autopilot **Selected text** screen should show that word and its character count. If it is
   missing here too, the install or registration is the problem, not ChatGPT.
3. Open **ChatGPT** and long-press inside an **assistant response**. Drag the handles to select a
   paragraph.
4. Look at the selection menu, then open its **⋮** overflow if there is one, and swipe the toolbar
   if it scrolls.
5. If **Send to Autopilot** is listed, tap it. Expected: Autopilot opens on **Selected text**, showing
   "Received through the text-selection menu", the exact text and its character count. Tap **Close**:
   you are back in ChatGPT with the selection unchanged.
6. Also try the same in ChatGPT's **message input box** (type some text, select it). Editable text
   fields often use the system menu even where message bubbles do not.
7. Record which of steps 2, 4, 5 and 6 worked.

**Genuine `PROCESS_TEXT` vs ChatGPT's own commands.** Third-party entries come from Android's resolver:
- they appear in *every* app's selection menu;
- they carry each app's label ("Send to Autopilot", "Translate", "Ask ChatGPT" from other apps);
- tapping one opens *that* app's screen.

ChatGPT's own commands, such as its in-app "Ask ChatGPT", act inside ChatGPT and appear only there.
Two checks:
- If ChatGPT's "Ask ChatGPT" also appears in Chrome's selection menu, ChatGPT registers its own
  `PROCESS_TEXT` handler. Seeing it inside ChatGPT then suggests ChatGPT may show system actions.
- The only conclusive evidence is **Send to Autopilot** appearing in ChatGPT and opening the
  Autopilot **Selected text** screen with the exact text.

**Success:** the exact selected text appears on the Autopilot **Selected text** screen.
**Failure:** Send to Autopilot appears in Chrome or Notes (step 2) but not in ChatGPT. Then ChatGPT does
not expose third-party text-processing actions. Record it here; per scope, no accessibility,
clipboard or UI-scraping workaround is attempted.

## Limitations

- **ChatGPT decides.** An app that draws its own selection toolbar (common in Compose and custom
  views) shows third-party actions only if it queries `PROCESS_TEXT` handlers itself. Android 11+
  package-visibility rules also apply to that query. Nothing on the Autopilot side can change this.
- The Samsung One UI selection toolbar can hide extra actions behind overflow or a scrolling row.
  Check both.
- Receiving text is not a handoff. Parsing an `autopilot-handoff` block from the selection (the
  shared-link PoC's §6 fallback) and the Proposal Review bridge remain separate, unapproved steps.
- Selected text lives only in memory and in-memory saved state for this screen. It is lost when the
  screen is closed.

## Results

| Command (this container, no KVM) | Result |
| --- | --- |
| `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` | **BUILD SUCCESSFUL**: lint **0 issues**; **670 tests, 0 failures** (655 on the base branch + 15 new: 10 `ProcessTextActivityTest`, 4 `ShareImportViewModelTest`, 1 `ManifestContractTest`); debug, release and androidTest APKs build |
| Instrumented `ShareImportDeviceTest.theSystemListsSendToAutopilotForSelectedText` | Runs in CI on the API 34 emulator (see CI below) |

Found while testing: `ActivityScenario.close()` hangs once `onNewIntent` + `setIntent()` swaps in an intent
with a different action. The scenario looks its activity up by the launch intent. This affects the test
harness, not the app. The test restores the launch intent before closing.

### Real device

Not yet performed (owner to run the test above).
