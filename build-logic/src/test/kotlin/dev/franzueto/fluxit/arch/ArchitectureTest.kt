package dev.franzueto.fluxit.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.FunSpec

// FluxIt architecture rules — Phase 01 §8.4.
//
// These run as a regular JUnit 5 test in :build-logic. They scan the OUTER
// repo (parent of build-logic) so feature modules added in phases 07–10 are
// covered automatically without per-module wiring.
//
// Run with: `./gradlew :build-logic:test`.
class ArchitectureTest : FunSpec({

    // build-logic is an included build; the test task's working directory is
    // `<repo>/build-logic`, so `..` lands on the outer FluxIt project root.
    // Konsist's scopeFromDirectory treats its argument as relative to the cwd.
    val scope = { Konsist.scopeFromDirectory("..") }

    test("shared:domain has no forbidden imports (Phase 04 §1 + ADR-007 + ADR-007a)") {
        // Phase 04 §1 exit criteria: :shared:domain stays pure Kotlin —
        // no Android/iOS framework, no SQLDelight, no Koin runtime, no
        // designsystem (per ADR-007a — domain owns the tokens, design-
        // system consumes them; the inward arrow stays forbidden), no
        // Arrow (per ADR-007 — Outcome<T, E> is in-house).
        //
        // kotlin.Result is also forbidden at the domain boundary
        // (ADR-007), but `kotlin.*` is auto-imported by the compiler so
        // a "no `import kotlin.Result`" rule catches ~nothing in
        // practice. Instead we scan file text for code-shape uses of
        // the qualified reference — `kotlin.Result<`, `kotlin.Result.`,
        // `kotlin.Result(` — which catch the fully-qualified type
        // params, method calls, and constructors people actually write.
        // Bare KDoc mentions ("distinct from kotlin.Result") slip
        // through the regex, which is the point: documenting *why*
        // domain doesn't use it is allowed. Bare `Result<T>` usage at
        // the symbol level stays a reviewer-discipline matter; the
        // domain already exports `Outcome` as the only result-shaped
        // return type, so a stray `Result<...>` would stand out at
        // review.
        scope()
            .files
            .filter { "/shared/domain/" in it.path && "/build/" !in it.path }
            .filter { file ->
                // Allow test source sets to use kotlin.Result for
                // interop with platform APIs that return it.
                "/src/commonTest/" !in file.path &&
                    "/src/androidHostTest/" !in file.path &&
                    "/src/iosTest/" !in file.path
            }
            .assertFalse(
                additionalMessage = "Domain must stay pure — no Android/iOS framework, " +
                    "no SQLDelight, no Koin runtime, no designsystem (ADR-007a), " +
                    "no Arrow / kotlin.Result (ADR-007 — use Outcome<T, E>).",
            ) { file ->
                val forbiddenImport = file.imports.any { imp ->
                    val n = imp.name
                    n.startsWith("android.") ||
                        n.startsWith("androidx.") ||
                        n.startsWith("platform.UIKit") ||
                        n.startsWith("platform.Foundation") ||
                        n.startsWith("dev.franzueto.fluxit.platform.") ||
                        n.startsWith("app.cash.sqldelight.") ||
                        n.startsWith("org.koin.core.") ||
                        n.startsWith("dev.franzueto.fluxit.core.designsystem.") ||
                        n.startsWith("arrow.")
                }
                val kotlinResultRef = Regex("kotlin\\.Result[<.(]")
                    .containsMatchIn(file.text)
                forbiddenImport || kotlinResultRef
            }
    }

    test("no top-level suspend fun in :shared:domain usecase/ (ADR-007b)") {
        // ADR-007b ratification: every use case in :shared:domain/usecase/
        // is a class with constructor-injected dependencies and a single
        // `operator fun invoke(...)`. A top-level `suspend fun foo(...)`
        // would be the free-function shape the ADR rejects — ban it so the
        // class-with-invoke convention can't silently erode. Member invoke
        // operators (suspend or not) live on a class, not at file scope, so
        // `file.functions()` (top-level only) never sees them.
        scope()
            .files
            .filter { "/shared/domain/" in it.path && "/usecase/" in it.path && "/build/" !in it.path }
            .flatMap { it.functions() }
            .filter { it.isTopLevel }
            .assertFalse(
                additionalMessage = "Use cases must be classes with `operator fun invoke` (ADR-007b) — " +
                    "no top-level suspend fun in usecase/.",
            ) { it.hasSuspendModifier }
    }

    test("feature-* modules do not depend on each other") {
        scope()
            .files
            .filter { "/features/feature-" in it.path }
            .assertFalse(additionalMessage = "Cross-feature flows must go through domain use cases, not direct imports.") { file ->
                // Identify this file's owning feature (e.g. "feature-lists").
                val ownFeature = "/features/(feature-[a-z-]+)/".toRegex()
                    .find(file.path)
                    ?.groupValues
                    ?.get(1)
                    ?: return@assertFalse false

                file.imports.any { imp ->
                    val match = "dev\\.franzueto\\.fluxit\\.feature\\.([a-z]+)".toRegex()
                        .find(imp.name)
                        ?: return@any false
                    val importedFeature = "feature-${match.groupValues[1]}"
                    importedFeature != ownFeature
                }
            }
    }

    test("feature-* modules do not import :shared:data directly (plan/07 §11)") {
        // A feature reaches persistence only through domain interfaces, via use
        // cases, via its store (plan/07 §11). A direct `dev.franzueto.fluxit.shared.data`
        // import would let the UI bind to SQLDelight-backed types and skip that seam.
        scope()
            .files
            .filter { "/features/feature-" in it.path && "/build/" !in it.path }
            .assertFalse(
                additionalMessage = "Feature modules must reach data only through domain use cases via the store — " +
                    "no direct :shared:data import.",
            ) { file ->
                file.imports.any { it.name.startsWith("dev.franzueto.fluxit.shared.data") }
            }
    }

    test("GlobalScope and runBlocking are forbidden outside test source sets") {
        scope()
            .files
            .filter { file ->
                "/build/" !in file.path &&
                    "/src/test/" !in file.path &&
                    "/src/commonTest/" !in file.path &&
                    "/src/androidTest/" !in file.path &&
                    "/src/androidHostTest/" !in file.path &&
                    "/src/iosTest/" !in file.path &&
                    !file.name.endsWith("Test.kt") &&
                    !file.name.endsWith("Spec.kt")
            }
            .assertFalse(additionalMessage = "Use a structured CoroutineScope (Dispatchers + supervisorScope); never GlobalScope or runBlocking outside tests.") { file ->
                file.imports.any { imp ->
                    imp.name == "kotlinx.coroutines.GlobalScope" ||
                        imp.name == "kotlinx.coroutines.runBlocking"
                } || "kotlinx.coroutines.GlobalScope" in file.text ||
                    Regex("\\brunBlocking\\b").containsMatchIn(file.text)
            }
    }
})
