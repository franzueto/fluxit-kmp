package com.fluxit.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxit.config.FirebaseDevFlags
import com.fluxit.data.RoomItemRepository
import com.fluxit.data.RoomListRepository
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.AndroidFirebaseListRepository
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class RepositoryDiInstrumentedTest {
    @Test fun actualApplicationGraphSelectsConfiguredRepositories() {
        val graph = GlobalContext.get()
        if (FirebaseDevFlags.USE_FIREBASE_REPOSITORIES) {
            assertIs<AndroidFirebaseListRepository>(graph.get<ListRepository>())
            assertIs<AndroidFirebaseItemRepository>(graph.get<ItemRepository>())
        } else {
            assertIs<RoomListRepository>(graph.get<ListRepository>())
            assertIs<RoomItemRepository>(graph.get<ItemRepository>())
        }
    }
}
