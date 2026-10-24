# Live research evaluation (development only)

Paid, end-to-end runs of the production Research Online engine against the real Anthropic API, scored with Document F. The harness is a JUnit test in `app/src/test/java/com/lifeadmin/autopilot/liveeval/`. Gradle excludes it from every test run unless `-PliveEval=true` is passed, so it is never part of the app, the APKs or CI.

## What it exercises

The real `InvestigationService` performs each step in order:

1. Local objective preparation, which makes no call.
2. Authorization, built exactly as the card builds it.
3. The staged run, with ceilings, checkpoints and the ledger.
4. The real `AnthropicResearchCalls` adapter, sending web search with structured citations.
5. `InvestigationParser`, then `ClaimAssessor`, then `ResultDeriver`, then `AnswerComposer`.
6. A file-backed Room database and `RoomUsageLedger`.

Only the planning provider is a fake. It serves objective preparation by provider, which these runs do not use.

## Guards

- **Key handling.** The key is read from the environment variable `LIVE_EVAL_ANTHROPIC_API_KEY` by `EnvApiKeyStore`. It is deliberately not `ANTHROPIC_API_KEY`: cloud environments reserve that name for Claude Code's own authentication, and a value set under it did not reach the session's environment. It is never written, logged, printed or reported. Reports pass through a redaction step and are checked for the key before they are saved.
- **Free preflight.** Before anything paid, the Models API must confirm that the key can use the configured model.
- **One run per scenario.** The harness refuses if a report for the scenario already exists. Pass `-PliveEvalRerun=true` only with the owner's approval.
- **Spending.** The estimate must be under the cap: R-N(a) uses Standard, 3 searches and a 300-cent per-turn spending limit enforced by the service. There are no retries.

## Running

The key must be in the environment of the session that runs Gradle, and the session needs an Android SDK (`ANDROID_HOME` or `local.properties`) for `testDebugUnitTest` to configure. A cloud session does not pick up an environment variable added after the session started. `--no-daemon` makes sure the test JVM inherits the current environment.

```
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true --tests 'com.lifeadmin.autopilot.liveeval.LiveInvestigationEval'
```

Output: `app/build/live-eval/<scenario>/result.json`. This holds every request and reply, every search with its results, the ledger rows, the run events and the stored turn. The prompt text is synthetic and public. The system prompt is recorded by version and SHA-256. A reviewed, sanitized copy and the Document F scoring are committed under `docs/live-eval/`.

## Runs

| Scenario | Date | Report |
|---|---|---|
| R-N(a) | not yet run: the key was not in the first session's environment | — |
