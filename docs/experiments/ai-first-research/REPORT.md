# AI-first research engine: A/B experiment against M4

**Status:** experiment only. Nothing here is wired into the app. The frozen M4 engine (`342db13`, PR #8) is unchanged, and so are Document F and every other frozen document.
**Branch:** `ccr-205def5b-4gv9qp`, the session's designated branch, based on `342db13`. The suggested name `experiment/ai-first-research` was not used because this session may only push to its assigned branch.
**Date:** 2026-10-24. **Model:** `claude-sonnet-5-5` for both engines, with provider-default effort. **Spend:** $4.43 measured over 33 billed turns. The ceiling was $25.

## 1. Bottom line

The simple engine produced better research answers in this sample. It did so with about 1/12 of the research-core code, about 2.7× lower latency and about 20% lower cost per turn. M4 did three things better:

- it asked a clarifying question about an ambiguous place before anything was paid;
- it gave tighter answers to simple fact questions;
- it found the official British Library page.

M4's failures were structural and repeated on every run:

- its regex objective preparer misread a technical question;
- its lexical grounding pass rewrote answers into unreadable label residue;
- status headlines contradicted the evidence it had cited.

The AI-first engine's failures came from the model itself, and they were minor. It added unrequested side details, and it made a few uncited side remarks.

**Recommendation:** replace M4's research core. Keep the infrastructure it sits on and add three small safeguards (§7). Do not tune M4 further.

Neither engine passes Document F cleanly in this sample. Under the strict rule, M4 passed 3 of 15 graded turns and the AI-first engine passed 6 of 15 (§5).

## 2. The two architectures

**M4 (frozen).** `InvestigationService` runs the following steps:

1. A local regex `ObjectivePreparer` sets the ask kind, constraints and clarification.
2. An authorization card.
3. Staged calls: GATHER, then FILL_GAPS, and on Deep also CROSS_CHECK and SYNTHESIZE. Each stage has its own share of the searches.
4. A custom output grammar, an 18.8 kB system prompt with `## CLAIM cN · type=… · load=…` blocks.
5. `InvestigationParser`, then `ClaimAssessor` (independence, freshness and scope), then `ResultDeriver` (state from claim statuses).
6. `AnswerGrounding` and `EvidenceText`, which do sentence-level lexical grounding against cited text.
7. `WordingCheck`, a model-assisted wording check on consequential questions.
8. `AnswerComposer`, which composes the shown answer.

**AI-first (this branch, `app/src/main/java/com/lifeadmin/autopilot/ai/aifirst/`).** The engine does the following:

- One Messages call with server-side `web_search`, `max_uses` set to the budget and a 2.6 kB plain-language system prompt. A `pause_turn` is continued only within the remaining budget.
- Claude decides what to search, when to stop and how to state uncertainty.
- The app enforces boundaries:
  - the frozen authorization is the only payload, plus earlier turns of the same thread;
  - a spending-limit check runs before every call;
  - every call gets a ledger row priced from measured usage;
  - the turn is saved before the first byte leaves and after every call;
  - a stop or failure keeps whatever already ran.
- Citations are the provider's `web_search_result_location` blocks, numbered in order. Any link the model wrote itself that no search supplied is listed as unverified.
- Follow-ups send the earlier questions, answers and source lists as text.
- There is no claim grammar, parser, status derivation or regex fact checking.

**Reused unchanged:**

- `AnthropicResearchCalls` (the SDK adapter, timeouts, error mapping and `pause_turn` handling);
- `ModelCall`/`CallResult`;
- `Research2Config` limits;
- `CostCalculator`/`PricingCatalog`;
- `UsageRecord`/`UsageLedger`/`RoomUsageLedger`;
- `ResearchError`;
- `ApiKeyStore`;
- the live-eval key handling and redaction.

## 3. Code size and complexity

| | M4 research core (`ai/investigation`) | AI-first (`ai/aifirst`) |
|---|---|---|
| Production files | 16 | 2 |
| Lines (all) / non-blank non-comment | 4,323 / 3,290 | 344 / 282 |
| `Regex(` uses | 120 | 1 (link detection) |
| System prompt | 18.8 kB grammar + 6.3 kB wording-check file | 2.6 kB plain language |
| Model calls per turn (measured mean) | 1.88 (1–3; wording check on health) | 1.00 |
| Unit tests for the core | 24 files, 3,261 lines, 167 tests | 1 file, 7 tests |
| External dependencies | Anthropic Java SDK (via the shared adapter) | the same, nothing new |
| Also coupled to it | `context/ContextAllowList` (248), the thread UI (`ui/investigation`, 1,212 lines, which renders claims and statuses), `InvestigationRepository` | needs a much simpler UI (not built) |

**Known failure modes, M4 (observed live).** Each one recurred:

- Objective misclassification by regex. A two-option architecture question became a "fact or figure" with a fabricated "from Postgres" constraint.
- False "not shown" verdicts. The provider caps each `cited_text` at about 150 characters (all 216 citations in this experiment were ≤153), so lexical checks compare a sentence with a fragment.
- Status labels leaking into prose ("Only indicated, not established:", "(the cited sources do not show: …)", claim ids `c1`–`c5`).
- Truncated unresolved lines.
- Headline states contradicting the cited evidence.

**Known failure modes, AI-first:**

- Unrequested side details on simple facts.
- Occasional uncited side remarks.
- No clarification before authorization.
- No machine-readable result state, which Document F's RES-060 assumes.

## 4. Live results

Each scenario ran twice per engine, with nothing changed between runs. Budgets and spending limits were identical for both engines: 3 searches and 300¢ for A and R-N(b), 6 searches and 500¢ otherwise. Every run used Sonnet 5.5. The sanitized per-run summaries are in [`runs/`](runs/); raw requests and replies stay untracked.

| Scenario | Engine | Calls · searches (run 1 / run 2) | Cost | Latency | What the person saw |
|---|---|---|---|---|---|
| A1 Union Station DC | M4 | 1·1 / 1·1 | $0.052 / $0.040 | 8 s | Run 1: correct and clean. Run 2: correct, but contains "(the cited sources do not show: 4)". |
| | AI-first | 1·1 / 1·1 | $0.045 / $0.044 | 6 s | Correct, with operator and Amtrak cited. It adds unrequested pickup and Columbus Circle details. Run 1 names cross-streets the cited text lacks; both runs have an uncited "bus site says 20001". |
| A2 British Library (unseen) | M4 | 1·1 / 1·1 | $0.039 / $0.034 | 8 s | Correct, cites `bl.uk`. Run 1 says "(the cited sources do not show: 2026)". Run 2 is clean. |
| | AI-first | 1·1 / 1·1 | $0.042 / $0.042 | 4 s | Correct. It cites Wikipedia and aggregators, says plainly that it did not use the library's own site, and adds a Boston Spa aside. |
| A3 Rijksmuseum hours (unseen, changing) | M4 | 2·2 / 2·2 | $0.107 / $0.096 | 25 s | The hours are correct, but the text is garbled ("held but undated", "Only indicated, not established:", duplicated unresolved bullets, 9 markers on one paragraph). The run 2 headline says "Not established: no source supported what this rests on", although the museum FAQ it cites supports the hours. |
| | AI-first | 1·1 / 1·1 | $0.051 / $0.050 | 6 s | Correct (9:00–17:00 daily) from the museum's own page. Last-entry disagreement flagged; currency caveat; shop, garden and restaurant extras. Run 1 has an uncited "Friday late openings" remark labelled as unofficial. |
| B Supplements, men ~40 | M4 | 3·4 / 3·4 | $0.231 / $0.220 | 62 s | The content is reasonable (creatine; USPSTF; VITAL). The prose is buried in residue. Wrong flags include "(the cited sources do not show: US Preventive Services Task Force)" on a sentence cited to the USPSTF page. The run 2 headline is "Not established". |
| | AI-first | 1·4 / 1·5 | $0.139 / $0.229 | 19 s | Clear, evidence-graded answer: creatine for training (ISSN position stand); USPSTF D grade for beta-carotene and vitamin E with harms; "insufficient" for multivitamins, explained as not "proven useless"; VITAL null results; COSMOS cognition results limited to men aged 60 or older and women aged 65 or older. It flags the extrapolation to age 40 and says what it did not search. |
| C Thorne 2/Day price | M4 | 2·4 / 2·4 | $0.184 / $0.197 | 46 s | The substance is honest (Amazon $30.60 cached and undated; $36 elsewhere). Residue and a claim-id leak ("the seller check in c5"); unresolved lines cut mid-sentence. "Partly answered". |
| | AI-first | 1·4 / 1·3 | $0.197 / $0.146 | 17 s | $36 at iHerb, Vitacost, Walmart and Swanson; subscription conditions quoted; NSF-for-Sport variant price warned about; Amazon page flagged as 469 days old. Run 1 says "listings checked today" (it saw search-index content) and leans on a 6-year-old Walmart result without flagging its age. |
| D Snowflake vs Postgres | M4 | 2·4 / 2·4 | $0.184 / $0.184 | 42 s | **Wrong objective, on both runs.** The objective preparer set ask = fact and constraint = "from Postgres". The answers read "Given your constraint to serve from Postgres…" and "Taking your constraint to mean…". Headlines "Not established" and "Partly answered". |
| | AI-first | 1·4 / 1·3 | $0.199 / $0.106 | 18 s | Conditional recommendation labelled as judgment. Premises are cited: per-second billing with a 60 s minimum, cache loss on suspend, a concurrency default of 8, and Snowflake interactive warehouses GA 2025-12 from the release notes. Also a decision table, assumptions and flip conditions, and vendor bias called out. |
| E1 Thorne vs Pure (compare) | M4 | 2·4 / 2·4 | $0.220 / $0.217 | 55 s | Headline "Not established: no source supported what this rests on" on a well-cited comparison. Residue splits "O.N.E." mid-name; claim ids leak. |
| | AI-first | 1·5 / 1·5 | $0.227 / $0.241 | 27 s | Even-handed table with "fits if" guidance and no overall winner. A few cells are uncited. The vitamin K row comes from the with-iron variant in run 1 (disclosed); run 2 says US labels show none. The runs disagree on this detail. |
| E2 "…someone who doesn't want iron?" | M4 | 2·4 / 2·3 | $0.236 / $0.184 | 55 s | It re-checked every earlier claim ("Check fresh, never reuse"). The pick is Thorne (NSF tiebreak), with flip conditions; residue throughout. |
| | AI-first | 1·1 / 1·2 | $0.064 / $0.095 | 13 s | It reused earlier findings and confirmed that both standard formulas are iron-free. It warned about the "with iron" variant and picked Pure, with flip conditions (NSF, vitamin K, higher B doses). |
| R-N(b) "address of Union Station?" | M4 | 0·0 | $0 | – | **Correct:** it asked which city before any authorization. |
| | AI-first | 1·3 | $0.094 | 11 s | It recognized the ambiguity but answered for DC, LA and Chicago with 3 paid searches. Document F lists this as unacceptable. |

**Per-turn means (16 comparable turns each; R-N(b) excluded).** Totals for these turns: M4 $2.42, AI-first $1.92.

| | Calls | Searches | Cost | Latency | Output tokens |
|---|---|---|---|---|---|
| M4 | 1.88 | 2.94 | $0.151 | 37.6 s | 4,966 |
| AI-first | 1.00 | 2.62 | $0.120 | 13.8 s | 1,515 |

M4 never used more than 4 of 6 searches, because its stage split is 60% / 30%. The AI-first engine used 3–5 of 6 by its own choice.

There were no failures, retries or incomplete outputs on either engine. M4 had one `CHECK_WORDING` call `TRUNCATED` (B run 2).

## 5. Document F assessment (manual, strict §2.2)

| Turn | M4 | AI-first |
|---|---|---|
| A1 ×2 | 1 pass (run 1); run 2 fails Leakage | 0: EG 1 both runs (uncited and over-specific side remarks) |
| A2 ×2 | 1 pass (run 2); run 1 fails Leakage | 2 passes (Presentation 1: aside) |
| A3 ×2 | 0: Leakage both; SH 0 in run 2 | 0: EG 1 (uncited side remarks); otherwise strong |
| B ×2 | 0: Leakage, Presentation 0, false grounding flags; SH 0 in run 2 | 2 passes |
| C ×2 | 0: Leakage (claim id, labels) | 0: EG 1 (stale Walmart listing not flagged; "checked today") |
| D ×2 | 0: serious failure (different objective), SH 0 | 2 passes (Judgment 2) |
| E ×2 (turns 1+2) | 0: SH 0 in turn 1, Leakage, over-re-verification | 0: EG 1 (uncited table cells); continuation 2 |
| R-N(b) | pass | fail (paid answers for all readings) |
| **Total** | **3 / 15** | **6 / 15** |

Evidence-grounding checks were done against the provider's cited text, which is 150 characters at most. For load-bearing facts the cited fragment did not show, I checked independent knowledge: the USPSTF 2022 grades, the VITAL and COSMOS populations, Snowflake's 60-second minimum, `MAX_CONCURRENCY_LEVEL` = 8, and the addresses. I found no load-bearing factual error in either engine. No citation in either engine pointed to anything the provider did not supply, and the AI-first engine wrote no unsupplied links. A formatted citation is not proof, and both engines' prose sometimes goes beyond the 150-character cited fragment.

## 6. Answers to the eight questions

1. **Better answers: AI-first.** It was more relevant, readable and decision-useful on B, C, D and E, and roughly equal on A. M4's underlying model output was often good. Its own post-processing made it worse.
2. **More trustworthy in practice: AI-first.** It is not perfect. Its weakness is visible, because uncited sentences carry no marker. M4's machinery makes confident but wrong meta-statements: "not established" on supported answers, "the sources do not show X" where they do, and a fabricated user constraint. Those misstatements look like verification, and that makes them more misleading than a plain uncited sentence.
3. **M4 demonstrably better at:**
   - asking about material ambiguity before authorization, free (R-N(b));
   - restraint on simple facts (A1 run 1, A2 run 2);
   - finding the issuer's own page for A2;
   - listing what it could not establish, explicitly.
4. **AI-first better at:**
   - objective fidelity on the technical question;
   - honest, proportionate status in prose;
   - evidence-quality and magnitude reasoning on health;
   - variant and subscription details on price;
   - continuation (it reused earlier findings and spent 1–2 searches instead of 3–4);
   - 2.7× lower latency and about 20% lower cost;
   - no leakage.
5. **Deterministic safeguards that are genuinely necessary:**
   - Authorization before any paid call, and a frozen payload: only the authorized context plus this thread.
   - Search budget as `max_uses`, and a spending limit checked before every call, including continuations.
   - A ledger row for every call, from measured usage, with unknown or failed usage marked as a floor.
   - Citations only from provider metadata. Flag model-written links.
   - Persistence before sending and after each call; preservation on stop or failure.
   - Never touching tasks without review.
   - Key handling.
   - **One new safeguard:** an ambiguity check before authorization (§7).
6. **Complexity that can be eliminated:**
   - the claim grammar and `InvestigationParser`;
   - `ClaimAssessor` (independence, freshness and scope heuristics);
   - `ResultDeriver`;
   - `AnswerGrounding`, `EvidenceText` and `StatusQualifier` sentence rewriting;
   - the regex `ObjectivePreparer`;
   - the multi-stage split with per-stage search shares;
   - `TurnAssembler` and inheritance planning;
   - most of `AnswerComposer`.

   `WordingCheck` showed no demonstrated benefit here. Its residue was the most damaging defect (B).
7. **The simplest maintainable production architecture** is §7.
8. **Replace M4's research core.** Do not simplify it in place: its failures come from the deterministic interpretation layers themselves, not from how they are tuned.

## 7. Proposed production shape (for approval; not built)

**Keep:**

- the `AnthropicResearchCalls` adapter;
- `UsageLedger`/`RoomUsageLedger`, `CostCalculator`/`PricingCatalog`;
- `ResearchError`, `ApiKeyStore`;
- the authorization card concept;
- `InvestigationRepository` storage, with turns serialized as `AfTurn`, under a migration that keeps old turns readable as legacy;
- the proposal review path, which a later "suggest a task change" step can feed.

**Simplify:**

- The thread UI. It renders answer text with `[n]` chips, a source list with the provider's quotes and page ages, the stop note and the cost. It renders uncited sentences in a quieter style, which the provider's citation spans make possible without any regex.
- The context allow-list, reduced to the card's switches.

**Remove:**

- the M4 interpretation layer (§6.6) and its 167 unit tests;
- the claim and status data model;
- the M4 prompt contract.

**Add, small and evidence-driven:**

1. **A clarification check before authorization.** One no-search Haiku call under general consent that returns either "clear" or one question. It replaces the regex preparer, which handled R-N(b) but broke D.
2. **Two prompt lines** aimed at the observed AI-first defects:
   - leave out details that were not asked for;
   - every factual sentence should rest on a search result, or be marked as the model's own inference.

   These would be verified with one rerun of A1 and C, not open-ended tuning.
3. **Optionally**, `web_search_20260209` (dynamic filtering), which Sonnet 5.5 supports. It was deliberately not used here, so both engines ran the same tool.

No model-assisted critique is recommended: this sample gives no evidence it helps, and M4's wording check hurt.

## 8. Limitations

- **Small sample.** There are 8 scenarios with 2 runs each, on one model, and they were graded manually by one assessor (me) who also built the new engine. Grading was done against cited text and independent knowledge, and M4's best runs are credited.
- **Freshness can't be checked live.** Neither engine can fetch live pages; the web-search tool returns indexed content with a page age. "Current price" claims are therefore unverifiable in principle for both. No browser or fetch tool was added.
- **Multi-turn history is plain text.** The AI-first engine sends earlier answers as text, not the provider's search-result blocks, so earlier evidence is re-read through the model's own summary. E2 handled this well; longer threads are untested.
- **Harness rendering of M4.** M4 answers were flattened from the stored `TurnResult` (lead, caveats, sections, recommendation, cited sources) in the order the thread screen uses. The first A1 M4 run's text lists the whole source registry; the renderer was then fixed to show only cited sources, as the screen does.
- **Pricing table.** The repository's table prices Sonnet 5.5 cache reads at $0.10/MTok; current Anthropic documentation lists $0.20. Only M4 used caching, so M4's costs here are understated by about $0.03 in total. Costs are not reconciled with an invoice.
- **Not tested:** cancellation in a real app, process death, physical devices.

## 9. Verification run in this session

`./gradlew lintDebug testDebugUnitTest` passed on this branch: 708 tests in 99 classes, 0 failures. That is M4's 701 tests plus the 7 new boundary tests. Lint was clean. APK assembly and instrumentation were not run, because the experiment does not touch app code paths. CI has not run on this branch.

## 10. How to reproduce

```
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.lifeadmin.autopilot.ai.aifirst.*'      # 7 boundary tests, free
./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalAb=<scenario> [-PliveEvalEngine=m4|aifirst] [-PliveEvalRerun=true] \
    --tests 'com.lifeadmin.autopilot.liveeval.LiveAbEval'                                          # paid; key from LIVE_EVAL_ANTHROPIC_API_KEY
```

The harness refuses to send if the Models API preflight fails, if a run for that scenario and engine already exists (unless `-PliveEvalRerun=true`), if either engine's estimate is above the cap, or if the measured cost of all earlier A/B runs plus this run's cap would pass $25.
