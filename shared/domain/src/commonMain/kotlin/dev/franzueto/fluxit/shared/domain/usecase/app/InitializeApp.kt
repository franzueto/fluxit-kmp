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
