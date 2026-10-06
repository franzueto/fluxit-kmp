@AGENTS.md

Claude Code uses the project-scoped `firebase-orchestrator` as the main agent via `.claude/settings.json`. It delegates implementation, review, and handoff to the matching project agents. If `.claude/agents/` was created after the current Claude session began, restart Claude Code once before starting migration work.
