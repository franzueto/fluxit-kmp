package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.CapturedPhoto
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.PhotoCapture
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import dev.franzueto.fluxit.shared.domain.port.PlatformHandle
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

// INTERIM — replaced by :platform:* in Phase 06 (ADR-015).
//
// Phase 05 closes the DI graph with real repositories + use cases but defers
// the expensive native port impls (camera, file storage, OS scheduler) to
// Phase 06. These no-ops let the graph resolve and the RootStore smoke run
// (empty DB → rescheduleAll(emptyList()) → Ok) without standing up the
// platform modules. Capture/storage writes are not exercised by the smoke and
// throw if hit, so a stray dependency surfaces loudly rather than silently.

/** INTERIM — replaced by `:platform:platform-reminders` in Phase 06 (ADR-015). */
internal object NoOpReminderScheduler : ReminderScheduler {
    override suspend fun schedule(reminder: Reminder): Outcome<PlatformHandle, SchedulerError> = Outcome.Ok(PlatformHandle("interim"))

    override suspend fun cancel(handle: PlatformHandle): Outcome<Unit, SchedulerError> = Outcome.Ok(Unit)

    override suspend fun rescheduleAll(active: List<Reminder>): Outcome<Unit, SchedulerError> = Outcome.Ok(Unit)
}

/** INTERIM — replaced by `:platform:platform-photo` in Phase 06 (ADR-015). */
internal object NoOpPhotoCapture : PhotoCapture {
    override suspend fun capture(): Outcome<CapturedPhoto, CaptureError> = error("interim — Phase 06")

    override suspend fun pickFromLibrary(): Outcome<CapturedPhoto, CaptureError> = error("interim — Phase 06")
}

/** INTERIM — replaced by `:platform:platform-photo` in Phase 06 (ADR-015). */
internal object NoOpPhotoStorage : PhotoStorage {
    override suspend fun write(
        bytes: ByteArray,
        mime: String,
    ): String = error("interim — Phase 06")

    override suspend fun read(relativePath: String): ByteArray? = null

    override suspend fun delete(relativePath: String): Boolean = false

    override fun resolveAbsolute(relativePath: String): String = relativePath
}

/**
 * INTERIM platform port bindings (ADR-015). `Clock`/`IdGenerator`/`AppLogger`
 * have real, cheap bindings; the camera/storage/scheduler ports are no-ops
 * replaced by `:platform:*` in Phase 06.
 *
 * The `CoroutineScope` is a `factory`: every store gets a fresh
 * `SupervisorJob`-backed scope so one store's failure can't cancel another's
 * (the `RootStore` `single` captures one such scope for the app session).
 */
public val platformModule: Module =
    module {
        single<AppLogger> { AppLogger.NoOp }
        single<Clock> { Clock.System }
        // No IdGenerator binding: the Sql repositories default `ids` to
        // IdGenerator.System (core-utils, not on :shared:state's classpath) and
        // no use case requests it via get(), so the graph never needs one.
        single<ReminderScheduler> { NoOpReminderScheduler }
        single<PhotoCapture> { NoOpPhotoCapture }
        single<PhotoStorage> { NoOpPhotoStorage }
        factory<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    }
