package dev.franzueto.fluxit.shared.domain.rule

/**
 * Pure rule helper for the "completed / total" rollup that backs the
 * dashboard progress bar and the list-detail `13/20` counter (Phase 04
 * §8). Centralised so Phase 08's UI doesn't reinvent the formula
 * (and doesn't accidentally diverge on the "what does 0/0 look like?"
 * edge case across two surfaces).
 */
public object CompletionCalculator {
    /**
     * Fraction in `[0f, 1f]`. Empty lists return `0f` (no items
     * means no progress, not "fully done").
     */
    public fun fraction(
        total: Int,
        completed: Int,
    ): Float {
        require(total >= 0) { "total must be non-negative: $total" }
        require(completed in 0..total) {
            "completed must be in [0, total]: completed=$completed, total=$total"
        }
        return if (total == 0) 0f else completed.toFloat() / total
    }

    /** Display string like `"13/20"`. Identical to `"$completed/$total"` — exists as a named call so UI code is greppable. */
    public fun display(
        total: Int,
        completed: Int,
    ): String {
        require(total >= 0) { "total must be non-negative: $total" }
        require(completed in 0..total) {
            "completed must be in [0, total]: completed=$completed, total=$total"
        }
        return "$completed/$total"
    }

    /**
     * `true` only when there's at least one item AND all of them are
     * done. Empty lists are NOT "fully complete" — matches dashboard
     * intent where a 0/0 list shouldn't render the completion
     * indicator.
     */
    public fun isFullyComplete(
        total: Int,
        completed: Int,
    ): Boolean {
        require(total >= 0) { "total must be non-negative: $total" }
        require(completed in 0..total) {
            "completed must be in [0, total]: completed=$completed, total=$total"
        }
        return total > 0 && completed == total
    }
}
