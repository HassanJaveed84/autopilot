# Test matrix

Legend: ✅ automated & passing in this container · 🟦 automated, runs on CI emulator only ·
📱 manual on a physical device (not yet performed) · — not applicable.

Commands: `./gradlew testDebugUnitTest` (JVM + Robolectric, 123 tests), `./gradlew
connectedDebugAndroidTest` (3 instrumented tests, CI emulator API 33), `./gradlew lintDebug`.

## SPEC §10.1 — domain / scheduler (fake clock & zone)

| Requirement | Test(s) | Status |
|---|---|---|
| 09:00/18:00 daily computes next slot from arbitrary instants | `ReminderPlannerTest.twiceDailyPicksNextUpcomingSlot`, `twiceDailySimulatedWeekKeepsAlternating`, `ReminderEngineTest.trainTicketRemindsAt0900And1800UntilDone` | ✅ |
| Repeated reminder never creates another Task | `ReminderEngineTest.trainTicket…` (task count stays 1), `TaskRepositoryTest.persistentPresetCreatesOneTaskWithTwoSlots` | ✅ |
| Once-only stops after one occurrence | `ReminderPlannerTest.onceFiresOnlyOnce`, `ReminderEngineTest.onceReminderStopsAfterOneDelivery` | ✅ |
| Every-N-days anchored, no drift | `ReminderPlannerTest.everyThreeDaysAnchoredToStartDate`, `startDateInFutureIsRespected` | ✅ |
| DST spring gap / fall overlap | `LocalTimeResolverTest.*`, `ReminderPlannerTest.dstSpringForward…`, `dstFallBack…`, `nineAndSixKeepWallClockAcrossDstChange` | ✅ |
| Half-hour offset zones | `LocalTimeResolverTest.halfHourOffsetZone`, `halfHourDstShiftZoneGap` (Lord Howe), `ReminderPlannerTest.halfHourOffsetZone` | ✅ |
| Time-zone change | `ReminderPlannerTest.deviceTimeZoneChangeRecomputesWallClock`, `fixedZoneIgnoresDeviceZone`, `ReminderEngineTest.timeZoneChangeRecomputesWallClockSlots` | ✅ |
| Quiet hours crossing midnight, defer/coalesce, no double 08:00 | `QuietHoursCalculatorTest.*`, `ReminderPlannerTest.quietHours*`, `ReminderEngineTest.quietHoursDeferAndCoalesceIntoOneMorningReminder` | ✅ |
| Snooze across slot / resume | `ReminderPlannerTest.snooze*`, `ReminderEngineTest.snoozeFromNotification…`, `shortSnooze…`, `snoozeWithoutEndReminder…`, `snoozeDuringInFlightDeliveryCancelsIt` | ✅ |
| Waiting/Paused/Done/Canceled suppress reminders | `ReminderPlannerTest.waitingPausedDoneCanceledSuppressPolicyReminders`, `ReminderEngineTest.pausedHasNoAlarms…`, `waitingUsesOnlyCheckIn`, `archivedOrCanceledTasksNeverRemind` | ✅ |
| Reopen → new generation, stale broadcasts ignored | `ReminderEngineTest.doneThenStaleBroadcastThenReopen…`, `staleGenerationAfterEditIsIgnored` | ✅ |
| Missing deadline not inferred | `TaskRepositoryTest.quickCaptureToInboxHasNoReminderAndNoDeadline` | ✅ |
| Idempotent duplicate broadcasts; concurrent Done + delivery | `ReminderEngineTest.duplicateBroadcastNotifiesOnce`, `concurrentDoneAndDelivery…` (20 rounds), `AndroidIntegrationTest.duplicateAlarmBroadcastPostsOnce`, `DaoTest.compareAndSetClaimsOnlyOnce` | ✅ |
| Old missed alarms coalesced, no storm after reboot, next still scheduled | `ReminderEngineTest.rebootWithinGrace…`, `rebootBeyondGrace…`, `manyLateRemindersAfterOutageAreCoalescedNoStorm`, `multiDayOutageDoesNotReplayEveryMissedSlot`, `reconcileIsIdempotent` | ✅ |
| Clock moved back / early fire | `ReminderEngineTest.clockSetBackDoesNotRepeatDeliveredReminder`, `alarmFiringEarlyAfterClockChange…` | ✅ |
| Transaction crash / retry | `BackupRepositoryTest.partiallyCorruptBackupChangesNothing` (rollback), `ReminderEngineTest.osRefusingAlarmLeavesPlannedAndIsRetried` | ✅ (partial: no injected SQLite crash) |

## SPEC §10.2 — Room and app

| Requirement | Test(s) | Status |
|---|---|---|
| CRUD/status changes survive process death | `TaskRepositoryTest.tasksSurviveProcessRestart` (file DB closed & reopened) | ✅ |
| FK cascade; unique occurrence index | `DaoTest.*` | ✅ |
| Migration retains task text and policy times | `MigrationTest` (Robolectric, from exported `1.json`), `InstrumentedDatabaseTest` (MigrationTestHelper) | ✅ / 🟦 |
| Export/import roundtrip; malformed rejected w/o partial corruption | `BackupRepositoryTest.*` (7) | ✅ |
| UI: capture, complete, reopen, snooze, policy editing, search | `AppUiTest.*` (7) | ✅ |
| UI: waiting with typed reason | `InstrumentedUiTest.captureThenMarkWaitingWithTypedReason` (Robolectric can't idle a text field in an AlertDialog) | 🟦 |
| Permission warnings | `ReminderEngineTest.deniedNotificationsAreRecordedHonestly`, `AndroidIntegrationTest.deniedPermissionDoesNotPostAndIsRecorded` (banner UI itself: 📱) | ✅ |
| Accessibility semantics | `AppUiTest.iconOnlyControlsHaveAccessibleLabels`, `AccessibilityUiTest.*` (2× font, 48 dp touch targets, headings, merged row descriptions) | ✅ |
| Notification receiver actions: correct ids, authorized commands | `AndroidIntegrationTest.alarmPostsNotificationWithActionsAndDoneCompletesTask`, `snoozeAction…`, `unknownActionsAndMalformedIdsAreIgnored`, `ManifestContractTest.internalReceiversAreNotExported` | ✅ |
| Real alarm → notification on Android | `InstrumentedReminderTest` | 🟦 |
| Oversized/corrupt import; no secrets in logs | `BackupRepositoryTest.oversizedInputIsRejectedBeforeParsing`, `pathLikeOrHugeIdsAreRejected`, `PerformanceAndDiagnosticsTest` (diagnostics have no titles/notes), `TaskRepositoryTest.editLogsChangedFieldsWithoutNotesContent` | ✅ |
| Permissions minimal (no INTERNET, no USE_EXACT_ALARM) | `ManifestContractTest.*` + `aapt2 dump permissions` on release APK | ✅ |
| 100 tasks / 200+ slots responsiveness, sane alarm count | `PerformanceAndDiagnosticsTest` (300 slots → 100 alarms, reconcileAll ≈ 0.5 s JVM) | ✅ (device timing 📱) |

## SPEC §10.3 — physical device (📱 all pending)

1. Train tickets with 2 daily slots (pick near-future times), lock phone, verify delivery + actions.
2. Swipe notification away → task stays open, later reminders continue.
3. Done from notification → task done, no further alarms; Reopen reschedules.
4. Snooze until tomorrow → intervening alarms absent, policy resumes.
5. Doze via `adb shell dumpsys deviceidle force-idle` → note deviation.
6. Deny POST_NOTIFICATIONS → app usable, banner shown, ledger shows “not shown”.
7. Reboot → reminders rebuilt (Settings → Reminder health → Last check “boot: …”).
8. Change time zone / wall clock → schedule recomputed.
9. Toggle Alarms & reminders access with Precise timing on → exact/best-effort shown correctly.
10. Force stop → no delivery until relaunch (expected OS limitation).
11. Samsung battery settings / Do Not Disturb / channel blocked.
12. Airplane mode: create/edit/receive local reminders.
13. Reinstall + import backup → data readable, no reminder flood.
14. 100 tasks / 200+ slots → startup responsiveness.
