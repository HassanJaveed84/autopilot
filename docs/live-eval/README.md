# Live research evaluation (development only)

> **Retired 2026-10-27.** Built-in research was removed from the app (`docs/RETIRE_EMBEDDED_AI_REPORT.md`), and the harness and its reply fixtures with it. The runs below are kept as history; the commands no longer work on branches that include the retirement.

These are paid, end-to-end runs of the production Research Online engine against the real Anthropic API. Each run is scored against **Document F v1.0 (frozen)**: [`docs/product-baseline/Document_F_Evaluation_Framework_v1.0_FROZEN.md`](../product-baseline/Document_F_Evaluation_Framework_v1.0_FROZEN.md). That file is the only standard used here. It changes only through Document E's change control, never to fit the implementation.

The harness is a JUnit test in `app/src/test/java/com/lifeadmin/autopilot/liveeval/`. Gradle excludes it from every test run unless `-PliveEval=true` is passed, so it is never part of the app, the APKs or CI.

## Runs (chronological)

| Scenario | Date | Source commit | Model | Level | Searches | Cost (from measured usage / estimate shown) | Essential dimensions (OF · EG · SH) | Verdict | Report |
|---|---|---|---|---|---|---|---|---|---|
| R-N(a), Stage 1 run 1/2 | 2026-10-23 (00:34Z 10-24) | `6d3a70b` | `claude-sonnet-5-5` | Standard (3 searches, 300¢) | 2 of 3, in 2 billed calls | $0.09611 / $0.13–0.24 | 2 · 1 · 1 | **Fail**: Leakage gate; Efficiency 0; serious failures (misleading completeness, misattributed quotation) | [R-N-a-2026-10-23.md](R-N-a-2026-10-23.md) · [sanitized JSON](R-N-a-2026-10-23.sanitized.json) |
| R-N(a) rerun on the fixed build (Stage 3 targeted follow-up) | 2026-10-24 (03:39Z) | `9cc17fe` (engine = `41dc471`), prompt v4 | `claude-sonnet-5-5` | Standard (3 searches, 300¢) | 1 of 3, in 1 billed call; FILL_GAPS skipped on sufficiency | $0.051416 / $0.13–0.24 | 2 · 2 · 2 | **Fail**: Leakage gate and Presentation 0 (unparsed attribute block on evidence rows, N1); no serious failure | [R-N-a-2026-10-24-rerun.md](R-N-a-2026-10-24-rerun.md) · [sanitized JSON](R-N-a-2026-10-24-rerun.sanitized.json) |
| R-N(a) third run, after the N1/N2/source-date fixes | 2026-10-24 (05:07Z) | `afb9cad`, prompt v5 | `claude-sonnet-5-5` | Standard (3 searches, 300¢) | 1 of 3, in 1 billed call; FILL_GAPS skipped on sufficiency | $0.053328 / $0.13–0.24 | 2 · 1 · 2 | **Fail**: evidence grounding 1 from an unrequested Worth-knowing side statement (c2) that goes beyond its cited text (N3/N4); all gates pass; no serious failure | [R-N-a-2026-10-24-run3.md](R-N-a-2026-10-24-run3.md) · [sanitized JSON](R-N-a-2026-10-24-run3.sanitized.json) |

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
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true --tests 'com.lifeadmin.autopilot.liveeval.LiveInvestigationEval'                              # R-N(a), paid
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalScenario=R-A --tests 'com.lifeadmin.autopilot.liveeval.LiveInvestigationEval'     # R-A, paid
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalScenario=R-N-b --tests 'com.lifeadmin.autopilot.liveeval.LiveInvestigationEval'   # R-N(b), no call, no key
```

`-PliveEvalScenario` picks one scenario per invocation; without it only R-N(a) runs. R-A uses the Standard level's own budget (6 searches) and a 100-cent per-turn limit; for a consequential objective the authorization also includes the one wording-check call, which the estimate covers. R-N(b) records the clarification flow before any authorization: it uses no key, its provider refuses every call, and the report shows zero provider calls and zero ledger rows.

The output goes to a new directory for every run, `app/build/live-eval/<scenario>/run-<UTC time>/result.json`, with its own database. The harness refuses before any call if that directory already exists, so no earlier report is overwritten. The first R-N(a) run predates this layout and stays at `app/build/live-eval/R-N-a/result.json`. Reports stay untracked. It holds every request and reply, every search with its results, the ledger rows, the run events and the stored turn. The prompt text is synthetic and public. The system prompt is recorded by version and SHA-256.

## After a run: what gets committed

1. **The sanitized copy:** `docs/live-eval/<scenario>-<date>.sanitized.json`. It is the raw report with a `sanitization` header: raw SHA-256, source commit, a credential statement and counts of what was removed. It replaces email addresses, phone numbers and any named person quoted from cited pages, found by a regex pass plus a manual read. Public page text is otherwise verbatim, so the replies can be replayed. Before committing, grep it for `sk-ant`, `api_key`, `x-api-key`, `bearer`, `@` addresses and phone patterns.
2. **The report:** `docs/live-eval/<scenario>-<date>.md`. It is a Document F §8 scoring sheet with evidence for every gate (§2.1), every dimension score (§4), each §6 serious-failure item and the §7 tests the run informs. It separates factual correctness, evidence grounding, honesty and resource use, and adds a defect investigation.
3. **The row in the table above.**
4. **For each confirmed defect, a deterministic regression test** that replays the run's own replies. The fixture is extracted from the sanitized copy into `app/src/test/resources/fixtures/live/` (for example `LiveRunRegressionTest`, `LiveRunServiceReplayTest`).

## Next steps

These need the owner's authorization unless stated otherwise.

1. **N3 and N4 are fixed** (objective relevance; factual grounding, with a model-assisted wording check for consequential answers). See `docs/PRODUCTION_KICKOFF_AUDIT_AND_PLAN.md`, "M4 answer-grounding pass". One conditional R-N(a) rerun and, only if it passes, one exploratory R-A run are approved for this pass.
2. **R-N(b)** can now be recorded with no call (`-PliveEvalScenario=R-N-b`).
3. Then the rest of the Stage 1 subset (R-D, R-J, …): two runs each on the candidate, as Document F §2.3 requires.

All R-N(a) reports are historical records and are not edited after a fix:
- the 2026-10-23 report covers the prompt-v2 run and the fixes that followed it;
- the 2026-10-24 rerun report covers the prompt-v4 run (N1, N2);
- the 2026-10-24 run-3 report covers the prompt-v5 run (N3, N4).

Engine changes are recorded in `docs/PRODUCTION_KICKOFF_AUDIT_AND_PLAN.md`.
