package com.fluxit.parity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseDevFlags
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.*
import com.fluxit.domain.*
import com.fluxit.domain.auth.*
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.AndroidFirebaseListRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicit opt-in emulator-only acceptance fixture; never compiled into the app. */
@RunWith(AndroidJUnit4::class)
class FirebaseRegressionInstrumentedTest {
    @Test fun contractAndBidirectionalAppleAndroidRealtime() = runBlocking {
        check(FirebaseEmulatorConfig.ENABLED && FirebaseDevFlags.USE_FIREBASE_REPOSITORIES)
        val args = InstrumentationRegistry.getArguments()
        val email = requireNotNull(args.getString("parityEmail"))
        val password = requireNotNull(args.getString("parityPassword"))
        val marker = requireNotNull(args.getString("parityMarker"))
        val graph = GlobalContext.get()
        val auth = graph.get<AuthRepository>()
        assertEquals(AuthResult.Success, auth.signIn(email, password))
        val uid = withTimeout(15_000) { auth.session.first { it is AuthSession.Authenticated } }.uidOrNull!!
        val lists = assertIs<AndroidFirebaseListRepository>(graph.get<ListRepository>())
        val items = assertIs<AndroidFirebaseItemRepository>(graph.get<ItemRepository>())
        try {
            val trace = RepositoryRegressionScenario.run(lists, items) { "users/$uid/items/$it/parity.jpg" }
            assertEquals(16, trace.size)
            println("FB-703 Android Firebase regression PASS checkpoints=${trace.size}")
            // Register before Apple's update and keep this same collector alive through it.
            val shared = withTimeout(90_000) { lists.observeListSummaries().first { r -> r.any { it.list.name == "$marker-ready" } } }
                .first { it.list.name == "$marker-ready" }.list.id
            val seen = MutableStateFlow<FluxList?>(null)
            val listener = launch { lists.observeList(shared).collect { seen.value = it } }
            try {
                withTimeout(15_000) { seen.first { it?.name == "$marker-ready" } }
                lists.updateList(shared, "$marker-android", ListIcon.HOME, ListColor.EMERALD)
                withTimeout(60_000) { seen.first { it?.name == "$marker-ios" } }
                items.addItem(shared, "android-item")
                val item = withTimeout(15_000) { items.observeItems(shared).first { it.size == 1 } }.single()
                withTimeout(60_000) { items.observeItem(shared, item.id).first { it?.isCompleted == true } }
                val counts = withTimeout(15_000) { lists.observeListSummaries().first { r -> r.any { it.list.id == shared && it.completedItems == 1 } } }
                    .first { it.list.id == shared }
                assertEquals(1, counts.totalItems)
                val photos = graph.get<PhotoStorage>()
                val androidPhoto = photos.uploadPhoto(item.id, parityPhotoBytes)
                items.setPhotoRef(shared, item.id, androidPhoto)
                val applePhoto = withTimeout(60_000) { items.observeItem(shared, item.id).first {
                    it?.photoRef != null && it.photoRef != androidPhoto
                } }!!.photoRef!!
                assertContentEquals(parityPhotoBytes, assertIs<PhotoContent.Bytes>(photos.loadPhoto(applePhoto)).bytes)
                photos.deletePhoto(applePhoto)
                assertNull(photos.loadPhoto(applePhoto))
                items.setPhotoRef(shared, item.id, null)
                println("FB-703 Android cross-platform-photo PASS bytes=equal replacements=1 deleted=1")
                println("FB-703 Android realtime PASS Apple-list-edit Apple-item-completion counters=1/1")
            } finally { listener.cancelAndJoin() }
        } finally { auth.signOut() }
    }
}
