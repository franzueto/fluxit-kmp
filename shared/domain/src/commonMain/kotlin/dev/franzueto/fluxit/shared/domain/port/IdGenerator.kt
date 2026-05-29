package dev.franzueto.fluxit.shared.domain.port

/**
 * Domain port for entity-id minting (Phase 04 §5). Physically lives in
 * `:core:core-utils` (per ADR-006a — placed there so both `:shared:domain`
 * and `:shared:data` can depend on it without a cycle); re-exported here
 * as a typealias so use-case call sites import from the domain's
 * `port` package and the §5 surface stays coherent.
 *
 * Production: `IdGenerator.System` (the companion-default impl in core-
 * utils, delegating to the platform's UUID v4 generator).
 * Tests: inject a `FakeIdGenerator` returning UUID-v4-shaped strings
 * (introduced as a reusable fixture by the slice that first needs it).
 */
public typealias IdGenerator = dev.franzueto.fluxit.core.utils.IdGenerator
