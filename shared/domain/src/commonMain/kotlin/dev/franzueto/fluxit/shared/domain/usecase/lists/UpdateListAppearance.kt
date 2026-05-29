package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.rule.PaletteCatalog

/**
 * Change a list's icon + color (Phase 04 §7). Validates both against
 * [PaletteCatalog] (the single source of truth for pickable swatches /
 * chips) before persisting, then lifts repository failures via the
 * standard `toDomain(entity = "List")` seam.
 *
 * **Forward-looking guard, not a reachable v1 failure.** `icon` and
 * `color` are enums (`FluxItIconRef` / `ColorToken`), and the v1
 * `PaletteCatalog` wraps the *full* `.entries`, so every well-typed
 * argument is already in the catalog — the membership check can't fail
 * today. It's written anyway because the catalog's own KDoc anticipates
 * a v2 where it surfaces a *subset* (per-tier picker, A/B test): the
 * moment the catalog narrows, this guard starts rejecting out-of-catalog
 * values as [ValidationError.InvalidFormat] without a code change here.
 * Treat the `color`/`icon` Validation branches as defensive seams, not
 * paths v1 UI can exercise.
 */
public class UpdateListAppearance(
    private val lists: ListsRepository,
) {
    public suspend operator fun invoke(
        id: ListId,
        icon: FluxItIconRef,
        color: ColorToken,
    ): Outcome<Unit, DomainError> {
        if (icon !in PaletteCatalog.icons) {
            return Outcome.Err(DomainError.Validation(field = "icon", rule = ValidationError.InvalidFormat))
        }
        if (color !in PaletteCatalog.colors) {
            return Outcome.Err(DomainError.Validation(field = "color", rule = ValidationError.InvalidFormat))
        }
        return lists.updateAppearance(id, icon, color).mapError { it.toDomain(entity = "List") }
    }
}
