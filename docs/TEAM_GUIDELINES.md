# FluxIt — Team Guidelines

> **Placeholder.** Filled in alongside Phase 10 (CI workflows) and Phase 15
> (CI/CD), once branch protection and the PR template are in place. Until
> then, treat the conventions below as in-effect-from-day-one defaults.

**Status:** placeholder · last touched 2026-05-15 (Phase 01 §9).

---

## In effect today (interim defaults)

### Commit messages — Conventional Commits

Format: `type(scope): subject`.

- **Type:** `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `style`, `perf`.
- **Scope:** module path or area (e.g. `android-app`, `quality`, `arch`,
  `adr`, `plan`). Use lowercase. For an ADR commit, scope is `adr`; for a
  plan-file edit, scope is `plan`.
- **Subject:** imperative, lowercase, ≤ 72 chars, no trailing period.
- **Body** (optional, separated by a blank line): the *why* — link to the
  phase checkbox or ADR. Wrap at 72 chars.
- **Granularity:** one logical change per commit. Don't batch unrelated
  work; don't queue tech debt.

Examples from the current history:

```
docs(adr): accept ADR-013 locking Android minSdk=26 + iOS 16
build(quality): wire Spotless for Kotlin/KTS via ktlint, Markdown at root
test(arch): enforce domain/feature/coroutine rules via Konsist
feat(android-app): scaffold Compose shell with Koin init
```

### Branching

- **One long-lived feature branch per phase**, named
  `phase/<NN>-<slug>` (e.g. `phase/02-design-system`,
  `phase/03-data-layer`). Opened at phase start, kept current with
  `main` via merge or rebase, merged in a single PR at the phase's
  hand-off gate. Default model until superseded by anticipated
  ADR-011 (Phase 15).
- **Rationale:** GitHub Actions minutes are a constrained resource on
  the current plan. Batching a whole phase into one PR keeps CI to
  one ramp-up run + a final review run per phase, instead of one run
  per logical commit. Commits stay granular on the branch (still
  Conventional Commits per logical change); only the *merge cadence*
  changes.
- **Exceptions** (get their own short-lived branch + PR): Dependabot
  bumps, security hotfixes, urgent production fixes, repo-level
  chores that block all phases (e.g. CI workflow repair). These
  branches are named `<type>/<slug>` as before
  (e.g. `fix/ci-macos-runner`, `chore/p01-handoff`).
- No long-running release branches in v1 (single trunk → store).

### Pull requests

- **One PR per phase** (the merge of its `phase/<NN>-<slug>` branch),
  except for the exception classes listed under Branching. PRs
  routinely run >400 lines because they bundle a whole phase — review
  is structured around the phase's checklist in `plan/NN_*.md`, not
  the raw diff.
- PR template (added in Phase 01 §10) covers: Summary, Linked
  phase/ADR, Screenshots (mobile), Test plan, Risk.
- Required gates per PR: `spotlessCheck`, `ktlintCheck`, `detekt`,
  `:build-logic:test --rerun-tasks` (Konsist), `assembleDebug`,
  `scripts/build-ios.sh`. A red gate blocks merge.
- Direct pushes to `main` are off once branch protection is enforced
  in GitHub (currently documented but not yet active — see
  [`../README.md`](../README.md) "Branch protection on `main`").

### Code review

- **SLA target:** first response within one working day for a PR ≤ 400
  lines; same-day for ≤ 100 lines or a hotfix.
- **Author responsibility:** PR description must let the reviewer
  reproduce the change without reading the diff cold. Always link the
  phase checkbox or ADR being satisfied.
- **Reviewer responsibility:** explicitly approve, request changes, or
  comment-only. Don't leave a PR in limbo.

### Pre-commit hook

`scripts/install-hooks.sh` wires `.githooks/pre-commit` (opt-in). The
hook runs `spotlessApply` + `ktlintFormat` on staged Kotlin / KTS /
Markdown files and re-stages them. It does **not** run Konsist (slow,
and a Konsist break is caught in CI via `--rerun-tasks`).

---

## TODO (target: Phase 15)

- [ ] **Code review checklist.** Concrete bullet list to paste into a PR
      review (architecture / tests / accessibility / perf).
- [ ] **Definition of Done per change type.** What "done" means for a
      feature PR vs. a bug-fix PR vs. a refactor PR.
- [ ] **Hotfix process.** Branching, fast-track review, release-tag
      workflow when the trunk is mid-feature.
- [ ] **Release cadence.** Internal-track cadence; criteria to promote
      from internal → closed → open testing.
- [ ] **On-call / escalation.** Who pages whom when a Crashlytics spike
      crosses a threshold.
- [ ] **Coding style notes** beyond ktlint/detekt: naming for use cases,
      stores, intents/effects; file-per-class vs. grouped exceptions.
- [ ] **AI-assisted dev.** Conventions for Claude/agent commits
      (Co-Authored-By trailer; what to disclose in PR body).

---

## Cross-references

- Architecture: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).
- ADRs: [`docs/DECISIONS.md`](DECISIONS.md) → [`/plan/00_DECISIONS.md`](../plan/00_DECISIONS.md).
- Active phase: [`/plan/01_INITIAL_SETUP.md`](../plan/01_INITIAL_SETUP.md).
- Roadmap: [`MASTER_PLAN.md`](../MASTER_PLAN.md).
