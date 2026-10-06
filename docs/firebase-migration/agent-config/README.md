# Firebase migration agent configuration (reference only)

These files ran the four-agent Firebase migration workflow (orchestrator, developer,
reviewer, handoff). The migration closed on 2026-10-06 for the development-only scope
(see `FIREBASE_MIGRATION_STATUS.md` at the repository root), so they were moved here to
keep as reference. **None of them is active.**

| File here | Original location |
|---|---|
| `FIREBASE_MIGRATION_WORKFLOW.md` | `FIREBASE_MIGRATION_WORKFLOW.md` |
| `AGENTS.reference.md` | `AGENTS.md` |
| `CLAUDE.reference.md` | `CLAUDE.md` |
| `claude/settings.json` | `.claude/settings.json` |
| `claude/agents/*.md` | `.claude/agents/*.md` |
| `codex/config.toml` | `.codex/config.toml` |
| `codex/agents/*.toml` | `.codex/agents/*.toml` |

The instruction files were renamed (`*.reference.md`) and the tool directories lost their
leading dot so that Claude Code and Codex do not load them as live instructions.

To run the workflow again, copy the files back to their original locations and restore the
original names. Note that `claude/settings.json` makes `firebase-orchestrator` the main
agent for every Claude Code session in the repository.
