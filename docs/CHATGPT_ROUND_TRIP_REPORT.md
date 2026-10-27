# ChatGPT Round Trip: Implementation Report

Date: 2026-10-27 · Branch: `claude/fervent-archimedes-5dhxmq` (from `ccr-125b38a8-btl7ka` @ `62af1cd`) · No PR opened · Nothing merged

**Goal.** Autopilot task → Discuss in ChatGPT → natural conversation → "Send this to Autopilot" → **Open in
Autopilot** → Proposal Review → Apply → Return to ChatGPT. There's no backend, no copying or pasting, no scraping
and no AI API call. UX design: `docs/CHATGPT_ROUND_TRIP_UX.md`. Earlier evidence: `docs/CHATGPT_APP_LINK_SPIKE.md`,
`docs/CHATGPT_APP_LINK_HANDOFF.md`.

## 1. Status

| Gate | State |
| --- | --- |
| UX design note | **Done** (`docs/CHATGPT_ROUND_TRIP_UX.md`) |
| Discuss in ChatGPT (outbound) | **Done**, unit and Robolectric tests |
| `ap=v1` + `r=` reference, `/autopilot/import` route | **Done**, unit, Robolectric and emulator tests |
| `ProposalSource.HANDOFF` → canonical review / apply / undo | **Done**, Room-backed tests |
| Local gate (lint, all unit tests, debug/release/androidTest builds) | **Green**: 751 tests, 0 failures. Lint: no issues (§7.1) |
| CI on this branch | **Green**: run #180, including 28/28 emulator tests (§7.2) |
| Release APK signed with the personal key | **Built by CI** run #180; certificate `A5:C5:…:21:46` matches the published `assetlinks.json` (§7.2) |
| Real-phone acceptance test | **Passed on S24 Ultra**: owner verified share, return link, correct task, two applied changes, and return to same ChatGPT conversation (§8) |
| Outbound intent from Autopilot to ChatGPT on the phone | **Passed**: ChatGPT composer received prepared task message and user sent it (§8) |

## 2. What was built

| Area | Files | What it does |
| --- | --- | --- |
| Outbound prompt | `share/chatgpt/ChatGptPrompt.kt` (`ChatGptPrompt`, `DiscussChoices`) | Builds the prefilled message: a conversational opener, then the task context the user chose, then the short link instructions after a `---` divider |
| Outbound share | `share/chatgpt/ChatGptPrompt.kt` (`ChatGptShare`) | `ACTION_SEND` `text/plain`. Targets `com.openai.chatgpt` directly when it accepts shared text, otherwise opens the system chooser without Autopilot's own share target. No `FLAG_ACTIVITY_NEW_TASK`, the same as an ordinary share |
| Detail UI | `ui/detail/DiscussInChatGpt.kt`, `TaskDetailScreen.kt` | A "Discuss in ChatGPT" card above the AI card for every open task, whatever the AI settings. A sheet shows what ChatGPT will see: task (always), steps, deadline and notes (off by default). It also has "See the full message" and **Open ChatGPT** (or **Share…** when ChatGPT isn't installed) |
| URL contract | `share/applink/HandoffLink.kt`, `TestLink.kt`, `ImportLink.kt` | Optional `r=` in `ap=v1`. A shared `AppLinkEnvelope` for scheme, host, path and length. `ImportLinkParser` for `/autopilot/import`. `TaskRef` checks the reference's form |
| Bridge | `share/applink/HandoffProposalBridge.kt` | `LinkHandoff` → canonical `ProposalSet` (§4) |
| Import | `share/applink/HandoffImporter.kt` | Resolves the reference only, saves the set (idempotent), discards an untouched set when the destination changes, and creates an explicitly requested new task |
| Import UI | `ui/handoff/HandoffImportActivity.kt`, `HandoffImportViewModel.kt`, `HandoffImportScreen.kt` | The link's own activity and task. It hosts the existing `ProposalReviewScreen` and `ProposalReviewViewModel`, plus the task picker, outcome cards and destination card |
| Review | `ui/review/ProposalReviewScreen.kt`, `ReviewPresenter.kt`, `ProvenanceSheet.kt` | Three optional parameters (exit label, back icon and description, header slot). Source labels moved into exhaustive `when`s. For handoff sets, "Select all" instead of "Select recommended" and no "Recommended" badge |
| Apply path | `data/repository/ProposalApplier.kt` | `HANDOFF` origin (`USER`), history key `chatgpt_handoff`, label "ChatGPT". Close rule: open while anything is pending, like research sets |
| Labels elsewhere | `TaskDetailScreen.kt` (review banner, provenance icon), `ProvenanceSheet.kt` | "From ChatGPT" |
| Manifest | `AndroidManifest.xml` | `HandoffImportActivity` with one `autoVerify` filter for `https://${appLinkHost}/autopilot/import` (`singleTop`, `taskAffinity=""`, `persistNever`), and `<queries><package com.openai.chatgpt/></queries>` |
| Static site | `applink-site/autopilot/import.html`, `applink-site/README.md` | Fallback page for the new path. It never shows or sends the fragment, and its button hands the fragment to the app by package name. `assetlinks.json` is unchanged |
| Wiring | `di/AppContainer.kt` | `handoffImporter` |

Not changed (at the time; the share-import PoC and `HandoffParser` were later removed, §12): Plan with AI, Research Online, `autopilot.handoff.v1` / `HandoffParser`, the share-import PoC, the
`/autopilot/test` route and screen (apart from naming `r` in its messages and field count), reminders,
scheduling, Room schema and migrations (no new tables or columns), the signing config and `assetlinks.json`.

## 3. URL contract changes

```
https://hassanjaveed84.github.io/autopilot/import#ap=v1&r=<task ref>&t=<title>&d=<decision>…&s=<step>…&n=<note>…
```

| Change | Rule |
| --- | --- |
| **New optional field `r`** | Opaque task reference. Today it's the task's id (a UUID, `[A-Za-z0-9_-]{1,64}`). At most once, anywhere after `ap=v1`, ≤ 64 characters, the same strict decoding as every value. It identifies, it never authorises. A malformed or unknown reference resolves to nothing, and the user picks the task |
| **Backward compatible** | Still version `v1`. Every previously valid link (`t`, `d`, `s`, `n` only) parses exactly as before, and `#message=` on `/autopilot/test` is untouched. Builds from before this branch refuse a link containing `r` as an *unknown field* rather than misreading it |
| **New path `/autopilot/import`** | The production route. Same envelope as `/autopilot/test`: `https`, exact host, no user info, default port, exact path, query ignored |
| **Length** | Import route: whole URL ≤ **4,096** characters. Test route: unchanged at 2,048. The prompt asks ChatGPT to stay under 2,000. Item caps are unchanged: 5 decisions, 10 steps, 5 notes, each ≤ 200 characters, title ≤ 120 |
| **Title** | `t` stays required. It's context and a cross-check only, never a match key and never a rename. The prompt pre-encodes the title, shortened with "…" if it's over 120 characters |
| **Failure** | Refusals are grouped into four messages: didn't come through right, more than one link can carry, needs a newer Autopilot, or not a handoff. Values are never echoed, and nothing is truncated to fit |

**Budget.** The prefix (route, `ap=v1`, a 36-character reference and an encoded title) is about 90–130
characters, which leaves well over 3,900 for content. ASCII text costs about 1.1 URL characters per character
(spaces become `%20`). Non-Latin text costs 6–12 per character, so a full handoff written in such a script won't
fit and is refused with the "more than one link can carry" message.

## 4. Proposal mapping (`HandoffProposalBridge`)

| Link field | Proposal | Notes |
| --- | --- | --- |
| `s` | `ADD_STEP`, appended at the end, origin `USER` | Skipped when the task already has it, done or not, or the link repeats it. Matching uses the normalisation planning uses (`ProposalValidator.normalizeKeyPublic`: case and punctuation-insensitive). Never added as done. Existing steps are never edited or removed |
| `d` | `APPEND_NOTE` "Decided: …" | Not prefixed again if it already starts with "Decided" or "Decision". Skipped if the notes already contain that line |
| `n` | `APPEND_NOTE` as written | Skipped if the notes already contain that line |
| `t` | none | Destination card only ("ChatGPT called it …") |
| `r` | none | Destination only |

All items are plain (not material), recommended, and **not pre-selected**. The set's id is
`hps-` + the first 12 bytes of SHA-256(task id + the ordered `d`/`s`/`n` values). The same changes for the same
task always give the same set, so a repeated link reopens it (pending, partly applied, or settled) and never
proposes twice. A link with nothing new saves nothing and says *Already in Autopilot*.

**Why steps from ChatGPT are stored with origin `USER`.** They're the user's own decisions, approved in review.
Storing them as `USER` means a later AI suggestion to edit or remove one counts as a material change. Provenance
(`source = HANDOFF`, set and item ids, applied text) records that they came from ChatGPT. That avoided adding a
new `ContentOrigin` value, which would have touched backups and older builds.

**Write path.** Link → `HandoffImporter.prepare` saves a pending `ProposalSet` → the existing Proposal Review
→ `ProposalApplier.apply`, in one Room transaction, reconciled against the live task, with provenance and
history → `ProposalApplier.undo`. There's no other write. The only exception is **New task** in the picker, which
creates an empty Inbox task with ChatGPT's title through the existing `TaskRepository.createCapturedTask`, on an
explicit tap, keyed by the handoff so it's made once.

## 5. Navigation and lifecycle

- **Import activity in its own task.** It's the same shape as the App Link test activity that already returned to
  ChatGPT on the phone. Close, ✕ and system Back call `finishAndRemoveTask()` when the screen is its task's root,
  and `finish()` otherwise. If Android ever stacks the screen on the caller's task, closing it never closes
  ChatGPT with it.
- **Never Today.** `MainActivity` and its back stack aren't involved. "Open the task in Autopilot", shown after
  applying, deliberately leaves for the main app and closes the import task.
- **Cold start** reads the intent. **Warm start** (`singleTop` `onNewIntent`) replaces the state.
  **Rotation and process death**: the view model keeps the validated fields, the chosen task, the set id and the
  stage in `SavedStateHandle`, never the raw URL. A screen that died mid-step redoes it, which is idempotent.
  The review keeps its selection, edits and conflict choices as it always has.
- **Recents.** While open, the import screen is a separate "Autopilot" card. It's removed when closed, and
  `persistNever` keeps the launch intent, which carries the handoff, out of the recents store across reboots.
- **Outbound.** The share uses no `FLAG_ACTIVITY_NEW_TASK`, the same as sharing from Samsung Notes. Depending on
  how ChatGPT declares its share activity, the composer may open on top of Autopilot's task. Back from ChatGPT
  then returns to the task, which helps if the user cancels. **Verified on S24 Ultra by owner:** share opened ChatGPT and the user sent the prepared prompt.

## 6. Privacy and safety

- **Leaving Autopilot.** Only what the sheet shows is shared: title and reference always, steps and deadline by
  default, notes only when switched on. Nothing leaves until the user presses send in ChatGPT. Autopilot makes no
  network call for this feature.
- **Coming back.** The link is untrusted input, run through strict validation (§3). No AI interprets it.
  Nothing is fetched or scraped. A matching reference only preselects the task, which the user sees and can
  change. Nothing changes without **Apply**.
- **Where the content lives.** The fragment isn't sent to `hassanjaveed84.github.io` when Android opens the
  app. If a browser shows the fallback page, the browser still doesn't send it. But the content does appear in
  the ChatGPT conversation (on OpenAI's servers, under the user's ChatGPT account and settings), in the Android
  intent while the screen is open, and in the saved instance state while the screen exists. Autopilot then
  stores only the pending proposal set, a normal part of the task's data that's included in Autopilot's own
  backup export.
- **The reference** is the task id. It isn't a secret and grants nothing. Anyone who could send you a link
  could equally make a link without one.
- **No logging** of URLs or task content was added, and refusal screens don't echo values.
- **Signing and verification.** Signing and `assetlinks.json` are unchanged. The new path is on the
  already-verified host, and Android verifies per host, so the same statement covers it.

## 7. Verification

### 7.1 Local (this container: JDK 21, SDK 36, no KVM so no emulator)

| Command | Result |
| --- | --- |
| Baseline `./gradlew compileDebugUnitTestKotlin` at `62af1cd` | The first attempt hit **HTTP 429 from Maven Central** while resolving KSP, as earlier sessions did. The retry succeeded |
| Focused runs while building | The first runs found only test mistakes, fixed in the tests: a title-length assertion, a share test started from the application context instead of an activity, a merged versus unmerged semantics selector, and Robolectric's own referrer making the exit read "Close" (correct behaviour). One UI fix came out of testing: the outcome card and the always-shared row now merge their semantics, so TalkBack reads each as one item. A known Robolectric hang, `ActivityScenario` close or `recreate` after a direct `onNewIntent`, was avoided by covering warm delivery in the view-model and emulator tests |
| `./gradlew --stacktrace lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` (`d8e746f`) | **1 failure**: `HardeningTest.returningFromExactAlarmSettingsUpgradesExistingAlarms` got diagnostic outcome `new` instead of `mode_changed`. It's a reminder test this branch doesn't touch. It passed 3 of 3 times when rerun alone, and in CI run #178 and the rerun below. It reads the newest of two diagnostics written in the same instant, so it depends on timing under load. Left unchanged here: it isn't in this change's scope, and nothing in the reminder code changed |
| `./gradlew --continue lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --rerun-tasks` | **BUILD SUCCESSFUL**. **751 tests, 0 failures, 0 errors** (base `62af1cd` had 702; this branch adds 49). Lint: **No issues found**. Debug, release and androidTest APKs built |

### 7.2 CI

| Run | Commit | Result |
| --- | --- | --- |
| [#178](https://github.com/HassanJaveed84/autopilot/actions/runs/40459515226) | `d8e746f` | Build job **green**: lint, the full unit and Robolectric suite including `HardeningTest`, and debug, release and androidTest APKs. Signed with the personal key: `A5:C5:6F:43:16:77:81:B6:8B:5F:10:70:BF:36:A6:AF:17:76:D5:6C:1D:BB:B8:8D:C2:44:BD:BD:A2:56:21:46`, the published `assetlinks.json`. Emulator job **red: 2 of 28, both in the new `HandoffImportDeviceTest`**. (1) The exit read "Close" because the instrumentation, not ChatGPT, was the referrer. That's correct app behaviour, so the test now sets ChatGPT as the referrer. (2) `ActivityMonitor.hits` doesn't count system-started activities, so `singleTop` is now proven by the same instance receiving the next link |
| [#179](https://github.com/HassanJaveed84/autopilot/actions/runs/40461702830) | `81655f5` | Build job **green**, same certificate. Emulator job **red: 2 of 28, the same new test**. (1) Its `waitFor` matched button text in the unmerged semantics tree; it now uses the merged tree, as the Robolectric tests do. (2) The repeat-link note wasn't found within 15 s after three warm deliveries. The same sequence passes in `HandoffImportViewModelTest.aDifferentLinkForTheSameTaskThenTheFirstAgainIsARepeat`, and the note itself is checked by `HandoffImportActivityTest.aRepeatedLinkReopensTheSameReview`. The device test now checks the device-level facts: the same instance takes each link, no set is duplicated, no step is written. **The repeat note on a real device stays a phone check (§8 step 11)** |
| [#180](https://github.com/HassanJaveed84/autopilot/actions/runs/40464545048) | `b4c675a` | **Green.** Build job: lint, the unit and Robolectric suite, and debug, release and androidTest APKs (BUILD SUCCESSFUL in 10m 18s). The release APK is signed with the personal key `A5:C5:6F:43:…:56:21:46`, identical to the published `assetlinks.json`. Emulator job (API 34): **28 tests, 0 failed** (the 26 from before plus the 2 new `HandoffImportDeviceTest` cases) |

**Installable release APK:** run #180 → artifact [`apks`](https://github.com/HassanJaveed84/autopilot/actions/runs/40464545048/artifacts/12716273694)
→ `release/app-release.apk` (personal key, package `com.lifeadmin.autopilot`). Downloading needs a GitHub login.
Later commits on this branch change only docs, which CI skips, so this is the APK for the branch head.

### 7.3 Coverage of the requested tests

| # | Requirement | Tests |
| --- | --- | --- |
| 1, 3 | Outbound prompt, task context, reference | `ChatGptPromptTest` (conversation first; defaults; each choice; exact deadline time; the reference and pre-encoded title start a valid link) |
| 2 | Android share intent, missing ChatGPT | `ChatGptShareTest` (plain `SEND`, no `NEW_TASK`, direct when installed, chooser without Autopilot's own target otherwise), `DiscussInChatGptUiTest` |
| — | Works with AI off, no task write | `DiscussInChatGptUiTest` (AI off, no key; notes off until switched on; task `rev` unchanged) |
| 4 | Parsing the returned link | `ImportLinkTest`, existing `HandoffFragmentTest` and `TestLinkParserTest` unchanged and passing |
| 5, 6 | Valid, invalid, missing or stale reference | `HandoffImporterTest.aValidReferencePreselectsItsTaskAndATitleAloneNeverDoes`, `HandoffImportActivityTest` (picker, stale reference) |
| 7, 8 | Mapping, dedup (existing, done, within link) | `HandoffProposalBridgeTest`, `HandoffImporterTest.preparingSavesOnlyAPendingSet…` |
| 9 | Duplicate handoffs, repeat apply | `HandoffImporterTest.aRepeatedLinkReopensTheSameSet…`, `HandoffImportActivityTest.aRepeatedLinkReopensTheSameReview`, `HandoffImportViewModelTest`, `HandoffImportDeviceTest` (warm, emulator) |
| 10 | Conflicts after task edits | `HandoffImporterTest.editsSinceTheConversationGoThroughTheReconciler`, `aDoneTaskBlocksEveryChange` |
| 11 | Review before write | `HandoffImporterTest.preparingSavesOnlyAPendingSet…`, `HandoffImportActivityTest` (no steps until Apply) |
| 12 | Apply and undo | `HandoffImporterTest.applyAndUndo…` (provenance `HANDOFF`, history "from ChatGPT", undo restores, completed step kept) |
| 13 | Back, Close, cold, warm, recreation | `HandoffImportActivityTest` (cold, back/close, change → back, recreation keeps selection), `HandoffImportViewModelTest` (warm replace, process death), `HandoffImportDeviceTest` (system `NEW_TASK` launch is task root; apply; Return closes; `singleTop` repeat) |
| 14 | Existing App Link and reminders | `AppLinkTestActivityTest`, `AppLinkDeviceTest`, `ManifestContractTest` (both routes, queries), `HandoffImportActivityTest.eachRouteResolvesToItsOwnScreen…`, `HandoffImporterTest` (reminder policy identical after apply), full reminder suites |
| 15 | Backup compatibility | `BackupRepositoryTest.aChatGptHandoffSetAndItsProvenanceRoundTrip` (and a repeat link after restore finds the restored set) |

## 8. Real-phone test (owner, Galaxy S24 Ultra) — PASSED

Install the release APK from §7.2 over the current app. It uses the same personal key, so data is kept and the
domain stays verified. Android re-verifies on update, and the new path needs no change to `assetlinks.json`.
Optionally, publish `applink-site/autopilot/import.html` to the `HassanJaveed84.github.io` repo as
`autopilot/import.html`. It only matters if a browser ever shows the link.

1. In Autopilot, create **Choose a coffee grinder** and add a step or two (tick one).
2. Open the task. Under the steps, tap **Discuss in ChatGPT**.
3. Check the sheet: Task, Steps, Deadline (if set); **Notes off**. Optionally tap **See the full message**.
4. Tap **Open ChatGPT**. Record whether ChatGPT opened **directly** with the text in the composer, **not sent**,
   or whether a chooser appeared. This is the first test of the outbound intent from Autopilot itself.
5. Send it and talk normally: compare grinders, decide on one, agree next steps.
6. Say **"Send this to Autopilot."** ChatGPT should reply with **Open in Autopilot**.
7. Tap it. Expected: Autopilot opens directly to **Review changes · From ChatGPT**, the destination card reads
   **Adding to Choose a coffee grinder**, and the steps and "Decided: …" notes are listed.
8. Tap **Select all**, then **Apply N changes**, then check **Changes applied**.
9. Tap **Return to ChatGPT** and confirm the same conversation is showing.
10. Back in Autopilot, open the task: the new steps, the notes and "From ChatGPT" provenance (ⓘ on a step).
11. Optional edge checks: tap the same link again (*You've already brought this in*, nothing added twice).
    Ask ChatGPT to send one more step and apply it. Long-press a link, copy it and share it to yourself to keep
    as a record if anything fails.

Record for step 4: **direct** / **chooser** / **failed**. For step 7: **Autopilot directly** / **browser first**,
whether the right task was identified, and any message shown.

## 9. Known limitations and next steps

- **Deadlines and reminders aren't carried.** Adding them means a date grammar ChatGPT must write reliably and
  separate material proposals. That was deferred by design. Dates in notes are never read as deadlines. It's the
  natural next enhancement, for example `due=YYYY-MM-DD` mapped to `SET_DEADLINE`, or `SET_TARGET` when a
  deadline exists, always material.
- **A single link has limits** (§3). There's no compression or remote storage.
- **Which app opened the link** comes from Android's referrer. If another app opened it, the button reads Close.
- **ChatGPT's own behaviour** (keeping the reference intact, encoding correctly, waiting until asked) depends on
  the model. The app refuses bad links clearly, and a missing or wrong reference falls back to the picker.
- **No new-task creation from the share sheet side.** The handoff's new-task option is basic by intent: an Inbox
  task with the title only.
- **Resolved on `chatgpt-roundtrip-polish`:** deleted tasks previously left orphaned proposal sets and
  provenance records; restoring the resulting backups could fail. The polish branch cleans both types on task
  deletion and filters legacy orphaned records during backup export. Existing already-exported broken backup
  files still require separate recovery work if encountered.


## 10. Phone acceptance and post-review fixes (2026-10-27)

The owner verified the entire round trip on the S24 Ultra: a saved task's **Discuss in ChatGPT** action shared the prepared context; ChatGPT generated **Open in Autopilot**; the link opened the existing task's canonical review; a step and a budget note were applied; **Return to ChatGPT** returned to the same conversation.

Follow-up branch `chatgpt-roundtrip-polish` (on top of the feature branch) addresses the review findings:

- Hide the pre-apply explanation after successful application.
- Exclude proposal sets and provenance records for deleted task/draft targets from backup exports; retain strict import validation.
- Delete task-targeted proposal sets and provenance with the task in a Room transaction, preventing the same handoff from binding to stale applied proposals if a task ID is recreated.
- Strengthen the regression test to apply a step, capture its provenance, delete the task, simulate orphaned records from an older database, and restore the exported backup.

These changes are pending a fresh green CI run at the time of this update. No PR opened and nothing merged. Note that changing the destination of an already-existing pending set leaves the old set reviewable on the original task by design; this behavior avoids deleting user-created proposal history.

## 11. Emulator CI failures in run #188: root cause and fix (2026-10-27)

Run [#188](https://github.com/HassanJaveed84/autopilot/actions/runs/40534401725) (`2be74b2`) passed its build job (lint, 754 unit/Robolectric tests, APKs). Its emulator job failed in both attempts:

| Attempt | Failing test | Exact failure (from the uploaded `instrumented-reports`) |
|---|---|---|
| 1 | `HandoffImportDeviceTest.aRepeatedLinkReachesTheOpenScreenAndAddsNothing` | `ComposeTimeoutException` at line 99: after the **third** delivery, the destination title never came back within 15 s |
| 2 | same test | `AssertionError: expected:<2> but was:<1>` at line 101: only one proposal set for the task after three deliveries |
| 2 | `InstrumentedUiTest.captureThenMarkWaitingWithTypedReason` | `ComposeTimeoutException` at line 46: the task never became Waiting within 10 s of tapping **Start waiting** |

The last fully green emulator runs were #180 (`b4c675a`) and #183 (`04100dc`).

### Repeat-link test: an app defect plus a test assumption (confirmed)

**App defect.** `HandoffImportViewModel.onLink` cancels the job of the link it replaces. All four `catch (_: Exception)` blocks in the view model also caught that job's `CancellationException` and published `FAILED` from `_state.value`, which by then belonged to the **newer** link. A Room call doesn't return to a cancelled job until its blocking work finishes, so when the replaced link's call outlasted the newer link's whole import (a repeat needs only two reads), the newer link's review was overwritten with "Something went wrong". On the device test's third delivery this is exactly the attempt-1 symptom (no destination title), and it explains #179's missing repeat note. A person tapping a second ChatGPT link while the first is still preparing could hit the same thing.

Evidence: `HandoffImportViewModelTest.aLinkArrivingMidImportKeepsItsReviewWhenTheReplacedWorkReturns` parks the replaced link's first database call (a test dispatcher for Room), lets the newer link reach its review, then releases the call. Without the fix it fails 3 of 3 runs with `expected stage=REVIEW … existed=true but was stage=FAILED`; with the fix it passes 5 of 5.

**Fix.** Each catch rethrows `CancellationException`. Genuine failures still show `FAILED`. Nothing else changes: activity, launch mode, task affinity, saved state, return to ChatGPT.

**Test assumption.** The device test sent the third link once the second was *delivered* (`activity.intent` changed) rather than *prepared*. The third delivery could therefore cancel the second before it saved its set: attempt 2's count of 1. The test now waits for the second set to be saved before sending the repeat, then waits for the repeat note (`handoff_repeat`), which only the third delivery can show. That restores the check #180 had to drop.

### `captureThenMarkWaitingWithTypedReason`: independent, cause not confirmed

There's no shared state with the handoff test: different activity, different task, and the handoff test runs about six classes earlier. The failure is a Save tap that didn't take effect. The likely cause is a hypothesis, not a confirmed finding:

- Typing focuses the reason field, and the soft keyboard then opens asynchronously. The job log shows `enable-hw-keyboard: false`, so the keyboard does open.
- The sheet's pinned footer moves up with it (`imePadding`). Compose's idling doesn't wait for the keyboard, so a tap aimed just before it settles can land where Save used to be.
- The same test failed the same way before (`FLIGHT_PLAN_STABILIZATION_REPORT.md`), with a keyboard-layout cause.
- In attempt 2 the emulator's `Failed to find ColorBuffer` warning appears about 1 s before the wait began, which fits a window surface change at that moment. The same warning also appeared in attempt 1, where this test passed, so it is not a failure on its own.

The test now waits until the Save button sits above the open keyboard before tapping, for both the Capture and Waiting sheets. A button left behind the keyboard still fails, as it should. No timeouts were raised.

### Verification

- Local (no KVM, so no emulator): `./gradlew lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed. **755 tests, 0 failures** (754 plus the new view-model test). The handoff suites passed: `HandoffImportViewModelTest` 6, `HandoffImportActivityTest` 11, `HandoffImporterTest` 10.
- CI on `2d10c26`: run [#189](https://github.com/HassanJaveed84/autopilot/actions/runs/40545214808) passed on its first attempt with no reruns. Build job (lint, unit/Robolectric, APKs) **green**; emulator job (API 34) **28 tests, 0 failed**.
- **Remaining risk.** One green emulator run shows these tests can pass, but it is not proof the failures are gone. The repeat-link failure is explained and reproduced. The Waiting-sheet tap remains a hypothesis: if it recurs, the new wait will fail with the button still behind the keyboard, which points to a different cause. The real-phone round trip (§8) was not repeated for this change.

## 12. Share-import proof of concept removed (2026-10-27)

The round trip passed its phone acceptance test (§8, §10), so the older share-page proof of concept
(`docs/CHATGPT_SHARE_HANDOFF_POC.md`) was removed. It fetched public `chatgpt.com/share` pages and read
undocumented page data; nothing in the round trip depended on it.

**Removed.**

| Area | Files |
| --- | --- |
| Share target | `ui/share/ShareImportActivity.kt`, `ShareImportScreen.kt`, `ShareImportViewModel.kt`; its `ACTION_SEND` `text/plain` manifest entry and the `share_import_label` string |
| Fetch and extraction | `share/chatgpt/ShareFetcher.kt` (with `UrlConnectionTransport`), `ShareExtractor.kt`, `ShareImporter.kt`, `ShareLink.kt` (`ShareLink`, `SharedTextParser`) |
| JSON handoff | `share/chatgpt/Handoff.kt` (`autopilot.handoff.v1`, `HandoffParser`); only the share import used it |
| Wiring | `AppContainer.shareImporter` |
| Tests | `ShareLinkTest`, `ShareFetcherTest`, `ShareExtractorTest`, `HandoffParserTest`, `SharePageFixtures`, `ShareImportViewModelTest`, `ShareImportActivityTest`, `ShareImportDeviceTest` (emulator) |

**Changed.** `ChatGptShare.chooserIntent` no longer excludes Autopilot's share target, since there is none;
it now takes only the text. `ChatGptShareTest` checks the chooser fallback and that no Autopilot activity
accepts shared text; `ManifestContractTest` checks the activity set is the launcher plus the two App Link
routes and that no activity declares `SEND`/`SEND_MULTIPLE`. Manifest and network-security comments now
name only the optional AI requests as network use; `INTERNET` stays for them.

**Kept.** `ChatGptPrompt` and `ChatGptShare` (direct `ACTION_SEND` to `com.openai.chatgpt`, chooser
fallback, `<queries>` entry), `ChatGptShare.PACKAGE` for "Return to ChatGPT", the `/autopilot/import`
App Link (`HandoffImportActivity`, `ImportLinkParser`, `HandoffLink`, `HandoffImporter`,
`HandoffProposalBridge`), Proposal Review, `ProposalApplier` and undo, the `/autopilot/test` spike route,
and all AI features. No migrations, backup format, signing or `assetlinks.json` changes.

**Verification.** Local (no KVM, so no emulator): `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
assembleDebugAndroidTest` passed: lint clean, **708 tests, 0 failures** (755 before, minus the 48 removed PoC tests,
plus `autopilotIsNotAShareTarget`), debug, release and androidTest APKs built. The round-trip suites are unchanged
and green (`HandoffImportActivityTest` 11, `HandoffImporterTest` 10, `ChatGptPromptTest` 5, `ChatGptShareTest` 4,
`ManifestContractTest` 11). Emulator tests run in CI on push (`ShareImportDeviceTest` removed). The real-phone round
trip was not repeated for this removal.
