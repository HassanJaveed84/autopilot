# Live research evaluation (development only)

These are paid, end-to-end runs of the production Research Online engine against the real Anthropic API. Each run is scored against **Document F v1.0 (frozen)**: [`docs/product-baseline/Document_F_Evaluation_Framework_v1.0_FROZEN.md`](../product-baseline/Document_F_Evaluation_Framework_v1.0_FROZEN.md). That file is the only standard used here. It changes only through Document E's change control, never to fit the implementation.

The harness is a JUnit test in `app/src/test/java/com/lifeadmin/autopilot/liveeval/`. Gradle excludes it from every test run unless `-PliveEval=true` is passed, so it is never part of the app, the APKs or CI.

## Runs (chronological)

| Scenario | Date | Source commit | Model | Level | Searches | Cost (from measured usage / estimate shown) | Essential dimensions (OF · EG · SH) | Verdict | Report |
|---|---|---|---|---|---|---|---|---|---|
| R-N(a), Stage 1 run 1/2 | 2026-10-23 (00:34Z 10-24) | `6d3a70b` | `claude-sonnet-5-5` | Standard (3 searches, 300¢) | 2 of 3, in 2 billed calls | $0.09611 / $0.13–0.24 | 2 · 1 · 1 | **Fail**: Leakage gate; Efficiency 0; serious failures (misleading completeness, misattributed quotation) | [R-N-a-2026-10-23.md](R-N-a-2026-10-23.md) · [sanitized JSON](R-N-a-2026-10-23.sanitized.json) |
| R-N(a) rerun on the fixed build (Stage 3 targeted follow-up) | 2026-10-24 (03:39Z) | `9cc17fe` (engine = `41dc471`), prompt v4 | `claude-sonnet-5-5` | Standard (3 searches, 300¢) | 1 of 3, in 1 billed call; FILL_GAPS skipped on sufficiency | $0.051416 / $0.13–0.24 | 2 · 2 · 2 | **Fail**: Leakage gate and Presentation 0 (unparsed attribute block on evidence rows, N1); no serious failure | [R-N-a-2026-10-24-rerun.md](R-N-a-2026-10-24-rerun.md) · [sanitized JSON](R-N-a-2026-10-24-rerun.sanitized.json) |

OF = objective fidelity, EG = evidence grounding, SH = status honesty. Essential dimensions must all score 2.

A historical row and its report are never edited after a fix. A rerun gets its own row and report, and the earlier one stays for comparison, as Document F §2.2 requires ("historical failures remain in the test record").

## What the harness exercises

The real `InvestigationService` performs each step in order:

1. Local objective preparation, which makes no call. The provider objective pass is off, as it is by default in the app.
2. Authorization, built exactly as the card builds it.
3. The staged run, with ceilings, checkpoints and the ledger.
4. The real `AnthropicResearchCalls` adapter, sending web search with structured citations.
5. `InvestigationParser`, then `ClaimAssessor`, then `ResultDeriver`, then `AnswerComposer`.
6. A file-backed Room database and `RoomUsageLedger`.

Only the planning provider is a fake. It serves objective preparation by provider, which these runs do not use. A scenario that depends on the provider objective pass, such as R-N(b), needs the harness extended first.

## Guards

- **Key handling.** The key is read from the environment variable `LIVE_EVAL_ANTHROPIC_API_KEY` by `EnvApiKeyStore`. It is deliberately not `ANTHROPIC_API_KEY`: cloud environments reserve that name for Claude Code's own authentication, and a value set under it did not reach the session's environment. Check only that it is non-empty. It is never written, logged, printed or reported. Reports pass through a redaction step and are checked for the key before they are saved.
- **Free preflight.** Before anything paid, the Models API must confirm that the key can use the configured model. If the preflight does not return HTTP 200, the run stops without changing the model and without retrying.
- **One run per scenario.** The harness refuses if a report for the scenario already exists. Pass `-PliveEvalRerun=true` only with the owner's approval for that specific run.
- **Spending.** The estimate must be under the cap: R-N(a) uses Standard, 3 searches and a 300-cent per-turn spending limit enforced by the service. There are no retries.

## Running

The key must be in the environment of the session that runs Gradle, and the session needs an Android SDK (`ANDROID_HOME` or a git-ignored `local.properties`) for `testDebugUnitTest` to configure. In a cloud session, install the SDK as in `docs/FLIGHT_PLAN_UI_PROGRESS.md`: command-line tools 11076708 from dl.google.com with the sha1 checked against Google's `repository2-3.xml`, then platform 36, build-tools 36.0.0 and platform-tools. If Maven Central answers HTTP 429, use the session-only mirror described there and never commit it.

A cloud session does not pick up an environment variable added after the session started. `--no-daemon` makes sure the test JVM inherits the current environment.

```
./gradlew --no-daemon :app:compileDebugUnitTestKotlin          # free: everything compiles
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true --tests 'com.lifeadmin.autopilot.liveeval.LiveInvestigationEval'
```

The output goes to a new directory for every run, `app/build/live-eval/<scenario>/run-<UTC time>/result.json`, with its own database. The harness refuses before any call if that directory already exists, so no earlier report is overwritten. The first R-N(a) run predates this layout and stays at `app/build/live-eval/R-N-a/result.json`. Reports stay untracked. It holds every request and reply, every search with its results, the ledger rows, the run events and the stored turn. The prompt text is synthetic and public. The system prompt is recorded by version and SHA-256.

## After a run: what gets committed

1. **The sanitized copy:** `docs/live-eval/<scenario>-<date>.sanitized.json`. It is the raw report with a `sanitization` header: raw SHA-256, source commit, a credential statement and counts of what was removed. It replaces email addresses, phone numbers and any named person quoted from cited pages, found by a regex pass plus a manual read. Public page text is otherwise verbatim, so the replies can be replayed. Before committing, grep it for `sk-ant`, `api_key`, `x-api-key`, `bearer`, `@` addresses and phone patterns.
2. **The report:** `docs/live-eval/<scenario>-<date>.md`. It is a Document F §8 scoring sheet with evidence for every gate (§2.1), every dimension score (§4), each §6 serious-failure item and the §7 tests the run informs. It separates factual correctness, evidence grounding, honesty and resource use, and adds a defect investigation.
3. **The row in the table above.**
4. **For each confirmed defect, a deterministic regression test** that replays the run's own replies. The fixture is extracted from the sanitized copy into `app/src/test/resources/fixtures/live/` (for example `LiveRunRegressionTest`, `LiveRunServiceReplayTest`).

## Next steps

These need the owner's authorization. None is scheduled.

1. **Fix N1** (balanced-parenthesis attribute parsing, plus cleaning displayed summaries) and N2 (per-call search allowance described as the investigation's limit), with replay tests from the 2026-10-24 rerun. See `R-N-a-2026-10-24-rerun.md` §6.
2. **Rerun R-N(a)** on the candidate build once those fixes land: one call, about $0.05.
3. **R-N(b).** The app asks which place locally, with no call. The harness needs a clarification-scenario mode before this run can be recorded.
4. Then the rest of the Stage 1 subset (R-A, R-D, R-J, …): two runs each on the candidate, as Document F §2.3 requires.

Both R-N(a) reports are historical records and are not edited after a fix. The 2026-10-23 report covers the prompt-v2 run and the fixes that followed it. The 2026-10-24 report covers the prompt-v4 rerun. Engine changes are recorded in `docs/PRODUCTION_KICKOFF_AUDIT_AND_PLAN.md`.
