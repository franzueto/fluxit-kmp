# FluxIt — Architecture Decision Records (ADR Log)

> Living log. One section per decision. **Do not edit accepted ADRs in place** — supersede them with a new entry that references the old one.
>
> **Split threshold:** when this file passes ~15 ADRs *or* an ADR needs diagrams/long appendices, migrate to `plan/adr/NNN-slug.md` per-file format and replace each section here with a one-line link.

**Format**

```
### ADR-NNN — Title
- Status: Proposed | Accepted | Superseded by ADR-XXX | Deprecated
- Date: YYYY-MM-DD
- Context: …
- Decision: …
- Consequences: …
- Alternatives considered: …
```

---

### ADR-001 — Native UI per platform; share domain/data/state only
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** Two source prompts conflict. `Architect-Prompt.md` mandates "Do NOT share UI" with Compose on Android and SwiftUI-ready iOS. `FluxIt_Architect_Prompt.md` asks me to consider Decompose/Voyager/Circuit (which are UI/navigation libraries that imply shared UI via Compose Multiplatform). FluxIt's brand emphasizes a "calming, sophisticated, dependable" feel — perceived native quality matters.
- **Decision:** Share **domain, data, and presentation state** (MVI stores) via Kotlin Multiplatform. Keep **UI native**: Jetpack Compose on Android, SwiftUI on iOS. Navigation is per-platform (Navigation Compose / `NavigationStack`).
- **Consequences:**
  - ➕ Best-in-class native feel, blur effects and platform gestures behave correctly with no CMP escape hatches.
  - ➕ Eliminates Decompose/Voyager/Circuit from v1 stack.
  - ➕ iOS team can ship without learning Compose semantics.
  - ➖ Two UI implementations to maintain. Each new screen costs Compose + SwiftUI work.
  - ➖ Mitigation: shared MVI stores keep behavior identical; design tokens generated once and mirrored to both platforms (see ADR-005 in design-system phase).
- **Alternatives considered:**
  - Compose Multiplatform everywhere — faster delivery, but glassmorphic/blur surfaces and tab-bar chrome are still rough on iOS today.
  - Hybrid (CMP + native for sensitive flows) — highest complexity, deferred until we have a proven pain point in v2.

---

### ADR-002 — Brand name is "FluxIt"
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** `DESIGN.md` frontmatter declares `name: Lumina Lists`, but the project, repo, and `FluxIt_Architect_Prompt.md` all say FluxIt. The product owner confirmed FluxIt is canonical and Lumina Lists was a draft.
- **Decision:** "FluxIt" is the user-facing product name. Update `DESIGN.md` frontmatter as part of Phase 02 (Design System). Package IDs: `com.fluxit.android` / `com.fluxit.ios`. Module prefix: `fluxit-` is **not** used in module names (modules are named by capability, e.g. `feature-lists`).
- **Consequences:**
  - ➕ One name across code, store listings, analytics events.
  - ➖ All references to "Lumina" in design assets must be swept (caption text, brand prose). Tracked in Phase 02.
- **Alternatives considered:** Two-tier branding (FluxIt platform / Lumina Lists app) — rejected as gratuitous complexity for a single-app product.

---

### ADR-003 — v1 is local-only; no backend, no auth, no networking
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** No backend has been specified. Multi-device sync, auth, and conflict resolution are large subsystems that would dominate v1 scope. The product hypothesis (high-performance list-making) can be validated on a single device.
- **Decision:** v1 ships with SQLDelight on-device only. No Ktor client, no auth screens, no sync queue. **Reminders are local notifications only** (WorkManager / `UNUserNotificationCenter`). Photos stored in app sandbox; URIs in DB.
- **Consequences:**
  - ➕ Removes Ktor, Store5, OAuth, conflict-resolution code from v1.
  - ➕ Faster path to TestFlight / Play Internal track.
  - ➖ No cross-device continuity in v1 — must be communicated in onboarding.
  - ➖ Photo loss risk if user uninstalls; document in Phase 10.
  - 🔁 Repository contracts in `domain` are written **as if** a backend exists (suspending, returning `Result`) so v2 sync can swap implementations without touching call sites.
- **Alternatives considered:** Firebase/Supabase BaaS (deferred to v2), custom backend (no spec).

---

### ADR-004 — v1 ships only "Lists" and "Account" tabs; Calendar & Starred deferred
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** The dashboard mockup shows a 4-tab bar (Lists, Calendar, Starred, Account). Only Lists has a designed flow. Calendar implies an agenda/reminder timeline subsystem; Starred implies a cross-list filter and a `starred` boolean on items/lists.
- **Decision:** Render all four tab icons in v1 to preserve design fidelity, but Calendar and Starred route to a "Coming soon" placeholder screen using the design system's empty-state pattern. Lists and Account are fully functional.
- **Consequences:**
  - ➕ Honors the visual design without committing to undesigned subsystems.
  - ➕ Cuts ~30% of feature work from v1.
  - ➖ Two dead-end tabs may be perceived as broken — placeholder copy must be intentional ("Coming in a future update").
  - 🔁 Data model still includes `is_starred` on lists/items so v2 Starred ships without a migration.
- **Alternatives considered:** Hide tabs (rejected: design-system hit), build all four (rejected: scope), Lists+Starred (rejected: marginal Starred value without Calendar).

---

## Pending / Anticipated ADRs

These are *expected* to be opened during the relevant phase. Listed here so we don't forget.

- **ADR-005** (Phase 02): Design token pipeline — single source of truth for color/type/spacing, generation strategy for Compose + SwiftUI.
- **ADR-006** (Phase 03): SQLDelight schema versioning + migration policy.
- **ADR-007** (Phase 05): MVI store contract — intents/state/effects, error model, optimistic update pattern.
- **ADR-008** (Phase 06): expect/actual vs. Koin-injected interfaces for platform capabilities (we'll likely standardize on injected interfaces).
- **ADR-009** (Phase 13): Notification permission UX — when to ask, how to recover from denial.
- **ADR-010** (Phase 14): Test pyramid shape and minimum coverage gates per layer.
- **ADR-011** (Phase 15): Branching strategy + CI matrix shape (trunk-based vs. GitFlow).
