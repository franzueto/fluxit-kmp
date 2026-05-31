package dev.franzueto.fluxit.state

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.FunSpec

// FluxIt state-layer architecture rules — Phase 05 §1 + §11 (ADR-014).
//
// Invariants enforced here:
//   1. :shared:state commonMain stays UI- and platform-agnostic and depends on
//      use cases only — no Android/iOS framework, no SQLDelight, no :shared:data,
//      no :platform:* (§1). Stores compose the :shared:domain use-case surface.
//   2. Store-harness encapsulation (§11): no production property in :shared:state
//      exposes a MutableStateFlow / MutableSharedFlow publicly. The Store contract
//      is read-only `state` + `effects` + `dispatch`; the mutable backing flows
//      stay private inside BaseStore.
//   3. Store surface (§11): a concrete store (a `BaseStore` subclass) declares no
//      public members of its own — the only public API is the `state`/`effects`/
//      `dispatch` it inherits from the Store contract. Everything a store adds
//      (use-case deps, reduce, helpers) is private/protected. Now enforceable
//      because the first feature stores (RootStore, ListsDashboardStore) exist.
//
// Runs as a regular JUnit 5 test in :build-logic; scans the OUTER repo.
class StateLayerArchTest : FunSpec({

    val scope = { Konsist.scopeFromDirectory("..") }

    test(":shared:state commonMain has no UI, platform, SQLDelight, or :shared:data imports") {
        scope()
            .files
            .filter { "/shared/state/src/commonMain/" in it.path }
            // The di/ composition root (ADR-015) legitimately wires :shared:data
            // Sql repositories — that is its job. The ban stays in force on the
            // stores themselves, which compose use cases only.
            .filterNot { "/state/di/" in it.path }
            .assertFalse(
                additionalMessage = "State commonMain must stay UI/platform-agnostic and depend on " +
                    "use cases only — no android.*/androidx.*, no platform.UIKit/Foundation, no " +
                    "app.cash.sqldelight, no :shared:data, no :platform:* imports (§1, ADR-014).",
            ) { file ->
                file.imports.any { imp ->
                    val n = imp.name
                    n.startsWith("android.") ||
                        n.startsWith("androidx.") ||
                        n.startsWith("platform.UIKit") ||
                        n.startsWith("platform.Foundation") ||
                        n.startsWith("app.cash.sqldelight") ||
                        n.startsWith("dev.franzueto.fluxit.shared.data") ||
                        n.startsWith("dev.franzueto.fluxit.platform.")
                }
            }
    }

    test(":shared:state exposes no public MutableStateFlow / MutableSharedFlow property") {
        scope()
            .classes(includeNested = true)
            .filter { klass ->
                val path = klass.containingFile.path
                "/shared/state/" in path &&
                    "/build/" !in path &&
                    "/commonTest/" !in path &&
                    "/androidUnitTest/" !in path &&
                    "/iosTest/" !in path
            }
            .flatMap { it.properties() }
            .filter { it.hasPublicOrDefaultModifier }
            .assertFalse(
                additionalMessage = "Stores must not expose mutable flows. Keep the MutableStateFlow / " +
                    "MutableSharedFlow private and expose read-only state: StateFlow + effects: Flow (§11).",
            ) { property ->
                val typeName = property.type?.name ?: return@assertFalse false
                typeName.startsWith("MutableStateFlow") || typeName.startsWith("MutableSharedFlow")
            }
    }

    test("concrete stores (BaseStore subclasses) expose only the inherited state/effects/dispatch") {
        val stores =
            scope()
                .classes()
                .filter { "/shared/state/src/commonMain/" in it.containingFile.path }
                .filter { klass -> klass.parents().any { it.name == "BaseStore" } }

        stores
            .flatMap { it.functions() }
            .assertFalse(
                additionalMessage = "A store must not declare public functions — its only public surface is the " +
                    "inherited state/effects/dispatch. Make store-specific functions private/protected (§11).",
            ) { it.hasPublicOrDefaultModifier }

        stores
            .flatMap { it.properties() }
            .assertFalse(
                additionalMessage = "A store must not declare public properties — keep use-case deps and internal " +
                    "state private. The only public surface is the inherited state/effects/dispatch (§11).",
            ) { it.hasPublicOrDefaultModifier }
    }
})
