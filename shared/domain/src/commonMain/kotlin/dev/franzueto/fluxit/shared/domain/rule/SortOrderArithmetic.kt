package dev.franzueto.fluxit.shared.domain.rule

/**
 * Constants match `SqlListsRepository`'s companion verbatim — if these
 * ever diverge, the data layer's behavior is the source of truth and
 * this helper must be re-aligned (and the data layer migrated to
 * consume it).
 */
public object SortOrderArithmetic {
    /** First row in an empty list gets this value (matches `SqlListsRepository.SEED_SORT_ORDER`). */
    public const val SEED_SORT_ORDER: Double = 1.0

    /**
     * Bracket gap below which a row insert requires a global
     * compaction (matches `SqlListsRepository.REBALANCE_EPSILON`).
     * Picked to stay above `Double`'s representable precision at the
     * sort_order values FluxIt sees at realistic scale.
     */
    public const val REBALANCE_EPSILON: Double = 1e-9

    /**
     * Compute the new sort order between two existing brackets.
     *
     * - `(null, null)` — empty list. Returns [SEED_SORT_ORDER].
     * - `(null, next)` — insert at the head. Returns `next - 1.0`.
     * - `(prev, null)` — insert at the tail. Returns `prev + 1.0`.
     * - `(prev, next)` — midpoint of the bracket. Caller is responsible
     *   for checking [needsCompaction] separately if the bracket is
     *   tight; this function always returns the midpoint regardless of
     *   bracket width.
     */
    public fun between(
        prev: Double?,
        next: Double?,
    ): Double =
        when {
            prev == null && next == null -> SEED_SORT_ORDER
            prev == null -> next!! - 1.0
            next == null -> prev + 1.0
            else -> (prev + next) / 2.0
        }

    /**
     * `true` when at least one consecutive gap in [orders] is below
     * [REBALANCE_EPSILON]. Caller-supplied list MUST be sorted
     * ascending; this function does not sort defensively.
     *
     * Empty / single-element lists always return `false` (no gaps to
     * collapse).
     */
    public fun needsCompaction(orders: List<Double>): Boolean {
        if (orders.size < 2) return false
        for (i in 1 until orders.size) {
            if ((orders[i] - orders[i - 1]) < REBALANCE_EPSILON) return true
        }
        return false
    }
}
