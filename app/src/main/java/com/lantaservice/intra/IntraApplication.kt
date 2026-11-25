package com.lantaservice.intra

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.IntraNetworkFactory
import com.lantaservice.intra.data.network.IntraResilientDns
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.PendingOutboxStorage
import com.lantaservice.intra.data.storage.SecureSessionStorage
import com.lantaservice.intra.data.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Application entry point providing network failover, resilient DNS,
 * background update manager, and authenticated Coil ImageLoader.
 */
class IntraApplication : Application(), ImageLoaderFactory {

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    val sessionStorage: SecureSessionStorage by lazy {
        SecureSessionStorage(applicationContext)
    }

    val pendingOutboxStorage: PendingOutboxStorage by lazy {
        PendingOutboxStorage(applicationContext, json)
    }

    val credentialsProvider: SessionCredentialsProvider by lazy {
        SessionCredentialsProvider()
    }

    val resilientDns: IntraResilientDns by lazy {
        IntraResilientDns(applicationContext)
    }

    val connectionManager: IntraConnectionManager by lazy {
        IntraConnectionManager(
            context = applicationContext,
            resilientDns = resilientDns
        )
    }

    val okHttpClient by lazy {
        IntraNetworkFactory.createOkHttpClient(
            credentialsProvider = credentialsProvider,
            resilientDns = resilientDns,
            connectionManager = connectionManager
        )
    }

    val apiService by lazy {
        IntraNetworkFactory.createApiService(
            okHttpClient = okHttpClient,
            json = json
        )
    }

    val repository: IntraRepository by lazy {
        IntraRepository(
            context = applicationContext,
            api = apiService,
            json = json,
            credentialsProvider = credentialsProvider,
            connectionManager = connectionManager,
            sessionStorage = sessionStorage,
            outboxStorage = pendingOutboxStorage
        )
    }

    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(
            context = applicationContext,
            resilientDns = resilientDns
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Pre-warm DNS resolution for intra.lantaservice.com in background
        appScope.launch {
            runCatching { resilientDns.lookup(IntraResilientDns.FIXED_HOST) }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(180)
            .build()
    }
}
