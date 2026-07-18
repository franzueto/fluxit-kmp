package dev.franzueto.fluxit.shared.domain.port

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Reminder
import kotlin.jvm.JvmInline

/**
 * Opaque platform-level schedule identifier — the WorkManager request id
 * (Android) or `UNNotificationRequest` identifier (iOS). The domain never
 * interprets it; it round-trips through
 * [dev.franzueto.fluxit.shared.domain.repository.RemindersRepository.rebindPlatformHandle]
 * so a later cancel can address the OS-level schedule.
 */
@JvmInline
public value class PlatformHandle(
    public val raw: String,
)

public sealed class SchedulerError {
    /** The OS denied the notifications/exact-alarm permission. UI should prompt + retry. */
    public data object PermissionDenied : SchedulerError()

    /** The OS rejected the request transiently (e.g. exact-alarm quota). Retryable. */
    public data object SystemBusy : SchedulerError()

    public data class Unknown(
        val cause: Throwable?,
    ) : SchedulerError()
}

/**
 * Returns [Outcome] (not `kotlin.Result`) per ADR-007 — the failure channel
 * carries a typed [SchedulerError] the use case can pattern-match.
 */
public interface ReminderScheduler {
    /** Arm an OS-level schedule for [reminder]; returns its [PlatformHandle]. */
    public suspend fun schedule(reminder: Reminder): Outcome<PlatformHandle, SchedulerError>

    /** Disarm the OS-level schedule addressed by [handle]. Idempotent. */
    public suspend fun cancel(handle: PlatformHandle): Outcome<Unit, SchedulerError>

    /**
     * Repopulate the OS-level schedule for every reminder in [active] — run on
     * app start / boot-completed (Android) and cold-start (iOS) so reminders
     * survive a reboot or process death.
     */
    public suspend fun rescheduleAll(active: List<Reminder>): Outcome<Unit, SchedulerError>
}
