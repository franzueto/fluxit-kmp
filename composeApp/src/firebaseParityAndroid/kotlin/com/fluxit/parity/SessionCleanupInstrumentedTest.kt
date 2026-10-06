package com.fluxit.parity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.domain.session.*
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.data.PhotoStorage
import org.koin.dsl.module
import com.fluxit.data.AndroidPhotoStorage
import com.fluxit.firebase.session.AndroidSessionCleanup
import com.fluxit.firebase.auth.AndroidAuthRepository
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.*
import com.google.firebase.storage.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SessionCleanupInstrumentedTest {
    @Test fun nativeCacheAndPendingWritePrivacy() = runBlocking {
        check(FirebaseEmulatorConfig.ENABLED)
        val args = InstrumentationRegistry.getArguments()
        fun arg(key: String) = requireNotNull(args.getString(key))
        val graph = GlobalContext.get()
        val cleanup = RetrySessionCleanup(graph.get())
        graph.loadModules(listOf(module {
            single<SessionCleanup> { cleanup }
            single<PhotoStorage> { SessionPhotoStorage(AndroidPhotoStorage(photoIdFactory = { "fb709-upload" }), get()) }
        }))
        val auth = graph.get<AuthRepository>()
        val probe = object : SessionCleanupProbe {
            var old: FirebaseFirestore? = null
            var settings: FirebaseFirestoreSettings? = null
            override suspend fun disableNetwork() { FirebaseFirestore.getInstance().disableNetwork().awaitResult() }
            override fun recordClient() { old = FirebaseFirestore.getInstance(); settings = old!!.firestoreSettings }
            override fun verifyRecreated() {
                val fresh = FirebaseFirestore.getInstance()
                check(fresh !== old && fresh.firestoreSettings == settings)
                check(fresh.firestoreSettings.host.contains("10.0.2.2") && !fresh.firestoreSettings.isSslEnabled)
            }
            override suspend fun cachedName(path: String): String? = try {
                val doc = FirebaseFirestore.getInstance().document(path).get(Source.CACHE).awaitResult()
                doc.getString(if (path.contains("/items/")) "title" else "name")
            } catch (e: FirebaseFirestoreException) { check(e.code == FirebaseFirestoreException.Code.UNAVAILABLE); null }
            override suspend fun serverName(path: String): String? = FirebaseFirestore.getInstance().document(path).get(Source.SERVER).awaitResult().getString(if (path.contains("/items/")) "title" else "name")
            override suspend fun verifyStorageCancellation(uid: String) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val main = FirebaseApp.getInstance()
                val options = FirebaseOptions.Builder(main.options).build()
                val secondary = FirebaseApp.getApps(context).firstOrNull { it.name == "fb709-storage-cancel" }
                    ?: FirebaseApp.initializeApp(context, options, "fb709-storage-cancel")
                val storage = FirebaseStorage.getInstance(secondary)
                storage.maxUploadRetryTimeMillis = 40_000
                storage.maxDownloadRetryTimeMillis = 40_000
                storage.useEmulator("10.0.2.2", 9198) // Deliberately absent loopback endpoint: no object can be written.
                val upload = storage.reference.child("users/$uid/items/fb709-item/cancelled-upload").putBytes(parityPhotoBytes)
                check(!upload.isComplete && storage.reference.activeUploadTasks.contains(upload))
                com.fluxit.firebase.session.AndroidStorageSessionTasks.track(upload)
                val download = storage.reference.child("users/$uid/items/fb709-item/cancelled-upload").getStream { _, stream -> stream.use { it.read() } }
                com.fluxit.firebase.session.AndroidStorageSessionTasks.track(download)
                withTimeout(2_000) { while (!download.isInProgress) delay(10) }
                check(storage.reference.activeDownloadTasks.isEmpty()) { "SDK root list omits stream downloads" }
                val cancellationAuth = SessionAuthRepository(AndroidAuthRepository(), graph.get(), AndroidSessionCleanup(context, storage = storage))
                check(cancellationAuth.signOut() == com.fluxit.domain.auth.AuthResult.Failure(com.fluxit.domain.auth.AuthError.CleanupFailed))
                check(!upload.isComplete && graph.get<SessionCleanup>().pending)
                // A terminal callback must precede success on retry; default app retry
                // settings are untouched by this secondary synthetic SDK instance.
                try { withTimeout(60_000) { upload.awaitResult() }; error("Upload should cancel") }
                catch (e: StorageException) { check(e.errorCode == StorageException.ERROR_CANCELED && upload.isCanceled) { "upload terminal code=${e.errorCode}, canceled=${upload.isCanceled}" } }
                try { withTimeout(60_000) { download.awaitResult() }; error("Download should cancel") }
                catch (e: StorageException) { check(e.errorCode == StorageException.ERROR_CANCELED && download.isCanceled) { "download terminal code=${e.errorCode}, canceled=${download.isCanceled}" } }
                check(cancellationAuth.signOut() == com.fluxit.domain.auth.AuthResult.Success)
                secondary.delete()
            }
        }
        val result = withTimeout(150_000) {
            when (args.getString("phase", "run")) {
                "prepare" -> SessionCleanupScenario.prepareRestart(auth, cleanup, graph.get(), probe, arg("emailA"), arg("password"))
                "recover" -> SessionCleanupScenario.recoverRestart(auth, cleanup, probe, arg("emailA"), arg("password"), arg("uidA"))
                else -> SessionCleanupScenario.run(auth, cleanup, graph.get(), graph.get(), graph.get(), probe,
                    arg("emailA"), arg("emailB"), arg("password"), arg("uidA"), arg("uidB"))
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\nFB-709 Android $result\n")
        })
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnCompleteListener { if (c.isActive) { val e = it.exception; if (e != null) c.resumeWithException(e) else if (it.isCanceled) c.cancel() else c.resume(it.result) } }
}
