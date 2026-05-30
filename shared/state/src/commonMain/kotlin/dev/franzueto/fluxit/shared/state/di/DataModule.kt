package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.repository.SqlItemsRepository
import dev.franzueto.fluxit.shared.data.repository.SqlListsRepository
import dev.franzueto.fluxit.shared.data.repository.SqlPhotosRepository
import dev.franzueto.fluxit.shared.data.repository.SqlRemindersRepository
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.repository.PhotosRepository
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the `:shared:data` layer (ADR-015). This is the only DI
 * module that legitimately imports `:shared:data` / SQLDelight — the di/
 * package is exempted from StateLayerArchTest's use-case-only rule, which
 * still bans those imports in the stores.
 *
 * The [SqlDriver][app.cash.sqldelight.db.SqlDriver] is **not** bound here: the
 * platform start site supplies it via an `extra` module (Android: `DriverFactory`
 * over the app `Context`; iOS: `DriverFactory().create()`; tests: an in-memory
 * JVM/native driver). [fluxItDatabase] wires every column adapter over it.
 */
public val dataModule: Module =
    module {
        single { fluxItDatabase(get()) }
        single<ListsRepository> { SqlListsRepository(get()) }
        single<ItemsRepository> { SqlItemsRepository(get()) }
        single<RemindersRepository> { SqlRemindersRepository(get()) }
        single<PhotosRepository> { SqlPhotosRepository(get(), get()) }
    }
