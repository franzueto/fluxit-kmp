package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.data.db.DriverFactory
import org.koin.dsl.module

/**
 * The driver type is inferred from `DriverFactory().create()` (which returns a
 * `SqlDriver`) rather than named explicitly, so this file imports no
 * `app.cash.sqldelight` symbol — SQLDelight stays encapsulated in `:shared:data`
 * (DataLayerArchTest). Lives in iosMain, outside the commonMain surface
 * StateLayerArchTest guards, which is why the `:shared:data` reference is allowed
 * here (it is the platform start site, per ADR-015).
 *
 * Returns `Unit` (not Koin's `KoinApplication`): the Koin types are an
 * `implementation` dependency and aren't exported, so a function returning one
 * would be dropped from the Obj-C/Swift header. Swift resolves the graph through
 * [resolveRootStore] instead.
 */
public fun initKoinIos() {
    initKoin(extra = listOf(module { single { DriverFactory().create() } }))
}
