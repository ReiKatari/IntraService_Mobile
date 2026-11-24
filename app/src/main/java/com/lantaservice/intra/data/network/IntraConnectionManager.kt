package com.lantaservice.intra.data.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import com.lantaservice.intra.BuildConfig
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VpnBlockedConnectionException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Multi-path failover helper for IntraService API and web-form email login:
 * - Detects active VPN transports via standard ConnectivityManager APIs
 * - Maintains an optional cellular secondary Network handle for Wi-Fi failover
 * - Retries failed requests across non-VPN networks (Wi-Fi, Cellular) and direct verified IPv4
 * - Supports IntraService ASP.NET web-form login for resolving email addresses (e.g. name@domain)
 */
class IntraConnectionManager(
    context: Context,
    private val resilientDns: IntraResilientDns
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val cellularNetworkRef = AtomicReference<Network?>(null)

    init {
        registerPassiveCellularRequest()
    }

    private fun registerPassiveCellularRequest() {
        runCatching {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.requestNetwork(
                req,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cellularNetworkRef.set(network)
                    }

                    override fun onLost(network: Network) {
                        cellularNetworkRef.compareAndSet(network, null)
                    }
                }
            )
        }
    }

    fun isVpnActive(): Boolean {
        val activeCaps = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return true
        }
        return runCatching {
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.any { net ->
                connectivityManager.getNetworkCapabilities(net)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    fun getNonVpnCandidateNetworks(): List<Network> {
        val candidates = LinkedHashMap<String, Pair<Int, Network>>()
        runCatching {
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.forEach { net ->
                val caps = connectivityManager.getNetworkCapabilities(net) ?: return@forEach
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (hasInternet && notVpn) {
                    val priority = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3
                        else -> 4
                    }
                    candidates[net.toString()] = priority to net
                }
            }
        }
        cellularNetworkRef.get()?.let { cellNet ->
            val key = cellNet.toString()
            if (!candidates.containsKey(key)) {
                candidates[key] = 3 to cellNet
            }
        }
        return candidates.values.sortedBy { it.first }.map { it.second }
    }

    fun executeFailover(
        request: Request,
        primaryError: IOException,
        followRedirects: Boolean = true
    ): Response {
        var lastError: IOException = primaryError

        val nonVpnNetworks = getNonVpnCandidateNetworks()
        for (network in nonVpnNetworks) {
            val client = buildClient(
                dns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        resilientDns.lookupOnNetwork(hostname, network)
                },
                network = network,
                connectTimeoutSec = 6,
                followRedirects = followRedirects
            )
            val attempt = runCatching {
                client.newCall(request.newBuilder().header("Connection", "close").build()).execute()
            }
            if (attempt.isSuccess) return attempt.getOrThrow()
            val err = attempt.exceptionOrNull()
            if (err is IOException) lastError = err
        }

        if (request.url.host.equals(IntraResilientDns.FIXED_HOST, ignoreCase = true)) {
            val directClient = buildClient(
                dns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname.equals(IntraResilientDns.FIXED_HOST, ignoreCase = true)) {
                            listOf(InetAddress.getByAddress(hostname, IntraResilientDns.VERIFIED_FALLBACK_IPV4))
                        } else {
                            resilientDns.lookup(hostname)
                        }
                    }
                },
                network = null,
                connectTimeoutSec = 5,
                followRedirects = followRedirects
            )
            val directAttempt = runCatching {
                directClient.newCall(request.newBuilder().header("Connection", "close").build()).execute()
            }
            if (directAttempt.isSuccess) return directAttempt.getOrThrow()
            val err = directAttempt.exceptionOrNull()
            if (err is IOException) lastError = err
        }

        if (isVpnActive()) {
            throw VpnBlockedConnectionException(
                "Сервер intra.lantaservice.com не отвечает через активный VPN. " +
                    "Добавьте IntraService Mobile в исключения VPN или отключите VPN.",
                lastError
            )
        }
        throw lastError
    }

    /**
     * Authenticates against the IntraService web login form (supports email/login/domain lookup)
     * and returns the Cookie header string if ASP.NET Forms Auth succeeded.
     */
    fun authenticateViaWebForm(loginInput: String, password: String): String? {
        val baseUrl = SessionCredentialsProvider.FIXED_BASE_URL.trimEnd('/')
        val collectedCookies = LinkedHashMap<String, String>()

        val attempts = listOf(
            "$baseUrl/" to FormBody.Builder()
                .add("login", loginInput)
                .add("password", password)
                .add("rememberme", "true")
                .build(),
            "$baseUrl/Login?ReturnUrl=%2F" to FormBody.Builder()
                .add("Login", loginInput)
                .add("Password", password)
                .add("RememberMe", "true")
                .build()
        )

        for ((url, formBody) in attempts) {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "text/html,application/xhtml+xml,application/json")
                .header("User-Agent", "IntraService-Mobile/${BuildConfig.VERSION_NAME}")
                .post(formBody)

            if (collectedCookies.isNotEmpty()) {
                reqBuilder.header("Cookie", formatCookieHeader(collectedCookies))
            }

            executeWithDirectOrFailover(reqBuilder.build(), followRedirects = false).use { resp ->
                val newCookies = parseSetCookiesHeader(resp.headers("Set-Cookie"))
                collectedCookies.putAll(newCookies)

                val hasAuthCookie = collectedCookies.keys.any {
                    it.equals(".ASPXAUTH", ignoreCase = true) ||
                        it.contains("AUTH", ignoreCase = true)
                }
                val isRedirect = resp.code in 300..399
                if (hasAuthCookie || (isRedirect && collectedCookies.isNotEmpty())) {
                    return formatCookieHeader(collectedCookies)
                }
            }
        }
        return null
    }

    fun fetchCurrentUserWithCookie(cookieHeader: String): Response {
        val baseUrl = SessionCredentialsProvider.FIXED_BASE_URL.trimEnd('/')
        val req = Request.Builder()
            .url("$baseUrl/api/user?getcurrentuserinfo=true")
            .header("Accept", "application/json")
            .header("Cookie", cookieHeader)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        return executeWithDirectOrFailover(req, followRedirects = true)
    }

    private fun executeWithDirectOrFailover(request: Request, followRedirects: Boolean): Response {
        val primaryClient = buildClient(
            dns = resilientDns,
            network = null,
            connectTimeoutSec = 7,
            followRedirects = followRedirects
        )
        return try {
            primaryClient.newCall(request).execute()
        } catch (io: IOException) {
            val isCancelled = Thread.currentThread().isInterrupted ||
                io.message?.contains("Canceled", ignoreCase = true) == true
            if (isCancelled) throw io
            executeFailover(request, io, followRedirects = followRedirects)
        }
    }

    private fun buildClient(
        dns: Dns,
        network: Network?,
        connectTimeoutSec: Long,
        followRedirects: Boolean
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(dns)
            .apply {
                if (network != null) {
                    socketFactory(network.socketFactory)
                }
            }
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        fun openVpnSettings(context: Context) {
            val vpnIntent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(vpnIntent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallback) }
            }
        }

        fun parseSetCookiesHeader(headers: List<String>): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (raw in headers) {
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        map[name] = value
                    }
                }
            }
            return map
        }

        fun formatCookieHeader(cookies: Map<String, String>): String {
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}
