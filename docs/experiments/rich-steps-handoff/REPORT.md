# Rich Steps through the ChatGPT handoff: feasibility (v0.2.1)

**Status:** investigation only, 2026-10-28, on `claude/autopilot-rich-task-steps-sec9oe` at `f54e9df` (Release v0.2.0). No production code, database, protocol or existing test was changed by the investigation. Preserved here unchanged (apart from this sentence) as the basis for the v0.2.1 implementation. The experiment in this folder is a standalone Kotlin/JVM project. It copies the shipping v1 parser unchanged (`copy-v1-parser.sh`) and adds a prototype v2 parser and bridge. Its 6 tests pass (`results/TEST-RichStepsExperimentTest.xml`).

**Adopted design (not revisited):** hybrid A+B from the Rich Steps prototype. The compact checklist stays. The main link is tappable on the row. The title opens a detail sheet. Every step has an optional plain-text description and up to several labelled links. Manual creation stays one field. Completed steps are not folded. Updating a step never silently drops its details.

---

## 1. Answer

**Use `ap=v2`: the exact v1 grammar plus two step-detail keys and an end marker.** No JSON, no Base64, no compression.

```
https://hassanjaveed84.github.io/autopilot/import#ap=v2&r=<ref>&t=<title>
  &d=<decision>  &n=<note>                        (unchanged from v1)
  &s=<step title>
  &sd=<one paragraph of that step's description>  (0–4, right after its step)
  &sl=<label> <https address>                     (0–5, right after its step; the first is the main link)
  &end=1                                          (always last)
```

- The link carries everything it needs: realistic rich handoffs measured **700–1,260 URL characters** (§4). The shipping v1 cap is 4,096. v2 raises the app's cap to **8,192** for headroom, and the prompt keeps ChatGPT **under 4,000**.
- ChatGPT keeps the one skill the owner has already confirmed it has on the S24 Ultra: `key=value` pairs with `%XX`. The only new rules are "details go right after their step", "a link is a label, a space, then the address" and "finish with `&end=1`".
- **0.2.0** refuses every v2 link as **"needs a newer Autopilot"** (verified against the copied 0.2.0 parser). **0.2.1** reads v1 links exactly as before, so a conversation started before the update still works.
- Each handoff step becomes **one review item**: title, description and links are approved or rejected together, and can be edited first.
- Naming a step that is already on the task adds only what's new (a **"Step details"** item). The user's wording, description, links and main link are never changed or removed. Duplicate paragraphs and links are skipped.
- **One real data-loss trap exists today and must be fixed with the schema change:** Proposal Review's undo rebuilds every step from a title-only snapshot (§9.1).

---

## 2. What exists today

| Area | Finding |
|---|---|
| Parser | `share/applink/HandoffLink.kt` `HandoffFragment.parse`: `ap=v1` must be first; keys `t r d s n`; split on `&` and the first `=` before one strict decode pass. Raw `+`, raw `=`, control characters, line breaks and bidi controls are refused; an **unknown key refuses the whole link**. Caps: title 120, items 200 code points; 5 d, 10 s, 5 n |
| Envelope | `ImportLink.kt` `AppLinkEnvelope`/`ImportLinkParser`: https, exact host and path, the query ignored, `MAX_URL = 4_096`. An unsupported `ap=` version maps to `NEWER_VERSION`, oversize to `TOO_BIG`, everything else to `MALFORMED` |
| Prompt | `share/chatgpt/ChatGptPrompt.kt`: shares open/done step **titles** only; the return-link rules give a pre-encoded prefix, then "encode everything except `A–Z a–z 0–9 - . _ ~`", "under 2,000 characters" |
| Bridge | `HandoffProposalBridge.kt`: each `s` → `ADD_STEP` unless the task or handoff already has it (`MatchKey`); `d`/`n` → `APPEND_NOTE`. `setId` hashes the content so a repeated link reopens its set |
| Proposal model | `domain/proposal/ProposalModels.kt`: `ItemValue.Step(text, origin, verify, supersedes, afterStepId)`; sets persist as JSON (`ProposalJson`, `ignoreUnknownKeys`, defaults), so new optional fields decode from old rows |
| Review rules | `ProposalReconciler`: `EDIT_STEP` on a USER or done step is **material** (needs its own OK); `ADD_STEP` isn't |
| Step storage | `TaskStepEntity(id, taskId, sortOrder, title, isDone, completedAt, origin, provenanceId)`, Room **v8** |
| Backup | `BackupRepository`: `SCHEMA_VERSION = 1` since the start; new fields are optional and **older versions ignore unknown keys** (`ignoreUnknownKeys = true`) |
| Evidence | Owner confirmed on the S24 Ultra: ChatGPT writes `ap=v1` links that decode exactly, and the full round trip (share → ChatGPT → link → review → apply) works (`docs/ROADMAP.md`, `CHATGPT_ROUND_TRIP_REPORT.md`) |

---

## 3. The two strategies

| | Extend `ap=v1` with optional keys | **`ap=v2` = v1 grammar + `sd`/`sl` + `end`** |
|---|---|---|
| Installed 0.2.0 receiving a rich link | Refused as `UNKNOWN_FIELD` → **"didn't come through right"** (misleading: it blames ChatGPT) | Refused as `UNSUPPORTED_VERSION` → **"needs a newer Autopilot"** (true) |
| 0.2.1 receiving an old v1 link | Works | Works: the v1 path is unchanged |
| Parser complexity | One code path with optional keys | One code path; `sd`/`sl`/`end` are known only under `v2` |
| Prompt change | New keys | New keys + a new prefix string (the app writes it) |
| Can tighten rules (end marker, adjacency) without breaking old v1 links | No: v1 links without them would turn invalid | Yes |

A truly backward-compatible extension doesn't exist here, because v1 refuses unknown keys by design. Bumping the version costs one constant and gives the honest error message, so **v2**. JSON or Base64 v2 formats were rejected in the spike (`CHATGPT_APP_LINK_SPIKE.md` §8: models corrupt Base64 silently and drift when percent-encoding JSON) and are no simpler for the app.

---

## 4. Measured sizes

Built with the same encoder the app uses (`HandoffFragment.encode`). "Canonical" is what the current prompt asks for: everything outside `A–Z a–z 0–9 - . _ ~` becomes `%XX`. "Tolerant" leaves `: / ? , ; $ ' ( ) ! * @` raw, which the parser also accepts. The reference is a 36-character id, as task ids are. Tokens use OpenAI's `o200k_base` tokenizer.

| Payload | Fields | Canonical chars / tokens | Tolerant chars / tokens | 0.2.0 parser says |
|---|---|---|---|---|
| 1 perfume step: 3 paragraphs + product URL | 9 | **697** / 340 | 665 / 299 | NEWER_VERSION |
| 3 rich steps (gift plan), 4 links | 15 | **1,045** / 491 | 977 / 405 | NEWER_VERSION |
| Research step: 2 paragraphs + 5 URLs | 12 | **1,096** / 479 | 1,002 / 359 | NEWER_VERSION |
| Plain v1 (1 decision, 3 steps, 1 note) | 8 | 331 / 161 | 325 / 153 | Valid, as today |
| Mixed: 1 d, 1 n, 3 rich + 3 plain steps | 20 | **1,257** / 598 | 1,183 / 505 | NEWER_VERSION |
| Everything the item caps allow | 114 | 31,193 / 13,491 | 30,793 | TOO_BIG |

- A rich step costs about **300 URL characters**: 10 rich steps ≈ 3,200 with the prefix. The prompt target becomes **"under 4,000 characters"**; the app's hard cap becomes **8,192** (refused as too big, never cut down).
- Percent-encoding costs about **3.3× the tokens** of plain text (the perfume step is 102 tokens as text, 340 as a link). 600 tokens of output is ordinary for ChatGPT. Error risk grows with length, which is why the format fails closed and has an end marker.
- Non-ASCII is expensive: `“ ” —` take 9 URL characters each. That is fine at this size.
- Practical limits outside the app: Android hands an App Link's URI to the activity in the intent (binder transactions allow about 1 MB), so 8 KB is far from any platform limit. **Not measured here:** how the ChatGPT Android app renders and taps a 3–4 KB link. That is phone check C5 (§11).

---

## 5. Will ChatGPT write it reliably?

- **Same skill as v1, already proven on the phone.** v2 adds no new encoding. Paragraphs are repeated `sd=` fields, so ChatGPT never has to encode a line break (`%0A` stays refused).
- **Links are the new risk.** A web address must be one value. Measured outcomes for likely model output (`results/mistakes.md`):

| ChatGPT writes | Result |
|---|---|
| `sl=Product%20page%20https://www.chanel.com/…` (raw `:/`) | Accepted |
| `sl=Product%20page%20https%3A%2F%2Fwww.chanel.com%2F…` (fully encoded, as the prompt says) | Accepted, same value |
| `sl=https://…` without a label | Accepted; the label shows the website name |
| A citation with a raw `=`: `…/?utm_source=chatgpt.com` | Accepted (v2 tolerance: the key ends at the first `=`, so a later raw `=` is unambiguous). Tracking parameters are stripped when saved |
| A query with `%26`: `?size=50ml%26color=blue` | Accepted |
| A query with a **raw `&`** | **Refused** (`UNKNOWN_FIELD`): the whole link, never half of it |
| A raw `#anchor` in the address | **Refused** (`NOT_A_LINK`: a second `#` isn't a legal URL) |
| `%20` inside the address not re-encoded as `%2520` | **Refused** (`BAD_LINK`) |
| Raw `+` | Refused (`AMBIGUOUS_PLUS`), as v1 |
| `javascript:`, `intent:`, no scheme, `https://bank.com@evil.example` | Refused (`BAD_LINK` / `DISALLOWED_CHARACTER`) |
| `sd`/`sl` before any step, or after a `d`/`n` | Refused (`DETAIL_WITHOUT_STEP`) |
| Link cut short (no `&end=1`) | Refused (`INCOMPLETE`) |
| 6 links or 5 paragraphs on one step | Refused (`TOO_MANY_ITEMS`) |

- Every failure shows the existing "didn't come through right" message, and the user asks ChatGPT for the link again. The prompt reduces the main risk with one sentence: *use the page's plain address without tracking parameters, and inside an address write `&` as `%26`, `#` as `%23` and `%` as `%25`.*
- The format cannot stop **invented addresses**. The prompt says to use only addresses ChatGPT actually found or the user gave, and the review shows each link's website name before anything is accepted.

---

## 6. Recommended format: examples

These are encoded the way the prompt asks ChatGPT to write them (canonical). The purchase link is the experiment's own output; the existing-step link was encoded the same way with Python's `quote(…, safe='-._~')`. Addresses are realistic shapes for measurement and weren't opened.

**One purchase step (697 characters)**
```
https://hassanjaveed84.github.io/autopilot/import#ap=v2&r=3f2b8c1e-7d4a-4e6b-9a51-0c8d2e7f6b13&t=Thank-you%20gift%20for%20Omar&s=Buy%20Bleu%20de%20Chanel%20EDP%2050%20mL&sd=Estimated%20price%3A%20about%20%24135%20before%20tax%3B%20check%20the%20total%20at%20checkout.&sd=Gift%20packaging%3A%20choose%20the%20complimentary%20gift%20wrap%20at%20checkout%2C%20or%20ask%20for%20the%20box%20and%20ribbon%20in%20store.&sd=Card%3A%20%E2%80%9COmar%2C%20thank%20you%20for%20standing%20next%20to%20me.%20Wear%20this%20to%20the%20next%20good%20night%20out.%20%E2%80%94%20Sam%E2%80%9D&sl=Product%20page%20https%3A%2F%2Fwww.chanel.com%2Fus%2Ffragrance%2Fp%2F107460%2Fbleu-de-chanel-eau-de-parfum-spray%2F&end=1
```
Saved as: title *Buy Bleu de Chanel EDP 50 mL*; a description of three lines; main link *Product page · chanel.com*.

**Details for a step already on the list (414 characters).** The step is named exactly as Autopilot listed it:
```
https://hassanjaveed84.github.io/autopilot/import#ap=v2&r=3f2b8c1e-7d4a-4e6b-9a51-0c8d2e7f6b13&t=Renew%20passport%20before%20Lisbon%20trip&s=Get%20a%20passport%20photo&sd=2%20x%202%20in%2C%20color%2C%20plain%20white%20background%2C%20taken%20in%20the%20last%206%20months%2C%20no%20glasses.&sl=Photo%20requirements%20https%3A%2F%2Ftravel.state.gov%2Fcontent%2Ftravel%2Fen%2Fpassports%2Fhow-apply%2Fphotos.html&end=1
```

**Plain steps.** The v1 link is unchanged and still valid. The same content as v2 is `#ap=v2&r=…&t=…&s=Call%20dentist&s=…&end=1`. Both map to the **same proposal set id**, so a repeat tap still reopens one review (tested).

The 3-step and 5-link links are in `results/urls.tsv`.

---

## 7. Parser and validation requirements

1. **Version:** the first pair is `ap=v1` or `ap=v2`; anything else is `NEWER_VERSION`. v1 is parsed exactly as today: `sd`, `sl` and `end` are unknown keys, and a raw `=` is refused.
2. **v2 end marker:** the last pair is exactly `end=1`; otherwise `INCOMPLETE` (shown as "didn't come through right"). `end` anywhere else is a duplicate.
3. **Keys:** `t` once (required), `r` at most once, `d` ≤ 5, `n` ≤ 5, `s` ≤ 10, `sd` ≤ 4 per step, `sl` ≤ 5 per step. Any order for `d`/`n`/`s`; **`sd`/`sl` must directly follow their `s` or another detail of it.**
4. **Decoding:** the v1 single strict pass, with one v2-only tolerance: a raw `=` inside a value is kept. Everything else as v1: raw `+`, broken `%`, invalid UTF-8, control characters, line breaks and bidi controls are refused, as are empty values and leading or trailing spaces.
5. **Lengths (code points, after decoding):** `t` 120, `s`/`d`/`n` 200 (as v1), `sd` 300, `sl` label 80 + address 1,000. Whole link ≤ **8,192** characters, checked before parsing.
6. **`sl`:** the address is the last space-separated token; the label (optional) is everything before it. The address must:
   - use `https` or `http`,
   - parse as a `java.net.URI` with an ASCII host containing a dot,
   - have no user info.
   Otherwise `BAD_LINK`. One shared `WebLink.check` serves the parser, the step editor and backup import.
7. **Normalising on save:** strip `utm_*` query parameters. Keep everything else byte for byte. The address is opened only on the user's tap, through the browser (`ACTION_VIEW`); Autopilot never fetches it.
8. **Failure:** never truncate, never guess, never apply a partial link; refusals show categories only and never echo values (as v1).

---

## 8. Review and selective approval

- **One item per handoff step.** A step not on the task becomes `ADD_STEP` with `ItemValue.Step(text, …, description, links)`. Selecting it adds the title, description and every link together; rejecting it drops them together (prototype test `richStepsBecomeOneReviewItemEach`).
- **Partial acceptance is done by editing:** "Edit" on a proposed step opens the same step editor as manual editing (title, description, links, ↑ for main, remove). The edited value is what applies (the existing `choice.value` path in `ProposalApplier`).
- **Review card** (as in the prototype): the title, a 2-line description with "Read all", and each link with its **website name** and a "Main" tag on the first. Tapping a link opens the browser and doesn't select the item.
- **Plain steps** look and behave exactly as today.

---

## 9. Adding details to an existing step

**Matching:** ChatGPT names the step as Autopilot listed it. `MatchKey` (case, spacing and punctuation ignored) finds it, preferring an open step over a done one. Plain `s=` for an existing step proposes nothing, as today.

**New item kind `ADD_STEP_DETAILS`** (anchor = step id, value `ItemValue.StepDetails(description, links)`), labelled **"Step details"**:
- Built with **only what's new**:
  - description paragraphs whose `MatchKey` isn't already a paragraph of the step's description;
  - links whose page key isn't already on the step. The key ignores scheme case, `www.`, a trailing `/`, `#fragment` and `utm_*`.
  If nothing is new, there's no item.
- The same step named twice in one handoff merges into **one** item.
- **Apply:**
  - The description is appended after the user's text (blank line between). Nothing is rewritten.
  - New links go after existing ones. **The user's main link stays main.**
  - It is re-checked at apply time against the step as it is then, so applying twice changes nothing (tested).
  - Over the per-step caps (description 2,000, 10 links) the item is *blocked* with a reason, never cut.
- **Not material** (the user's wording is untouched), so it can be bulk-selected. Reconciler:
  - step deleted → conflict;
  - step ticked → conflict, as for any change to completed work (PLAN-074);
  - step renamed since → still applies;
  - everything already there → note "Already on the step".
- The step keeps `origin = USER`. Provenance is recorded for the applied details, and the step's own `provenanceId` isn't overwritten.

### 9.1 Paths that would silently drop details today (all must carry the new fields)

| Path | Why it matters |
|---|---|
| `ProposalApplier.undo` (`ProposalApplier.kt:350–353`) rebuilds **every** step from `StepSnapshot(id, sortOrder, title, isDone, origin, provenanceId)` | Undoing any step change would wipe every step's description and links. **Fix:** add `description` and `links` (with defaults) to `StepSnapshot` and restore them. Old apply records decode with empty defaults. That is safe because any later edit bumps `rev`, and undo is refused unless `rev == revAfter` |
| `Mappers.kt` `TaskStepEntity.toDomain()` / `TaskStep.toEntity()` (positional) | Any write through the domain model would drop the columns |
| `BackupRepository` export (`:196`) and import (`:311`) build `StepDto` / `TaskStepEntity` field by field | A backup would lose details |
| `ProposalApplier` `ADD_STEP` (`:150`) builds a new entity | Must take the proposed details |
| `renameStep`, `setStepDone`, `moveStep`, `EDIT_STEP` use `entity.copy(…)` | Safe as they are; covered by tests |

---

## 10. Database, backup and compatibility

**Room v8 → v9, one migration, no new table:**
```sql
ALTER TABLE task_steps ADD COLUMN description TEXT DEFAULT NULL;
ALTER TABLE task_steps ADD COLUMN links TEXT DEFAULT NULL;   -- JSON: [{"label":"Product page","url":"https://…"}]
```
- **No new table:** links are always read and written with their step, never queried on their own, and are capped at 10. One JSON column keeps the migration, the undo snapshot and the backup to one field each. A `step_links` table would add an entity, a DAO, cascade rules and ordering for no user-visible gain.
- Domain `TaskStep(…, description: String? = null, links: List<StepLink> = emptyList())`; a Room `TypeConverter` with kotlinx.serialization; export `schemas/…/9.json`; `MIGRATION_8_9` in `Migrations.ALL`; `MigrationTest` covers 8→9 keeping existing steps.
- Limits (domain validation, also on import): description ≤ 2,000 code points (line breaks allowed); ≤ 10 links per step; label ≤ 80; address per §7.6.

**Backup:**
- `StepDto` gains `description: String? = null` and `links: List<LinkDto> = emptyList()`.
- Old backups import unchanged.
- **`SCHEMA_VERSION` 1 → 2.** 0.2.0 ignores unknown keys, so without the bump it would import a 0.2.1 backup and silently drop every description and link. With it, 0.2.0 refuses the file with "made by a newer app version".

**Proposal sets stored as JSON:** new optional fields decode from old rows. `ADD_STEP_DETAILS` sets are unreadable to 0.2.0, which only matters on a downgrade.

**Compatibility matrix**

| Situation | Result |
|---|---|
| 0.2.1 + v1 link (old conversation, old link, repeat tap) | Works as today, same set id |
| 0.2.1 + v2 link | Rich review |
| 0.2.0 + v2 link (phone not updated) | "Needs a newer Autopilot" |
| 0.2.0 importing a 0.2.1 backup | Refused (schema 2), nothing lost |
| 0.2.1 importing any older backup | Works; steps have no details |

---

## 11. Risks and decisions

**Risks**
1. **A raw `&` or `#` inside a web address** makes ChatGPT's link invalid. It fails closed, and the user has to ask again. Mitigated by the prompt sentence in §5; phone check C3 includes an address with a query string.
2. **Invented or wrong addresses.** Not a format problem. Mitigated by the prompt rule, the website name shown in review, and nothing opening without a tap.
3. **ChatGPT app handling of a 3–4 KB link:** unverified (C5).
4. **Silent loss of details through an old code path:** §9.1. Each path gets a test.
5. **Steps named slightly differently** from Autopilot's list become new steps, not details. This is the same behaviour as v1 today, and the review shows it.

**Decisions to make (each with a recommendation)**
1. **`&end=1` marker.** Recommend **yes**: it costs 6 characters and catches a cut-short link that would otherwise import silently shorter.
2. **Share existing descriptions and links in "Discuss in ChatGPT"?** Recommend **no for 0.2.1**: titles only, as today, because descriptions may hold personal details. The app's dedupe handles repeats.
3. **Backup schema 1 → 2.** Recommend **yes** (§10).
4. **`http` addresses.** Recommend **accept**, showing the website name. Refusing them would fail whole links for a few old sites.
5. **Details for a ticked step.** Recommend a **conflict with "Apply anyway"**, consistent with protecting completed work. The alternative is to allow it silently.
6. **Raw `=` tolerance in v2.** Recommend **yes**: it is unambiguous and catches ChatGPT's `?utm_source=chatgpt.com` citations.

---

## 12. Implementation plan for v0.2.1 (one phase, in this order)

1. **Data:**
   - `StepLink` + `WebLink.check`; `TaskStep`/`TaskStepEntity`/`Mappers` fields + converter.
   - `MIGRATION_8_9`, `AppDatabase.VERSION = 9`, `9.json`.
   - `StepSnapshot` fields + undo restore.
   - `TaskRepository.updateStepDetails(stepId, title, description, links)` (validates, keeps provenance, logs, bumps `rev`).
   - `StepDto`/`LinkDto`, backup schema 2.
   - *Tests:* `MigrationTest` 8→9; mapper round trip; undo keeps details of untouched and touched steps; backup export/import round trip and old-backup import; validation limits.
2. **Handoff:**
   - `HandoffFragment` v2 (§7), `HandoffStep`, `INCOMPLETE`/`DETAIL_WITHOUT_STEP`/`BAD_LINK` mapped to `MALFORMED`, `MAX_URL = 8_192`.
   - `HandoffProposalBridge` v2 mapping and `setId`.
   - `ItemKind.ADD_STEP_DETAILS`, `ItemValue.Step` details, `ItemValue.StepDetails`.
   - `ProposalReconciler` and `ProposalApplier` (apply + undo).
   - `ReviewPresenter` labels and `isMaterial`.
   - *Tests:* port this folder's experiment tests (v1 unchanged, sizes, the mistakes table, merges, dedupe, set-id stability); applier apply/undo for both kinds; existing `HandoffFragmentTest` stays green unchanged.
3. **Prompt:** `ChatGptPrompt` prefix `ap=v2`, the rules from §5–§7 in the existing tone, "under 4,000 characters", "finish with `&end=1`". *Test:* `ChatGptPromptTest` checks that the prefix is followed by a valid v2 link.
4. **UI** (the approved prototype):
   - Hybrid step rows in `StepsCard` (main-link pill, chevron, hint line).
   - Step detail sheet on `AutopilotSheet`.
   - Step editor sheet.
   - Proposal Review rich cards with "Edit" opening the step editor.
   - Robolectric UI tests for tap targets, TalkBack labels and editing that keeps details.
5. **Verification:**
   - `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`.
   - CI emulator: the App Link delivers a v2 link to review.
   - Phone checks below.
   - A report under `docs/` and a roadmap update.

**Phone checks (owner, S24 Ultra)**
- **C1.** A known-good v2 link (§6) opens one rich review item; apply; undo; apply again.
- **C2.** A gift conversation asks ChatGPT to send a purchase step with a product page.
- **C3.** A research conversation with five links, one with a query string.
- **C4.** Details for an existing step: the user's wording and main link are kept.
- **C5.** A long plan (~3,500 characters) taps through from the ChatGPT app.
- **C6.** A conversation started on 0.2.0 sends a v1 link after the update.

---

## 13. Rerunning the experiment

```sh
cd docs/experiments/rich-steps-handoff
./copy-v1-parser.sh     # the shipping v1 parser, package renamed only
gradle test             # Gradle 8.14, Kotlin 2.2.21, JDK 21
```
The first run here hit HTTP 429 from Maven Central twice while downloading the Kotlin plugin, then succeeded. Results: `results/sizes.md`, `results/mistakes.md`, `results/urls.tsv`, `results/TEST-RichStepsExperimentTest.xml` (6 tests, 0 failures). Token counts came from `tiktoken` (`o200k_base`) over `results/urls.tsv`.
