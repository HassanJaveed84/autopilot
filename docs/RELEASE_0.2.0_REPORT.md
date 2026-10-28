# Release 0.2.0: Preparation Report

Date: 2026-10-27 · Branch: `retire-embedded-ai` · Code commit: `59b5347` (on top of the built-in AI
retirement, `docs/RETIRE_EMBEDDED_AI_REPORT.md`) · No PR opened · Nothing merged

**Product direction:** Autopilot manages tasks and follow-through; ChatGPT handles planning and research.
This release prepares the simplified app for its real-phone acceptance test before `main`.

## 1. Changes

| Area | Change |
| --- | --- |
| Version | `versionCode` 1 → **2**, `versionName` 0.1.0 → **0.2.0** (`app/build.gradle.kts`). Application ID `com.lifeadmin.autopilot`, signing configuration and Room schema **v8** unchanged |
| `/autopilot/test` spike | Removed (§2) |
| CI release gate | New `.github/scripts/verify-release-apk.sh` + *Verify release APK* step: fails the run unless the release APK has the expected ID and version, declares `/autopilot/import`, does not declare `/autopilot/test`, requests no `INTERNET`, verifies, and is signed with the certificate published in `applink-site/.well-known/assetlinks.json`. Only then is it uploaded as artifact **`autopilot-0.2.0-release`** (with the verification log) |
| Docs | `applink-site/README.md` (current state: one route), `docs/ROADMAP.md`, a dated note in `docs/CHATGPT_ROUND_TRIP_REPORT.md`; historical spike/handoff reports unchanged |

## 2. `/autopilot/test` removal: dependency trace

| Piece | Used by the import route? | Action |
| --- | --- | --- |
| `ui/applink/AppLinkTestActivity`, `AppLinkTestScreen`, `AppLinkTestViewModel`, `AppLinkDiagnostics` | No (spike screen only) | Deleted |
| `share/applink/TestLink.kt`: `TestLinkParser`, `TestLinkResult`, `TestLinkProblem` | No | Deleted |
| `share/applink/TestLink.kt`: `AppLinkEnvelope` (https, exact host, no user info, default port, exact path, length cap, query ignored) | **Yes** (`ImportLinkParser`) | Moved into `ImportLink.kt`, same checks; its refusal no longer carries the spike's problem enum (the import route only ever reported `NOT_A_LINK`) |
| `LinkHandoff.toUrl` default path; `LinkHandoff.FIXED_EXAMPLE` | Default path pointed at the spike; example used only by tests | Default is now `/autopilot/import`; the example moved into `HandoffFragmentTest` |
| Manifest `<activity .ui.applink.AppLinkTestActivity>` with its `autoVerify` filter for `/autopilot/test` | No | Removed. The import activity keeps its own `autoVerify` filter on the same host, so domain verification is unaffected |
| `applink-site/autopilot/test.html` | No | Deleted from this repo (the published Pages repo is separate; see §6) |
| Tests: `AppLinkTestActivityTest`, `AppLinkTestViewModelTest`, `TestLinkParserTest`, `AppLinkDeviceTest` (emulator) | Spike only | Deleted |
| `HandoffFragmentTest` (strict `ap=v1` parser) | Shared parser | Kept, now end to end through `/autopilot/import`; envelope cases extended (port, user info, `/autopilot/test` refused) |
| `ImportLinkTest`, `HandoffImportActivityTest`, `ManifestContractTest` | — | Spike references replaced: the test route is refused by the parser, resolves to no activity, and no manifest filter may claim it |

Untouched: `assetlinks.json`, `import.html`, `HandoffFragment`, `ImportLinkParser` checks, `HandoffImporter`,
`HandoffProposalBridge`, `HandoffImportActivity` (Proposal Review, undo, *Return to ChatGPT*),
`HandoffImportDeviceTest`, signing and App Link host.

## 3. Verification

### 3.1 Local (this container)

`./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --continue --rerun-tasks` → **BUILD SUCCESSFUL**

| Check | Result |
| --- | --- |
| `lintDebug` | No issues found |
| `testDebugUnitTest` | **459 tests, 0 failures** (485 before: the spike-only test classes were removed) |
| APKs | debug, release and androidTest built |
| `verify-release-apk.sh` on the local release APK | ID `com.lifeadmin.autopilot` ✓, versionCode 2 ✓, versionName 0.2.0 ✓, no `INTERNET` ✓ (permissions: `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, plus `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and the AndroidX dynamic-receiver permission merged by libraries), `/autopilot/import` ✓, no `/autopilot/test` ✓, signature verifies ✓ — **refused (exit 2)**: signed with this container's debug key `46:31:E3:…:EE:D1`, not the published `A5:C5:6F:…:21:46`. The personal key is not available here, so **no locally built APK is offered for the phone** |
| Room schema | `AppDatabase.VERSION = 8`; `app/schemas/…/1.json`–`8.json` and `data/local` unchanged since `6f53ed9` |

### 3.2 CI on the code commit

[Android CI #195](https://github.com/HassanJaveed84/autopilot/actions/runs/40604969440) on `59b5347` (the exact code
commit; later commits change Markdown only, which CI skips by design):

| Job / step | Result |
| --- | --- |
| Lint, unit/Robolectric tests, debug + release + androidTest APKs (personal key restored from secrets) | **Success** |
| *Verify release APK* | **Success**: `com.lifeadmin.autopilot`, versionCode 2, versionName 0.2.0, no `INTERNET`, `/autopilot/import` present, no `/autopilot/test`, signature verifies; signer SHA-256 `A5:C5:6F:43:16:77:81:B6:8B:5F:10:70:BF:36:A6:AF:17:76:D5:6C:1D:BB:B8:8D:C2:44:BD:BD:A2:56:21:46` **= published `assetlinks.json`** |
| Artifact [`autopilot-0.2.0-release`](https://github.com/HassanJaveed84/autopilot/actions/runs/40604969440/artifacts/12779479515) | `autopilot-0.2.0-release.apk` + `verify-release.txt` (2.7 MB zip) |
| Instrumented tests (emulator API 34) | **Success: 12/12** (16 before; the 4 removed were the spike's `AppLinkDeviceTest`) |

## 4. Updating the existing installation

The phone runs `com.lifeadmin.autopilot` 0.1.0 (versionCode 1), signed with the personal certificate
`A5:C5:…:21:46` (owner-confirmed App Link verification on the S24 Ultra). Android installs 0.2.0 as an
in-place update, keeping app data, when: same application ID (✓), higher versionCode (2 > 1 ✓) and the
same signing certificate (✓ only for the CI artifact the gate verified; §3.2). The database stays v8 with
every migration, so no migration runs on update.

Install `autopilot-0.2.0-release.apk` from the CI artifact (`adb install -r` or the file manager). Do not
install the `apks` artifact's debug APK expecting an update: it is a different package
(`com.lifeadmin.autopilot.debug`).

## 5. Phone acceptance checklist (owner)

1. Settings → Export a fresh backup and keep it off the phone.
2. Install the 0.2.0 APK over 0.1.0; it should say *Update*, not ask to uninstall.
3. App info shows 0.2.0; tasks, steps, reminders and Inbox are intact; a reminder still fires.
4. Saved research opens read-only (task card and Settings → Saved research); Settings offers *Remove the old Anthropic API key* if one was stored.
5. `adb shell pm get-app-links com.lifeadmin.autopilot` → `hassanjaveed84.github.io: verified` (re-verified on update).
6. Discuss in ChatGPT → *Open in Autopilot* opens Proposal Review in the right task; apply, undo, *Return to ChatGPT*.

## 6. Risks

- **Published site.** `autopilot/test.html` may still be served by the separate `HassanJaveed84.github.io`
  repository. Harmless (no app handles the path any more; it is only a fallback page), and removable there.
- **CI without the secret** (forks): the release gate now fails instead of producing a debug-signed
  release APK. Intended for this repository.
- **Not yet done:** the phone install and acceptance test (§5).
