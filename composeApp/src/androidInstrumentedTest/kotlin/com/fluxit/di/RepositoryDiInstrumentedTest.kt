package com.fluxit.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.AndroidFirebaseListRepository
import kotlin.test.assertIs
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.annotation.KoinInternalApi

@RunWith(AndroidJUnit4::class)
class RepositoryDiInstrumentedTest {
    @OptIn(KoinInternalApi::class)
    @Test fun actualApplicationGraphSelectsConfiguredRepositories() {
        val graph = GlobalContext.get()
        assertIs<AndroidFirebaseListRepository>(graph.get<ListRepository>())
        assertIs<AndroidFirebaseItemRepository>(graph.get<ItemRepository>())
        assertFalse(graph.instanceRegistry.instances.values.any {
            it.beanDefinition.primaryType.qualifiedName == "com.fluxit.data.FluxItDatabase"
        }, "The retired database must have no Koin definition")
    }
}
