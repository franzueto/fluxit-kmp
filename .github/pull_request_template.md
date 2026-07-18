<!--
FluxIt PR template. Keep sections; delete instructional comments.
Convention: title is "type(scope): subject" (Conventional Commits).
See docs/TEAM_GUIDELINES.md for the full convention list.
-->

## Summary

<!-- One-paragraph description of *what* changes and *why*. The reviewer
should be able to understand the change without reading the diff cold. -->

## Linked issue / ADR

<!--
Cite the issue and/or ADR this PR satisfies, e.g.:
  - Closes #123
  - ADR-013 (platform minimums)
If none applies, write "N/A — chore" and explain why this PR exists.
-->

## Screenshots (mobile)

<!-- Required for any UI change on Android or iOS. Side-by-side
preferred (Android + iOS, light + dark). Delete this section if the
PR has no UI surface. -->

## Test plan

<!-- How you verified the change. Cover both the golden path and the
edge cases you considered. Include the commands you actually ran:

  - [ ] `./gradlew spotlessCheck ktlintCheck detekt`
  - [ ] `./gradlew :build-logic:test --rerun-tasks` (Konsist)
  - [ ] `./gradlew :android-app:assembleDebug`
  - [ ] `scripts/build-ios.sh` (macOS only)
  - [ ] Manual: <describe what you exercised on device / simulator>
-->

## Risk

<!-- What could break? Who is affected if it does? Include rollback
plan if non-trivial (feature flag, revert PR, migration reversal,
runtime config). For low-risk changes write "Low — isolated to <area>". -->
