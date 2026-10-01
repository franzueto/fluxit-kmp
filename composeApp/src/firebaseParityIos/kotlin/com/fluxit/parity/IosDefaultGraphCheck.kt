package com.fluxit.parity

import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.config.FirebaseDevFlags
import com.fluxit.data.FluxItDatabase
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.list.IosFirebaseListRepository
import com.fluxit.initializeIosKoin
import org.koin.core.annotation.KoinInternalApi
import org.koin.mp.KoinPlatform

/** Test-only default-config runtime probe. No auth, repository operation or UI mount. */
object IosDefaultGraphCheck {
    @OptIn(KoinInternalApi::class)
    fun run(): String {
        check(!FirebaseEmulatorConfig.ENABLED)
        check(FirebaseDevFlags.USE_FIREBASE_REPOSITORIES)
        initializeIosKoin() // Exact helper called by ordinary MainViewController startup.
        val graph = KoinPlatform.getKoin()
        check(graph.get<ListRepository>() is IosFirebaseListRepository)
        check(graph.get<ItemRepository>() is IosFirebaseItemRepository)
        val database = graph.instanceRegistry.instances.values.single {
            it.beanDefinition.primaryType == FluxItDatabase::class
        }
        check(!database.isCreated(null))
        check(!graph.instanceRegistry.instances.values.single {
            it.beanDefinition.primaryType == AuthRepository::class
        }.isCreated(null))
        return "FB-702 iOS default-graph PASS Firebase-list-item emulator=false Room-initialized=false auth-initialized=false"
    }
}
