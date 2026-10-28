# ChatGPT Share Handoff — Feasibility Proof of Concept

Date: 2026-10-26 · Branch: `ccr-69eac4c4-5vsk4s` (from `right-time-nudges` @ `197d8e0`) · No PR opened · PR #8 untouched

> **Retired 2026-10-27.** The code described here (share target, `ShareFetcher`, `ShareExtractor`,
> `ShareImporter`, `ShareLink`, `autopilot.handoff.v1` / `HandoffParser` and their tests) was removed
> from the app after the App Link round trip passed its phone acceptance test
> (`docs/CHATGPT_ROUND_TRIP_REPORT.md` §12). Autopilot no longer fetches `chatgpt.com/share` pages
> and no longer appears in the Android share sheet. This document is kept as historical evidence.

**Scope (owner-approved for this session):** a feasibility experiment only. It proves whether a ChatGPT
Android "Share" link can carry a structured handoff into Autopilot. It does **not** create, edit or
propose task changes, does not call any AI provider, and is not connected to `ProposalApplier`.

## 1. Verdict: CONDITIONAL GO

The transport works today. A plain anonymous HTTPS GET of a real `chatgpt.com/share/<id>` link
(the owner's sample) returned the full conversation text with no cookies, tokens or JavaScript
execution. The latest assistant response can be identified reliably, and missing shares are
distinguishable. It is **conditional** because:

1. **Undocumented format.** The text lives in React Router's internal turbo-stream loader data, not
   in any documented API. ChatGPT can change it without notice. The extractor fails closed
   (`Unrecognized`) and never falls back to page metadata, but a format change would break import
   until the parser is updated.
2. **OpenAI's Terms of Use** list, under what users may not do, automatically or programmatically
   extracting data or Output from the Services. A single fetch the user starts, of their own public
   share link, is not bulk scraping, but it is programmatic extraction from chatgpt.com. This is
   the owner's call for a personal app and is not a legal opinion. If it is unacceptable, use the
   fallback in §6, which never contacts chatgpt.com.
3. **Privacy.** A share link is public to anyone who has the URL (`is_public: true`, indexing off
   in the sample). The import screen says so.
4. **Not yet verified on a phone.** Nobody has shared from the real ChatGPT Android app into this
   build. Intent resolution is tested in Robolectric and in an instrumented test that CI runs on
   an emulator.

## 2. How the shared link behaved (structural findings only)

All observations come from the owner's sample on 2026-10-26, plus synthetic and nonexistent ids. No
conversation content, title or URL is recorded here or committed. Diagnostics printed only counts,
key names and types.

| Question | Finding |
| --- | --- |
| Reachable without auth? | **Yes.** `HTTP 200 text/html`, ~1.0 MB, Cloudflare in front. Identical results with a mobile-browser UA, an `okhttp` UA, no UA, and the app's honest UA `Autopilot-ShareImport/0.1`. No challenge page was served. |
| Server-rendered HTML? | **No message text in markup.** About 14 KB of non-script markup, no `data-message-*` elements. Metadata tags (`og:title`, `og:description`, `og:image`) exist, but only summarize the conversation. |
| Embedded data? | **Yes.** Script `window.__reactRouterContext.streamController.enqueue("…")` holds a JSON-string-encoded **turbo-stream** payload. Its first line is a flat table (~8 100 entries); objects map `"_<key idx>"` → value idx; `-5` is null. A later line resolves an unrelated promise (`P<id>:[…]`). A separate `client-bootstrap` JSON blob (~500 KB) holds logged-out app config, not the conversation. |
| Where is the conversation? | `loaderData["routes/share.$shareId.($action)"].serverResponse = {type: "data", data: {…}}`. `data` has `title`, `mapping` (tree, 96 nodes), `current_node`, **`linear_conversation`** (96 nodes, equal to the parent chain from `current_node`), `highlighted_message_id`, `is_public`, `continue_conversation_url`, moderation fields. This inner shape matches what share pages carried under their earlier Next.js `__NEXT_DATA__` envelope (recalled from prior knowledge, not re-tested). The envelope changed; the payload did not. |
| Actual text recoverable? | **Yes.** `message.content.parts[]` are raw Markdown strings (fences and headings intact). |
| Visible vs internal messages | 96 nodes: 20 system, 21 tool, 14 assistant tool calls (`recipient: "web.run"`), 12 `thoughts`, 7 `reasoning_recap`, 1 `model_editable_context`, 13 assistant `text` with `channel: "final"`, of which 6 have `metadata.is_visually_hidden_from_conversation: true`. Filtering to user/assistant text addressed to `all`, `final` channel and not hidden gives **7 user + 7 assistant** messages. That matches a normal 7-turn chat. |
| Single response vs whole conversation | The page always carries the **whole conversation path** up to the shared point, not one message. `highlighted_message_id` named the response the share was taken from. In the sample it was the last assistant message and equal to `current_node`. With one sample, I could not test whether sharing an *earlier* response moves the highlight or truncates the path. The extractor handles both: highlight if valid, else latest. |
| Latest assistant response reliable? | **Yes for the sample**: highlight = last visible assistant message = `current_node`. |
| Citations | ChatGPT can embed private-use citation markers (`U+E200…U+E201`). The sample had none in final text but has `content_references`. Markers are stripped. Source metadata is ignored in this PoC. |
| Deleted / nonexistent / malformed id | **HTTP 200** (soft error) with `serverResponse: {type: "error"}` and no data. Seen for a well-formed nonexistent UUID and for a malformed id. Revoked links were not tested (no such sample); assumed to behave the same. |
| Other hosts / redirects | `http://chatgpt.com/…` and `https://www.chatgpt.com/…` → 301 to `https://chatgpt.com/share/<id>`. `https://chat.openai.com/share/<id>` → 308 to the same. The importer accepts only the exact canonical form, so it never needs these. |
| Undocumented JSON API? | `https://chatgpt.com/backend-api/share/<id>` → **403 Cloudflare challenge**. Not usable and not pursued. |
| End-to-end through the prototype | The JVM probe ran `ShareImporter` → live chatgpt.com in **~1.3 s**: `Success`, 7 user + 7 assistant, target `HIGHLIGHTED` (= last message), `handoff=NotFound` (expected: the sample has no handoff block). Nonexistent id → `Unavailable`. The probe was temporary, read the URL from an env var, and was deleted before commit. |

## 3. Prototype (what was built)

All new code is in two isolated packages, plus a one-line container seam and a manifest entry.
Nothing in tasks, reminders, Room, notifications or the main navigation changed.

| File | Role |
| --- | --- |
| `share/chatgpt/ShareLink.kt` | `ShareLink.parse`: scheme `https`, host exactly `chatgpt.com`, no user-info, default port, path exactly `/share/<8-4-4-4-12 hex>` (optional `/`); query and fragment dropped; canonical URL rebuilt. `SharedTextParser` finds the first such link in `EXTRA_TEXT` (8 KB cap) and tells *no link* apart from *other link*. |
| `share/chatgpt/ShareFetcher.kt` | `ShareTransport` seam + `UrlConnectionTransport`. Redirects are never auto-followed: each `Location` is resolved and must be the **same share id on chatgpt.com**, or the fetch stops with `RedirectBlocked` and makes no request. Max 3 hops. Limits: 10 s connect, 15 s read, 30 s per request **and** 30 s total, 6 MiB body, HTML only. Mapping: 403/429/503 → `Refused`, others → `HttpError`. No cookies or auth headers, no cache. |
| `share/chatgpt/ShareExtractor.kt` | Finds the first `enqueue("…")` literal by a manual scan (no regex backtracking over 1 MB). Decodes it as JSON, then reads the turbo-stream table **lazily by index**, so shared references never expand. Result is `Success` / `Unavailable` (`type: "error"`) / `Unrecognized(reason)`. Falls back to `mapping` + `current_node` with a cycle guard. Executes nothing. |
| `share/chatgpt/Handoff.kt` | `autopilot.handoff.v1` model and `HandoffParser` (§4). |
| `share/chatgpt/ShareImporter.kt` | Fetch → extract (off the main thread) → parse handoff from the **target response only**. Persists nothing. |
| `ui/share/ShareImportActivity.kt` | Separate, exported `singleTop` activity for `ACTION_SEND` `text/plain`. Handles the intent on a fresh create and in `onNewIntent` only. Rotation and process recreation restore the view model and do not replay the intent. |
| `ui/share/ShareImportViewModel.kt` | States `NoLink` / `Ready` (preview) / `Fetching` / `Done`. **Fetch only on tap.** A repeat of the same link is a no-op, so a result already shown survives. A different link replaces the preview and cancels a fetch in flight. Only the validated link goes into `SavedStateHandle`. Content stays in memory, so process death returns to the preview. |
| `ui/share/ShareImportScreen.kt` | Shows the link, the public-link warning, an explicit **Fetch conversation** button, then the title, message counts, which response was used, a handoff summary ("Preview only: nothing has been added to your tasks") and the response text. Errors are short; Try again only where retrying can help. |
| `di/AppContainer.kt` | `shareImporter` constructor parameter (replaceable seam). |
| `AndroidManifest.xml`, `strings.xml`, `network_security_config.xml` | Activity + filter; share-sheet label "Autopilot"; comments say network is now also used for this explicit fetch. TLS-only and system-CA-only are unchanged. No new permission. |

Not built, by design: WebView or browser scraping, other sites, any arbitrary-URL fetch, persistence
of fetched content, task writes, and AI calls.

## 4. `autopilot.handoff.v1` (minimal specification)

**Where it lives.** Exactly one fenced code block in the shared assistant response, with info
string `autopilot-handoff` (case-insensitive, backtick or tilde fence). A `json` or unlabeled block
counts only if its top-level `"schema"` starts with `autopilot.handoff.`. Text outside the block is
ignored, so no prose, inline code or bare JSON is ever read as an instruction. Two candidate blocks
are refused as `Ambiguous` instead of picking one.

```autopilot-handoff
{
  "schema": "autopilot.handoff.v1",
  "target": { "task_ref": "optional opaque ref copied from Autopilot", "title": "Choose a dentist" },
  "summary": "optional one-paragraph outcome",
  "decisions": [ { "text": "Book Dr. Example", "rationale": "optional why" } ],
  "notes": [ "useful facts worth keeping" ],
  "steps": [ { "text": "Call to book a cleaning", "due": "2026-11-03" } ],
  "deadline": { "date": "2026-11-15", "time": "17:00", "note": "optional" },
  "reminders": [ { "date": "2026-11-02", "time": "09:00", "note": "optional" } ],
  "open_questions": [ "Does my insurance cover X-rays?" ]
}
```

| Field | Rule |
| --- | --- |
| `schema` | Required, exactly `autopilot.handoff.v1`. Other `autopilot.handoff.*` → `UnsupportedVersion`. Anything else → `Malformed`. |
| `target.title` | Required, ≤ 200 chars. `target.task_ref` optional, ≤ 100 chars, opaque (matched exactly in the next milestone, never guessed). |
| `decisions[].text`, `notes[]`, `steps[].text`, `open_questions[]` | Non-empty, ≤ 2 000 chars each. ≤ 30 items per list. |
| Dates / times | `yyyy-MM-dd` and `HH:mm`, local to the phone's zone. No relative phrases. `deadline.date` and `reminders[].date` are required when the object is present. |
| Unknown fields | Ignored (additive v1 evolution). Wrong types → `Malformed`. |
| Size | Block ≤ 32 KB. |

Status codes, priorities, deleting steps, messages and purchases are **not representable**. A handoff
can only *propose* additions that go through review.

**Prompt the owner can add to ChatGPT** (custom instructions or per chat):
"When I ask you to prepare this for Autopilot, end your reply with exactly one fenced code block
labelled `autopilot-handoff` containing JSON with schema `autopilot.handoff.v1`, fields: target
{title, task_ref if I gave one}, summary, decisions [{text, rationale}], notes, steps [{text, due}],
deadline {date, time}, reminders [{date, time, note}], open_questions. Use ISO dates; omit unknowns;
never invent dates."

## 5. Next milestone: into the existing Proposal Review (not started)

The canonical path already exists: a `ProposalSet` (source, target snapshot, items) is saved by
`ProposalSetRepository`, reviewed in `ProposalReviewViewModel`/`ProposalReviewScreen` (nothing
pre-selected, material items need their own accept, conflict-aware reconcile), and written only by
`ProposalApplier` (transactional, provenance, undo). A handoff should become **one more bridge**,
like `PlanningProposalBridge`, without a new review system:

1. **Target resolution (user-confirmed).** An exact `task_ref` match is pre-selected. Otherwise the
   user picks a task, with a title-similarity suggestion, or explicitly chooses "Create task from
   handoff". A task is never matched silently. Optionally add a "Copy for ChatGPT" action on a task
   that copies the prompt above plus `task_ref`.
2. **`HandoffProposalBridge.build(handoff, details, snapshot, nowMs)`**, mirroring the planning bridge:
   - `steps` → `ADD_STEP` (recommended), with existing steps de-duplicated via
     `ProposalValidator.normalizeKeyPublic`. A per-step `due` has no slot in `ItemValue.Step`, so it
     goes in the step text or is dropped (owner decision).
   - `decisions` + `notes` → one `APPEND_NOTE`.
   - `deadline` → `SET_DEADLINE` if the task has none, else `SET_TARGET` with basis "from ChatGPT".
     It is material, and user deadlines are never altered (OD-6).
   - `reminders` → `SET_REMINDER` (`onceAt`), material.
   - `open_questions` → `ProposalSet.questions`; `summary` → `advice`.
3. **Source/provenance.** Add `ProposalSource.HANDOFF`. Handle it in `ProposalApplier.originOf`,
   `sourceKey`/`sourceLabel`, `closeIfDone` and the review/provenance labels, which currently assume
   planning or research. Also pick a `ContentOrigin`. `ProposalSet` JSON is decoded leniently, but
   an older build reading a newer backup would drop sets with the unknown enum. Cover that with a
   backup-compatibility test. Keep only the share id and message id as provenance, never the
   conversation.
4. **Hand-off between activities.** After the user confirms the target, save the set and start
   `MainActivity` with an explicit, validated "open review" extra (like the existing task deep
   link). `ShareImportActivity` then finishes.
5. **Tests.** Bridge unit tests (dedupe, deadline vs target, material flags), an applier test with
   the new source, a review UI test, backup round-trip, and that nothing applies without accept.

## 6. Fallback if the share page format breaks (or the ToS condition is not accepted)

**Share the response text instead of the link.** In the ChatGPT app, select the response text
(long-press → select all → Share), or use the response's Copy action. Android then sends
`text/plain` whose `EXTRA_TEXT` **is** the handoff block. The same share target can run
`HandoffParser` on it directly: no network, no undocumented page format, nothing touches
chatgpt.com, and it is still roughly one action. This is a few lines in `ShareImportViewModel`
(parse a handoff from text when no link is present) and is the recommended first follow-up.
Sharing a generated `.json` file (`EXTRA_STREAM`) is a second option. It is unverified whether the
ChatGPT Android app can share generated files directly.

## 7. Verification

### Automated (this container: Android SDK 36 installed for the session; no KVM, so no local emulator)

| Command | Result |
| --- | --- |
| Baseline `./gradlew lintDebug testDebugUnitTest` on `right-time-nudges` @ `197d8e0` (clean worktree) | 604 tests, **603 passed, 1 failed**: `MainActivitySmokeTest` class setup could not fetch Robolectric's `android-all-instrumented` jar on first use (environment/network, not code). Gradle stopped before lint. |
| `./gradlew testDebugUnitTest --tests 'com.lifeadmin.autopilot.share.*' --tests '…ui.ShareImport*'` | **48 tests, 0 failures** |
| Full gate on this branch: `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` | see §7.1 |
| CI [Android CI #172](https://github.com/HassanJaveed84/autopilot/actions/runs/40379544730) on `2fd876b` | **success**: lint, unit/Robolectric tests, all APKs, and instrumented tests on the API 34 emulator, including `ShareImportDeviceTest`. The APKs are signed with the personal key (same certificate as earlier builds), so they install over the existing app |

New tests and the requested cases they cover:

| Requested case | Test |
| --- | --- |
| Valid ChatGPT share URL | `ShareLinkTest.acceptsTheShareShapeAndCanonicalizes`, `ShareImportActivityTest.aSharedChatGptLinkOpensAPreviewWithoutFetching`, `ShareImportDeviceTest.theSystemResolvesAPlainTextShareToTheImportScreen` |
| Shared text containing a URL | `ShareLinkTest.findsTheLinkInSharedText` |
| Invalid / malicious host | `ShareLinkTest.rejectsOtherHostsSchemesAndPaths` (look-alike hosts, user-info, ports, other paths, traversal, `http`, `javascript:`), `ShareImportActivityTest.anotherHostIsRefused`, device test |
| Redirect away from the allowed domain | `ShareFetcherTest.neverFollowsARedirectAwayFromTheShare` (other host, `http`, login page, different share id, protocol-relative; asserts nothing further was requested), `stopsARedirectLoop`, `transportDoesNotFollowRedirectsOrSendCookies` (MockWebServer) |
| Unavailable shared conversation | `ShareExtractorTest.anErrorResponseIsUnavailable` (the observed HTTP-200 error page), `ShareImportViewModelTest.anUnavailableShareCanBeRetriedAfterAFailure`, `ShareFetcherTest.mapsStatusAndBodyProblems` |
| Successful text extraction | `ShareExtractorTest` (synthetic page in the observed shape with system/tool/tool-call/thoughts/hidden messages filtered out; highlighted vs latest; mapping fallback; a hand-written turbo-stream table; escaped quotes), `fetchExtractsAndFindsTheHandoffWithoutWriting` |
| Metadata not treated as content | `ShareExtractorTest.metadataAloneIsNotAConversation`, `aConversationWithoutAnAssistantResponseIsUnrecognized`, `malformedLoaderDataIsUnrecognized` |
| No recognizable handoff | `HandoffParserTest.ordinaryRepliesAreNeverHandoffs`, `aJsonFenceCountsOnlyWhenItDeclaresTheSchema` |
| Malformed handoff JSON | `HandoffParserTest.malformedJsonInAHandoffFenceIsReportedNotGuessed`, `fieldValuesAreValidated`, `anotherVersionIsRefused`, `twoHandoffBlocksAreAmbiguous`, `anOversizedBlockIsRefused` |
| Repeated Android share intent | `ShareImportViewModelTest.aRepeatedShareOfTheSameLinkChangesNothing`, `aDifferentShareReplacesThePreview`, `ShareImportActivityTest.aNewShareWhileOpenReplacesThePreview`, `rotationKeepsThePreviewAndDoesNotReplayTheIntent`, `ShareImportViewModelTest.processRecreationRestoresOnlyThePreview`, `ShareImportDeviceTest.aSecondShareReachesTheOpenScreen` |
| No automatic download | `aShareShowsAPreviewAndDownloadsNothing` (transport call count 0), activity tests assert no fetching state |
| Ordinary launches unaffected | `ShareImportActivityTest.ordinaryLaunchesStillOpenToday`, `theShareTargetIsRegisteredForPlainTextOnly`, `ManifestContractTest.shareTargetAcceptsOnlyPlainTextAndTheLauncherIsUnchanged`, plus the unchanged `MainActivitySmokeTest` |
| Limits | `enforcesTheTotalDeadline`, `transportCapsTheBody`, `transportTimesOutOnASilentServer`, `transportStopsADripFeed` |

Every fixture is synthetic (invented words, a made-up share id). The real sample was used only by the
temporary probe in §2, which printed structure and counts and was not committed.

### 7.1 Full gate result

| Run | Result |
| --- | --- |
| Full gate, run 1 (a small layout-only edit to `ShareImportScreen.kt` landed during this run) | **BUILD SUCCESSFUL**: lint, **655 tests, 0 failures**, debug/release/androidTest APKs |
| Full gate, run 2 (final code) | `testDebugUnitTest`: **655 tests, 1 failure**. `AppUiTest.snoozeSheetSnoozesOnOneTapAndShowsTheResultingTime` threw `IllegalStateException: Method removeObserver must be called on the main thread` while disposing a `ModalBottomSheet`. That is an existing test, which this branch does not touch, and it passed in run 1 |
| `./gradlew testDebugUnitTest --tests '…ui.AppUiTest'` (final code) | **7 tests, 0 failures** (the failure above did not reproduce) |
| `./gradlew lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` (final code) | **BUILD SUCCESSFUL**; lint report has **0 issues**; all three APKs build (release signed with the debug key locally) |

655 = 606 existing (the right-time-nudges report's count; the baseline run reported 604 only because
the 3 `MainActivitySmokeTest` tests collapsed into one class-level failure) + 48 new share/handoff
tests + 1 new manifest test. CI runs the same gate plus `ShareImportDeviceTest` on an emulator for every push.

### Still needed on a real phone

1. Install this branch's CI APK. In the ChatGPT Android app, open a conversation → Share → copy/share
   link → pick **Autopilot** in the share sheet. Check the preview appears with no network activity
   until **Fetch conversation**.
2. Fetch a real share. Check the counts and that the response shown is the one shared. Repeat from an
   **earlier** response's Share, if the app offers per-response sharing, to see whether
   `highlighted_message_id` moves.
3. Ask ChatGPT to end with an `autopilot-handoff` block (prompt in §4), share it, and check "Autopilot
   handoff found" and its summary. Check that a reply without one shows "No Autopilot handoff".
4. Delete or unshare the link in ChatGPT, then fetch again → "isn't available".
5. While the import screen is open, share a second link → the preview updates. Rotate → no refetch.
   Open Autopilot from the launcher → Today, unchanged.
6. Optional: test on mobile data and on a slow network for the timeout messages.
