# Project Agent Instructions

For any work governed by `FIREBASE_MIGRATION_PLAN.md`, the main session is the orchestrator and must follow `FIREBASE_MIGRATION_WORKFLOW.md`.

Before acting, read `FIREBASE_MIGRATION_STATUS.md` in full, then the relevant plan section, latest handoff entry, and current git state. Treat the status file—not chat history—as canonical.

Use exactly these project roles:

- `firebase_developer` for implementation and verification as a senior Android/Kotlin Multiplatform engineer.
- `firebase_reviewer` for read-only review and approval/change requests.
- `firebase_handoff` for status/resume reconciliation after review.

Run the roles sequentially for each implementation task. Keep at most one task `IN_PROGRESS`; do not let the developer self-approve; do not mark manual work complete without user evidence. The orchestrator alone selects tasks, changes canonical task status, and asks the user for decisions or Firebase/Xcode/backend actions.

Preserve existing user changes. Do not commit Firebase service-account keys, tokens, passwords, signing secrets, or user data. Development must not use permissive Firebase Rules.

For unrelated repository work, these Firebase-specific delegation and tracking rules do not apply unless the user asks to use them.
