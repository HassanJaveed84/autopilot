# ChatGPT → App Link: Structured Handoff Experiment

Date: 2026-10-27 · Branch: `ccr-125b38a8-btl7ka` (from the App Link spike `ccr-594b2954-khysx3` @ `779d0ff`) · No PR opened · Nothing merged

**Question.** Can ChatGPT write a clickable link that carries a small structured task handoff, and does Autopilot
receive every field exactly? The app only displays what it receives. It creates no tasks, schedules nothing,
writes no proposals and makes no AI calls. The existing `autopilot.handoff.v1` parser and Proposal Review are untouched.

Builds on `docs/CHATGPT_APP_LINK_SPIKE.md`. The owner has already confirmed on a Galaxy S24 Ultra that the
`#message=hello-from-chatgpt` link opens the native test screen directly from ChatGPT, the message arrives
intact, and Close returns to the conversation.

## 1. Status

> **Update 2026-10-27.** The owner reports that Test A and Test B both passed on the Galaxy S24 Ultra (Test B
> carried one decision, two steps and one note, including punctuation that needed percent-encoding), and that
> Close returned to the conversation. The production round trip built on this format, with an optional `r=`
> task reference and a separate `/autopilot/import` route, is in `docs/CHATGPT_ROUND_TRIP_REPORT.md`. The rows
> below are the record as of this experiment.

| Gate | State |
| --- | --- |
| URL format (`ap=v1`) and validator | **Done**, unit-tested (§3, §5) |
| Test screen shows the handoff and diagnostics | **Done**, Robolectric and emulator tests (§5) |
| CI on this branch | **Green**: run #177 on `ada87a8`, including 26/26 emulator tests (§5.3) |
| Release APK signed with the personal key | **Built by CI** run #177; certificate `A5:C5:…:21:46` = the published `assetlinks.json` (§5.3) |
| Test A (known-good link) in ChatGPT on the phone | **Not done**: owner |
| Test B (ChatGPT writes the link itself) on the phone | **Not done**: owner. This is the deciding test |

## 2. URL contract (`ap=v1`)

```
https://hassanjaveed84.github.io/autopilot/test#ap=v1&t=<task>&d=<decision>&s=<step>&n=<note>
```

| Part | Rule |
| --- | --- |
| Route | Unchanged from the spike: `https`, host exactly `hassanjaveed84.github.io`, path exactly `/autopilot/test`. A query (e.g. `?utm_source=…`) is ignored and never read |
| Where the data goes | Only in the **fragment** (after `#`). Browsers never send it to the website; the static site sees only `/autopilot/test` |
| Version | The fragment starts with exactly `ap=v1`. Any other first field (`ap=v2`, `ap=V1`, …) is refused. A fragment that doesn't start with `ap=` follows the old `message=` rule, so `#message=…` links work as before |
| Fields | `&`-separated `key=value` pairs. `t` task title, **exactly once**. `d` decision, `s` step, `n` note: each repeatable, order kept, and at least one of them overall. Keys are lowercase; anything else (`S`, `step`, `x`, a second `ap`) is refused |
| Values | Percent-encoded UTF-8, **decoded exactly once** (`%2520` shows as `%20`). Split on `&` and the first `=` happens before decoding, so `%26` (`&`) and `%3D` (`=`) stay inside a value |
| Canonical encoding | Leave `A–Z a–z 0–9 - . _ ~` as they are; encode every other byte as `%XX`. Space is `%20` |
| Tolerated as written | RFC 3986 allows `! $ ' ( ) * , ; : @ / ?` in a fragment, so they're accepted unencoded (e.g. `$1,000`). Raw non-ASCII text (`Café`, `東京`) is accepted as UTF-8 as well, since browsers and Android may hand over either form |
| Refused, never guessed | Raw `+`: form encoding reads it as a space, RFC 3986 as a plus, so the app refuses it rather than guess (`%20` for space, `%2B` for plus). Also refused: a broken `%` escape, invalid UTF-8, raw `=` inside a value, empty values, leading or trailing whitespace, line breaks, tabs and other control characters, and bidi override characters |
| Limits | Task ≤ 120 characters, each item ≤ 200 (Unicode code points, after decoding). At most 5 decisions, 10 steps, 5 notes. Whole URL ≤ 2,048 characters, the limit the spike already had. That cap leaves room for about 200 non-ASCII characters in total, because each one takes 6–12 URL characters when percent-encoded |
| Failure | The screen says **Handoff not accepted**, names the rule and the field's position (e.g. *Field 3*), and never shows the received values. Nothing is partially shown |

### Why this format

- **No JSON and no Base64.** A language model writes `key=value&key=value` with `%20` reliably. Hand-written Base64
  breaks silently, and percent-encoding a JSON object multiplies the characters it has to escape (`{ " : , [ ]`).
- **Repeated keys** carry lists with no indices, counts or brackets to get wrong.
- **One-letter keys** keep URLs short. The fixed example is 182 characters.
- **A strict refusal list** means a model mistake shows up as *Handoff not accepted* rather than as quietly
  changed text, which is what Test B needs to measure.
- **`t` was added** to the brief's example, because the expected screen shows a task title.

### Fixed test URL (Test A)

```
https://hassanjaveed84.github.io/autopilot/test#ap=v1&t=Choose%20a%20coffee%20grinder&d=Choose%20Model%20A&s=Check%20warranty&s=Compare%20retailer%20prices&n=Budget%20is%20%241%2C000
```

`LinkHandoff.FIXED_EXAMPLE.toUrl(host)` produces exactly this string (asserted in `HandoffFragmentTest`).
Expected screen: *ChatGPT Handoff Test* · *Task: Choose a coffee grinder* · **Decision** • Choose Model A ·
**Steps** 1. Check warranty 2. Compare retailer prices · **Note** • Budget is $1,000 · *Fields received: 6
(format, task, 1 decision, 2 steps, 1 note)* · *Decoding and validation: Succeeded* · *Same as the fixed example: Yes, every field*.

### Handoff specification to give ChatGPT (Test B)

Paste this as is, then describe a task in ordinary words:

> **Autopilot handoff link (format v1).** When I ask for an Autopilot link, reply with one Markdown link
> labelled "Open in Autopilot" and nothing else in the link.
>
> URL: `https://hassanjaveed84.github.io/autopilot/test#ap=v1` followed by fields, each written as `&key=value`:
> - `t` = the task title. Exactly one.
> - `d` = a decision, `s` = a step, `n` = a note. Repeat the key once per item, in order. Include at least one.
>
> Encode every value as UTF-8 percent-encoding: keep only letters A–Z a–z, digits, `-` `.` `_` `~`; write
> everything else as %XX. Space is `%20`. Never use `+` for a space. For example `&` → `%26`, `=` → `%3D`,
> `+` → `%2B`, `#` → `%23`, `%` → `%25`, `$` → `%24`, `,` → `%2C`, `'` → `%27`, `(` → `%28`, `)` → `%29`, `é` → `%C3%A9`.
>
> Limits: task up to 120 characters, each item up to 200; at most 5 decisions, 10 steps, 5 notes; no line
> breaks; no spaces at the start or end of a value; the whole URL under 2,000 characters.
>
> Example: `[Open in Autopilot](https://hassanjaveed84.github.io/autopilot/test#ap=v1&t=Choose%20a%20coffee%20grinder&d=Choose%20Model%20A&s=Check%20warranty&s=Compare%20retailer%20prices&n=Budget%20is%20%241%2C000)`

## 3. What changed

| File | Change |
| --- | --- |
| `share/applink/HandoffLink.kt` (new) | `HandoffFragment.parse` (strict single-pass decoder and validator), `HandoffFragment.encode`, `LinkHandoff` (+ `toUrl`, `FIXED_EXAMPLE`), `HandoffProblem` / `HandoffIssue` |
| `share/applink/TestLink.kt` | After the unchanged scheme/host/path/size checks, a fragment starting `ap=` goes to `HandoffFragment`; results `HandoffReceived` / `HandoffRejected`. The `message=` rule is untouched |
| `ui/applink/AppLinkTestViewModel.kt` | Saves the **validated** handoff fields (or the refusal category and position) in `SavedStateHandle` for process recreation, never the raw URL. Each new link replaces the previous one |
| `ui/applink/AppLinkTestScreen.kt` | `LazyColumn` → scrolling `Column` (a short screen; every row now stays composed, so off-screen diagnostics exist for tests and accessibility). *ChatGPT Handoff Test*: task, Decision(s), numbered Steps, Note(s), *No task changes made*. Diagnostics card adds *Fields received*, *Decoding and validation*, *Same as the fixed example*, next to the spike's *Opened by*, *Delivered*, *Domain … (verification state)* and *Signing certificate SHA-256* |
| `AndroidManifest.xml` | `android:persistableMode="persistNever"` on the test activity, so Android doesn't write the task's launch intent, which includes the link and its handoff text, to its recent-tasks store across reboots. Launch mode, task affinity, filter, Close (`finishAndRemoveTask`) and Back are unchanged |
| Tests | `HandoffFragmentTest` (new), `AppLinkTestViewModelTest` (+3), `AppLinkTestActivityTest` (+5), `AppLinkDeviceTest` (+2, emulator), `ManifestContractTest` (+1 assertion) |

Not changed: the GitHub Pages site (`applink-site/`), `HandoffParser` / `autopilot.handoff.v1`, Proposal Review,
tasks, reminders, Room, AI code, navigation elsewhere. No logging was added, and nothing is written to app
storage. If a browser keeps an `ap=` link, the unchanged fallback page says *No test message in this link*.
That outcome counts as a failed tap anyway.

## 4. Requirements → evidence

| # | Requirement | Covered by |
| --- | --- | --- |
| 1 | Fixed example decodes exactly | `HandoffFragmentTest.theFixedExampleDecodesExactly`, `AppLinkTestActivityTest.theFixedHandoffShowsEveryFieldAndItsDiagnostics`, `AppLinkDeviceTest.theSystemDeliversTheFixedHandoffIntact` |
| 2 | Spaces, punctuation, `&`, Unicode, percent encoding | `separatorsInsideValuesStayInside`, `valuesAreDecodedExactlyOnce`, `unicodeEncodedOrRawIsTheSame`, `legalRawPunctuationNeedsNoEncodingAndOrderIsKept`, `encodeAndParseRoundTrip`, `aRawPlusIsRefusedNotGuessed`, `brokenEscapesAndInvalidUtf8AreRefused`, `controlAndBidiCharactersAreRefused` |
| 3 | Missing, duplicated, malformed, unknown fields | `theVersionComesFirstAndOnlyOnce`, `theTaskIsRequiredOnceAndSomethingMustFollowIt`, `malformedPairsAreRefusedWithTheirPosition`, `unknownOrMiscasedKeysAreRefused`, `valuesMustNotBeEmptyOrPadded` |
| 4 | Long URLs and fields | `valueLengthsAreCappedInCharactersNotBytes`, `itemCountsAreCapped`, `anOversizedLinkIsRefusedBeforeParsing` |
| 5 | Unexpected hosts, paths, schemes | `HandoffFragmentTest.theEnvelopeRulesStillApply` plus the spike's `TestLinkParserTest` and resolver/manifest tests |
| 6 | Cold and warm launches | Cold: the activity and device tests above. Warm: `AppLinkTestActivityTest.aWarmHandoffReplacesTheMessageAndBack` (message → handoff → message into the open screen), `AppLinkDeviceTest.aHandoffReachesTheOpenScreenAndCloseEndsItsTask` (two system launches with `NEW_TASK`) |
| 7 | Close / Back return to ChatGPT | Automated: `closeAndBackBothFinishTheScreen` (Robolectric), device test (Close finishes the task). Returning to *ChatGPT* specifically is phone-only |
| 8 | `#message=` still works | `TestLinkParserTest` unchanged and passing, `HandoffFragmentTest.theMessageRouteStillWorks`, the spike's activity and device tests |
| — | No values echoed on refusal; nothing raw saved | `aRefusedHandoffNamesTheRuleWithoutEchoingValues`, `aHandoffSurvivesProcessDeathAsValidatedFieldsOnly`, `aRefusedHandoffKeepsOnlyItsCategoryAndPosition` |

## 5. Verification

### 5.1 Baseline

Branch head before this work: `779d0ff` (spike docs). The spike's CI run #176 was green on `cfe353f`, the last code commit.

### 5.2 Commands run in this container

Android SDK 36 was installed for the session. There's no KVM, so there's no local emulator.

| Command | Result |
| --- | --- |
| `./gradlew testDebugUnitTest --tests '…share.applink.*'` (first run) | 26 tests, **2 failures, both in my test inputs**: a plain space where the test meant NBSP, and an "oversized" URL that was still under 2,048 characters. Tests fixed; the parser was unchanged |
| Same plus `…ui.AppLinkTest*`, `…ManifestContractTest` | Robolectric couldn't fetch `android-all-instrumented` because Maven Central returned **HTTP 429**, as in the spike. The jar was fetched with curl into `~/.m2` (SHA-1 verified) |
| Rerun | `AppLinkTestViewModelTest`, `HandoffFragmentTest`, `ManifestContractTest`, `TestLinkParserTest` passed. `AppLinkTestActivityTest`: the new tests found the diagnostics card **not composed** below the taller handoff card in the old `LazyColumn` → switched to a scrolling `Column`. A Close/Back test waiting for `ActivityScenario` to report DESTROYED timed out under Robolectric → now asserts `isFinishing`. A `scenario.recreate()` after two `onNewIntent` deliveries hung in Robolectric → removed from the new warm test (recreation stays covered by the spike's test and the view-model tests) |
| `./gradlew testDebugUnitTest --tests '…AppLinkTestActivityTest'` | **11 tests, 0 failures** |
| `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` (`ada87a8`) | **BUILD SUCCESSFUL**. **702 tests, 0 failures, 0 errors** (the spike head had 674: 673 at `efc0df8` plus one manifest test in `cfe353f`; this adds 28). Lint: **0 issues** |

The local release APK is signed with a throwaway debug key, so it's not the one to install. The APK to install comes from CI (§5.3).

### 5.3 CI

| Run | Commit | Result |
| --- | --- | --- |
| [#177](https://github.com/HassanJaveed84/autopilot/actions/runs/40448563031) | `ada87a8` | **Green.** Build job: lint, unit/Robolectric tests, debug/release/androidTest APKs (BUILD SUCCESSFUL in 7m 15s). The assetlinks step printed `A5:C5:6F:43:16:77:81:B6:8B:5F:10:70:BF:36:A6:AF:17:76:D5:6C:1D:BB:B8:8D:C2:44:BD:BD:A2:56:21:46` for the release APK: the personal key, identical to the live `assetlinks.json`. Emulator job (API 34): **26 tests, 0 failed** (the spike's 24 plus the 2 new `AppLinkDeviceTest` cases) |

**Installable release APK:** run #177 → artifact [`apks`](https://github.com/HassanJaveed84/autopilot/actions/runs/40448563031/artifacts/12709700963) → `release/app-release.apk`
(personal key, `com.lifeadmin.autopilot`). Downloading needs a GitHub login. Later commits on this branch change only docs, which CI skips, so this is the APK for the branch head.

What the emulator proves: the system resolver routes the structured link to the test screen and every field arrives intact; Close finishes the screen. It doesn't prove domain verification, ChatGPT's link rendering, or ChatGPT generating the link. Only §6 shows those.

## 6. Phone tests (owner)

Install the release APK from §5.3 over the current app (same personal key, so data is kept and the domain
stays verified after the update). Before testing, the screen's *Signing certificate SHA-256* should still read
`A5:C5:6F:43:…:21:46` and *Domain hassanjaveed84.github.io* should read *Verified*.

**Test A: known-good link.** In ChatGPT: *Show this exactly as a clickable Markdown link labelled "Open in
Autopilot", and change nothing in the URL:* followed by the fixed URL (§2). Tap it.
Pass: Autopilot opens directly, *Decoding and validation: Succeeded*, *Same as the fixed example: Yes, every field*, and Close returns to the conversation.

**Test B: ChatGPT-generated link.** New chat. Paste the specification (§2), then for example: *"Make me an
Autopilot link. Task: choose a coffee grinder. I've decided on Model A. Next I need to check the warranty and
compare retailer prices. Note that my budget is $1,000."* Tap the link ChatGPT writes.
Pass: *Succeeded*, and each field on screen matches what you asked for. *Same as the fixed example* reads *Yes*
only when ChatGPT's wording matches the fixed example word for word. Otherwise compare by eye. Also try one task with
`&`, an apostrophe, a `+` and an accented letter.

Record for each: **Autopilot directly** / **browser first** / **browser only**, plus *Opened by*,
*Fields received* and *Decoding and validation*. If validation fails, the screen names the rule and field
position. Note that, and if you can, long-press the link in ChatGPT and copy the URL into the test notes.
That URL is the model's output. If Test A passes and Test B fails, the problem is generation, not transport.
