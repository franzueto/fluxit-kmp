# Firebase Migration Agent Workflow

This workflow is mandatory for tasks in `FIREBASE_MIGRATION_PLAN.md`. Architecture lives in the plan; mutable execution state lives only in `FIREBASE_MIGRATION_STATUS.md`.

## Agent roster

| Role | Codex | Claude Code | Authority |
|---|---|---|---|
| Orchestrator | `gpt-5.6-sol`, medium | Opus 5, low effort | Selects one task, owns status transitions, delegates, asks the user for decisions/manual work, and reports outcomes. |
| Developer | `gpt-5.6-sol`, medium | Opus 5, low effort | Implements one assigned task as a senior Android/KMP engineer and records verification evidence. Never self-approves. |
| Reviewer | `gpt-5.6-terra`, medium | Sonnet 5, medium effort | Read-only review of acceptance criteria, diff, tests, security, and regressions. Approves or requests changes with blocking issue IDs. |
| Handoff | `gpt-5.6-terra`, medium | Sonnet 5, medium effort | Reconciles tracker state, git state, evidence, blockers, and the resume capsule. May edit workflow/status documentation only. |

The role definitions are project-scoped under `.codex/agents/` and `.claude/agents/`. Agent/thread IDs are temporary and must never be written to the tracker.

For Codex, `.codex/config.toml` makes the main session use the orchestrator model. The main session follows `AGENTS.md` and spawns `firebase_developer`, `firebase_reviewer`, and `firebase_handoff` by name.

For Claude Code, `.claude/settings.json` selects `firebase-orchestrator` as the main agent. If the agent directory was created after a Claude session started, restart Claude Code once so it is discovered.

### Claude Code model and effort binding

Each Claude Code role pins its own model and reasoning effort in its `.claude/agents/<role>.md` YAML frontmatter:

| Agent file | `model` | `effort` |
|---|---|---|
| `firebase-orchestrator.md` | `opus` | `low` |
| `firebase-developer.md` | `opus` | `low` |
| `firebase-reviewer.md` | `sonnet` | `medium` |
| `firebase-handoff.md` | `sonnet` | `medium` |

Rules:

- Use the aliases `opus` and `sonnet`, not pinned dated model IDs, so the roles track the current Opus 5 / Sonnet 5 releases.
- The orchestrator must spawn `firebase-developer`, `firebase-reviewer`, and `firebase-handoff` by `subagent_type` **without** passing any per-invocation `model` override. A per-invocation override outranks the frontmatter and would silently run the role on the orchestrator's model.
- Never spawn these roles as a `fork`; a fork always inherits the parent model and effort and ignores the role frontmatter.
- `.claude/settings.json` pins the main session to `"model": "opus"` with `"effortLevel": "low"` so the orchestrator itself matches the table even before its frontmatter applies.
- Resolution order for a subagent's model is: per-invocation parameter, then agent frontmatter, then `CLAUDE_CODE_SUBAGENT_MODEL`, then the main conversation's model. Leave `CLAUDE_CODE_SUBAGENT_MODEL` unset so the frontmatter decides.
- After editing any agent frontmatter, restart Claude Code once; agent definitions are read at session start.

## Sequential orchestration loop

Only one implementation task may be active. The roles run sequentially to prevent overlapping writes.

1. Orchestrator reads the plan, the entire status file, `git status --short`, and the latest relevant diff. It reconciles stale state before selecting work.
2. Orchestrator selects the first dependency-satisfied `READY` task, confirms its acceptance evidence and `Owner/kind`, and changes it to `IN_PROGRESS`.
3. Route by task kind:
   - `Developer/implementation`: delegate exactly that task to the developer. The developer implements the smallest complete slice, runs proportionate checks, and returns changed files, commands/results, decisions, risks, and remaining manual checks.
   - `Orchestrator/decision`: research bounded options. If user approval is missing, change the task to `BLOCKED_USER` and ask only for the material choice. After the response, return it to `READY`; on reselection, change it to `IN_PROGRESS`, record the approved decision and rationale, and continue to `IN_REVIEW`. Do not delegate the choice to the developer.
   - `User/manual`: inspect the linked manual state. If it is `NOT_STARTED` or `FAILED`, change it to `REQUESTED`, change the task to `BLOCKED_USER`, and give the user exact safe instructions. After the user supplies evidence, record `PASS` or `WAIVED` and return the task to `READY`. On reselection, if the manual state is already `PASS` or `WAIVED`, change the task to `IN_PROGRESS`, validate the recorded evidence, and continue to `IN_REVIEW` without re-requesting the action.
   - `Orchestrator/gate`: verify all required tasks, manual gates, acceptance evidence, reviewer verdicts, and handoff state. This task validates a phase and does not request product code.
4. When work/evidence is ready, the orchestrator changes the task to `IN_REVIEW`. Reviewer validates acceptance criteria and actual evidence/diff, then returns `APPROVED` or `CHANGES_REQUESTED` with blocking findings labeled by task ID.
5. For blocking implementation findings, the orchestrator changes the task to `CHANGES_REQUESTED`, sends it back to the developer, and repeats review. For decision/manual/gate findings, the orchestrator resolves the missing decision, evidence, or gate condition with the responsible owner. Non-blocking follow-ups become new tracker tasks or explicit notes; they are not silently dropped.
6. After approval, the orchestrator changes the task to `VERIFIED`. The handoff agent then reconciles the task row, evidence, decisions, blockers, workspace state, phase gate, and Resume Here block.
7. The orchestrator changes `VERIFIED` to `DONE` only after handoff validation. It may then make the next dependency-satisfied task `READY`.

The reviewer and handoff can run in parallel only for read-only discovery. Handoff finalization must happen after the reviewer verdict. Never run two code-writing developers against the same checkout.

## Status lifecycle

Allowed states:

```text
NOT_STARTED -> READY -> IN_PROGRESS -> IN_REVIEW
IN_REVIEW -> CHANGES_REQUESTED -> IN_PROGRESS
IN_REVIEW -> VERIFIED -> DONE
READY | IN_PROGRESS | IN_REVIEW -> BLOCKED_USER | BLOCKED_TECHNICAL
BLOCKED_USER | BLOCKED_TECHNICAL -> READY
NOT_STARTED -> SKIPPED (requires a recorded decision)
```

- Only the orchestrator changes canonical task status or `next_task`.
- A task cannot be `READY` while any dependency is not `DONE`.
- `VERIFIED` requires reviewer approval and objective evidence.
- `DONE` additionally requires handoff reconciliation.
- `SKIPPED` requires a decision-log entry explaining why scope changed.
- Manual checks are never assumed. Missing human evidence means `BLOCKED_USER`, not `DONE`.

Manual action states are separate from task states:

```text
NOT_STARTED -> REQUESTED -> PASS
REQUESTED -> WAIVED (explicit user approval and rationale required)
REQUESTED -> FAILED -> REQUESTED
```

Only `PASS` or `WAIVED` satisfies a manual gate. A manual action is linked from a `User/manual` task; `MAN-*` IDs are not task dependencies themselves.

## Session start and stop

At session start, read in this order:

1. `FIREBASE_MIGRATION_STATUS.md` — Resume Here, active blockers, and current task.
2. The referenced task row and phase gate.
3. The relevant section of `FIREBASE_MIGRATION_PLAN.md`.
4. The latest handoff entry, `git status --short`, and the relevant diff.

Before any session stops—even mid-task—the orchestrator or handoff agent must update Resume Here with:

- current task/state and exact next action;
- completed and remaining work;
- commands run and results;
- changed/untracked files and current branch/commit;
- reviewer findings still open;
- decisions/assumptions;
- blockers and exact user action required.

Chat history is helpful context, never authoritative state.

## User decisions and manual work

Only the orchestrator asks the user for decisions or external work. Each request must state:

- task ID and why it blocks progress;
- exact Firebase Console, CLI, Xcode, Apple Developer, device, or billing action;
- environment (`development` or `production`);
- safe proof of completion;
- what must not be shared or committed.

Never request or record service-account private keys, access tokens, passwords, signing secrets, or user data. Firebase mobile configuration files are not service-account credentials, but their intended repository/secret-handling policy must be explicitly decided before they are added.

## Evidence and review standards

Every completed task records evidence appropriate to its scope:

- exact build/test command, exit result, date, and target;
- automated test/report name for behavior and Rules checks;
- sanitized manual checklist for Android device/emulator, iOS simulator, or iOS physical device;
- deployed development environment/version when deployment is part of acceptance;
- changed files plus commit/PR identifier, or an explicit dirty-worktree list.

A phase gate passes only when every required task is `DONE`, all manual gates are `PASS` or explicitly `WAIVED` by the user with rationale, and the handoff agent has updated Resume Here. Production cutover additionally requires a rollback point and proof that both packaged targets no longer initialize or include Room/SQLite in the production path.

## Plan changes

Stable task IDs must never be renumbered or reused. Add new IDs at the end of the relevant phase. Record architecture/scope changes in the decision log before changing dependencies or marking tasks `SKIPPED`. Keep handoff history append-only; keep Resume Here and the task ledger canonical.
