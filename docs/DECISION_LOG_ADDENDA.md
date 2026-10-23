# Decision Log Addenda — proposed entries for Documents E, G and H (baseline v1.0 → v1.1)

**Status:** Owner decisions recorded on 2026-10-23 through the change-control process of Document E ("Change control", rules 1–4). The frozen documents in the owner's package are **not** edited here; this file holds the entries the document owner applies when Documents E, G and H advance to version 1.1. Until then, implementation follows these entries as confirmed owner decisions (status **C**) and every report cites baseline **v1.0 + addenda A1–A3**.

| Addendum | Decision entry | Status | Affected documents / IDs |
|---|---|---|---|
| A1 | **G6 — Milestone sequence after M1.** | C (owner, 2026-10-23) | Document G §3 (suggested delivery order), §7 step 6; kickoff plan §4 |
| A2 | **R48 — Research presets: Quick, Standard, Thorough, Deep, plus Custom.** Closes OQ-C1. | C (owner, 2026-10-23) | Document C RES-132 (named presets), RES-134 (saved defaults); Document E R31, OQ-C1; Document F §2.3 ("Standard level") |
| A3 | **D52 — Draft expiry: v1 drafts never expire automatically.** Closes the expiry part of OQ-B1. | C (owner, 2026-10-23) | Document B PLAN-013 ("Open: expiry policy"); Document E D26, OQ-B1 |

## G6 — Milestone sequence after M1 (process)

- **Decision.** M2 (canonical Proposal Review) is next. After M2 the Research Online milestone (Investigation Thread, previously numbered M4) is executed **before** the Living Draft milestone (previously M3). M5 (integration and spending) and M6 (hardening and acceptance) follow. Milestone numbers are kept for traceability; the execution order is M1 → M2 → M4 → M3 → M5 → M6.
- **Rationale.** Document G §1 and §3 put trustworthy research first ("where the known failures live"; the first Stage 1 live benchmark is research-heavy). Invariants (G §2) are served first by M1 and M2 regardless of order.
- **Rejected alternatives.** Living Draft before research (the kickoff plan's original order); running both in parallel (one canonical Proposal Review must land first for either).
- **Consequences.** Draft-origin research (INT-013, Document F I-3) lands with the Living Draft (M3), after the Investigation Thread exists; the Investigation Thread is built task-linked and standalone (RES-110–112) first.

## R48 — Research preset names and count (Research Online)

- **Decision.** Presets are **Quick, Standard, Thorough, Deep**, plus **Custom** for independently configured controls (model, reasoning effort where supported, depth, search budget, spending limit; R28/R41 unchanged). "Standard" is the level Document F uses for Stage 1 and the primary Stage 2 configuration.
- **Migration rule (owner's condition).** Existing saved settings map **safely and visibly**: Quick → Quick, Thorough → Thorough, Deep → Deep, Custom → Custom, each keeping its saved model override, search budget and spending limit exactly. No saved value is relabelled to "Standard" and no effort or budget changes without the user choosing it. Standard appears as a new choice; a saved default is never changed silently.
- **Rejected alternatives.** Three presets plus Custom with "Standard" mapped onto Thorough for Document F runs (would misstate the level run); renaming an existing preset (would silently change what a saved default means).
- **Implementation.** Research Online milestone (M4): preset definitions, settings migration with tests asserting the mapping above, authorization card and ledger record the preset name actually run.

## D52 — Draft expiry (Plan with AI)

- **Decision.** In v1 a draft is **never discarded automatically**: no time-based expiry and no count-based eviction. Drafts leave the open list only when the user saves or discards them (PLAN-013: "deleted only by user action or by a policy the user has been shown"; the policy shown is "drafts stay until you save or discard them").
- **Rejected alternatives.** Expiry after N days (risks silent loss of input, D16/D21); a cap with oldest-first eviction (same risk).
- **Still open (OQ-B1 remainder).** Draft count display and surfacing details are UX (Living Draft milestone); not blocking.
- **Implementation.** Already satisfied by `DraftRepository` (no expiry path exists); the Living Draft surface states the policy.

## Change-log entry (Document H, proposed v1.1)

| Where | Change | Decision |
|---|---|---|
| Document G §3/§7 | Execution order after M1 recorded: M2 → M4 → M3 → M5 → M6 | G6 |
| Document C RES-132/134; Document F §2.3 | Preset set fixed to Quick/Standard/Thorough/Deep + Custom; "Standard" is Document F's level; migration rule for saved settings | R48 |
| Document B PLAN-013; Document E OQ-B1 | Expiry: none in v1; user save/discard only | D52 |

No requirement IDs are added, removed or reprioritized by these entries.
