package dev.franzueto.fluxit.shared.domain.usecase.app

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.usecase.reminders.RehydrateReminders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Progress emitted by [InitializeApp] so a splash / state layer can show
 * startup progress — or just collect to completion silently in v1.
 */
public sealed interface InitProgress {
    public data object Started : InitProgress

    public data object RemindersRehydrated : InitProgress

    public data object Completed : InitProgress

    public data class Failed(
        val error: DomainError,
    ) : InitProgress
}

/**
 * Composite startup use case (Phase 04 §7), run once on app launch: rehydrate
 * the OS-level reminder schedule, then (eventually) sweep orphaned photos.
 * Emits a [Flow] of [InitProgress] so the caller can render a splash or just
 * await [InitProgress.Completed].
 *
 * **Spec/reality reconciliation:** the §7 row composed `RehydrateReminders`
 * **+ `PhotoJanitor`**, but the batch photo sweep is deferred — the shipped
 * `PhotosRepository` has no `selectOrphaned` enumeration to feed a startup
 * GC pass (only `PhotoJanitor`'s per-photo form ships, driven by
 * `DetachPhotoFromItem`). So this composite currently runs the reminder
 * rehydration only; the janitor step lands here once the data layer surfaces
 * `selectOrphaned`. A rehydration failure terminates the flow with
 * [InitProgress.Failed] (startup continues — the state layer decides whether
 * to surface it).
 */
public class InitializeApp(
    private val rehydrateReminders: RehydrateReminders,
) {
    public operator fun invoke(): Flow<InitProgress> =
        flow {
            emit(InitProgress.Started)
            when (val rehydrated = rehydrateReminders()) {
                is Outcome.Err -> {
                    emit(InitProgress.Failed(rehydrated.error))
                    return@flow
                }
                is Outcome.Ok -> emit(InitProgress.RemindersRehydrated)
            }
            // PhotoJanitor batch sweep deferred — no `selectOrphaned` primitive yet.
            emit(InitProgress.Completed)
        }
}
