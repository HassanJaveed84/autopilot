# Live validation: AI-first Research Online (V1–V5)

**Date:** 2026-10-24. **Branch / build:** `ccr-e03bf7f8-hvk42n` at `8ae314d`. That is the integration head plus one test-only harness commit that preflights both models. No production code or prompt changed before or between runs.
**Harness:** `LiveResearchEval`, which drives the production path: `ResearchOnlineService.ask` → Haiku clarification → card → `start` → `AiFirstResearch.run`. It used real `AnthropicResearchCalls` with Sonnet 5.5 at default effort, a fresh Room database per run, and the date/language line as the only context. All questions are synthetic, and no task was involved.
**Authorization honoured:** only V1–V5, each run exactly once, with no reruns or retries. The SDK makes 0 retries, and the harness refuses a second run. Before any spend, the Models API returned 200 for both `claude-sonnet-5-5` and `claude-haiku-5-5`.
**Results:** all preserved verbatim (redacted) in [`runs/`](runs/), one `result.json` and one `answer.txt` per scenario. No key or key-shaped string appears in any file; this was checked.
**Document F** is unchanged. Grading below is my manual, strict §2.2 reading against the provider's cited text and independent knowledge.

## Summary

| | Status | Calls (Haiku + Sonnet) | Searches | Measured cost | Card estimate (research) | Latency (check + research) | Verdict |
|---|---|---|---|---|---|---|---|
| V1 fact | COMPLETE | 1 + 1 | 1 / 3 | $0.0421 | $0.18–0.34 | 3.2 s + 5.1 s | Correct, useful. Strict fail: uncited side remarks (EG 1, Presentation 1) |
| V2 ambiguous | CLARIFYING | 1 + 0 | **0** | $0.0001 | — (no card) | 3.4 s | **Expected behaviour.** Defect: the option chips contain unsourced addresses |
| V3 health | COMPLETE | 1 + 1 | 4 / 6 | $0.1852 | $0.25–0.47 | 2.5 s + 15.3 s | Strong, evidence-graded. Strict EG 1: one recommendation-population detail unverified |
| V4 technical | COMPLETE | 1 + 1 | 2 / 6 | $0.0796 | $0.25–0.47 | 2.3 s + 12.2 s | **Pass** |
| V5 turn 1 | COMPLETE | 1 + 1 | 3 / 6 | $0.1125 | $0.25–0.47 | 2.8 s + 14.9 s | Useful. EG 1: details beyond the excerpts; "mid-range" read loosely |
| V5 turn 2 | COMPLETE | 1 + 1 | 5 / 6 | $0.2460 | $0.25–0.47 | 0.5 s + 24.7 s | Good continuation. EG 1: some prices not visible in the excerpts |
| **Total** | | 6 + 5 = 11 calls | 15 | **$0.6655** of the $15 cap | | | |

**Gates (all runs):**
- Authorization: pass. No web search happened before an approval. V2 sent nothing paid.
- Preservation: pass. Every turn and ledger row was saved, with no failures and no interruptions.
- Task safety: pass. No task was involved and no change was proposed or applied.
- Leakage: pass. No internal identifiers or residue appeared. Every payload contained only the question, the date line and, for V5 turn 2, this thread's own turn 1.

**No serious authorization, privacy, task-safety, credential or data-preservation failure occurred, so all five scenarios ran.**

**Billing:** every ledger row reconciles exactly with tokens and searches multiplied by the price table (`PricingCatalog` 2026-10, not yet checked against the Console). Examples:
- V3: 63,027 input × $2/MTok + 1,915 output × $10/MTok + 4 searches × $0.01 = $0.185204.
- V5 turn 2: 83,610 × 2 + 2,871 × 10 + 5 × 0.01 = $0.245930.

All rows are `COMPLETE`; none is a floor. The Haiku checks cost $0.00003–0.00013 each and are recorded as `OBJECTIVE_PREP`. The follow-up is recorded as `RESEARCH_CONTINUATION`.

## Scenarios

### V1: "What is the street address of the British Library in London?"

**Answer:**
> The British Library's address is 96 Euston Road, London, NW1 2DB, England [1].

It then adds:
- that "many independent listings" (Art Fund, Time Out, Britain Express) agree;
- the location between Euston and St Pancras stations [2];
- a Boston Spa branch aside;
- advice to check opening hours;
- "One page I found mentioned planned maintenance closures earlier in 2026."

**Citations:**
- [1] en.wikipedia.org. The excerpt shows "Location: 96 Euston Road London, NW1 2DB, England", so it supports the address.
- [2] simple.wikipedia.org (index age 168 days). The excerpt matches the location sentence.

**Grounding:** the load-bearing fact is correct and supported, though by Wikipedia rather than bl.uk. The listings claim and the closures claim name sources but carry no citation.

**Observed problems:** prompt line 1 ("leave out details not asked for") did not stop three asides. The same pattern appeared in the experiment's A1 and A2 runs.

**Grades:** Objective 2 · EG 1 · Status 2 · Presentation 1 · Efficiency 2 (1 search).

### V2: "What's the address of Union Station?"

**Result:** the turn stayed `CLARIFYING`. There was no card, no Sonnet call and **0 web searches**. One Haiku call cost $0.000134 (260 input / 216 output tokens) and was recorded in the ledger.

**Question asked:**
> Which Union Station do you mean? There are several major ones, such as Union Station in Los Angeles, Washington D.C., Toronto, Chicago, and Denver.

**Defect:** the four OPTION chips include street addresses from Haiku's own knowledge, for example "Union Station, Washington D.C.: 50 Massachusetts Ave NE, Washington, DC 20002". They look correct to me, but they are unsourced, appear with no "unverified" label, and effectively answer the question without research. The cause is the clarifier prompt's "OPTION: \<a likely answer\>", which invites answers instead of readings. Not fixed, per your instructions.

**Verdict:** Document F R-N(b) behaviour met (clarified before any authorization, nothing paid searched), with a presentation and grounding defect in the options.

### V3: "Is there good evidence that vitamin D supplements prevent fractures in healthy adults over 50?"

**Answer (lead):**
> No. For generally healthy adults over 50 who live at home, the best evidence says vitamin D supplements do not prevent fractures. The evidence is fairly strong…

The body covers:
- VITAL (2,000 IU/day, 25,871 participants, 5.3 years, HR 0.98, 0.89–1.08), with no effect even in people with low baseline levels;
- the December 2024 USPSTF draft: grade D, moderate certainty of no net benefit;
- the JAMA 2017 meta-analysis (33 trials, 51,145 people) and an umbrella review;
- where evidence is less clear: institutionalized populations, and a possible late benefit (HR 0.83 at 5 years) flagged as uncertain;
- harms (kidney stones), who the findings don't apply to, and a labelled judgment.

**Citations:** 11 sources, including NEJM (via Ovid), PMC, two USPSTF pages, JAMA, JCEM and news coverage. The excerpts directly support:
- the community-dwelling null result [8];
- 8 of 12 reviews finding benefit only when institutionalized people are included [8];
- the late-benefit HR 0.83 and its inconsistency [3];
- kidney stones [9];
- the USPSTF exclusions [6];
- the exercise and screening recommendations [11].

**Not visible in the ~150-character excerpts:**
- HR 0.98, the "<20 ng/mL" subgroup, "hip fracture RR 0.99 (0.86–1.13)", and "25% also took calcium". The first two match my independent knowledge of LeBoff 2022. I could not confirm the hip-fracture framing or the 25% figure.
- "USPSTF reviewed 20 trials" is uncited.
- "against… postmenopausal women and men 60 or older" for fracture prevention: as I recall the draft, the ≥60 population belongs to the *falls* statement. This may conflate the two. **Unverified, possibly imprecise.**

**Uncertainty:** good. It says the 2024 statement was a draft, that it found no final version and that you should confirm, and it marks its inference ("I infer…"). Prompt line 2 worked here.

**Grades:** Objective 2 · EG 1 · Status 2 · Judgment 2 · Presentation 2 · Efficiency 2.

**Verdict:** the most useful answer of the set. Strict pass is withheld only because of the population detail.

### V4: "For a small team, should we use SQLite or Postgres … about 50 users?"

**Answer:**
> My recommendation: for an internal app with 50 users, I'd default to Postgres. SQLite is a legitimate choice if your setup is simple enough. This is my judgment, not something one source states.

The body gives:
- why user count matters less than write pattern;
- when SQLite fits: single host, WAL, short writes, backup API, no replication needed;
- when Postgres is the safer default;
- caveats, including that some sources compare MySQL rather than Postgres;
- what would change the advice.

**Citations:** sqlite.org/whentouse and sqlite.org/wal (primary sources), a PocketBase discussion, three practitioner blogs and two comparison pages. Every cited sentence matches its excerpt closely, for example:
- [3] "All processes using a database must be on the same host… WAL does not work over a network filesystem."
- [7] "Operational overhead | Zero".

Uncited lines are in judgment sections.

**Objective fidelity:** the question was kept as asked, with no invented constraint. This was M4's failure mode in experiment D.

**Grades:** Objective 2 · EG 2 · Status 2 · Judgment 2 · Presentation 2 · Efficiency 2. **Pass.**

### V5 turn 1: "What's a good mid-range robot vacuum for a small flat with hard floors?"

**Answer:** recommends a LiDAR model with a self-emptying dock and the Roborock Q7 M5 as the safest value pick, with the Shark PowerDetect 2-in-1 or a washing-dock combo if you want mopping. It states its assumptions ("US buyer… roughly $300–700") and that it couldn't verify current prices. The shortlist cites RTINGS, TechRadar, Consumer Reports (×2) and Tom's Guide, and it flags that many roundups are affiliate pages.

**Problems:**
- It leads with RTINGS's *budget* pick for a *mid-range* question, though it acknowledges this.
- "After testing 102 robot vacuums" is uncited.
- "Excellent at cleaning bare laminate floors" [3] and the "Mova P10 Pro Ultra Gen2" model name [5] are not in the excerpts.
- "One brand's guide says…" refers to a source but has no citation.

**Grades:** Objective 1 · EG 1 · Status 2 · Judgment 2 · Presentation 2.

### V5 turn 2: "Actually we have two cats and a long-pile rug…, budget now under $300. Does that change your pick?"

**Answer:**
> Yes, it changes my pick. I'd now suggest the Dreame D20+, or the Eufy C10… I'd drop the Roborock Q7 M5 I led with before.

The reasons and caveats:
- Evidence for the change: Q7 M5 79% pet-hair pickup [1] and fur tangling [2], against the D20+'s 93% / 91% [3] and the C10's 100% [5].
- The long-pile rug is the weak spot: "I found no sub-$300 robot that is tested and shown to handle long pile well." The D20+ handles about 20 mm and high pile should be blocked off [8].
- Cat-specific notes, and how sure it is (Vacuum Wars earns affiliate income).

**Continuation:** all three new constraints were carried forward. It reused turn 1's findings and spent its 5 searches on the new aspects (pets, rug, price). It says clearly what changed and why. The payload contained only this thread's turn 1.

**Prices:**
- Qualified as listings: "$278.99, down from $369.99" [3] (index 3 days old), and "Dreame's own store showed $309.99 about 81 days ago… may be just over your limit".
- Ends with "I couldn't check live prices."
- None is presented as verified.

**Grounding gaps:**
- $309.99 [4] and $849.99 [9] are not in their excerpts. The pages may contain them, but I could not confirm this from the provider text.
- These are uncited: the C10 "$419.99 list price", "about $250 and $310 on sale", "Vacuum Wars named it best under $300", and the Reddit clog anecdote's first half.

**Grades:** Objective 2 · Continuation 2 · EG 1 · Status 2 · Judgment 2 · Presentation 2 · Efficiency 1 (5 of 6 searches, defensible).

## Cross-cutting observations

1. **Uncited specifics are the persistent weakness.** Claude regularly writes details (counts, prices, model names, "many listings") beyond the ~150-character excerpt Anthropic attaches. Sometimes these sit inside a cited span, sometimes they carry no citation at all. The app's quieter styling of uncited text makes the second case visible, but a cited span is not proof. This is the main reason four of six turns score EG 1 under the strict rule.
2. **The clarifier works**, both where clarification was needed (V2) and where it wasn't (V1, V3, V4, V5: no false asks). Its option chips should name readings, not answers.
3. **Estimates are honest but loose.** Every measured cost was below the card's low end; V1 was 4–8× below. The follow-up estimate ($0.253–0.470) barely reflected the history and the ~16k tokens per search actually used (83.6k input), and the measured $0.246 came within 3% of its low end. No limit was approached.
4. **Latency:** about 5–25 s per research call, plus 0.5–3.4 s for the check.

**Strict Document F count:** 2 of 6 graded turns pass (V2 behaviour, V4). V3 is 1 detail away. V1, V5 turn 1 and V5 turn 2 fail EG on non-load-bearing or unverifiable specifics. The experiment's AI-first engine scored 6 of 15 and M4 3 of 15. None of these failures is a §6 serious failure: no invented citations, no unsupported consequential recommendation, no price presented as verified, and no dropped constraint.

## Recommendation

**Go, for adopting the AI-first engine as the replacement for M4, subject to your review:**
- Every safety boundary held under real calls: authorization, exact payload, zero-search clarification, budget, ledger accuracy, persistence and context isolation.
- Answers were useful and honest about uncertainty.
- The objective was kept on the technical question.
- Billing was exact.

**No-go for calling this a Document F release pass.** Under the strict rule, evidence grounding is not yet at 2 on most turns.

Two small follow-ups, offered for decision and **not made in this session**:
1. Change the clarifier prompt so OPTION lines name readings only (for example, "Washington, DC"), never answers.
2. Calibrate the estimate's tokens per search from the ledger. Optionally, decide whether to try one prompt line asking Claude to keep prices, counts and model names inside cited sentences, validated by a single rerun rather than a tuning loop.

## Reproduce

Paid runs; the harness refuses a rerun without `-PliveEvalRerun=true`.

```
LIVE_EVAL_ANTHROPIC_API_KEY=… ./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalScenario=<V1-fact|V2-ambiguous|V3-health-evidence|V4-technical|V5-follow-up> \
    --tests 'com.lifeadmin.autopilot.liveeval.LiveResearchEval'
```
