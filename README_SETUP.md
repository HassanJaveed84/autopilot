# Life Admin Autopilot — Overnight Claude Code Cloud Session

This package is intended for an unattended Claude Code session started from the Claude **mobile app → Code** with a GitHub repository selected.

- `CLAUDE.md` — autonomous implementation instructions for M0–M1C.
- `docs/SPEC.md` — full original product and technical specification.
- `PROMPT_FOR_CLAUDE_MOBILE.md` — paste-ready session prompt (also available separately).
- `README_SETUP.md` — this document.

## Quick start

1. Create/select a **private** GitHub repo and start a cloud **Code** session (not Plan).
2. Attach the ZIP, if your Code UI supports attachments that are accessible to the cloud coding environment. Otherwise upload these files to the repo first.
3. Paste the contents of `PROMPT_FOR_CLAUDE_MOBILE.md` into the session message and send.
4. Cloud sessions generally keep working with the mobile app closed, but can be stopped by usage limits, permission requests, environment/tool failures or other platform limits. No prompt can override those limits.
5. Review `docs/OVERNIGHT_REPORT.md`, the diff/PR, build results and Android notification permissions on a physical device in the morning.

## Scope

The unattended goal is the **offline-only Android MVP M0–M1C**. Optional AI and agent features M2+ are future work, not part of this run. Never treat an untested build as production-ready.
