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
| Local gate (lint, all unit tests, debug/release/androidTest builds) | See §7.1 |
| CI on this branch | See §7.2 |
| Release APK signed with the personal key | See §7.2 |
| Real-phone acceptance test | **Not done**: owner (§8) |
| Outbound intent from Autopilot to ChatGPT on the phone | **Not verified here**: owner (§8, step 4) |

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

Not changed: Plan with AI, Research Online, `autopilot.handoff.v1` / `HandoffParser`, the share-import PoC, the
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
  then returns to the task, which helps if the user cancels. **Not verified here; check on the phone.**

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

_filled in below_

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

## 8. Real-phone test (owner, Galaxy S24 Ultra)

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
- **Pre-existing, not changed here:** proposal sets aren't removed when their task is deleted. The backup
  importer rejects a set whose task is missing, so a backup taken after deleting a task that had any proposal set
  (planning, research or handoff) may not import. This feature makes such sets more common. A small follow-up
  should skip orphaned sets on export.
