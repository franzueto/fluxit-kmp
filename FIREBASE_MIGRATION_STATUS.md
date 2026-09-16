# Firebase Migration Status

This is the canonical mutable state for `FIREBASE_MIGRATION_PLAN.md`. Update it through the process in `FIREBASE_MIGRATION_WORKFLOW.md`.

```yaml
migration_status:
  schema_version: 1
  plan_version: "2.0"
  overall_status: NOT_STARTED
  current_phase: P0
  next_task: FB-000
  active_task: null
  last_completed_task: null
  last_updated: 2026-09-16
  active_blockers: []
  pending_decisions:
    - DEC-001
    - DEC-002
    - DEC-003
  verification_summary: NOT_RUN
```

## Resume Here

- Current task: `FB-000`
- State: `READY`
- Objective: Capture the pre-migration repository/build/test baseline and confirm identifiers and platform integration points.
- Completed: Migration architecture reviewed; execution workflow and initial ledger created; the workflow package received an `APPROVED` reviewer verdict with no outstanding blocking findings.
- Remaining: Run the baseline commands and record existing failures without changing product behavior.
- Exact next action: The orchestrator changes `FB-000` to `IN_PROGRESS`, sets `active_task: FB-000`, and assigns it to `firebase_developer`; the developer then inventories `README.md`, Gradle tasks, Xcode configuration, repository contracts, and tests and runs the feasible Android/common/iOS baseline checks.
- Pending reviewer findings: None. The workflow-package review is `APPROVED`; `FB-000` has not started and has not yet been reviewed.
- Decisions needed: `DEC-001` auth/account linking, `DEC-002` environments/config-file handling, `DEC-003` cleanup/retention/cache/conflict policy.
- Blockers: None for `FB-000`.
- User action required: None yet; the orchestrator will ask when a decision/manual task becomes the next blocker.
- Workspace state: Dirty. Untracked: `.claude/`, `.codex/`, `AGENTS.md`, `CLAUDE.md`, `FIREBASE_MIGRATION_PLAN.md`, `FIREBASE_MIGRATION_STATUS.md`, and `FIREBASE_MIGRATION_WORKFLOW.md`.
- Last known branch/commit: `epic/firebase` at `aa8d0e4`.
- Last verification: Workflow/documentation review `APPROVED` on 2026-09-16. Product baseline verification for `FB-000` has not run.

## Status values

`NOT_STARTED`, `READY`, `IN_PROGRESS`, `IN_REVIEW`, `CHANGES_REQUESTED`, `VERIFIED`, `DONE`, `BLOCKED_USER`, `BLOCKED_TECHNICAL`, `SKIPPED`.

Manual actions use `NOT_STARTED`, `REQUESTED`, `PASS`, `WAIVED`, or `FAILED`. Only `PASS` and a user-approved `WAIVED` satisfy a manual gate. `MAN-*` IDs are linked evidence records, not task dependencies.

## Pending decisions

| ID | Status | Decision | Blocks | Resolution/evidence |
|---|---|---|---|---|
| DEC-001 | OPEN | Select sign-in providers, recovery behavior, and account-linking policy that preserve one identity across Android and iOS. | FB-001, Phase 1 | — |
| DEC-002 | OPEN | Select dev/prod Firebase topology, exact app IDs, and how mobile config files are stored/distributed. | FB-002, FB-004 | — |
| DEC-003 | OPEN | Select sign-out cache policy, tombstone retention, backend cleanup mechanism/billing owner, edit conflict policy, and orphan-photo reconciliation policy. | FB-003, Phases 2–5 | — |

## Manual action queue

| ID | Status | Owner | Environment | Action | Safe completion evidence |
|---|---|---|---|---|---|
| MAN-001 | NOT_STARTED | User | Development | Create/register the selected Firebase development apps; enable chosen Auth provider, Firestore, and Storage; provide mobile config files using the policy from DEC-002. | App registrations and enabled services confirmed; file paths recorded without secrets/tokens. |
| MAN-002 | NOT_STARTED | User | Development | Provide Firebase CLI authentication/deployment access or perform the documented Rules/index/functions deployment. | Sanitized command result or Console deployment/version confirmation. |
| MAN-003 | NOT_STARTED | User | Development | Provide iOS signing and a physical device for the Phase 0 and final device checks. | Device/build/date checklist. |
| MAN-004 | NOT_STARTED | User | Development | Enable billing/scheduler/backend services required by the cleanup choice, if applicable. | Service/billing readiness confirmation without credentials. |
| MAN-005 | NOT_STARTED | User | Production | Create/register production apps, configure providers, deploy Rules/indexes/cleanup, set budget alerts, and approve backup/export policy. | Sanitized production readiness checklist. |
| MAN-006 | NOT_STARTED | User | Development | Deploy the reviewed cleanup backend when direct agent deployment access is unavailable. | Sanitized deployment/version confirmation. |
| MAN-007 | NOT_STARTED | User | Development | Deploy the reviewed hardened Rules and indexes when direct agent deployment access is unavailable. | Sanitized deployment/version confirmation. |

## Task ledger

Evidence is required before `VERIFIED`; reviewer approval and handoff reconciliation are required before `DONE`. `—` means no evidence has been produced yet.

### P0 — Baseline, decisions, Firebase setup, and SDK spike

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-000 | READY | Developer/implementation | Inventory IDs/source sets/contracts/build commands and record Android, common-test, iOS simulator baseline plus known failures. | — | Commands/results, identifiers, integration map, git state. | — |
| FB-001 | NOT_STARTED | Orchestrator/decision | Resolve DEC-001 authentication providers, recovery, and account linking. | FB-000 | User-approved decision log entry. | — |
| FB-002 | NOT_STARTED | Orchestrator/decision | Resolve DEC-002 environment topology, exact IDs, and config-file handling. | FB-000 | User-approved decision log entry. | — |
| FB-003 | NOT_STARTED | Orchestrator/decision | Resolve DEC-003 cache, retention, cleanup/backend, conflict, and orphan policies. | FB-000 | User-approved decision log entry; later implementation may refine details explicitly. | — |
| FB-004 | NOT_STARTED | User/manual | Complete MAN-001 development Firebase provisioning. | FB-001, FB-002 | MAN-001 is `PASS` or explicitly `WAIVED`; config paths recorded without credentials. | — |
| FB-005 | NOT_STARTED | Developer/implementation | Author Firebase CLI/emulator config and baseline authenticated owner-only Firestore/Storage Rules. | FB-001, FB-002 | Emulator starts; allowed owner and denied cross-user tests pass locally. | — |
| FB-010 | NOT_STARTED | User/manual | Complete MAN-002 Firebase CLI access or baseline development deployment. | FB-004, FB-005 | MAN-002 is `PASS` or explicitly `WAIVED`; deployment/access evidence recorded safely. | — |
| FB-006 | NOT_STARTED | Developer/implementation | Add Android official Firebase SDK dependencies/init; prove debug and release compilation. | FB-004, FB-005, FB-010 | Exact Gradle commands pass; no Firebase types in commonMain. | — |
| FB-007 | NOT_STARTED | Developer/implementation | Select and implement Apple SDK interop; prove iOS simulator linking/init. | FB-004, FB-005, FB-010 | Xcode/Gradle simulator evidence; integration requirements documented. | — |
| FB-011 | NOT_STARTED | User/manual | Complete MAN-003 physical-device signing/access and validate iOS linking/init. | FB-007 | MAN-003 is `PASS` or explicitly `WAIVED`; device/build/date evidence recorded. | — |
| FB-008 | NOT_STARTED | Developer/implementation | Build temporary Android/iOS smoke harness for UID, Firestore listen/write/offline reconnect, and Storage upload/download/delete. | FB-006, FB-007, FB-011 | Sanitized two-platform smoke matrix passes under owner-only Rules. | — |
| FB-009 | NOT_STARTED | Orchestrator/gate | Close Phase 0 and remove/disable temporary harness without changing production repositories. | FB-008 | Reviewer approval, phase exit matrix, handoff updated. | — |

### P1 — Authentication and session boundary

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-101 | NOT_STARTED | Developer/implementation | Add Firebase-neutral AuthRepository, session/error states, fakes, and shared unit tests. | FB-009 | Common tests cover loading, signed-out, authenticated, error, and restoration transitions. | — |
| FB-102 | NOT_STARTED | Developer/implementation | Implement Android official Auth adapter. | FB-101 | Android integration checks for sign-up/in/recovery/sign-out/restoration. | — |
| FB-103 | NOT_STARTED | Developer/implementation | Implement iOS official Auth adapter. | FB-101 | iOS integration checks for the same flows. | — |
| FB-104 | NOT_STARTED | Developer/implementation | Add authentication UI and application root session gate. | FB-102, FB-103 | UI/manual matrix proves no user listener starts before session resolution. | — |
| FB-105 | NOT_STARTED | Developer/implementation | Enforce sign-out listener disposal, in-memory clearing, selected cache policy, and user A→B isolation. | FB-104 | Automated lifecycle tests plus two-platform manual account-switch check. | — |
| FB-106 | NOT_STARTED | Orchestrator/gate | Close Phase 1. | FB-105 | Phase exit criterion approved and handoff updated. | — |

### P2 — Schema and Firebase repositories

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-201 | NOT_STARTED | Developer/implementation | Finalize nested item API (`listId`), DTO/schema constants, enum fallback, pending timestamp/client fallback, ordering, conflict, and error mappings. | FB-106, FB-003 | Mapping/contract tests cover malformed fields, pending timestamps, stable tie-breaks, and conflict expectations. | — |
| FB-202 | NOT_STARTED | Developer/implementation | Implement Android list repository with cancellable listeners, ordering, errors, soft delete, and restore. | FB-201 | Emulator integration tests and listener-removal evidence. | — |
| FB-203 | NOT_STARTED | Developer/implementation | Implement iOS list repository with equivalent behavior. | FB-201 | Emulator integration tests and listener-removal evidence. | — |
| FB-204 | NOT_STARTED | Developer/implementation | Implement Android item repository and idempotent atomic counter mutations. | FB-201 | Add/toggle/delete/restore/clear tests, including retry/offline/concurrency and >500-item chunk strategy. | — |
| FB-205 | NOT_STARTED | Developer/implementation | Implement iOS item repository with equivalent behavior. | FB-201 | Equivalent integration evidence. | — |
| FB-206 | NOT_STARTED | Developer/implementation | Add cross-client counter consistency, conflict, pending-write, malformed-document, and reconnect tests. | FB-202, FB-203, FB-204, FB-205 | Emulator suite passes with documented expected outcomes. | — |
| FB-207 | NOT_STARTED | Developer/implementation | Move repository bindings to platform modules behind a temporary development flag and authenticated-session gate. | FB-206 | Existing ViewModel tests pass; Room comparison path remains available; no pre-auth user listener. | — |
| FB-208 | NOT_STARTED | Orchestrator/gate | Close Phase 2. | FB-207 | All repository operations pass on emulator; phase exit approved and handed off. | — |

### P3 — Cloud Storage photos

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-301 | NOT_STARTED | Developer/implementation | Rename the durable domain concept `photoPath` to `photoRef`; update contracts/fakes/tests. | FB-208 | Common tests compile/pass; no durable local absolute path remains in domain contracts. | — |
| FB-302 | NOT_STARTED | Developer/implementation | Redesign Firebase-neutral PhotoStorage/rendering contracts and define deterministic object/orphan reconciliation behavior. | FB-301, FB-003 | Contract tests and recorded failure semantics. | — |
| FB-303 | NOT_STARTED | Developer/implementation | Add image type/size validation and resize/compression policy with pure tests. | FB-302 | Boundary, unsupported, and corrupt-image tests pass. | — |
| FB-304 | NOT_STARTED | Developer/implementation | Implement Android Storage upload/load/delete and remote rendering. | FB-303 | Android integration/manual checks pass under Storage Rules. | — |
| FB-305 | NOT_STARTED | Developer/implementation | Implement iOS Storage upload/load/delete and remote rendering. | FB-303 | iOS integration/manual checks pass under Storage Rules. | — |
| FB-306 | NOT_STARTED | Developer/implementation | Implement item photo progress/retry/remove and safe replacement sequence. | FB-304, FB-305 | Failure-injection tests prove old photo preservation and new-upload orphan handling. | — |
| FB-307 | NOT_STARTED | Developer/implementation | Verify reinstall, cross-device availability, interrupted upload, and orphan detection on both platforms. | FB-306 | Sanitized two-platform manual matrix. | — |
| FB-308 | NOT_STARTED | Orchestrator/gate | Close Phase 3. | FB-307 | Phase exit approved and handed off. | — |

### P4 — Error, loading, and offline UX

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-401 | NOT_STARTED | Developer/implementation | Add application error taxonomy/mapping without Firebase exceptions in shared UI. | FB-208 | Mapping tests cover permission, auth, unavailable/offline, validation, serialization, and unknown. | — |
| FB-402 | NOT_STARTED | Developer/implementation | Harden Dashboard/Create List ViewModels with `try/finally`, retry/events, and duplicate-submit guards. | FB-401 | Success/failure unit tests pass. | — |
| FB-403 | NOT_STARTED | Developer/implementation | Harden List Detail/Item Detail ViewModels and photo actions similarly. | FB-401, FB-306 | Success/failure/unit tests pass; loading flags always reset. | — |
| FB-404 | NOT_STARTED | Developer/implementation | Distinguish initial loading, empty, cached/offline, pending writes, and fatal session states in UI. | FB-402, FB-403 | UI-state tests plus reviewer-approved behavior. | — |
| FB-405 | NOT_STARTED | Developer/implementation | Run airplane mode, interrupted upload, denied write, restart, and background/foreground matrix on both platforms. | FB-404 | Sanitized manual matrix demonstrates understandable recovery. | — |
| FB-406 | NOT_STARTED | Orchestrator/gate | Close Phase 4. | FB-405 | Phase exit approved and handed off. | — |

### P5 — Backend cleanup and cascading deletion

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-506 | NOT_STARTED | User/manual | Complete MAN-004 billing/scheduler/backend prerequisites selected in DEC-003. | FB-003, FB-308 | MAN-004 is `PASS` or explicitly `WAIVED`; service readiness recorded safely. | — |
| FB-501 | NOT_STARTED | Developer/implementation | Implement the selected development cleanup mechanism. | FB-003, FB-308, FB-506 | Cleanup target builds and local/emulator harness is documented. | — |
| FB-502 | NOT_STARTED | Developer/implementation | Implement idempotent item tombstone/photo cleanup with eligibility re-check and restore-race protection. | FB-501 | Emulator/unit tests cover boundary time, retries, and restored records. | — |
| FB-503 | NOT_STARTED | Developer/implementation | Implement paginated/idempotent list cascade across subcollections and Storage beyond batch limits. | FB-502 | Large-fixture retry/partial-failure tests pass. | — |
| FB-507 | NOT_STARTED | User/manual | Complete MAN-006 deployment of the reviewed development cleanup backend when required. | FB-503 | MAN-006 is `PASS` or explicitly `WAIVED`; deployment/version evidence recorded. | — |
| FB-504 | NOT_STARTED | Developer/implementation | Test deployed development cleanup and remove client dashboard purge only after backend proof. | FB-507 | Sanitized end-to-end evidence; client tests pass. | — |
| FB-505 | NOT_STARTED | Orchestrator/gate | Close Phase 5. | FB-504 | No accumulation in test scenario; phase exit approved and handed off. | — |

### P6 — Hardened Rules, indexes, and emulator security tests

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-601 | NOT_STARTED | Developer/implementation | Harden Firestore Rules for ownership, fields, types/ranges, counters, and immutable ownership. | FB-208 | Allowed/denied emulator matrix, including malicious counter and cross-user writes. | — |
| FB-602 | NOT_STARTED | Developer/implementation | Harden Storage Rules for ownership, image content type, size, and paths. | FB-308 | Allowed/denied emulator matrix passes. | — |
| FB-603 | NOT_STARTED | Developer/implementation | Add indexes from actual query shapes and validate against local/emulator queries. | FB-601 | Index config checked in; active emulator queries pass without missing-index errors. | — |
| FB-608 | NOT_STARTED | User/manual | Complete MAN-007 deployment of reviewed development Rules and indexes when required. | FB-602, FB-603 | MAN-007 is `PASS` or explicitly `WAIVED`; deployment/version evidence recorded. | — |
| FB-604 | NOT_STARTED | Developer/implementation | Run the full deployed-development cross-user document/photo security suite. | FB-608 | Sanitized denied-access and allowed-owner results. | — |
| FB-605 | NOT_STARTED | Orchestrator/gate | Close Phase 6. | FB-604 | Security exit criterion approved and handed off. | — |

### P7 — Cutover and Room removal

| ID | Status | Owner/kind | Task | Depends on | Acceptance evidence | Evidence/result |
|---|---|---|---|---|---|---|
| FB-701 | NOT_STARTED | Developer/implementation | Run Firebase-vs-Room parity matrix under development flag on Android and iOS, including same-account realtime. | FB-406, FB-505, FB-605 | Full parity matrix passes; rollback point recorded. | — |
| FB-702 | NOT_STARTED | Developer/implementation | Switch production-path bindings to Firebase while retaining a rollback commit. | FB-701 | Debug/release and iOS builds/tests pass with Firebase path. | — |
| FB-703 | NOT_STARTED | Developer/implementation | Remove Room entities/DAOs/database/repos/plugins/dependencies/KSP/schema/builders. | FB-702 | Clean builds and package/dependency inspection prove Room/SQLite absent. | — |
| FB-704 | NOT_STARTED | Developer/implementation | Remove obsolete local photo storage/path decoding and stale references. | FB-703 | Repository search and two-platform photo regression checks pass. | — |
| FB-705 | NOT_STARTED | Developer/implementation | Update README for auth, Firebase setup/emulators, offline behavior, operations, privacy, and clean-cut reinstall. | FB-704 | Reviewer confirms a fresh developer can follow setup without secrets. | — |
| FB-706 | NOT_STARTED | Developer/implementation | Run final unit/emulator/security/manual suites on Android debug/release, iOS simulator, and iOS device. | FB-705 | Final verification matrix passes. | — |
| FB-707 | NOT_STARTED | User/manual | Complete MAN-005 production provisioning/deployment, budget alerts, and backup/export approval. | FB-706 | MAN-005 is `PASS` or explicitly `WAIVED`; user-approved production readiness evidence. | — |
| FB-708 | NOT_STARTED | Orchestrator/gate | Run final production smoke and close migration. | FB-707 | Definition of done checked, reviewer approved, handoff finalized, rollback documented. | — |

## Phase gates

| Phase | Status | Required tasks | Manual gate | Exit evidence |
|---|---|---|---|---|
| P0 | OPEN | FB-000…FB-011 | MAN-001, MAN-002, MAN-003 as referenced | — |
| P1 | NOT_STARTED | FB-101…FB-106 | Provider-specific setup from DEC-001 | — |
| P2 | NOT_STARTED | FB-201…FB-208 | — | — |
| P3 | NOT_STARTED | FB-301…FB-308 | Two-platform/reinstall checks | — |
| P4 | NOT_STARTED | FB-401…FB-406 | Offline/interruption matrix | — |
| P5 | NOT_STARTED | FB-501…FB-507 | MAN-004, MAN-006 | — |
| P6 | NOT_STARTED | FB-601, FB-602, FB-603, FB-608, FB-604, FB-605 | MAN-007 | — |
| P7 | NOT_STARTED | FB-701…FB-708 | MAN-005 and final device smoke | — |

## Decision log

Append resolved or superseded decisions; never erase history.

| Date | ID | Status | Decision and rationale | Approved by |
|---|---|---|---|---|
| 2026-09-16 | PLAN-001 | ACCEPTED | Use a sequential orchestrator → developer → reviewer → handoff loop with one active implementation task and a canonical tracker. | User request |
| 2026-09-16 | PLAN-002 | ACCEPTED | Establish owner-only baseline Rules in Phase 0; Phase 6 hardens schema validation and completes the security matrix. | Workflow review |

## Handoff log

Append concise entries only. Resume Here and the task ledger remain authoritative.

### 2026-09-16 — Workflow initialization

- From/to: planning session → next orchestrator
- Outcome: Converted the architecture plan into stable tasks with role ownership, status lifecycle, dependencies, evidence requirements, manual gates, and phase gates.
- Current task: `FB-000` (`READY`).
- Files changed: `FIREBASE_MIGRATION_PLAN.md`, `FIREBASE_MIGRATION_WORKFLOW.md`, `FIREBASE_MIGRATION_STATUS.md`, agent configuration files, `AGENTS.md`, and `CLAUDE.md`.
- Open issues: DEC-001 through DEC-003 remain intentionally unresolved until baseline completion and user decision.
- Next action: Execute `FB-000`; do not begin Firebase integration yet.

### 2026-09-16 — Final workflow handoff

- From/to: workflow reviewer → handoff → next orchestrator.
- Outcome: Reviewer verdict `APPROVED`; no outstanding blocking findings. Plan, workflow, status ledger, and Codex/Claude role configuration are ready for iterative execution.
- Current task/state: `FB-000` (`READY`); no task is `IN_PROGRESS`.
- Completed: Workflow setup and review only. No Firebase migration implementation or product baseline verification has run.
- Remaining: Execute `FB-000` and record its commands, identifiers, integration map, known failures, and git state before selecting any decision or Firebase integration task.
- Commands/results: Read the plan, workflow, status, repository instructions, and agent configurations; reconciled `git status --short --branch`, branch, and commit. Documentation review is approved; product checks were intentionally not run.
- Changed/untracked files: `.claude/`, `.codex/`, `AGENTS.md`, `CLAUDE.md`, `FIREBASE_MIGRATION_PLAN.md`, `FIREBASE_MIGRATION_STATUS.md`, and `FIREBASE_MIGRATION_WORKFLOW.md` are untracked.
- Workspace: Dirty on `epic/firebase` at `aa8d0e44219979277dfa5716ed30e3ff918bcae1`.
- Open reviewer findings: None.
- Decisions/blockers: DEC-001 through DEC-003 remain open but do not block `FB-000`; there are no active blockers.
- User action required: None for `FB-000`.
- Exact next action: The orchestrator changes `FB-000` to `IN_PROGRESS`, sets `active_task: FB-000`, and assigns it to `firebase_developer`; the developer inventories `README.md`, Gradle tasks, Xcode configuration, repository contracts, and tests, then runs and records the feasible pre-migration Android/common/iOS baselines without changing product behavior.
