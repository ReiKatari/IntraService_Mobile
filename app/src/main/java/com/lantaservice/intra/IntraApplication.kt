package com.lantaservice.intra

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.IntraNetworkFactory
import com.lantaservice.intra.data.network.IntraResilientDns
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.SecureSessionStorage
import com.lantaservice.intra.data.update.AppUpdateManager
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
            sessionStorage = sessionStorage
        )
    }

    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(
            context = applicationContext,
            resilientDns = resilientDns
        )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }
}
