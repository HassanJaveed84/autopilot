# Test matrix

Legend: ✅ automated & passing in this container · 🟦 automated, passed on the CI emulator (API 34, run #8) ·
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

## Reminder reliability (2026-10-17)

| Requirement | Test(s) | Status |
|---|---|---|
| One-time reminder; absolute time across zone change | `DiagnosticsLifecycleTest.*` (18:25 one-time), `HardeningTest.oneTimeReminderKeepsItsAbsoluteMomentAcrossTimeZoneChange`, `ReminderEngineTest.onceReminderStopsAfterOneDelivery` | ✅ |
| Daily reminders with multiple times | `ReminderEngineTest.trainTicketRemindsAt0900And1800UntilDone`, `ReminderPlannerTest.twiceDaily*` | ✅ |
| Snoozing and unsnoozing (ledger + rescheduling) | `DiagnosticsLifecycleTest.snoozeAndUnsnoozeAreRecordedAndRescheduleIsLogged`, `ReminderEngineTest.snooze*` | ✅ |
| Completion cancels alarms + notification, logged with reason | `DiagnosticsLifecycleTest.completionLogsCancellationWithReason`, `pauseLogsCancellationWithReason`, `AndroidIntegrationTest.alarmPostsNotificationWithActionsAndDoneCompletesTask` | ✅ |
| Duplicate alarm broadcasts | `DiagnosticsLifecycleTest.duplicateBroadcastIsRecordedAndPostsOnce`, `coldStartRaceShowsAlarmReceiptEvenWhenCatchUpPostedFirst`, `SelfTestTest` (test alarm duplicate) | ✅ |
| Delayed alarm delivery (+24 min) with actual vs scheduled timestamps | `DiagnosticsLifecycleTest.lateAlarmRecordsActualReceiptAndPostTimesNotThePlannedTime` | ✅ |
| Overdue reconciliation attributed to its source | `DiagnosticsLifecycleTest.catchUpDeliveryIsAttributedToTheReconcileNotToAnAlarm`, `HardeningTest.hourlySafetyNetCatchesUpADroppedAlarmWithinTheGraceWindow`, `bootRecoveryRecordsReRegistrationAndMissedEntries` | ✅ |
| Quiet hours deferral recorded | `DiagnosticsLifecycleTest.quietHoursDeferralIsRecorded`, `ReminderEngineTest.quietHours*` | ✅ |
| Reboot recovery | `ReminderEngineTest.reboot*`, `AndroidIntegrationTest.bootBroadcastRebuildsAlarmsAfterTheyWereCleared`, `idempotentReconcilesDoNotFloodTheLedger` | ✅ |
| Time-zone changes and DST | `ReminderPlannerTest.dst*`, `ReminderEngineTest.timeZoneChangeRecomputesWallClockSlots`, `QuietHoursCalculatorTest.quietEndInsideRepeatedFallBackHourUsesSameNight` | ✅ |
| Notification permission denied | `DiagnosticsLifecycleTest.deniedPermissionIsRecordedAsSuppressedWithEnvironment`, `SelfTestTest.testNotificationWhenBlockedIsReportedAsFailed`, `AndroidIntegrationTest.deniedPermissionDoesNotPostAndIsRecorded` | ✅ |
| Exact-alarm permission unavailable / granted later | `DiagnosticsLifecycleTest.exactUnavailableIsVisibleInTheScheduleEntry`, `HardeningTest.returningFromExactAlarmSettingsUpgradesExistingAlarms`, `SelfTestTest.testReminderUsesExactWhenPreciseTimingOnAndGranted` | ✅ |
| Notification shows real posting time; due time as subtext | `NotificationChannelAndContentTest.reminderShowsActualPostingTimeAndDueTimeAsSubtext` | ✅ |
| New-install channel vibrates; existing channel never overwritten | `NotificationChannelAndContentTest.newInstall…`, `existingUserConfiguredChannelIsNeverOverwritten`, `InstrumentedReliabilityTest.freshInstallReminderChannelVibratesAndAlerts` | ✅ / 🟦 |
| Sound/vibration detection (ringer, DND, channel) | `NotificationChannelAndContentTest.environmentProbeReadsRingerModeAndChannelVibration`, `AlertAssessorTest` (12 rules incl. the S24 case) | ✅ |
| Self-tests (now / in 2 minutes) | `SelfTestTest`, `ReminderHealthUiTest.sendTestNotificationNowPostsOnce`, `scheduleTestReminderInTwoMinutesUsesTheAlarmDriver`, `InstrumentedReliabilityTest.testNotificationNow…`, `testAlarmArrivesThroughAlarmManager…` | ✅ / 🟦 |
| Health dashboard shows silent verdict and approximate timing | `ReminderHealthUiTest.vibrateModeWithChannelVibrationOffIsFlaggedAsProbablySilent`, `approximateTimingIsClearlyIndicated` | ✅ |
| Late-delivery hint only on evidence | `HardeningTest.lateBanner*` | ✅ |
| Diagnostics export has no task content; retention | `DiagnosticsLifecycleTest.exportContainsTimingButNoTaskContent`, `pruningKeepsTheNewestRows` | ✅ |
| Schema v2→v3 migration | `MigrationTest.migrateV2ToV3AddsDiagnosticsAndKeepsData` | ✅ |
| Physical Samsung device: actual sound/vibration/timing | `docs/REMINDER_RELIABILITY_REPORT.md` §6 | 📱 not performed |

## M2 — optional AI capture and planning (2026-10-18)

All provider tests use fakes or a local MockWebServer. **No test calls the live Anthropic API.**

| Requirement | Test(s) | Status |
|---|---|---|
| 20+ realistic requests, expected shapes, date/recurrence/ambiguity handling | `NaturalLanguageScenarioTest` (24 scenarios + 2: train ride, tomorrow at 8, 9 AM/6 PM, every weekday, next Friday, in 2 hours, next weekend, sometime soon, before my trip, when they reply, monthly, every 3 days, simple task, ISO deadline, injection, quiet hours, ambiguous hour, early enough, impossible date/time, overlong checklist, single weekday, past deadline, waiting check-in, hourly) | ✅ |
| Validation: missing fields, impossible dates, invalid time, duplicates, overlong checklist, unsupported schedules | `ProposalValidatorTest`, `NaturalLanguageScenarioTest` #4, #11, #19, #20, #21, #24 | ✅ |
| Review / edit / approve / reject / cancel; user text preserved | `PlanViewModelTest`, `PlanScreenUiTest` | ✅ |
| No DB writes or alarm scheduling before confirmation | `AiPlanApprovalTest.proposingWritesNothingAndSchedulesNothing`, `providerErrorsAreSurfacedAndNothingIsWritten`, `PlanScreenUiTest.trainRideReview…` | ✅ |
| Approval creates a real task, steps and the expected reminder policy (+ real alarm) | `AiPlanApprovalTest.approvalCreatesARealTaskWithStepsAndBothDailyReminders`, `theCreatedTaskKeepsRemindingUntilDone`, `PlanScreenUiTest` | ✅ |
| Repeated approvals / retries cannot duplicate tasks or alarms | `AiPlanApprovalTest.repeatedApprovalIsIdempotent`, `concurrentApprovalsCreateOneTaskAndOneAlarm`, `followThrough…AppliesTheConfirmedDiffOnce`, `PlanViewModelTest.doubleTapApproveCreatesOneTask` | ✅ |
| Edit of an existing task shows a diff and needs confirmation; stale edits rejected | `AiPlanApprovalTest.followThrough…`, `staleSuggestionIsRejectedIfTheUserEditedTheTaskMeanwhile`, `PlanScreen` confirm dialog | ✅ |
| Online errors: 401, 429, 5xx, timeout, refusal, malformed, no network, cancel | `AnthropicProviderHttpTest` (20 tests) | ✅ |
| AI disabled / unconfigured / offline: no call; offline workflows intact | `AiPlanApprovalTest.disabledAiMakesNoCall…`, `missingConsentOrKeyMakesNoCall`, `offlineMakesNoCall`, `manualCaptureStillWorksWithAiOffOrOffline`, `PlanScreenUiTest.withoutAi…`, the full M1 suite | ✅ |
| Spend caps: input length, output tokens, daily requests, no SDK retries | `AiPlanApprovalTest.overlongInputIsRejectedLocallyNotTruncated`, `dailyRequestCapBoundsSpendAndResetsNextDay`, `AnthropicProviderHttpTest.http429…NotRetriedSilently` | ✅ |
| API key never in export, diagnostics, settings, errors or VCS; encrypted at rest | `ApiKeySecurityTest` (incl. repository secret scan), `AnthropicProviderHttpTest.errorMessagesNeverContainTheKey`, `KeystoreApiKeyStoreTest` (real Keystore) | ✅ / 🟦 (CI run #17, 8/8 instrumented) |
| Consent before first use; key save/replace/test/delete; delete AI settings | `AiSettingsUiTest`, `AiPlanApprovalTest.deletingAiDataRemovesKeyConsentAndSettingsButNotTasks` | ✅ |
| Process recreation / rotation | `PlanViewModelTest.processDeathDuringRequestRestoresTextAndExplains`, `reviewSurvivesRecreationWithEdits` | ✅ |
| Provider boundary (only `ai/anthropic` imports the SDK) | `ArchitectureBoundaryTest` | ✅ |
| Deterministic stall hints (no AI, no background calls) | `StallDetectorTest` | ✅ |
| Network config: TLS only, system CAs | `ManifestContractTest.networkIsTlsOnlyWithSystemCertificates` | ✅ |
| Regression: scheduler, snooze, quiet hours, notifications, diagnostics | the full pre-M2 suite (186 tests) | ✅ |
| Live Anthropic API with a real key; device install | `docs/M2_AI_CAPTURE_REPORT.md` § device steps | 📱 not performed |

## M3 — consented web research (2026-10-20)

| Requirement | Tests | Status |
|---|---|---|
| Citations only from structured provider metadata; prose URLs stripped and labelled suggestion; unsafe URLs ignored | `BriefParserTest` (`citedSentencesBecomeSourced…`, `proseUrlsAreStripped…`, `uncitedResponseIsUnverified…`, `unsafeCitationUrlsAreIgnored`), `AnthropicResearchProviderHttpTest.parsesCitationsSearchesAndUsage` | ✅ |
| Disagreement needs two different cited sides; injection-looking lines never become moves | `BriefParserTest.disagreementNeedsTwoDifferentCitedSides`, `injectionLikeTextIsDroppedFromMovesAndFlagged` | ✅ |
| Conservative source tiers: *Official* only for government domains on label boundaries; look-alikes, smuggled suffixes, userinfo hosts and brand names are *Web page* | `SourcesTest`, `BriefParserTest.sourceTieringIsConservative` | ✅ |
| Citation accounting: once per cited block across sections and line splits; disagreement sides not double counted; duplicate URL in one block; uncited results stay 0 | `CitationAccountingTest` | ✅ |
| Apply/undo atomic with research bookkeeping: injected failure rolls back steps, notes and history; retry applies once; undo never overwrites a later edit | `ResearchActionsTest` (hardening tests) | ✅ |
| Delete all / delete for task while a request runs: cancels, awaits, saves nothing, other task's run untouched | `ResearchServiceTest` (finding D tests) | ✅ |
| Request shape: `web_search_20250305`, `max_uses = 3`, no `user_location`, no fallbacks, minimal scope | `AnthropicResearchProviderHttpTest.sendsWebSearchToolWithCapsAndNoLocation` | ✅ |
| `pause_turn` continued once within the budget; not continued when the budget is spent | `…pauseTurnIsContinuedOnceWithinTheSearchBudget`, `…pauseTurnWithBudgetSpentIsNotContinued`, `…secondPauseTurnIsNotContinuedAgain…` | ✅ |
| Stop reasons: refusal, max_tokens (truncated), search-tool error, empty answer | `…refusalNeverReachesParsing`, `…maxTokensIsASuccessMarkedTruncated`, `…searchToolErrorWithoutAnswerIsReported`, `…emptyAnswerIsMalformed` | ✅ |
| Error table: web search not enabled, credit, 401/403/429(+retry-after)/5xx, timeout, network, cancel; one HTTP request per attempt | `…mapsWebSearchNotEnabledAndCreditErrors`, `…mapsAuthRateLimitAndServerErrors`, `…timeoutAndNetworkFailuresAreReportedNotRetried`, `…cancellationAbortsTheCallAndPropagates` | ✅ |
| Gating: AI off, consent, key, explainer version, offline, daily cap (10, separate from planning); rejected starts send nothing | `ResearchServiceTest` (`gateReflectsEveryPrecondition`, `rejectedStartsSendNothing`, `dailyCapIsEnforcedAndResetsNextDay`) | ✅ |
| Scope toggles change exactly what is sent; brief saved with status; failures save nothing but count | `ResearchServiceTest` (`successfulRunSavesABrief…`, `scopeTogglesChangeWhatIsSent`, `providerFailureIsSurfaced…`, `unverifiedResponseIsSavedAsUnverified`) | ✅ |
| Stop cancels the run, reports searches seen, saves nothing; one run at a time | `ResearchServiceTest.stopCancelsTheRunAndReportsSearchesSeen` | ✅ |
| Delete all research removes briefs + explainer only; task delete cascades to briefs | `ResearchServiceTest.deleteAllResearch…`, `…deletingTheTaskCascadesToItsBriefs`, `ResearchRepositoryTest.deleteAndCascade` | ✅ |
| Persistence: round trip of every finding type, applied moves, pruning to 20, payload cap, unreadable rows hidden | `ResearchRepositoryTest` | ✅ |
| Migration 3→4 keeps data and adds `research_briefs` with cascade | `MigrationTest.migrateV3ToV4AddsResearchBriefsAndKeepsData` | ✅ |
| Backup includes briefs (typed, no transcript/key); orphan brief rejects the import; re-import idempotent | `BackupRepositoryTest.researchBriefsRoundTripAndBadOnesRejectTheWholeImport` | ✅ |
| Selective apply: dedupe against steps and applied list, notes appended under one header, idempotent per selection, conflict on changed task, refused on done task, never touches reminders/status/deadline | `ResearchActionsTest` (`planDeduplicatesAndShapesRows`, `applyIsTransactionalIdempotentAndRecorded`, `applyRefusesWhenTheTaskChangedUnderneath`, `applyRefusesOnDoneTasks`, `notesAreAppended…`) | ✅ |
| Undo only while safe | `ResearchActionsTest.undoRestoresOnlyWhileSafe` | ✅ |
| UI end to end: tiles → preflight → explainer → progress → brief → source sheet → apply → undo → research card; AI off + offline reading; provider failure keeps the question; Stop; offline preflight blocker; stateDescription on move rows; 48dp source targets | `ResearchFlowUiTest` (Robolectric, fake provider) | ✅ |
| Screens in light/dark at 1.0/1.3/2.0 | `ScreenshotTourResearch` (emulator, CI screenshot run 39405082136: 4 variants × 23 screens) | ✅ |
| Whole flow at 200% text under Robolectric; return to a scrolled task after a long detour | `ResearchFlowLargeTextUiTest`, `ResearchFlowUiTest.returningToTheTaskAfterALongDetourStillShowsIt` | ✅ |
| Live BYOK research against the real API | — | ⏳ pending (not run by the agent; see report) |


## M3A.2 — deep research, actionable results, cost ledger (2026-10-20)

| Requirement | Tests | Status |
|---|---|---|
| Deterministic requirement extraction: supplements → 4 items + brand + "one step per item"; fragrance → shortlist + budget/region/gift; how-to/checklist/quick answer; exclusions; unsure flag; user ids never collide | `RequirementExtractorTest` | ✅ |
| Contract parser: every REQ id kept (unknown status → Not found), picks/label facts/for-you lines, candidates/variants/prices, excluded, conflicts, callouts, proposals, unresolved; uncited Fact → Unknown; `src=` only for known sources; prose URLs stripped; injection dropped; free-form answer keeps citations; stable source numbers across runs | `ContractParserTest` | ✅ |
| Price policy: confirmed only when the line is cited (with retrieval time); symbols/codes/sizes/retailers parsed | `ContractParserTest.priceParsingHandlesSymbolsCodesAndSizes`, `fragranceFixtureParses…` | ✅ |
| Completeness rules: Complete only when every Item/Output is covered and the run ended normally; budget exhausted never Complete; pending → Not found; coverage line | `SessionAssemblerTest` | ✅ |
| Proposal coverage: four requested categories → four reviewable proposals (research-later for the missing one); how-to steps → step proposals; evidence inherited from picks | `SessionAssemblerTest.supplementsBenchmark…`, `howToSteps…`, `Research2ActionsTest` | ✅ |
| Orchestrator: Thorough = gather + gap-fill with known sources and no repeated queries; Quick = one call; gap-fill skipped when covered; exhausted searches → Incomplete·budget; soft spend limit stops or trims the next call; failure after partial → Partial; refusal/empty → Failed, nothing saved; truncated → Partial; `pause_turn` continued once as a separate billed call; stop keep/discard; continuation keeps source ids and adds a run; busy/ delete-while-running; interrupted ledger rows settled on start | `DeepResearchServiceTest` (17) | ✅ |
| Pricing catalog and calculator: list prices for Haiku/Sonnet/Opus 5.5 and Fable 5.1 incl. cache write/read and the Haiku >100k tier; web search $0.01; unknown counts → partial/floor; unknown model → no price; pre-run estimate formula and range | `CostCalculatorTest` | ✅ |
| Ledger honesty: COMPLETE / NOT_BILLED ($0.00) / UNKNOWN (no $0) / PARTIAL (searches as floor); totals mark partial; provider message id de-duplicates; in-flight → interrupted; retention delete | `CostCalculatorTest` (ledger tests), `ResearchSessionRepositoryTest.roomLedger…` | ✅ |
| Planning (M2) calls recorded with cache usage and honest outcomes | `PlanningUsageLedgerTest` | ✅ |
| Adapter: cache_control on the system prompt, `max_uses`, no `user_location`, no tool when 0 searches, prior turn resent after `pause_turn`, usage incl. cache tokens and message id, stop reasons, error classes, no invented usage | `AnthropicResearchCallsHttpTest` (MockWebServer) | ✅ |
| Room v5 migration keeps tasks and legacy briefs; sessions cascade, ledger does not | `MigrationTest.migrateV4ToV5…` | ✅ |
| Session repository: round trip, applied bookkeeping, prune to 30, payload trimming keeps cited sources, unreadable rows hidden | `ResearchSessionRepositoryTest` | ✅ |
| Backup: sessions + usage round trip; append-only ledger on re-import; in-flight → interrupted; orphan session rejects the import | `BackupRepositoryTest.sessionsAndUsageRoundTrip…` | ✅ |
| Editable proposals → one transactional edit: edits, Add anyway, duplicates, evidence + check date in notes, idempotent key per task version, conflict on changed task, undo only while safe; never touches reminders/status/deadline | `Research2ActionsTest` | ✅ |
| UI end to end (Robolectric): tile → preflight (requirements edit, template, modes, Adjust, estimate) → explainer → investigation → result C/B (coverage, item cards, callouts, hero, re-rank, compare, excluded, conflict) → proposals (edit, Add anyway, apply, undo) → usage dashboard; stop sheet; provider failure; offline + AI-off reading; continuation | `Research2FlowUiTest` | ✅ 6 tests (CI run 39486121354: 474/474) |
| Whole M3A.2 flow at 200% text | `ResearchFlowLargeTextUiTest` | ✅ 1 test, fontScale 2.0 (fixed a real squeeze bug first) |
| Legacy M3A briefs still readable/applicable | `ResearchFlowUiTest` (2 tests) | ✅ |
| Screens in light/dark at 1.0/1.3/2.0 | `ScreenshotTourResearch2` (emulator, CI screenshot workflow) | ✅ run 39486121391: 4/4 variants, 32 screens each; light/dark 1.0 committed under `docs/screenshots/m3a2/` |
| Live BYOK research against the real API | — | ⏳ pending (not run by the agent) |
