package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.domain.usecase.app.InitializeApp
import dev.franzueto.fluxit.shared.domain.usecase.items.AddItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ClearCompletedItems
import dev.franzueto.fluxit.shared.domain.usecase.items.DeleteItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveListDetail
import dev.franzueto.fluxit.shared.domain.usecase.items.ToggleItemCompleted
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList
import dev.franzueto.fluxit.shared.domain.usecase.lists.DeleteList
import dev.franzueto.fluxit.shared.domain.usecase.lists.ObserveLists
import dev.franzueto.fluxit.shared.domain.usecase.lists.RenameList
import dev.franzueto.fluxit.shared.domain.usecase.lists.SearchLists
import dev.franzueto.fluxit.shared.domain.usecase.lists.UpdateListAppearance
import dev.franzueto.fluxit.shared.domain.usecase.photos.AttachPhotoToItem
import dev.franzueto.fluxit.shared.domain.usecase.photos.DetachPhotoFromItem
import dev.franzueto.fluxit.shared.domain.usecase.photos.PhotoJanitor
import dev.franzueto.fluxit.shared.domain.usecase.photos.ResolvePhotoUri
import dev.franzueto.fluxit.shared.domain.usecase.reminders.CancelReminder
import dev.franzueto.fluxit.shared.domain.usecase.reminders.RehydrateReminders
import dev.franzueto.fluxit.shared.domain.usecase.reminders.ScheduleReminder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the `:shared:domain` use cases (ADR-015). Each use case is
 * a `factory` — they are cheap stateless orchestrators over the repository
 * `single`s in [dataModule] and the port `single`s in [platformModule]. The
 * `get()` slots resolve against those two modules at start time.
 */
public val domainModule: Module =
    module {
        factory { RehydrateReminders(get(), get()) }
        factory { InitializeApp(get()) }
        factory { ObserveLists(get()) }
        factory { SearchLists(get()) }
        factory { CancelReminder(get(), get()) }
        factory { DeleteList(get(), get(), get()) }
        factory { CreateList(get()) }
        factory { RenameList(get()) }
        factory { UpdateListAppearance(get()) }
        factory { ObserveListDetail(get(), get()) }
        factory { ToggleItemCompleted(get()) }
        factory { AddItem(get()) }
        factory { DeleteItem(get()) }
        factory { ClearCompletedItems(get()) }
        factory { ObserveItem(get()) }
        factory { UpdateItemDetails(get()) }
        factory { ScheduleReminder(get(), get(), get()) }
        factory { ResolvePhotoUri(get(), get()) }
        factory { PhotoJanitor(get(), get()) }
        factory { AttachPhotoToItem(get(), get(), get()) }
        factory { DetachPhotoFromItem(get(), get(), get()) }
    }
