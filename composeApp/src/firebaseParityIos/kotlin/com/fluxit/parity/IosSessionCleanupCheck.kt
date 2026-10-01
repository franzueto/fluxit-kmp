package com.fluxit.parity

import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.domain.session.*
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.data.PhotoStorage
import org.koin.dsl.module
import com.fluxit.data.IosPhotoStorage
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.initializeIosKoin
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.mp.KoinPlatform
import kotlin.coroutines.resume

interface IosSessionCleanupProbe {
    fun disableNetwork(completion: (Boolean) -> Unit)
    fun cachedName(path: String, completion: (String?, Boolean) -> Unit)
    fun serverName(path: String, completion: (String?, Boolean) -> Unit)
    fun recordClient()
    fun verifyRecreated(): Boolean
    fun verifyStorageCancellation(uid: String, completion: (Boolean) -> Unit)
}

object IosSessionCleanupCheck {
    suspend fun run(phase: String, emailA: String, emailB: String, password: String, uidA: String, uidB: String,
        bridge: IosSessionCleanupProbe): String {
        check(FirebaseEmulatorConfig.ENABLED)
        initializeIosKoin()
        val graph = KoinPlatform.getKoin()
        val cleanup = RetrySessionCleanup(graph.get())
        graph.loadModules(listOf(module {
            single<SessionCleanup> { cleanup }
            single<PhotoStorage> { SessionPhotoStorage(IosPhotoStorage(photoIdFactory = { "fb709-upload" }), get()) }
        }))
        val auth = graph.get<AuthRepository>()
        val probe = object : SessionCleanupProbe {
            override suspend fun disableNetwork() { check(await { bridge.disableNetwork(it) }) }
            override fun recordClient() = bridge.recordClient()
            override fun verifyRecreated() { check(bridge.verifyRecreated()) }
            override suspend fun cachedName(path: String) = name { bridge.cachedName(path, it) }
            override suspend fun serverName(path: String) = name { bridge.serverName(path, it) }
            override suspend fun verifyStorageCancellation(uid: String) { check(await { bridge.verifyStorageCancellation(uid, it) }) }
        }
        if (phase == "prepare") return SessionCleanupScenario.prepareRestart(auth, cleanup, graph.get(), probe, emailA, password)
        if (phase == "recover") return SessionCleanupScenario.recoverRestart(auth, cleanup, probe, emailA, password, uidA)
        return SessionCleanupScenario.run(auth, cleanup, graph.get(), graph.get(), graph.get(), probe,
            emailA, emailB, password, uidA, uidB)
    }
    private suspend fun await(call: ((Boolean) -> Unit) -> Unit): Boolean = suspendCancellableCoroutine { c -> call { if (c.isActive) c.resume(it) } }
    private suspend fun name(call: ((String?, Boolean) -> Unit) -> Unit): String? {
        val result = suspendCancellableCoroutine<Pair<String?, Boolean>> { c -> call { name, ok -> if (c.isActive) c.resume(name to ok) } }
        check(result.second)
        return result.first
    }
}
