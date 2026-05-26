package dev.franzueto.fluxit.data

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.FunSpec

// FluxIt data-layer architecture rules — Phase 03 §1 + Definition-of-Done.
//
// Two invariants:
//   1. :shared:data commonMain stays platform-agnostic. androidMain and
//      iosMain legitimately import platform driver code; commonMain must
//      not. Mirrors the :shared:domain rule in ArchitectureTest.
//   2. :shared:data is the ONLY module that imports app.cash.sqldelight.*.
//      Every other module sees data only through :shared:domain interfaces.
//
// Runs as a regular JUnit 5 test in :build-logic; scans the OUTER repo.
class DataLayerArchTest : FunSpec({

    val scope = { Konsist.scopeFromDirectory("..") }

    test(":shared:data commonMain has no Android, iOS, or platform-module imports") {
        scope()
            .files
            .filter { "/shared/data/src/commonMain/" in it.path }
            .assertFalse(
                additionalMessage = "Data commonMain must stay platform-agnostic — " +
                    "no android.*, androidx.*, platform.UIKit/Foundation, or :platform:* imports. " +
                    "Driver-specific code belongs in androidMain / iosMain.",
            ) { file ->
                file.imports.any { imp ->
                    val n = imp.name
                    n.startsWith("android.") ||
                        n.startsWith("androidx.") ||
                        n.startsWith("platform.UIKit") ||
                        n.startsWith("platform.Foundation") ||
                        n.startsWith("dev.franzueto.fluxit.platform.")
                }
            }
    }

    test(":shared:data is the only module that imports app.cash.sqldelight") {
        scope()
            .files
            .filter { file ->
                "/build/" !in file.path &&
                    "/shared/data/" !in file.path &&
                    "/build-logic/" !in file.path
            }
            .assertFalse(
                additionalMessage = "SQLDelight must stay encapsulated inside :shared:data. " +
                    "Consume the database through :shared:domain repository interfaces instead.",
            ) { file ->
                file.imports.any { imp -> imp.name.startsWith("app.cash.sqldelight") }
            }
    }

    // Phase 03 §9 + ADR-006a: entity ids must come from newId() / IdGenerator,
    // never from ad-hoc sources that would create collisions or leak creation
    // time. The actual `newId()` impls in :core:core-utils are the only place
    // these primitives are allowed; test source sets are exempt (FakeClock,
    // deterministic seeds, etc.).
    test("entity ids are minted via newId() / IdGenerator, not Random or Clock-derived sources") {
        val bannedIdAssignment =
            Regex(
                """\b(?:val|var)\s+\w*[Ii]d\s*[:=][^\n]*?""" +
                    """(?:""" +
                    """kotlin\.random\.Random|""" +
                    """\bRandom\.(?:nextLong|nextInt|nextBytes)|""" +
                    """currentTimeMillis|""" +
                    """toEpochMilliseconds|""" +
                    """\.hashCode\(\)""" +
                    """)""",
            )
        scope()
            .files
            .filter { file ->
                "/build/" !in file.path &&
                    "/build-logic/" !in file.path &&
                    "/src/test/" !in file.path &&
                    "/src/commonTest/" !in file.path &&
                    "/src/androidTest/" !in file.path &&
                    "/src/androidUnitTest/" !in file.path &&
                    "/src/iosTest/" !in file.path &&
                    !file.name.endsWith("Test.kt") &&
                    !file.name.endsWith("Spec.kt") &&
                    // The newId() actuals themselves are the canonical source.
                    !file.name.startsWith("Ids.")
            }
            .assertFalse(
                additionalMessage = "Mint entity ids via dev.franzueto.fluxit.core.utils.newId() / IdGenerator. " +
                    "Random / currentTimeMillis / Instant.toEpochMilliseconds / hashCode() are banned id sources (ADR-006a).",
            ) { file ->
                bannedIdAssignment.containsMatchIn(file.text)
            }
    }
})
