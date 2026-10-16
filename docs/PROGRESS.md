# Progress log

All commands run from the repo root in the Claude Code cloud container (Linux, JDK 21,
Android SDK installed from dl.google.com: platform 36, build-tools 36.0.0, platform-tools).
No KVM is available in the container, so **no emulator or device was available**; Android
framework behaviour is exercised with Robolectric on the JVM, and instrumented tests are wired
for GitHub Actions (emulator) only.

Sandbox note: Maven Central (`repo.maven.apache.org`) rate-limits this container (HTTP 429/404 with
`retry-after` ≈ 17 h). The build therefore resolves Maven Central artifacts through Google's official
mirror `maven-central.storage-download.googleapis.com` via `~/.gradle/init.d/` and
`robolectricRepoUrl` in `~/.gradle/gradle.properties`. **Neither file is part of the repo**; CI and
developer machines use the normal repositories.

## M0 — Bootstrap and architecture ✅

Completed:
- Gradle Kotlin DSL + version catalog; AGP 8.13.2, Gradle 8.14.3 wrapper, Kotlin 2.2.21, KSP 2.2.21-2.0.5,
  compileSdk/targetSdk 36, minSdk 26, Compose BOM 2026.06.01, Room 2.8.5, WorkManager 2.11.2,
  DataStore 1.2.1, Navigation 2.9.8. (Newer AndroidX releases such as core 1.19 / Compose UI 1.12 require
  compileSdk 37 + AGP 9.1; deliberately not adopted tonight to keep a known-good toolchain.)
- Package layout per SPEC §11 (`domain`, `data/local`, `data/repository`, `reminders`, `notifications`,
  `receivers`, `ui/*`, `di`, `core`).
- Room entities/DAOs for Task, TaskStep, ReminderPolicy, ReminderTimeSlot, ReminderOccurrence (unique
  `(policyId, plannedAt)`), TaskEvent; type converters; schema v1 exported to `app/schemas/`.
- DataStore-backed typed settings repository (+ in-memory fake).
- Manual DI container with injectable `AppClock`, `ZoneProvider`, `IdGenerator`; `AlarmDriver` and
  `NotificationPublisher` interfaces; `ScheduleCoordinator` seam.
- Pure scheduling core: DST-aware `LocalTimeResolver`, `QuietHoursCalculator`, `PolicySlotGenerator`,
  `ReminderPlanner`, `SnoozeCalculator`, presets, `TaskStateMachine`.
- Compose Material 3 theme (dynamic color + light/dark fallback), adaptive launcher icon, navigation scaffold.
- GitHub Actions CI (`.github/workflows/android-ci.yml`): lint, unit/Robolectric tests, debug+release APKs,
  emulator instrumented tests.

Validation (M0):
- `./gradlew lintDebug testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL; lint 0 errors, 3 warnings
  (unused resources reserved for M1B; `mipmap-anydpi-v26` folder kept because aapt2 rejected `mipmap-anydpi`).
- Unit tests: 49 run, 0 failures (44 pure JVM scheduling/state tests, 5 Robolectric Room DAO tests).
- Lint caught a real issue (`LocalDate.ofInstant` is API 34) — fixed.
