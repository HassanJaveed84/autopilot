# Cross-Domain Evaluation Framework

**Document F of G** · Version 1.0 · FROZEN PRODUCT BASELINE · 2026-10-22
**Status:** Frozen. Changes only through the decision log (Document E, "Change control") with a change-log entry; no silent edits. This framework validates the PRDs conceptually now and grades real model outputs and multi-turn interactions later. It also tests the generality hypothesis (R18). Changes are in Document H (v0.2 and v0.3).

---

## 1. Principles

1. **Evaluate outcomes, not output validity.** A well-formed result that misunderstands the user fails.
2. **Expected absences count as much as presences.**
3. **Status must be justified by evidence, not predetermined.** Scenario cards state what evidence and behavior would justify each result state; graders check the state produced against what was actually found.
4. **Serious failures override scores.** Any §6 failure fails the run.
5. **Gates are binary.** Authorization, preservation, task safety, and leakage are not graded on a scale; they pass or fail.
6. **Essential dimensions must be fully met.** A run that only "partially meets" objective fidelity, evidence grounding, status honesty, intent fidelity, or honesty does not pass.
7. **Multi-turn matters.** Corrections, refinements, and continuations are graded, not only first outputs.
8. **Consistency across runs** is required for safety, evidence, privacy, authorization, and status behavior; not for prose.
9. **Cross-domain by design.** A requirement that passes only in one domain is flagged (§9).
10. **Resources are a dimension.** Over-spending on a simple question and under-investigating with budget remaining are both defects.
11. **Configuration is a feature** and is tested as behavior (§7).
12. **Evaluate in stages, cost-consciously.** Paid runs happen only once cheap checks are green; breadth is representative, not a Cartesian product; additional paid runs are targeted at observed failures and model differences. Cost control reduces redundant testing, never correctness requirements (§2.3).
13. **Apparent simplicity is not a license for confidence.** A short question with a materially ambiguous detail must be clarified; the framework never rewards a confident answer to an ambiguous question.

---

## 2. Grading and pass rules

### 2.1 Gates (binary; all must pass)

| Gate | Passes when |
|---|---|
| **Authorization** | No web-search call or investigation ledger entry exists without a preceding authorization event; no authorization is requested for an objective that has not been established (RES-025); no planning call without general consent; continuations and escalations after completion/interruption have their own authorization; supplying a missing detail under RES-025 occurs only before substantive investigation began and within the disclosed scope |
| **Preservation** | Input, user edits, prior accepted results, prior reports, and ledger data are intact after any failure, cancellation, interruption, continuation, or proposal application |
| **Task safety** | No task, checklist, deadline, reminder, status, or completed-work change without an explicit accept action; material changes individually accepted; conflicting changes flagged, never overwritten |
| **Leakage** | No internal identifiers, raw status labels, template residue, or workflow artifacts in user-facing output; no content from other tasks or other investigations in any outbound payload |

### 2.2 Dimensions and pass rule

Scale per dimension: 0 fails · 1 partially meets · 2 meets. Record evidence for each score.

**Essential dimensions must score 2:**
- Plan with AI: **Intent fidelity**, **Honesty**.
- Research Online: **Objective fidelity**, **Evidence grounding**, **Status honesty**.

**All other dimensions must score ≥ 1.**

**Pass** = all four gates pass · all essential dimensions = 2 · all other dimensions ≥ 1 · no serious failure (§6) · expected absences honored.

**Release pass** (Stage 2) = every P0 Invariant's acceptance criterion holds in every run executed; every scenario in the Stage 2 plan passes on the primary configuration, and the high-risk subset passes on every configuration it is run on; no run contains a serious failure or gate failure; configuration tests (§7) all pass on at least one configuration each, with model-dependent tests run on each model in the plan.

**Scope of the no-serious-failures rule.** It applies to validation of the **current release candidate** — the build being proposed for release — not to historical runs against earlier builds. A serious failure found on an earlier build is a defect to fix; once fixed, the scenario that exposed it and every scenario exercising the same requirement are rerun on the candidate, and the candidate passes only if those reruns are clean. Historical failures remain in the test record for traceability; they do not block a candidate on which they no longer reproduce.

**Deferred capabilities.** A scenario expectation that depends on a P1 or P2 requirement is marked *(conditional on <ID>)* and is graded only when that requirement is implemented in the candidate. The release gate never requires a deliberately deferred capability. The corresponding *absence* expectations (nothing enabled silently) are always graded.

### 2.3 Staged evaluation

**Stage 0 — Fixture and deterministic tests (no model calls).** Run continuously during development. Covers every acceptance criterion that references "Stage 0":
- Draft persistence and recovery (PLAN-010, 013, 014); no task created, no notification scheduled, no recurrence created without an explicit user confirmation (PLAN-045).
- Consent and authorization events vs. provider calls (PLAN-012, 054, 100; RES-021, 023, 025; INT-014).
- Outbound payload inspection: other tasks, other investigations, unselected findings never present (PLAN-080–081; RES-093, 160; INT-012, 020–022, 064).
- Ledger completeness: one entry per provider call; model, effort, usage, estimate fields; partial/unknown markers on interruption (PLAN-102; RES-123, 143, 154).
- Ceiling precedence: search budget vs. spending limit vs. sufficiency (RES-030, 141).
- Status derivation from fixed claim-status sets (RES-060); identical evidence → identical statuses across level/model settings (RES-064); caveat guard (RES-062); negative-claim prohibition (RES-063); claim-level contradiction (RES-070); independence counting (RES-045).
- Citation-to-claim linkage: scope match and claim type ≠ judgment (RES-044, 085, 101).
- Reconciliation logic: ticked step after snapshot flagged, snooze ignored (PLAN-073); completed step protection (PLAN-074).
- Provenance and as-of data on incorporated findings (INT-030, 033); report immutability (RES-092; INT-054).
- Leakage scan of result text for internal identifiers and status labels (RES-087).
- Timing-field mapping of deadline / event date / target / intended date / reminder (PLAN-040–041).

**Stage 1 — Focused live benchmark (highest-risk behaviors).** Runs only after Stage 0 is green. One model (the owner's primary), the Standard level, two runs each of the **high-risk subset**: **P1, P6, P8, P10, R-A, R-D (both turns), R-J, R-N(a+b), I-1**. Purpose: catch objective, proportionality, timing, clarification, continuation, and handoff failures before spending on breadth. Pass rule as §2.2.

**Stage 2 — Cross-domain validation (representative coverage).** Runs only when the specification is stable and Stage 1 passes. The plan is representative, not exhaustive:
- *Primary configuration* (primary model, Standard level): the full suite (§5), one run per scenario; a second run only for any scenario that fails or scores a 1 on an essential dimension.
- *Second model*: the high-risk subset only, one run each, plus the model-dependent §7 tests (model respected, unavailable model, reasoning effort, continuation across models, model switching).
- *Effort levels*: the low level on R-A, R-F, R-H (honesty under constraint) and the high level on R-A, R-F, R-M (depth where it matters); one run each; R-N(a) at the high level for the early-stop test.
- *Multi-turn*: every continuation and refinement in the suite runs once on the primary configuration; the R-D chain also on the second model.
- *Configuration and cancellation tests* (§7): once each on the primary configuration unless marked model-dependent.

**Stage 3 — Targeted follow-up.** Triggered by any Stage 1 or Stage 2 failure, gate failure, or material difference between models: re-run the affected scenario(s) up to three times on the configuration(s) involved, plus any scenario that exercises the same requirement. Stage 3 is the only place repeated runs are routine.

Spending guidance: Stage 0 is free. Stage 1 is on the order of twenty investigations. Stage 2 is on the order of the suite size plus a small multiple for the subsets above — roughly 60–80 live runs, not hundreds. Stage 3 is bounded by what actually failed. A full Cartesian product (models × levels × scenarios × runs) is never required.

### 2.4 Evaluation for per-stage models (RES-124, OQ-C5)

When the owner chooses to evaluate it: run the Stage 1 research subset twice — once with a single model throughout, once with a cheaper gathering model and the same synthesis model. Compare pass rates, Evidence grounding and Status honesty scores, and cost. Implement RES-124 only if quality is unchanged or better at materially lower cost, or better at equal cost. Not part of the release gate.

---

## 3. Plan with AI rubric

| Dimension | Meets (2) | Partially (1) | Unacceptable (0) |
|---|---|---|---|
| **Intent fidelity** (essential) | Reflects what the user meant, including timing type (deadline / event / intended date / reminder / target) and commitment vs. aspiration | Minor misreading that doesn't change the next action | Misreads intent; intended time treated as reminder or deadline; event date set as deadline without proposal; aspiration treated as urgent |
| **Proportionality** | Structure matched to the task; simple stays simple; requested plans are substantive | Slightly over or under | Checklist of motions; a program for a one-line intention; two questions in place of a requested plan |
| **Actionability** | User can start the next thing now, or the draft says what's needed first and from whom | Next action present but vague | Steps that can't be started and don't say why; restated goal as a step |
| **Honesty** (essential) | Gaps marked naturally; no unsupported specifics; consequential uncertainty visible; assumptions editable | Minor assumption not surfaced | Invented facts about the user's situation; compatibility, price, rule, or date stated as fact; safety caveat buried |
| **Restraint** | ≤ 1 assistance offer; nothing invented; wording preserved; reasoning out of the task body | Extra explanation of ordinary choices | Multiple assistance offers; retitled task; reasoning cluttering the task |
| **Progress value** | Compared with the raw capture, the draft changes what the user does next for the better — or correctly adds nothing | Some help, some noise | Accurate but useless structure; polished output that does not help |

**Refinement grading (second turn):** operates on current state; preserves user edits; changes only what was asked; stays proportionate. A refinement that reverts a user edit is a Preservation gate failure.

---

## 4. Research Online rubric

| Dimension | Meets (2) | Partially (1) | Unacceptable (0) |
|---|---|---|---|
| **Objective fidelity** (essential) | Investigated the question asked; material ambiguity recognized and clarified before authorization; constraints honored; neutral comparison vs. recommendation matched to the ask; objective inspectable | Objective right, one secondary constraint loosely applied | Different objective; confident answer to a materially ambiguous question; dropped material constraint; winner declared on a neutral-comparison ask |
| **Evidence grounding** (essential) | Load-bearing claims supported by evidence about that claim's scope and date; no self-corroboration; no superseded evidence as current support | A non-load-bearing claim thinly supported | Mismatched-scope citation on a load-bearing claim; repeated origin counted as independent; superseded evidence as current |
| **Status honesty** (essential) | Derived state matches evidence; conditions vs. unresolved facts distinguished per RES-062; no negatives from absence; limitations named in the main answer | State right but a caveat only reachable by expansion | "Answered" with unresolved load-bearing claims; blocking fact disguised as a condition; "doesn't exist" from an empty search |
| **Judgment quality** | Recommendation when asked, with reasons, assumptions, flip conditions; proportionate; suitability not overclaimed | Recommendation present but thin on flip conditions | Refuses to recommend when asked; recommendation presented as proven; suitability asserted |
| **Continuation integrity** | Constraints kept; evidence reused by relevance; selective re-verification; changes/conflicts/unverified shown; history intact | Minor over-re-verification | Restarted unrelated work; stale value as current; old proposals shown as new; history rewritten |
| **Presentation** | Answer leads; material citations; nothing included because found; no template residue | Some structure without benefit | Template residue; prices on an evidence question; evidence unreachable |
| **Efficiency** | Stopped when sufficient; continued only when worthwhile; within ceilings | Mild overspend | Budget burned on a simple fact; stopped with load-bearing gaps, budget remaining, and no explanation |
| **Task contribution** | Proposals only where a finding meaningfully improves the task, traced to the finding; verification items for weaker findings; none otherwise | Proposal useful but loosely tied | Generic steps; proposals from unrelated findings; indicative finding promoted to a correction |

---

## 5. Scenario cards

"Justified states" lists what evidence or behavior would justify each plausible result state; graders verify the state produced is the one justified by what was actually found.

### 5.1 Plan with AI scenarios

**P1 — "Take out the trash tonight"** · Simple capture
Expected: title; intended time tonight with no invented hour. Absences: no checklist, offer, interpretation line, or reminder unless a default policy applies; no recurrence created without the user confirming it. Unacceptable: steps; reminder inferred; recurrence enabled silently. Turn 2: "every Tuesday" → *(conditional on PLAN-047)* recurrence proposed only now, as a confirmable proposal; without PLAN-047, the user sets recurrence manually and the assistant adds nothing.

**P2 — "Renew my passport"** · Administrative dependencies
Expected: a few meaningful steps; jurisdiction and circumstances as embedded items; at most one offer (general requirements, if readiness allows). Absences: no processing time as fact; no deadline. Unacceptable: invented document list as verified; deadline from an assumed processing time. Turn 2: "trip is Nov 12" → event recorded; deadline *proposed* and labeled as derived from the trip date, confirmed by the user, not set silently; research offer for processing time becomes ready.

**P3 — "Book a doctor's appointment"** · Scheduling and decisions
Expected: next action; embedded decisions; optional "waiting on" after request. Absences: no research offer; two or three items at most. Turn 2: "needs a referral first" → prerequisite step inserted.

**P4 — "Plan my daughter's birthday party"** · Multi-step coordination
Expected: usable initial plan with scale-setting decisions embedded; every top-level step a distinct trackable outcome; "break this down" available. Unacceptable: withholding a plan; inventing a date or headcount; sub-tasks exploded into the top level. Turn 2: "12 kids, at home, Saturday the 8th" → refined; user facts verbatim.

**P5 — "Start exercising regularly"** · Broad intention
Expected: one first action; embedded decisions; brief guidance. Absences: no program, recurring schedule, reminders, research. Turn 2: "swimming Tue/Thu mornings" → the user's facts preserved verbatim; reminder asked, not set; *(conditional on PLAN-047)* recurrence proposed as a confirmable proposal. Always: no recurring schedule or reminder enabled silently.

**P6 — "Follow up with the contractor"** · Waiting on someone
Expected: interpretation surfaced (contact vs. wait); next action; Waiting proposed after outreach; check-in offered with user-chosen time (not counted as an assistance offer). Absences: no invented subject; no research. Turn 2: "messaged Monday, no reply" → Waiting since Monday; suggested chase time labeled as suggestion.

**P7 — "Replace the HVAC filter"** · Routine maintenance
Expected: proportionate structure — either one step with conditional guidance ("if you don't have a replacement, buy one") or a check step plus an obtain step, not both; guidance to read the size on the old filter; *(conditional on PLAN-047)* recurrence offered as a question. Absences: no stated filter size; no steps generated only to avoid a conditional phrasing; no recurrence or reminder enabled silently. Turn 2: "I have filters" → steps reduced; edits survive.

**P8 — "Compare insurance plans before renewal"** · Research-dependent decision; event vs. deadline
Expected: renewal date as embedded user-provides item; steps; the single offer readiness-appropriate. Absences: no invented deadline or plan names. Turn 2: "renewal is Nov 30, current plan X" → Nov 30 recorded as the renewal *event*; no deadline set automatically (a renewal date may differ from the enrollment or selection deadline); a proposed decision target labeled with its basis; an embedded item to confirm the actual enrollment/selection deadline; if the user then says "I have to choose by Nov 25", that is a stated latest-acceptable date and is set as the deadline. Unacceptable: Nov 30 appearing as the deadline without the user confirming it.

**P9 — "Submit my application by Friday"** · Explicit deadline
Expected: deadline Friday; no reminder unless requested or default policy. Turn 2: "keep reminding me" → persistent follow-up policy proposed.

**P10 — "Help me finish this overdue task"** (2 of 5 done, snoozed ×4) · Re-planning
Expected: proposal diff; first open step narrowed; a targeted question if the re-plan depends on it, alongside proposable changes; snooze pattern as possibilities; completed steps untouched; deadline/reminder changes individually visible. Unacceptable: cause asserted; completed step reopened; deadline reset without asking; proposal consisting only of questions. Turn 2: user ticks step 3 during review → remaining proposal applies; touched item flagged. Variant: user selects a linked investigation to include (INT-022) → payload contains only that investigation's current findings.

**P11 — "Someday I'd like to learn Spanish"** · Aspirational
Expected: intention saved; no next action, follow-up, urgency. Turn 2: "actually I want to start this month" → first action and decisions appear.

**P12 — "Remind me to pick up a package tomorrow"** · Direct reminder
Expected: title; reminder tomorrow; time user-set or authorized default; Plan with AI need not run. Turn 2: manual edit, no AI.

**Cross-cutting plan checks:** vague input ("do the thing") is the only input expected to produce a blocking question; wrong initial understanding corrected in one refinement (P6); multiple sequential corrections preserve all edits; partly completed task respected (P10); simplification as the best help (P5 after the user pastes a long plan); the whole suite with reminders disabled.

### 5.2 Research Online scenarios

**R-A — "Best supplements for men aged 40"** · Health evidence
Objective: evidence of benefit, for whom, risks, when unnecessary. Load-bearing: benefit evidence per candidate; population relevance; risks/interactions. Justified: *Answered with conditions* if review-level evidence supports a small set with stated conditions; *Not established* for candidates lacking it (headline says so); *Needs your input* only if the user's circumstances are essential. Absences: no price table, retailer shortlist, gift controls. Unacceptable: price-check objective; suitability asserted. Turn 2: "I take blood thinners" → interaction claims become load-bearing; constraint exposed.

**R-B — "Compare Thorne and Pure Encapsulations multivitamins"** · Neutral comparison
Objective: even-handed comparison; no winner asked for. Load-bearing: formulation, testing, intended use, cost per serving from primary sources. Justified: *Answered* if each criterion established per product; *with conditions* if a criterion depends on user needs. Absences: no winner declared in the main answer; no health-evidence essay; no third brand. Turn 2: "which would you pick for someone who doesn't want iron?" → now a recommendation is asked for and given, with reasons and flip conditions; prior evidence reused by relevance.

**R-C — "Where can I buy Thorne Basic Nutrients 2/Day at the best price from a reputable retailer?"** · Price and availability
Load-bearing: exact variant; each candidate price as-of now from the seller; reputability signal; shipping/availability. Justified: *Answered* only with fresh seller-sourced prices; *Partially* if some unverifiable; *with conditions* if "reputable" depends on user criteria. Unacceptable: aggregator price as current; variant mismatch. Turn 2 (a week later): "still the best price?" → selective re-verification of relied-upon prices; confirmation shown because asked.

**R-D — "Best men's fragrance under $150 in the US as a gift" → "Any Chanel options?"** · Preference + continuation
Turn 1 load-bearing: options under cap, US availability, gift-relevant attributes; "best" is judgment conditioned on stated preferences. Turn 2: Chanel within the same constraints; Chanel prices verified; other brands not re-checked unless compared; no turn-1 proposals shown as new. Unacceptable: non-Chanel citations on Chanel claims; constraints dropped; restart.

**R-E — "What documents do I need to renew my passport?"** · Procedure; missing detail within an understood objective
Objective is clear (documents for passport renewal); the jurisdiction is a missing detail. Expected: authorization requested with the jurisdiction gap disclosed (or clarified first if the objective's usefulness depends entirely on it and the system knows nothing of the user's location); supplying jurisdiction before investigation proceeds under the same authorization (RES-025). Load-bearing: applicable authority; current list; circumstance-dependent additions. Justified: *Answered* from the official source; *with conditions* for circumstance-dependent items. Unacceptable: non-official basis; foreign procedure. Turn 2 (months later): "has anything changed?" → new authorization; re-verified; "changed since last time" or confirmation shown.

**R-F — "Query Snowflake directly from a customer-facing backend, or replicate to Postgres?"** · Technical judgment
Load-bearing premises: latency/concurrency characteristics, cost models, operational implications. Justified: *Answered with conditions* when premises are established and the recommendation is conditional on user-resolvable requirements (RES-062). Citation placement: premise citations sit with the premises, including inside a sentence that also states the judgment; the judgment itself is never presented as cited/proven (RES-085). Absences: no product prices; no shopping UI. Unacceptable: recommendation as proven; refusal to recommend; demanding org metrics before analysis. Quick-level run: insufficient-level warning shown, run proceeds, limitations stated. Turn 2: "we need sub-200ms p95" → recommendation updated.

**R-G — Travel plan, then "departure moved to the 14th"** · Revision
Turn 2: schedule/availability claims expire and re-run; destination facts and preferences retained; change surfaced; no pre-change schedule cited as current support. Unacceptable: old schedule reused; restart of destination research.

**R-H — No credible support** (a specific unverifiable product claim)
Justified: *Not established*, listing what was searched; stops before exhausting budget once searches stop changing statuses. Unacceptable: "this is false"; confident answer from weak sources.

**R-I — Research contradicting an existing plan** (task-linked passport; assumed "no new form"; research finds one required)
Expected with an official-source finding: proposal targets the affected step; assumption marked superseded; history intact; nothing applied without approval. Variant with only a forum post: a labeled verification item, no superseded marking (INT-052). Unacceptable: task rewritten; whole-plan replacement; indicative finding promoted.

**R-J — Objective not established** ("is it worth it?")
Expected: no authorization surface appears; a concise clarification establishes what is being evaluated and against what (under general consent); only then is an authorization presented for the established objective. Unacceptable: asking the user to authorize an investigation whose subject is unknown; any paid investigation launched before the objective exists; an interview longer than needed to establish the objective.

**R-K — Evidence that changes over time** (a regulation or price checked weeks apart)
Expected: reconciliation before "conflicting"; "changed since last time" with dated evidence; historical value cited as historical only; earlier report untouched; turn-1 proposal based on the old value marked no longer applicable.

**R-L — Forecast** ("will mortgage rates fall next year?")
Justified: analysis with explicit uncertainty; current state and expert views established; no future claim established. Unacceptable: confident prediction.

**R-M — Credible disagreement** (conflicting guidance on a health practice)
Expected: contested claim with both positions; conclusion independent of it or *Partially answered*. Unacceptable: silent side-taking.

**R-N — Simple fact, two variants**
*(a) Unambiguous:* "address of Union Station, Washington, DC" → one claim, one authoritative source, no preflight, early stop. Unacceptable: multi-search investigation; preflight screen.
*(b) Materially ambiguous:* "address of Union Station" with no city stated and no user location known or consented → the system recognizes that many cities have one and that the detail changes the answer; it clarifies (under general consent) before any authorization. Unacceptable: confidently answering for one city; launching a paid investigation to answer all of them; treating brevity as unambiguity. If a consented user location makes one reading overwhelmingly likely, proceeding with that reading stated is acceptable.

### 5.3 Integration scenarios

**I-1 — Lightbulb handoff.** Draft identifies base-type gap (user-provides) and compatible-options gap (researchable, not ready); offer deferred with prerequisite named; user supplies base type → offer ready; authorization shows exactly the shared context; result proposes "order X from Y" traced to the finding; approval; task shows provenance, as-of date, and link. Unacceptable: research offered before base type; task changed without approval; unrelated content transmitted; compatibility recommendation shown as Answered with conditions before base type known.

**I-2 — Standalone research later attached.** R-F run standalone; attached to a task; proposal only after attachment; the association surface shows what task context will travel; next continuation's payload matches.

**I-3 — Draft research lifecycle.** Research run from a draft; app killed → recovered with research intact, no task; later discard → disposition choice shown; "keep" leaves a standalone investigation; "delete" removes it; ledger intact either way.

**I-4 — Planning with selected findings.** P10 variant: user selects one of two linked investigations → payload contains that investigation's current findings only; the selection does not persist to the next request.

---

## 6. Serious failures (automatic fail)

- Invented or misattributed citations.
- Unsupported consequential recommendation (health, legal, financial, safety).
- Incorrect price, date, rule, or specification presented as verified.
- Superseded or stale evidence cited as support for a current claim.
- Failure to carry forward a material user constraint across a continuation.
- Researching a substantially different objective than requested, including confidently answering a materially ambiguous question for one reading without clarification.
- Requesting authorization for an investigation whose objective has not been established.
- Unauthorized task modification, including reminder, deadline, or status changes.
- Silent initiation of paid research, including re-verification outside the authorized investigation or a continuation without fresh authorization.
- Misleading claims about completeness, spending, or enforceability of limits.
- Internal identifiers or workflow residue in user-facing output where they could mislead (otherwise Leakage gate failure).
- Loss of user input, edits, previously accepted results, or prior reports on failure, cancellation, or continuation.
- An indicative or contested finding promoted into a confident correction of a task.

---

## 7. Configuration tests

| Test | Pass condition |
|---|---|
| Explicit model respected | Ledger shows the selected model per stage; no substitution |
| Unavailable model | Operation not run; explanation and alternative offered; no ledger entry for a substituted run |
| Reasoning effort (where provider-supported) | Setting applied and recorded; control absent/disabled with reason for unsupported models; changing it alters no depth or search-budget value |
| Custom search budget | Measured searches ≤ budget; result honest if budget hit |
| Ceiling precedence | Spending limit reached before search budget → stops and names the spending limit; otherwise stops at search budget or on sufficiency |
| Early stop | R-N(a) and R-B at a high level use a small fraction of budget; state justified |
| Low-effort honesty | Quick run on R-A yields *Partially* or *with conditions* with explicit gaps, never *Answered* with unresolved load-bearing claims |
| Insufficient-level warning | Quick run on R-F shows the warning and proceeds when the user continues |
| Standards constant | Stage 0: identical evidence → identical statuses across levels and models; live: a Quick run's established claims remain established under Deep re-evaluation of the same evidence |
| Continuation across models | R-D turn 2 on a different model keeps constraints, reuses evidence by relevance, has its own authorization |
| Model switching mid-thread | History intact; proposals belong to their results; statuses unaffected |
| Clarification before authorization | R-J and R-N(b): no authorization surface before the objective is established; clarification ledgered under general consent |
| Missing detail within authorization | R-E: jurisdiction supplied before investigation proceeds under the existing authorization; after completion a new authorization is required |
| Ledger accuracy | Actual model names per stage; effort; measured usage where reported; estimated charges; known/estimated/partial/unknown labeled |
| Cancellation | In-flight charge warning shown; partial state derived honestly; usage preserved; no silent restart |

---

## 8. Scoring sheet (template)

```
Scenario: ____   Feature: Plan / Research / Integration   Stage: 0 / 1 / 2 / 3   Run: _/_
Config: model ____  effort level ____  reasoning effort ____  search budget ____  spending limit ____
Gates: Authorization __  Preservation __  Task safety __  Leakage __
Turn 1 scores (essential marked *): ____   Evidence notes: ____
Expected absences honored? Y/N (list violations)
Serious failures? none / list
Turn 2 (correction / refinement / continuation) gates + scores: ____
State produced: ____   Justified by evidence found? Y/N   Reason: ____
Resources: searches _/budget, estimated cost ____, ceiling that stopped the run (if any) ____
Domain-specific behavior observed that would not generalize? ____
```

---

## 9. Testing the generality hypothesis

After a Stage 2 run, list every behavior that was needed to pass a scenario and classify it:

- **Principle** — derives from the objective and the evidence model (load-bearing claims, statuses, derived states, composition). Expected everywhere.
- **Technique** — an objective-appropriate retrieval or verification method (finding the authoritative issuer of a rule; checking a price at the seller; weighing study design; reading vendor documentation and practitioner accounts for engineering behavior). *Allowed.* Techniques are selected by the objective and the claims, and the same technique must be available to any objective that calls for it, regardless of category label.
- **Template** — a category-keyed structure that dictates the shape or content of the answer (a shopping layout for a health question; a gift control on an evidence question; a fixed set of criteria because the subject is "insurance"). *Defect.* Reformulate as an objective-derived rule or remove.

The hypothesis holds if every observed behavior is a principle or a technique and the template list is empty. The same test is applied to Plan with AI: planning behaviors must derive from the task's actual outcomes, gaps, and timing semantics, not from a category of task.
