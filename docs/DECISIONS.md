# FluxIt architecture decisions

This log records durable product and technical choices. Accepted decisions should
not be rewritten to disguise a change in direction; add a superseding decision
when a choice materially changes.

## ADR-001 — Share behavior, keep native UI

**Status:** Accepted · **Date:** 2026-05-09

FluxIt shares domain, data, and MVI state through Kotlin Multiplatform. Android UI
uses Jetpack Compose and iOS UI uses SwiftUI, with navigation owned by each host.
This favors native interaction and rendering at the cost of maintaining two view
implementations. Shared stores keep behavior consistent across them.

## ADR-002 — FluxIt is the canonical product name

**Status:** Accepted · **Date:** 2026-05-09

The user-facing product, repository, and application are named FluxIt. Earlier
draft names are not aliases or sub-brands.

The original package-ID clause in this decision is superseded by ADR-012.

## ADR-003 — Offline-first product scope

**Status:** Accepted · **Date:** 2026-05-09

The current product stores data on-device with SQLDelight. It has no backend,
authentication, sync queue, or networking layer. Reminders are local and photos
live in the app sandbox.

Repository contracts remain asynchronous and implementation-agnostic so future
sync can be introduced without pushing persistence concerns into UI or domain
code.

## ADR-004 — Lists and Account are the active tabs

**Status:** Accepted · **Date:** 2026-05-09

Lists and Account provide the implemented tab experiences. Calendar and Starred
remain intentional placeholders until their product flows are defined. The data
model can retain starred fields without requiring those screens to ship.

## ADR-005 — Generate platform tokens from shared JSON

**Status:** Accepted · **Date:** 2026-05-18

`core/core-designsystem/tokens/tokens.json` is the visual-token source of truth.
Gradle tasks implemented in `build-logic` generate Compose Kotlin APIs and a
SwiftUI token mirror. Generated files are build outputs and are not edited by
hand.

This keeps both native UI implementations aligned without adding a Node-based
token toolchain. If transforms become substantially more complex, a standard
design-token tool can replace the in-repository emitters.

## ADR-005a — Generate native icons from repository SVGs

**Status:** Accepted · **Date:** 2026-05-18

Curated SVGs under `core/core-designsystem/icons` are the icon source. A Gradle
generator emits memoized Compose `ImageVector` APIs and iOS asset-catalog entries
plus Swift accessors. Only icons used by the product are included, avoiding a
large font dependency and platform drift.

## ADR-005b — Dark-only theme

**Status:** Accepted · **Date:** 2026-05-20

FluxIt currently ships a fixed dark theme because the product design is specified
and validated in dark mode. The token structure reserves a future light namespace,
but light values and system-theme following require a deliberate design and
accessibility pass before they are enabled.

## ADR-006 — SQLDelight migration discipline

**Status:** Accepted · **Date:** 2026-05-28

The SQLDelight schema uses explicit integer versions and forward-only migrations.
Once a database version has been distributed, it is immutable; changes require a
new migration. `shared/data/schema.sql` is the committed schema snapshot and
verification tasks detect drift.

Before the first distributed build, version 1 may be rewritten while development
data is disposable. After distribution, migrations must preserve existing data
and be covered by migration tests.

## ADR-006a — UUID strings for entity IDs

**Status:** Accepted · **Date:** 2026-05-28

All persisted entities use opaque UUID v4 strings stored in `TEXT` primary keys.
IDs are generated through an injected seam rather than timestamps, randomness at
call sites, or database row IDs. Sorting is expressed through timestamps and
fractional sort-order fields, never ID ordering.

## ADR-006b — Soft deletes and tombstones

**Status:** Accepted · **Date:** 2026-05-28

Persisted entities use nullable `deleted_at` timestamps. Normal reads and
aggregations exclude tombstones. List deletion cascades through related items and
reminders at the application layer so repository behavior stays explicit and
testable.

Photos have a separate janitor that permanently removes orphaned files and rows
after the retention window. Other tombstones remain available for eventual sync
or restoration policy.

## ADR-007 — Typed `Outcome` results

**Status:** Accepted · **Date:** 2026-05-28

Repository and use-case failures use the in-house sealed type
`Outcome<T, E>` with `Ok` and `Err` variants. It provides exhaustive typed errors
across Kotlin and Swift without Arrow or the restrictions of `kotlin.Result`.
Error mapping between data, domain, and presentation layers remains explicit.

## ADR-007a — Domain owns visual identity enums

**Status:** Accepted · **Date:** 2026-05-28 · **Supersedes:** ADR-006c

`ColorToken` and `FluxItIconRef` are product-level identities owned by
`:shared:domain`. The design system maps them to platform visuals, and SQLDelight
adapters persist them. Domain therefore does not depend on the design system, and
database rows avoid untyped visual strings.

## ADR-007b — One class per use case

**Status:** Accepted · **Date:** 2026-05-28

Each use case is a small constructor-injected class with a single
`operator fun invoke`. Use cases are grouped by capability and orchestrate ports,
repositories, validation, analytics, and scheduling without becoming broad
interactors. Pure reusable calculations live under `domain/rule`.

## ADR-008 — Inject platform capabilities

**Status:** Accepted · **Date:** 2026-06-01

Platform capabilities are domain-facing interfaces with Android and iOS
implementations bound through Koin. `expect`/`actual` is reserved for the narrow
factory or bootstrap points needed to create those implementations. This makes
capabilities replaceable in tests and keeps platform APIs out of shared business
logic.

## ADR-012 — Canonical package namespace

**Status:** Accepted · **Date:** 2026-05-13

`dev.franzueto.fluxit` is the canonical reverse-DNS base for Android namespaces,
the Android application ID, the iOS bundle ID, and Kotlin packages. It is anchored
to an owned domain and supersedes the provisional IDs in ADR-002.

## ADR-013 — Platform minimums

**Status:** Accepted · **Date:** 2026-05-14

Android uses `minSdk = 26`; iOS uses deployment target 16.0. Compile and target SDK
versions remain catalog-managed build inputs and may advance independently of the
minimum supported Android version.

## ADR-014 — Shared MVI store contract

**Status:** Accepted · **Date:** 2026-05-29

Shared presentation state uses `Store<S, I, E>` with renderable `StateFlow` state,
non-replayed one-shot effects, and serialized intent dispatch through `BaseStore`.
Optimistic mutation with repository reconciliation is the default for reversible
actions; navigation and irreversible operations may use pessimistic flows.

Stores own behavior, while native UI owns rendering and platform presentation.
Logging is injected, error-to-message mapping stays in the state layer, and store
tests run with controlled scopes and clocks.

## ADR-015 — Composition root in shared state

**Status:** Accepted · **Date:** 2026-05-30

`:shared:state` owns the Koin composition graph because it is the shared boundary
consumed by both application hosts. It aggregates data, domain, state, and
platform modules while keeping concrete database and capability imports confined
to the `di` package. Android and iOS provide their platform bootstrap inputs at
their respective composition roots.

## ADR-016 — WorkManager for Android reminders

**Status:** Accepted · **Date:** 2026-06-01

Android reminders use WorkManager and accept best-effort timing. The product does
not require exact alarms strongly enough to justify special permissions and Play
policy exposure. iOS uses `UNUserNotificationCenter` behind the same domain port.

## ADR-017 — Platform-specific backup policy

**Status:** Accepted · **Date:** 2026-06-01

Android auto-backup excludes the SQLDelight database and photo directory because
there is no server reconciliation path. iOS keeps the default iCloud container
behavior, allowing a normal device restore. This asymmetry is deliberate and must
be revisited if real sync is introduced.

## ADR-018 — Repository is named and licensed separately from the product

**Status:** Accepted · **Date:** 2026-08-04

Supersedes the repository-naming clause of ADR-002. ADR-002 still holds for the
application and the user-facing product, which remain FluxIt.

This repository is renamed to `kmp-architecture-reference` and its source code is
relicensed from All Rights Reserved to Apache-2.0. A code license and brand
protection are independent axes: licensing this code permissively does not
license the commercial product's code, and Apache-2.0 §6 withholds trademark
rights explicitly, which is why it is preferred over MIT here.

The lookalike risk — a UI clone shipped under the same name — is addressed by
naming the repository descriptively and reserving the brand assets and the
`design/` mockups in `LICENSE-ASSETS`, rather than by restricting the code.

Package namespaces, `applicationId`, the iOS bundle identifier, and `FluxIt*`
type prefixes are deliberately unchanged. They are product identifiers, not
repository identifiers, and changing a published `applicationId` or bundle ID has
consequences beyond this repository.
