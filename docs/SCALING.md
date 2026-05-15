# FluxIt — Scaling Notes

> **Placeholder.** Filled in during Phase 17 (Release Hardening). Until then
> this file is a TODO list so we don't lose the questions worth answering
> when the team and the codebase grow past one engineer.

**Status:** placeholder · last touched 2026-05-15 (Phase 01 §9).

---

## TODO (target: Phase 17)

- [ ] **Module ownership matrix.** Map each module path to a CODEOWNERS
      entry (or rely solely on `.github/CODEOWNERS` and link here). Decide
      whether `:platform:*` is owned by mobile-platform or by the
      capability owners (e.g. notifications team owns `:platform:platform-reminders`).
- [ ] **When to split a feature module.** Heuristic for when a `feature-*`
      module is too big — file count, store complexity, screen count, or
      external integrations. Default proposal: split when a single feature
      contains more than two MVI stores.
- [ ] **Version catalog stewardship.** Who owns `gradle/libs.versions.toml`
      bumps? Process for upgrading Kotlin / AGP / SQLDelight (compatibility
      matrix; canary branch first; rollback criteria).
- [ ] **Build performance budget.** Configuration-cache hit rate, clean
      build time on M-series Mac, incremental build time. Triggers for a
      build-perf investigation.
- [ ] **Test runtime budget.** Per-tier runtime ceilings (unit / integration
      / instrumentation) and what we cut when a tier blows its budget.
- [ ] **Dependency rule expansion.** New Konsist rules to add as the graph
      grows: e.g. forbid `:shared:data` from importing `:platform:platform-analytics`
      directly (must go through a logging port).
- [ ] **Feature-flag lifecycle.** Process for retiring stale flags from
      `:platform:platform-config`. Cite anticipated ADR-008 for the
      port-vs-injection model.
- [ ] **Onboarding budget.** Time-to-first-PR for a new mobile engineer.
      What we add to `README.md` / `docs/TEAM_GUIDELINES.md` when this
      exceeds a half-day.

---

## Cross-references

- Module shape today: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).
- Active phase: [`/plan/01_INITIAL_SETUP.md`](../plan/01_INITIAL_SETUP.md).
- Roadmap & milestones: [`MASTER_PLAN.md`](../MASTER_PLAN.md).
