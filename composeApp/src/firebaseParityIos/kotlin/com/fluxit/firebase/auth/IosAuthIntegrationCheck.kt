package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.errorOrNull
import com.fluxit.domain.auth.uidOrNull
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID

/**
 * FB-103 emulator-backed integration check for the iOS Auth adapter.
 *
 * Why this exists in the app binary rather than as an XCTest target: this repository has
 * no Xcode test target at all, and FB-008's iOS leg already set the precedent of
 * evidencing the iOS side by console capture from a real simulator run. Adding and
 * maintaining an XCTest target (plus its own Firebase SPM linkage and signing
 * configuration) for a single suite was judged disproportionate; if the reviewer
 * disagrees, this file is the thing that would move into it, essentially unchanged.
 *
 * Safety, deliberately doubled up:
 *
 * 1. It refuses to run unless the build was configured with
 *    `-Pfluxit.firebase.emulator.enabled=true`, which is the *same* flag that makes
 *    `FirebaseBootstrap` point Auth at the local emulator. It is therefore impossible
 *    for this to create accounts in the live development project: in any build where the
 *    check can run, Auth is not talking to the live project at all.
 * 2. The app only calls it when launched with the `-FluxItAuthSelfCheck` argument.
 *
 * Every account it creates is a throwaway with a random UUID local part, in the local
 * emulator, and is signed out at the end.
 */
// FB-702: this fixture is compiled only with fluxit.parity.enabled=true.
object IosAuthIntegrationCheck {

    private const val PASSWORD = "sw0rdfish!42"
    private const val WEAK_PASSWORD = "123"
    private const val SETTLE_MS = 250L

    /**
     * Cross-process restoration check, run as two separate app launches.
     *
     * `phase = "prepare"` signs a fixed throwaway emulator account in and deliberately
     * leaves it signed in; `phase = "verify"` runs in a *later process* and asserts that
     * `restoreSession()` resolves it back to `Authenticated` from Firebase Auth's own
     * keychain-backed persistence, then signs out again. Reported uids are compared
     * across the two runs by the evidence, which is the part [run] cannot cover because
     * it lives entirely inside one process.
     */
    suspend fun runRestorationPhase(phase: String): String {
        val report = Report()
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail(
                "preconditions",
                "emulator mode is disabled; refusing to run against a live project",
            )
            return report.render()
        }
        if (IosAuthBridgeRegistry.bridgeOrNull() == null) {
            report.fail("bridge registration", "no IosAuthBridge was registered from Swift")
            return report.render()
        }
        val repository = IosAuthRepository()
        val email = "fb103-restore@example.com"

        coroutineScope {
            val emissions = mutableListOf<AuthSession>()
            val collector = launch { repository.session.toList(emissions) }
            delay(SETTLE_MS)

            when (phase) {
                "prepare" -> {
                    // The account survives between runs, so the first sign-up succeeds
                    // and later ones report EmailAlreadyInUse; both are fine.
                    repository.signUp(email, PASSWORD)
                    report.expectSuccess("prepare: signIn", repository.signIn(email, PASSWORD))
                    delay(SETTLE_MS)
                    val uid = emissions.last().uidOrNull
                    report.check("prepare: authenticated", uid != null, "session=${emissions.last()}")
                    report.note("prepare: uid=$uid (left signed in on purpose)")
                }

                "verify" -> {
                    report.expect(
                        "verify: session starts Unresolved in the new process",
                        AuthSession.Unresolved,
                        emissions.firstOrNull(),
                    )
                    repository.restoreSession()
                    delay(SETTLE_MS)
                    val uid = emissions.last().uidOrNull
                    report.check(
                        "verify: persisted credential restored after an app restart",
                        uid != null,
                        "session=${emissions.last()}",
                    )
                    report.note("verify: uid=$uid (compare with the prepare run)")
                    report.expectSuccess("verify: signOut cleanup", repository.signOut())
                }

                else -> report.fail("phase", "unknown phase '$phase'")
            }
            collector.cancel()
        }
        return report.render()
    }

    /**
     * Runs every flow FB-103 is accepted on and returns a plain-text report.
     *
     * Exported to Swift as a completion-handler function; the caller just prints it.
     */
    suspend fun run(): String {
        val report = Report()
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail(
                "preconditions",
                "emulator mode is disabled; refusing to run against a live project",
            )
            return report.render()
        }
        report.pass(
            "preconditions",
            "emulator enabled at ${IosFirebaseEmulatorSettings.host}:" +
                "${IosFirebaseEmulatorSettings.authPort}",
        )

        val bridge = IosAuthBridgeRegistry.bridgeOrNull()
        if (bridge == null) {
            report.fail("bridge registration", "no IosAuthBridge was registered from Swift")
            return report.render()
        }
        report.pass("bridge registration", "Swift bridge present")
        // FB-701: a previously interrupted self-check may have persisted its synthetic
        // credential. Establish the empty-credential fixture before constructing the
        // adapter whose initial Unresolved/restoration behavior is asserted below.
        report.expectSuccess("discard prior emulator fixture credential", IosAuthRepository().signOut())

        val repository = IosAuthRepository()
        val email = "fb103-${NSUUID().UUIDString().lowercase()}@example.com"
        val unknownEmail = "fb103-absent-${NSUUID().UUIDString().lowercase()}@example.com"

        coroutineScope {
            val emissions = mutableListOf<AuthSession>()
            val collector = launch { repository.session.toList(emissions) }
            delay(SETTLE_MS)

            // --- session starts Unresolved, then resolves -----------------------------
            report.expect(
                "session starts Unresolved",
                AuthSession.Unresolved,
                emissions.firstOrNull(),
            )

            repository.restoreSession()
            delay(SETTLE_MS)
            report.expect("restore with no credential", AuthSession.SignedOut, emissions.last())

            // --- sign-up --------------------------------------------------------------
            report.expectSuccess("signUp", repository.signUp(email, PASSWORD))
            delay(SETTLE_MS)
            val uid = emissions.last().uidOrNull
            report.check(
                "signUp authenticates",
                uid != null && uid.isNotEmpty(),
                "session=${emissions.last()}",
            )

            // --- restoration of a live credential ------------------------------------
            repository.restoreSession()
            delay(SETTLE_MS)
            report.check(
                "restore with a live credential",
                emissions.last().uidOrNull == uid,
                "session=${emissions.last()}",
            )

            // --- sign-out -------------------------------------------------------------
            report.expectSuccess("signOut", repository.signOut())
            delay(SETTLE_MS)
            report.expect("signOut resolves SignedOut", AuthSession.SignedOut, emissions.last())

            // --- sign-in --------------------------------------------------------------
            report.expectSuccess("signIn", repository.signIn(email, PASSWORD))
            delay(SETTLE_MS)
            report.check(
                "signIn restores the same uid",
                emissions.last().uidOrNull == uid,
                "session=${emissions.last()}",
            )

            // --- recovery -------------------------------------------------------------
            report.expectSuccess(
                "sendPasswordResetEmail (known account)",
                repository.sendPasswordResetEmail(email),
            )
            delay(SETTLE_MS)
            report.check(
                "recovery does not change the session",
                emissions.last().uidOrNull == uid,
                "session=${emissions.last()}",
            )

            // --- error taxonomy against real SDK-emitted codes -------------------------
            report.expectError(
                "signUp with an already-used email",
                AuthError.EmailAlreadyInUse,
                repository.signUp(email, PASSWORD),
            )
            report.expectError(
                "signUp with a malformed email",
                AuthError.InvalidEmail,
                repository.signUp("not-an-email", PASSWORD),
            )
            report.expectError(
                "signUp with a weak password",
                AuthError.WeakPassword,
                repository.signUp("fb103-weak-${NSUUID().UUIDString().lowercase()}@example.com", WEAK_PASSWORD),
            )
            report.expectError(
                "signIn with a wrong password",
                AuthError.InvalidCredentials,
                repository.signIn(email, "definitely-not-the-password"),
            )
            report.record(
                "signIn with an unknown account",
                repository.signIn(unknownEmail, PASSWORD),
            )
            report.record(
                "sendPasswordResetEmail (unknown account)",
                repository.sendPasswordResetEmail(unknownEmail),
            )

            delay(SETTLE_MS)
            report.check(
                "a failed operation leaves the session untouched",
                emissions.last().uidOrNull == uid,
                "session=${emissions.last()}",
            )

            // --- FB-101-NB3 against the real SDK listener -----------------------------
            // Two separate proofs, because they fail for different reasons.
            //
            // (1) Directly against the bridge: register a raw listener, confirm a *real*
            // SDK auth-state change reaches it, remove it, then force another real
            // change and confirm nothing more arrives. This is what proves Swift's
            // `removeStateDidChangeListener` actually detaches the SDK listener; the
            // "while live" leg is asserted first so the "after removal" leg cannot pass
            // vacuously against a listener that never worked.
            val rawUids = mutableListOf<String?>()
            val rawHandle = bridge.addAuthStateListener { rawUids += it?.uid }
            delay(SETTLE_MS)
            repository.signOut()
            delay(SETTLE_MS)
            repository.signIn(email, PASSWORD)
            delay(SETTLE_MS)
            val whileLive = rawUids.size
            report.check(
                "a live bridge listener receives real SDK auth-state changes",
                whileLive >= 2,
                "callbacks while live=$whileLive (expected at least the sign-out and sign-in)",
            )

            rawHandle.remove()
            repository.signOut()
            delay(SETTLE_MS * 2)
            repository.signIn(email, PASSWORD)
            delay(SETTLE_MS * 2)
            report.check(
                "a removed bridge listener receives nothing further",
                rawUids.size == whileLive,
                "callbacks while live=$whileLive after removal=${rawUids.size}",
            )

            // (2) End to end through the flow: cancelling the collector must stop
            // emissions across a subsequent real state change.
            collector.cancel()
            delay(SETTLE_MS)
            val afterCancellation = emissions.size
            // A real, subsequent auth-state change: sign out for real. If the Firebase
            // listener had leaked, it would still deliver and the list would grow.
            repository.signOut()
            delay(SETTLE_MS * 4)
            report.check(
                "cancelling the collector releases the Firebase listener",
                emissions.size == afterCancellation,
                "emissions before=$afterCancellation after=${emissions.size}",
            )
        }
        return report.render()
    }

    private class Report {
        private val lines = mutableListOf<String>()
        private var failures = 0

        fun pass(name: String, detail: String = "") {
            lines += "PASS  $name${if (detail.isEmpty()) "" else " ($detail)"}"
        }

        fun fail(name: String, detail: String) {
            failures++
            lines += "FAIL  $name ($detail)"
        }

        fun note(detail: String) {
            lines += "INFO  $detail"
        }

        fun check(name: String, condition: Boolean, detail: String) {
            if (condition) pass(name) else fail(name, detail)
        }

        fun expect(name: String, expected: Any?, actual: Any?) {
            check(name, expected == actual, "expected=$expected actual=$actual")
        }

        fun expectSuccess(name: String, result: AuthResult) {
            check(name, result is AuthResult.Success, "result=$result")
        }

        fun expectError(name: String, expected: AuthError, result: AuthResult) {
            check(
                name,
                result.errorOrNull == expected,
                "expected=Failure($expected) actual=$result",
            )
        }

        /**
         * Records the outcome without asserting it. Used where the Firebase backend is
         * documented to be free to answer either way - notably email-enumeration
         * protection, which turns "unknown account" into a generic credential error or
         * into a silent success. The observed value is reported as evidence rather than
         * asserted as a requirement.
         */
        fun record(name: String, result: AuthResult) {
            lines += "INFO  $name -> $result"
        }

        fun render(): String {
            val failed = failures
            val header = if (failed == 0) {
                "FB-103 iOS Auth integration check: ALL CHECKS PASSED"
            } else {
                "FB-103 iOS Auth integration check: $failed CHECK(S) FAILED"
            }
            return (listOf(header) + lines + listOf("FB-103 END")).joinToString("\n")
        }
    }
}
