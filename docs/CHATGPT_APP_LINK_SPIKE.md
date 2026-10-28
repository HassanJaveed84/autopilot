# ChatGPT → Android App Link Spike

Date: 2026-10-26 · Branch: `ccr-594b2954-khysx3` (from `ccr-69eac4c4-5vsk4s` @ `f08e928`) · No PR opened · PR #8 untouched · `process-text-spike` not merged

**Question.** Can a clickable HTTPS link that ChatGPT displays in an ordinary Android conversation open
Autopilot directly? The test uses a fixed, harmless payload. No task changes, no AI calls, no handoff import.

## 1. Status

| Gate | State |
| --- | --- |
| App code: verified-ready intent filter, validator, test screen, tests | **Done** on this branch; CI #176 green, including the emulator |
| Static site files (`applink-site/`) | **Published 2026-10-27** to the public repo `HassanJaveed84/HassanJaveed84.github.io` @ `15dc0d5` (exactly `.nojekyll`, `.well-known/assetlinks.json`, `autopilot/test.html`, byte-identical to `applink-site/`); GitHub Pages from `main`, deployment run 40439525082 succeeded |
| Domain (`hassanjaveed84.github.io`) | **Live** (§5.5). The owner created the repository; this session's GitHub integration may not create repositories (403) |
| Real release-certificate fingerprint in `assetlinks.json` | **Done**: `A5:C5:…:21:46`, from CI's personal-key release APK, not a local build (§5.1). Confirm it on the phone |
| Domain verification on the phone (`pm get-app-links` → `verified`) | **Not done yet.** Server side is ready: Google's Digital Asset Links API reports `linked: true` (§5.5). Needs the APK installed after publication |
| Tapping the link inside ChatGPT on the S24 Ultra | **Not done.** This is the experiment's actual success criterion, and it remains open |

Nothing in this report claims verification or ChatGPT behavior that wasn't observed. Robolectric, the
emulator and adb all launch the screen without a verified domain, so they prove routing only.

## 2. Feasibility findings (Phase 1)

Sources: Android Developers [Verify App Links](https://developer.android.com/training/app-links/verify-android-applinks),
[Add intent filters](https://developer.android.com/training/app-links/add-applinks),
[Configure website associations](https://developer.android.com/training/app-links/configure-assetlinks),
[Dynamic App Links](https://developer.android.com/blog/posts/dynamic-app-links-elevating-your-android-deep-linking),
Chrome [Custom Tabs and native apps](https://developer.chrome.com/docs/android/custom-tabs/howto-custom-tab-native-apps), read 2026-10-26.

| Topic | Finding and decision |
| --- | --- |
| Verified filter | `autoVerify="true"`, action `VIEW`, categories `DEFAULT` + `BROWSABLE`, scheme http/https. The guide says to include both schemes. This filter is **https only**: AOSP's `IntentFilter.needsVerification()` accepts a filter whose schemes are all web schemes, and Android 12+ verifies per host. An `http` scheme would only add an unwanted cleartext route |
| Data elements merge | All `<data>` in one filter combine. One `<data scheme host path>` here, so nothing broader is claimed |
| Path scope | `android:path="/autopilot/test"` (exact). On Android ≤ 14 verification considers only scheme + host, but the manifest path still limits which URLs reach the app. `<uri-relative-filter-group>` and `dynamic_app_link_components` (Android 15+) aren't used: one exact path doesn't need them, and a broken dynamic rule fails verification for the whole host on 15+ |
| Fragment | The filter doesn't match on the fragment. The activity receives it in `intent.data` and validates it |
| `assetlinks.json` | `https://<host>/.well-known/assetlinks.json`, HTTPS, `application/json`, **no redirects**, public. Relation `delegate_permission/common.handle_all_urls`, namespace `android_app`, `package_name` = applicationId (`com.lifeadmin.autopilot` for release; debug is `….debug` and is deliberately not listed, so only one app claims the link) |
| Fingerprint | SHA-256 of the certificate that signs the **installed** APK. Release uses the personal key (`keystore/personal.jks`, CI secret `AUTOPILOT_KEYSTORE_B64`), else falls back to the debug key. There's no Play App Signing, so the upload-key fingerprint is the device fingerprint |
| Verification timing | Runs at install/update (Android 12+: per host, any verified host counts). Android 15+ also re-verifies in the background, but `assetlinks.json` changes can take up to 7 days. **Publish the site first, then install** |
| Android 16 | No App Link verification change found in the Android 16 behavior changes. Its intent-redirection hardening concerns nested intents, which this screen never launches |
| Commands | `adb shell pm verify-app-links --re-verify com.lifeadmin.autopilot`, `adb shell pm get-app-links com.lifeadmin.autopilot`, `adb shell pm get-app-links --user cur com.lifeadmin.autopilot`, reset `adb shell pm set-app-links --package com.lifeadmin.autopilot 0 all` |
| Unverified HTTPS | Android 12+ opens an **unverified** https link in the browser, not a chooser, unless the user turns on the link in *Open by default*. An unverified link is not a valid test |
| In-app browsers | Custom Tabs normally hand a URL to an installed app, but not once the host app has a Custom Tabs service connection, unless it opts back in (`setSendToExternalDefaultHandlerEnabled`). How the ChatGPT app opens links can't be learned from docs; OpenAI's help only says it uses Chrome as the default browser. **The phone test decides it** |
| Domain | The repository and the account have no domain or Pages site. Recommendation: the free GitHub Pages **user site** `hassanjaveed84.github.io` (details in `applink-site/README.md`) |

## 3. What changed

| File | Change |
| --- | --- |
| `share/applink/TestLink.kt` | `TestLinkParser`: scheme `https`, host exactly the configured host (no subdomains, user info or non-default port), raw path exactly `/autopilot/test`, fragment exactly `message=` + 1–64 URL-unreserved chars (`[A-Za-z0-9._~-]`), 2 KB URL cap. The query is ignored and never read: ChatGPT may append `utm_source`. No percent-decoding, so nothing is guessed |
| `ui/applink/AppLinkTestViewModel.kt` | Holds only the validated message, or the refusal category, plus display facts. Persisted in `SavedStateHandle` (no raw URL). No repository, network or AI dependency, so the screen **cannot** change data |
| `ui/applink/AppLinkTestActivity.kt` | Exported `singleTop`, `taskAffinity=""`: its own task, so an open Today screen is untouched and Close/Back return to the caller. Reads the intent on a fresh create and in `onNewIntent` only. Close = `finishAndRemoveTask()` |
| `ui/applink/AppLinkTestScreen.kt` | "ChatGPT Link Test": *Link received successfully* · *Received message* · *Source: Android App Link* · *No task changes made* · Close. A refused link names the category only and never echoes the payload |
| `ui/applink/AppLinkDiagnostics.kt` | Read-only phone evidence, so testing needs no adb: the **opener package** (Android's referrer, e.g. `com.openai.chatgpt` vs `com.android.chrome`), the **domain verification state** for the host (`DomainVerificationManager`, Android 12+), and the **signing-certificate SHA-256** of the installed app |
| `AndroidManifest.xml` | The activity plus one `autoVerify` filter (https, `${appLinkHost}`, path `/autopilot/test`) |
| `app/build.gradle.kts` | `appLinkHost` (default `hassanjaveed84.github.io`, override `-Pautopilot.appLinkHost=`) feeds the manifest placeholder and `BuildConfig.APP_LINK_HOST`, so the filter and validator can't drift |
| `.github/workflows/android-ci.yml` | Prints the release APK's `assetlinks.json` (public digest) in the job summary and uploads it as the `assetlinks` artifact |
| `applink-site/` | `.well-known/assetlinks.json`, `autopilot/test.html` fallback, `.nojekyll`, `assetlinks-from-apk.sh`, deployment README |
| Tests | `TestLinkParserTest`, `AppLinkTestViewModelTest`, `AppLinkTestActivityTest` (Robolectric), `AppLinkDeviceTest` (emulator), `ManifestContractTest` (+1 test, activity set updated) |

Nothing else changed: no tasks, reminders, Room, notifications, navigation, share import or AI code. No logging was added.

The fallback page appears only when a browser keeps the link. It shows the message as text, after
the same validation, and offers an `intent://…;package=com.lifeadmin.autopilot;end` button. That
button targets the package explicitly, so it works **without** verification. It's labelled as such
and counts as the "browser first, then Autopilot" outcome, never as success.

## 4. Requirements → evidence

| Requirement | Covered by |
| --- | --- |
| Cold launch | `AppLinkTestActivityTest.theTestLinkShowsTheFixedMessage`, `AppLinkDeviceTest.theSystemResolvesTheTestLinkToTheTestScreen` |
| Warm launch / repeated link | `AppLinkTestActivityTest.aRepeatedLinkReachesTheOpenScreenAndRecreationDoesNotReplayIt`, `AppLinkDeviceTest.theSameLinkAgainReachesTheOpenScreen` (two system launches with `NEW_TASK`: same instance, count 2), `AppLinkTestViewModelTest.aRepeatedLinkShowsTheSameMessageAndOnlyCountsTheRepeat` |
| Process recreation | `AppLinkTestViewModelTest.processRecreationRestoresTheValidatedMessageNotTheRawUrl`, `aRefusedLinkKeepsOnlyItsCategory`; rotation in the activity test |
| Unexpected hosts, schemes, paths | `TestLinkParserTest.rejectsOtherSchemesHostsAndPorts`, `rejectsEveryOtherPath`, `theMessageHasExactlyOneStrictForm`, `malformedOrOversizedInputIsRefused`; resolver scope `AppLinkTestActivityTest.onlyTheTestRouteResolvesToTheApp`; manifest `ManifestContractTest.appLinkFilterClaimsOnlyTheVerifiedTestRoute` |
| Payload not echoed when refused | `AppLinkTestActivityTest.anUnexpectedLinkIsRefusedWithoutEchoingIt` |
| No task changes, no AI call | Structural: the view model's only dependencies are `SavedStateHandle` and the parser |
| Launcher unaffected | `AppLinkTestActivityTest.ordinaryLaunchesStillOpenToday` |

## 5. Verification

### 5.1 Fingerprint

`applink-site/.well-known/assetlinks.json` lists **one** SHA-256:
`A5:C5:6F:43:16:77:81:B6:8B:5F:10:70:BF:36:A6:AF:17:76:D5:6C:1D:BB:B8:8D:C2:44:BD:BD:A2:56:21:46`.

- Source: CI run [#175](https://github.com/HassanJaveed84/autopilot/actions/runs/40423771716) (`b75ca6d`). Its `apksigner verify --print-certs app-release.apk` printed
  `V2 Signer: certificate DN: CN=Life Admin Autopilot, OU=Personal build, O=Personal`, so this is the **personal key** restored from `AUTOPILOT_KEYSTORE_B64`.
- The local build here was signed with a throwaway debug key (`CN=Android Debug`, `9A:76:…:4C:77`), which was deliberately **not** used.
- Because the key is unchanged, this should equal the certificate of the APK already on the phone, assuming it came from CI as earlier reports say.
  **Confirm it on the phone**: the test screen's *Signing certificate SHA-256* line must show this exact value.
- Run [#174](https://github.com/HassanJaveed84/autopilot/actions/runs/40422907398) exposed a bug in the new CI step. `tee` hid a failed extraction, because build-tools 37 words the line differently, and an empty `assetlinks.json` was uploaded.
  It was fixed in `b75ca6d` (pipefail plus diagnostics) and `cfe353f` (both wordings accepted).

### 5.2 Commands run in this container

Android SDK 36 was installed for the session. There's no KVM, so there's no local emulator.

| Command | Result |
| --- | --- |
| `./gradlew testDebugUnitTest --tests 'com.lifeadmin.autopilot.share.applink.*' --tests '…ui.AppLinkTest*' --tests '…ManifestContractTest' --tests '…ui.ShareImportActivityTest'` (first run) | 32 tests, **1 failure**: my own activity test expected the process-death label after a *rotation*. Rotation keeps the view model, so the screen correctly showed "Sent to the already open screen · 2". The test was fixed to assert no replay (the count stays 2) |
| Same, re-run | **0 failures** (`AppLinkTestActivityTest` 6/6) |
| `./gradlew lintDebug assembleRelease assembleDebugAndroidTest` | First attempt: Maven Central returned **HTTP 429** while lint downloaded its own dependencies (network, not code). Retry: **BUILD SUCCESSFUL**, lint **0 issues** |
| `./gradlew testDebugUnitTest` (full suite, `efc0df8`) | **673 tests, 0 failures** |
| `./gradlew testDebugUnitTest --tests '…ManifestContractTest'` (`cfe353f`) | **9 tests, 0 failures** |
| `applink-site/assetlinks-from-apk.sh` on the local release APK; the new `sed` pattern on both apksigner wordings | Correct JSON and digest, both formats parsed |

### 5.3 CI

| Run | Commit | Result |
| --- | --- | --- |
| [#174](https://github.com/HassanJaveed84/autopilot/actions/runs/40422907398) | `efc0df8` | Build job **green** (lint, unit/Robolectric, all APKs), but the new assetlinks step silently produced an empty file (§5.1). Its emulator job was cancelled by the next push (`cancel-in-progress`) |
| [#175](https://github.com/HassanJaveed84/autopilot/actions/runs/40423771716) | `b75ca6d` | **Red, as intended by the fix**: the now-strict step failed on build-tools 37's wording and printed the certificate listing that the fingerprint comes from. Emulator job skipped |
| [#176](https://github.com/HassanJaveed84/autopilot/actions/runs/40424846573) | `cfe353f` | **Green.** Lint, unit/Robolectric tests, debug/release/androidTest APKs, assetlinks step (prints `A5:C5:…:21:46`, the committed value), and instrumented tests on the API 34 emulator: **24 tests passed** (22 on the share PoC's run #172, plus the 2 new `AppLinkDeviceTest` cases) |

**Installable release APK:** run #176 → artifact [`apks`](https://github.com/HassanJaveed84/autopilot/actions/runs/40424846573/artifacts/12699228834) → `release/app-release.apk`
(personal key, `com.lifeadmin.autopilot`, versionName 0.1.0). Downloading needs a GitHub login. The commit after `cfe353f` changes only docs, which CI skips, so this is the APK for the branch head.

### 5.4 Not verified

- No `pm get-app-links` → `verified` on a device yet.
- No physical-device run of any kind, and no tap from the ChatGPT app.
- The `intent://` fallback button (Chrome's handling of a fragment inside an intent URI) is untested.

### 5.5 Deployment checks (2026-10-27, after Pages run 40439525082)

| Check | Result |
| --- | --- |
| `GET https://hassanjaveed84.github.io/.well-known/assetlinks.json` | **200**, `application/json; charset=utf-8`, **0 redirects**, no `Location` header, 297 bytes, valid JSON, byte-identical to `applink-site/.well-known/assetlinks.json` |
| Statement content | relation `delegate_permission/common.handle_all_urls`, `android_app`, `com.lifeadmin.autopilot`, fingerprint `A5:C5:6F:43:16:77:81:B6:8B:5F:10:70:BF:36:A6:AF:17:76:D5:6C:1D:BB:B8:8D:C2:44:BD:BD:A2:56:21:46` (the personal release key) |
| Google `statements:list` / `assetlinks:check` for that package and fingerprint | Statement returned; **`"linked": true`** |
| `http://…/assetlinks.json` | 301 to https. Not used: Android fetches the file over HTTPS |
| `GET https://hassanjaveed84.github.io/autopilot/test` | **200**, `text/html`, 0 redirects, byte-identical to `applink-site/autopilot/test.html` |
| Rendered in Chromium (412×915) with `#message=hello-from-chatgpt` | Heading "Autopilot didn't open", "Test message: hello-from-chatgpt", *Open in Autopilot* button shown; the only network request was the page itself, without the fragment |
| Same page with `#message=<script>…` or no fragment | "No test message in this link.", button hidden |

## 6. Phone test (owner)

Only after the site is published (`applink-site/README.md` steps 1–4).

**Test URL:** `https://hassanjaveed84.github.io/autopilot/test#message=hello-from-chatgpt`. It's live only once the site is published.

Setup:

1. Install the **release** APK from CI run #176 (§5.3: `apks` artifact → `release/app-release.apk`), *after*
   the site is live. Installing over the current app keeps its data, because it's the same personal key.
2. Optional, with adb: `adb shell pm verify-app-links --re-verify com.lifeadmin.autopilot`, wait about a minute, then
   `adb shell pm get-app-links com.lifeadmin.autopilot`. Expect `hassanjaveed84.github.io: verified`.
   Without adb, use **Settings → Apps → Autopilot → Set as default / Open by default**. The host should
   be listed as a verified link, and *Open supported links* should be on.
3. In ChatGPT, send:
   *Show this exactly as a clickable Markdown link with the label "Open in Autopilot", and change nothing in the URL:*
   `[Open in Autopilot](https://hassanjaveed84.github.io/autopilot/test#message=hello-from-chatgpt)`

Record for each case: **Autopilot directly** / **browser first, then Autopilot** / **browser only**, plus
the screen's *Opened by*, *Delivered* and *Domain* lines.

| # | Case | How |
| --- | --- | --- |
| 1 | From ChatGPT | Tap the link in ChatGPT's reply |
| 2 | From Chrome | Paste the URL into a Chrome tab, or tap it in a page or a Keep note. Typing it in the address bar usually stays in Chrome by design |
| 3 | Autopilot already running | Open Autopilot (Today), switch to ChatGPT, tap the link. Today should be unaffected underneath |
| 4 | Autopilot closed | Swipe Autopilot away in Recents (or Settings → Force stop), then tap the link |
| 5 | Repeat | With the test screen open, go back to ChatGPT via Recents and tap again. Expect *Sent to the already open screen*, count 2, same message |
| 6 | Return | Tap **Close** and, separately, Android **Back**. Both should land in ChatGPT |
| 7 (optional) | Model-generated link | Ask ChatGPT: *"Make me an Open in Autopilot link to https://hassanjaveed84.github.io/autopilot/test with #message= followed by three lowercase words joined by hyphens."* This checks whether it writes a usable link on its own |

**Success** means case 1 opens the ChatGPT Link Test screen **directly**, with no browser page in between, showing `hello-from-chatgpt` and *Domain …: Verified*.
*Opened by* is recorded, not required. A Custom Tab hand-off may report the browser package rather than `com.openai.chatgpt`.
If ChatGPT opens its own browser view or Chrome first, record that as "browser first". The fallback
page's button reaching Autopilot is **not** success.

## 7. Carrying `autopilot.handoff.v1` in a link (Phase 5 assessment, nothing built)

This is an assessment, not evidence. The only observed fact will be whether ChatGPT shows and opens the fixed link (§6).

| Question | Assessment |
| --- | --- |
| Fragment vs query | **Fragment.** Browsers never send `#…` to the server, nor in `Referer`. The static site only ever sees `/autopilot/test`. The app gets the full URL in `intent.data`. Caveat: if a browser does open the link, the fragment stays in local browser history, which may be synced (for example, Chrome history sync) |
| Cloud database | **Not needed.** The link carries the data. The site is static and stores nothing |
| Privacy | The task content is already in the ChatGPT conversation, so the link exposes nothing new to OpenAI. New exposure would come only if the link is shared, copied, or opened in a browser (history), or if ChatGPT's link-safety checks fetch the URL (the fragment still stays on the device). Keep payloads to what the conversation already holds, and never include credentials |
| Size | Android intents carry a few hundred KB, but chat rendering and copy/paste are the real limits. Proposed caps: **≤ 2,000 URL characters** as the target and an **8 KB hard refusal** in the app. A typical handoff (title, 3 decisions, 5 steps, a deadline) is about 600–900 bytes of compact JSON: about 1–1.3 KB percent-encoded, or about 0.8–1.2 KB in base64url |
| Encoding by the model | **Main risk, likely unreliable.** Hand-writing base64 for more than a few dozen bytes is a known weakness of language models: silent character errors give garbage, not a clean failure. Percent-encoding JSON by hand also tends to drift (a raw space, `#` or `"` mid-link, a truncated tail). ChatGPT's own link rendering may also rewrite or drop links it judges unusual. Mitigation, if links are kept: ask ChatGPT to build the URL with its Python tool (`json.dumps` → `base64.urlsafe_b64encode`), which encodes reliably. That tool isn't guaranteed in every chat mode |
| Partial or invalid payloads | Always fail closed: the version prefix (`v1.`) must match, then strict base64url, then a size cap, JSON parse, the existing `HandoffParser` schema validation, and Proposal Review before any write. Truncation then shows *"link incomplete, ask ChatGPT again"*. No repair or guessing |
| Commands | Payload fields are data for Proposal Review. A link never triggers writes, and the review screen is the only path to `ProposalApplier` |

**Recommendation.** If §6 shows that ChatGPT opens the fixed link directly, run **one** encoding check
before building anything: ask ChatGPT for a link carrying a small handoff, and see whether its default
reply or its Python tool produces a URL that decodes and validates. If model-built links are flaky, use
the simplest alternative: the App Link carries **no payload**, only `…/autopilot/import`. ChatGPT also
outputs the existing ```` ```autopilot-handoff ```` block, the user taps ChatGPT's **Copy**, then taps
*Open in Autopilot*, and Autopilot reads the clipboard on an explicit *Paste handoff* tap into the same
`HandoffParser` → Proposal Review. There is no encoding, no length limit and no server, and it reuses
existing code. It costs one extra tap.
