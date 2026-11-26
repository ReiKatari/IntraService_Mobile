package com.lantaservice.intra.data.network

import android.os.Build
import com.lantaservice.intra.BuildConfig
import com.lantaservice.intra.data.model.UserSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds active authenticated user credentials in memory and enforces strict auth isolation
 * against the locked IntraService server URL.
 */
class SessionCredentialsProvider {

    private val sessionRef = AtomicReference<UserSession?>(null)

    private val _unauthorizedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val unauthorizedEvents: SharedFlow<Unit> = _unauthorizedEvents.asSharedFlow()

    fun currentSession(): UserSession? = sessionRef.get()

    fun activeBaseUrl(): String = FIXED_BASE_URL

    fun setSession(session: UserSession) {
        sessionRef.set(session.copy(baseUrl = FIXED_BASE_URL))
    }

    fun clearSession() {
        sessionRef.set(null)
    }

    fun notifyUnauthorized() {
        clearSession()
        _unauthorizedEvents.tryEmit(Unit)
    }

    companion object {
        const val FIXED_BASE_URL = BuildConfig.FIXED_BASE_URL

        fun buildBasicAuthHeader(login: String, password: String): String {
            val raw = "$login:$password"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
            return "Basic $encoded"
        }
    }
}

object IntraNetworkFactory {

    fun createOkHttpClient(
        credentialsProvider: SessionCredentialsProvider,
        resilientDns: IntraResilientDns,
        connectionManager: IntraConnectionManager
    ): OkHttpClient {
        val deviceName = sanitizeHeaderAscii("Android ${Build.MANUFACTURER} ${Build.MODEL}")
        val deviceVersion = sanitizeHeaderAscii(BuildConfig.VERSION_NAME)

        val authAndFailoverInterceptor = Interceptor { chain ->
            val original = chain.request()
            if (original.header("X-Intra-Prewarm") == "1") {
                val warmReq = original.newBuilder().removeHeader("X-Intra-Prewarm").build()
                return@Interceptor chain.proceed(warmReq)
            }
            val explicitAuth = original.header("Authorization")
            val session = credentialsProvider.currentSession()

            val builder = original.newBuilder()
                .header("Device-Name", deviceName)
                .header("Device-Version", deviceVersion)

            when {
                !explicitAuth.isNullOrBlank() -> {
                    builder.header("Authorization", explicitAuth)
                }
                session != null && !session.authCookieHeader.isNullOrBlank() -> {
                    builder.header("Cookie", session.authCookieHeader)
                }
                session != null -> {
                    builder.header(
                        "Authorization",
                        SessionCredentialsProvider.buildBasicAuthHeader(session.login, session.password)
                    )
                }
                else -> throw IOException("Требуется авторизация")
            }

            if (original.header("Accept") == null) {
                builder.header("Accept", "application/json")
            }

            val authedRequest = builder.build()
            val response = try {
                chain.proceed(authedRequest)
            } catch (io: IOException) {
                val isCancelled = Thread.currentThread().isInterrupted ||
                    io.message?.contains("Canceled", ignoreCase = true) == true
                if (isCancelled || authedRequest.body?.isOneShot() == true) {
                    throw io
                }
                connectionManager.executeFailover(authedRequest, io)
            }

            if (response.code == 401 && explicitAuth.isNullOrBlank() && session != null) {
                credentialsProvider.notifyUnauthorized()
            }
            response
        }

        val dispatcher = Dispatcher().apply {
            maxRequests = 48
            maxRequestsPerHost = 16
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .dns(resilientDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .addInterceptor(authAndFailoverInterceptor)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createApiService(okHttpClient: OkHttpClient, json: Json): IntraApiService {
        val baseUrl = "${SessionCredentialsProvider.FIXED_BASE_URL.trimEnd('/')}/"
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(IntraApiService::class.java)
    }

    fun sanitizeHeaderAscii(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch in ' '..'~') ch else ' '
        }.joinToString("").trim().replace(Regex("\\s+"), " ")
        return cleaned.ifEmpty { "Android" }.take(64)
    }
}
