# FluxIt team guidelines

These are lightweight working agreements for keeping the repository reviewable.
They can evolve with the team; they are not a release roadmap.

## Branches

- Create a short-lived branch for each focused change.
- Use a descriptive `<type>/<slug>` name such as `feat/reminder-editor`,
  `fix/photo-cleanup`, or `docs/architecture-refresh`.
- Keep the branch current with `main` and remove it after merge.
- Do not push directly to protected `main`.

## Commits

Use Conventional Commits:

```text
type(scope): imperative subject
```

Common types are `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`,
`chore`, `style`, and `perf`. Use a module or capability as the scope when it adds
clarity. Keep the subject concise and explain the reason or tradeoff in the body
when it is not obvious from the diff.

Keep commits logically focused. Generated build outputs should not be committed;
commit their token, icon, schema, or project-definition sources instead.

## Pull requests

A pull request should give a reviewer enough context to validate the change
without reconstructing the author's thought process.

- Explain what changed and why.
- Link the relevant issue or ADR when one exists.
- Include Android and iOS screenshots for visible UI changes.
- List the commands and manual scenarios actually verified.
- Call out migrations, compatibility concerns, or rollback constraints.
- Keep unrelated cleanup out of a behavior change.

## Review checklist

- The change respects the dependency direction in `docs/ARCHITECTURE.md`.
- Shared behavior is tested below the native UI where practical.
- Android and iOS still interpret shared state and effects consistently.
- New visual values come from the design system.
- Database changes include the required schema snapshot and migration coverage.
- Errors, loading states, accessibility labels, and destructive actions are
  handled deliberately.
- Comments explain non-obvious constraints or tradeoffs rather than narrating the
  implementation history.

## Verification

Run checks proportional to the change. The broad local suite is:

```bash
./gradlew check
./gradlew :build-logic:test --rerun-tasks
scripts/test-ios.sh
```

For Android-only iteration, prefer the narrowest affected module checks before a
final application build. For shared API or state changes, verify both the JVM and
iOS consumers.

## Pre-commit hook

`scripts/install-hooks.sh` installs the optional repository hook. It formats
staged Kotlin, Kotlin DSL, and Markdown and re-stages those files. Architecture
tests remain an explicit local or CI check because they scan the repository and
must be forced to rerun.

## Documentation

- `README.md` explains the product, current scope, setup, and repository map.
- `docs/ARCHITECTURE.md` describes the current module graph and boundaries.
- `docs/DECISIONS.md` records durable product and architecture rationale.
- `DESIGN.md` and `core/core-designsystem` define the visual system.

Update documentation when the current behavior or architecture changes. Do not
keep implementation diaries or speculative delivery checklists in the product
repository.
