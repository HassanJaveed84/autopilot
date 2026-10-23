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
| **Hardening A** — completion policy: partly / unanswered / over-budget / budget verified by code / unpriced pick / user-permitted exception / brand preference (soft, strict) / exclusion / missing output / your-call with and without options / false Answered / outcome ⇔ gaps / extractor soft flags | `CompletionPolicyTest` (15) | ✅ |
| **Hardening B** — provenance: discovered-only result; pointer on a cited source (price never confirmed, Restated tag); provider citation confirms; validated restatement vs new fact (claims, answer sentences); unknown ids; citation on a neighbouring heading; evidence preserved across runs, confirmed beats unconfirmed; one identity per page; matcher strictness | `ProvenanceHardeningTest` (9), `ContractParserTest` | ✅ |
| **Hardening C** — cost: floor from observed searches, failure with complete usage, rejected = $0 assumed, nothing reported = unknown, stopped with searches, dashboard groups reconcile with the ledger; service: failure before dispatch, after observed searches, with full usage, stop during `pause_turn`; Room restore and backup import never duplicate a provider message id | `UsageAccountingTest` (6), `DeepResearchServiceTest`, `ResearchSessionRepositoryTest`, `BackupRepositoryTest` | ✅ |
| Review fixes: simultaneous Start → one run, one reservation; proposals inherit evidence only for the product they name; brand label stops at the sentence | `DeepResearchServiceTest`, `SessionAssemblerTest`, `CompletionPolicyTest` | ✅ |
| **P0 timeout** — real SDK client vs MockWebServer: M3A.2 call survives a reply slower than the legacy 60 s-class limit but within its own; times out at its own limit with the configured seconds in the message and one request only; legacy flow keeps its own limit; key change/reset keep the per-call limit | `ResearchTimeoutHttpTest` (4) | ✅ |
| **P0 orchestration** — per-call timeout policy; Deep gather in bounded batches of ≤6 with a checkpoint after every call; timeout after a completed batch keeps evidence (Partial) and never resends; first-call timeout saves nothing and names its limit; stop/discard removes the checkpoint, stop/keep finalises; process death → Interrupted checkpoint + unknown ledger row; budget across batches and pause-turn | `DeepResearchServiceTest` (7 new) | ✅ |
| **Second review** — strict budget needs confirmed same-currency prices (unconfirmed / missing / other currency → Needs a check; a seen violation still flagged); restatements with changed numbers, units, currency, dates, dosages, percentages or added/dropped values never inherit verification; brand preference judged on product identity, never on "not from Thorne" prose | `EvidenceIntegrityTest` (5) | ✅ |


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
| Screens in light/dark at 1.0/1.3/2.0 | `ScreenshotTourResearch2` (emulator, CI screenshot workflow) | ✅ runs 39486121391 and 39488460868: 4/4 variants, 32 screens each; light/dark 1.0 from the latter committed under `docs/screenshots/m3a2/` |
| Continuation never ends in a generic MALFORMED: cut off before text / paused before text with no budget / searches without text / empty reply each named, "No new findings were saved. Your previous research is unchanged.", original content untouched, failed run + call log recorded, ledger rows kept | `ContinuationRegressionTest` (11; all failed on the unfixed code) | ✅ |
| Reply shapes through the real SDK client: thinking + search + `max_tokens` with no text, tool-only `pause_turn`, empty `end_turn`, cited text — block kinds/counts only, never content | `ReplyShapeHttpTest` (3, MockWebServer) | ✅ |
| Follow-up question as the objective: verbatim quote, constraints, candidates by id, ≤ 12 cited sources, REQ id; answer saved as a follow-up card, original answer/title never overwritten; UNRESOLVED-only → Not found with reason; free text → Needs a check; headings without ids kept as text; no generated proposal for a follow-up | `ContinuationRegressionTest`, `DeepResearchServiceTest.continuationKeepsSourceIds…`, `Research2FlowUiTest.aTypedFollowUp…` | ✅ |
| Failure screen for a cut-off continuation: honest headline, "unchanged" message, technical record (`stop=max_tokens`, block counts) without the question, Copy, back to the unchanged result with the attempt on the log | `Research2FlowUiTest.aContinuationCutOffBeforeAnyText…` | ✅ |
| Paused turn with no searches left is never continued past the budget; with budget left it is continued once with the remainder | `ContinuationRegressionTest.toolOnlyPauseTurn…` (2) | ✅ |
| Live BYOK research against the real API | — | ⏳ pending (not run by the agent) |

## Production kickoff M1 — durable state, proposals, provenance, privacy allow-lists (2026-10-23)

| Requirement | Tests | Status |
|---|---|---|
| Room v6 migration keeps tasks, steps, deadlines, sessions and ledger; `rev` starts at 0; steps are USER-origin; new tables empty and FK-free | `MigrationTest.migrateV5ToV6…` | ✅ |
| PLAN-073: ticking an unrelated step flags only the item touching it; a snooze invalidates nothing; renamed/deleted steps conflict | `ProposalReconcilerTest.tickingAnUnrelatedStep…`, `snoozingNeverInvalidatesAnything`, `deletedOrRenamedSteps…`; `ProposalApplierTest.anUndecidedConflictAtApplyTimeWritesNothing` | ✅ |
| PLAN-074: a done step changes only through an explicit per-change choice and is never reopened | `ProposalReconcilerTest.deletedOrRenamedStepsConflictAndDoneStepsAreProtected`, `ProposalApplierTest.doneStepsChangeOnlyThroughAnExplicitChoice` | ✅ |
| OD-6 / PLAN-041 / INT-053: a user-stated deadline is never altered even when chosen; a target lives in its own field with its basis; a proposed deadline applies only when none exists and is labeled | `ProposalReconcilerTest.userStatedDeadlinesAreNeverChanged`, `ProposalApplierTest.userStatedDeadlineIsNeverChangedEvenWhenChosen`, `deadlineProposalAppliesOnlyWhenTheTaskHasNoneAndIsLabeled` | ✅ |
| OD-5: nothing pre-selected; "Select recommended" picks only recommended, non-material, non-conflicting items; PLAN-072 materiality | `ProposalReconcilerTest.selectRecommendedSkipsMaterialAndConflictingItems`, `materialityFollowsPlan072` | ✅ |
| INT-050 / PLAN-071: saving a set writes nothing; apply changes only chosen items; edited values apply without rewriting the proposal | `ProposalApplierTest.savingASetWritesNothingToTheTask`, `appliesOnlyChosenItems…`, `editedValueIsApplied…` | ✅ |
| INT-030–033: every applied element carries provenance with `src`, as-of and investigation; a user rename keeps the origin and marks the user as latest editor | `ProposalApplierTest.appliesOnlyChosenItems…`, `userRenameKeepsTheOriginAndMarksTheUserAsLatestEditor` | ✅ |
| INT-054 / OD-2: history names the set and both revisions; `baseOriginal` unchanged, `base` rebased; `rev` increments on every change | `ProposalApplierTest.appliesOnlyChosenItems…`, `everyUserChangeBumpsRev` | ✅ |
| OD-1: research sets stay open while items are pending; planning sets close on apply | `ProposalApplierTest.planningSetsCloseOnApply`, `appliesOnlyChosenItems…` | ✅ |
| Reminder and status items go through the policy path and state machine; finishing is never a proposal | `ProposalApplierTest.reminderAndStatusItemsGoThroughThePolicyAndStateMachine`, `ProposalReconcilerTest.completedTasksBlockEverything…` | ✅ |
| Undo restores title, notes, steps (ids, order, origin), reminders and targets while `rev` is unchanged; refuses after any later change | `ProposalApplierTest.undoRestoresEverything…`, `undoRefusesAfterAnyLaterChange` | ✅ |
| PLAN-010/013/014: drafts persist on every change, never create a task or policy, keep the paid result across a database restart, discard is user-initiated; text stored verbatim; exactly 20,000 characters accepted, 20,001 rejected on create and update with nothing written | `DraftRepositoryTest` (5) | ✅ |
| Concurrent Apply of the same set applies each item once (one note line, one provenance record, one history event, one `lastApply`); the loser is refused, never merged | `ProposalApplierConcurrencyTest` (deterministic gating coordinator; failed on the unfixed code) | ✅ |
| Partial apply then the rest: remaining items reconcile against the rebased base, `baseOriginal` unchanged, two history entries, undo reverts only the last apply; re-applying a settled item is refused; a mixed request applies only pending items | `ProposalApplierTest.partialApplyThenTheRest…`, `reapplyingASettledItemIsRefused…` | ✅ |
| PLAN-045 absence (Document F Stage 0; H §1): no reminder policy is written unless a reminder item is explicitly chosen; no recurrence exists to enable *(recurrence proposals conditional on PLAN-047, P1 — not graded)* | `ProposalApplierTest.nothingButAChosenReminderItemEverTouchesReminders`, `ReminderEngineTest`/`TaskRepositoryTest` no-silent-reminder rows | ✅ |
| OD-6 edge: a deadline the user removed after the snapshot makes a proposed deadline a conflict, not silently compatible | `ProposalReconcilerTest.userStatedDeadlinesAreNeverChanged` | ✅ |
| Handoff §14 items 1 and 4 (payload inspection): new-draft payload = text + timing; task payload = allow-list only, signals switchable, findings only when passed for this request, no internal ids; turn payload = included context + sent findings, counts for excluded/not-sent, fresh checks named; excluded item named in a hand-edited objective warns; stable fingerprint | `ContextAllowListTest` (7) | ✅ |
| Backup: drafts, proposal sets and provenance round trip; pre-v6 files import unchanged; a set for a missing target rejects the import | `BackupRepositoryTest.draftsProposalSetsAndProvenanceRoundTrip…`, `aProposalSetForAMissingTargetRejectsTheImport` | ✅ |
| Prototype acceptance suites (behavioural reference only, not production acceptance) | `tests/*.js` from the handoff package, Playwright 1.56 | ✅ 76/76, 31/31, 41/41 at 360 and 412 px |

## Document F Stage 0 acceptance mapping (baseline v1.0; reconciled 2026-10-23)

Legend: ✅ covered by deterministic tests now · 🟡 partially covered by existing M2/M3A.2 tests (semantics to be re-derived) · ⏳ planned (milestone). *(conditional on ID)* marks expectations the release gate does not require until that requirement ships (Document E G4).

| Document F §2.3 bullet | Requirement IDs | Tests now | Status / milestone |
|---|---|---|---|
| Draft persistence and recovery; no task, notification or recurrence without explicit confirmation | PLAN-010, 013, 014, 045 | `DraftRepositoryTest`, `ProposalApplierTest.nothingButAChosenReminderItem…`; recurrence proposals *(conditional on PLAN-047)* | ✅ storage/absence · ⏳ M3 recovery surface and kill-mid-planning |
| Consent and authorization events vs. provider calls | PLAN-012, 054, 100; RES-021, 023, 025; INT-014 | `PlanningUsageLedgerTest`, M3A.2 gating tests | 🟡 · ⏳ M4/M5 per-turn authorization record, RES-025 boundary |
| Outbound payload inspection: other tasks, other investigations, unselected findings never present | PLAN-080–081; RES-093, 160; INT-012, 020–022, 064 | `ContextAllowListTest` (7) | ✅ builders · ⏳ M4/M5 wiring |
| Ledger completeness: one entry per call; model, effort, usage, estimate; partial/unknown markers | PLAN-102; RES-123, 143, 154 | `CostCalculatorTest`, ledger tests, `PlanningUsageLedgerTest` | 🟡 (no `effort` field yet) · ⏳ M5 |
| Ceiling precedence: search budget vs. spending limit vs. sufficiency | RES-030, 141 | `DeepResearchServiceTest` spend-limit cases | 🟡 · ⏳ M4/M5 named stopping reason, combined budget |
| Status derivation from fixed claim sets; same evidence → same status; caveat guard; no negative claims; claim-level contradiction; independence | RES-060, 062, 063, 064, 070, 045 | `SessionAssemblerTest`, `ContractParserTest` (M3A.2 semantics) | 🟡 · ⏳ M4 after the template layer is retired |
| Citation-to-claim linkage: scope match; claim type ≠ judgment | RES-044, 085, 101 | provenance-pointer tests in `ContractParserTest` | 🟡 · ⏳ M4 |
| Reconciliation: ticked step flagged, snooze ignored; completed-step protection | PLAN-073, 074 | `ProposalReconcilerTest`, `ProposalApplierTest`, `ProposalApplierConcurrencyTest` | ✅ · ⏳ M2 choice surface |
| Provenance and as-of on incorporated findings; report immutability | INT-030, 033; RES-092; INT-054 | `ProposalApplierTest` provenance/history/snapshot tests; `ContinuationRegressionTest` (original answer never overwritten) | ✅ · ⏳ M4 research `src` population |
| Leakage scan of result text for internal identifiers and status labels | RES-087 | — | ⏳ M4 |
| Timing-field mapping of deadline / event / target / intended date / reminder | PLAN-040–041 | `ProposalApplierTest` (target apart from deadline; labeled deadline only when none exists), `ProposalReconcilerTest` | ✅ fields and apply rules · ⏳ M3 language → role mapping |

**Gates (Document F §2.1) served by M1 code:** Task safety and Preservation — `ProposalApplier` (one explicit choice per item, re-reconciled and re-read inside the lock, undo only while `rev` is unchanged), `DraftRepository` (verbatim, no truncation). Leakage — payload builders. Authorization — unchanged in M1 (M4/M5). Stage 1–3 live benchmarks: not run; require the owner's authorisation and key.

## Production M2 — the one Proposal Review (2026-10-23)

| Requirement | Tests | Status |
|---|---|---|
| OD-5 / PLAN-071: nothing selected on open; "Select recommended" picks only recommended, non-material, non-conflicting items; individual select/deselect; editing never selects | `ProposalReviewViewModelTest.nothingIsSelectedOnOpen…`, `ProposalReviewUiTest.nothingSelectedSelectRecommended…`, `Research2FlowUiTest.supplementsBenchmark…` | ✅ |
| PLAN-072: material changes (deadline, target, reminder, status, user wording) in their own section with a visible per-item Accept; never bulk-selected | `ProposalReviewUiTest.nothingSelected…` (reminder stays unaccepted and unwritten; target accepted explicitly), `ProposalReviewLargeTextUiTest` | ✅ |
| Current vs proposed shown per item; explanations collapsed until asked | `ProposalReviewUiTest` (`review_current_e`, `review_why_a`), `ReviewPresenter` via view-model tests | ✅ |
| PLAN-073 in the UI: a step ticked mid-review is flagged prominently without rewriting the proposal; the rest still applies; Keep yours declines without writing; an undecided conflict disables Apply | `ProposalReviewUiTest.aStepTickedMidReview…`, `ProposalReviewViewModelTest.applyWritesOnlyChosenItems…` | ✅ |
| OD-6 / PLAN-041 in the UI: a proposed deadline against a user-stated deadline is blocked and labelled; the target applies apart from the deadline; the deadline is unchanged after apply | `ProposalReviewUiTest.nothingSelected…` | ✅ |
| INT-042 / INT-050: research proposals go through the one review; the task is unchanged until Apply; applied steps carry `RESEARCH` origin and provenance; evidence lives in provenance, not note lines; undo restores | `Research2FlowUiTest.supplementsBenchmark_preflightToProposalsApplyUndoAndUsage` | ✅ |
| Planning on a saved task hands off to the review; nothing written, nothing pre-selected, no `plan_approve` | `PlanScreenUiTest.followThroughHandsOffToTheProposalReview…`, `AiPlanApprovalTest.followThroughSendsOnlyTheSelectedTask…ThroughTheReview` | ✅ |
| Unrelated edits meanwhile never invalidate a compatible planning change; a duplicate wording is a conflict, nothing written | `AiPlanApprovalTest.unrelatedEditsMeanwhileNeverInvalidate…` | ✅ |
| Bridge mapping: planning (steps/next recommended, material flags, target vs deadline, unsupported schedule alternative, no priority, advice/questions carried) | `PlanningProposalBridgeTest` (4) | ✅ |
| Bridge mapping: research (kinds → items, origins, recommendation only for sourced steps, `src` from refs/sources with status and as-of, legacy applied kept, idempotent set id) | `ResearchProposalBridgeTest` (3) | ✅ |
| Reject / Reject all decline without touching the task and close the set when nothing is pending | `ProposalApplierTest.declineMarksItems…`, `ProposalReviewUiTest.rejectAllClosesTheSet…` | ✅ |
| INT-031/032: provenance marker on the task step opens the record (findings, status, as-of, evidence keys, model) | `ProposalReviewUiTest.nothingSelected…` (`step_prov_*`, `provenance_sheet`) | ✅ |
| Task-detail banner per open set with the pending count; the saved task stays separate from pending changes | `ProposalReviewUiTest`, `Research2FlowUiTest` | ✅ |
| Process recovery: selection, edits and conflict decisions survive view-model recreation (SavedStateHandle) | `ProposalReviewViewModelTest.selectionEditsAndDecisionsSurviveRecreation` | ✅ |
| Concurrent apply / idempotency (unchanged from M1, now the only path for both features) | `ProposalApplierConcurrencyTest`, `ProposalApplierTest.reapplyingASettledItemIsRefused…` | ✅ |
| Narrow width (320 dp) at 200 % text: review usable end to end; 48 dp targets and labelled controls; the whole M3A.2 research flow at 200 % now ends in the review | `ProposalReviewLargeTextUiTest`, `ProposalReviewUiTest.touchTargetsAndLabelsAreAccessible`, `ResearchFlowLargeTextUiTest` | ✅ |
| Screenshot tour through the review (emulator, CI screenshot workflow) | `ScreenshotTour` s16/s16b/s17/s28, `ScreenshotTourAiReview` j2–j4 | 🟦 runs on the CI emulator job; not run locally |

