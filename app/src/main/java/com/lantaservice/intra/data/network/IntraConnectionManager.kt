package com.lantaservice.intra.data.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import okhttp3.ConnectionSpec
import okhttp3.Dns
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
 * Multi-path failover helper for IntraService API:
 * - Detects active VPN transports via standard ConnectivityManager APIs
 * - Maintains an optional cellular secondary Network handle for Wi-Fi failover
 * - Retries failed requests across available non-VPN networks (Wi-Fi, Cellular)
 *   and fresh direct IPv4 connection before raising an actionable VPN exclusion error
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

    /**
     * Tries alternative routes (non-VPN Wi-Fi/Cellular networks and direct verified IPv4 socket)
     * when the primary connection fails or times out.
     */
    fun executeFailover(request: Request, primaryError: IOException): Response {
        var lastError: IOException = primaryError

        // 1. Try available non-VPN networks (works on bypassable VPNs & Wi-Fi/Cellular failover)
        val nonVpnNetworks = getNonVpnCandidateNetworks()
        for (network in nonVpnNetworks) {
            val client = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        resilientDns.lookupOnNetwork(hostname, network)
                })
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .build()

            val attempt = runCatching {
                client.newCall(request.newBuilder().header("Connection", "close").build()).execute()
            }
            if (attempt.isSuccess) {
                return attempt.getOrThrow()
            }
            val err = attempt.exceptionOrNull()
            if (err is IOException) {
                lastError = err
            }
        }

        // 2. Try fresh direct IPv4 socket (89.208.212.47) with TLS SNI intra.lantaservice.com
        if (request.url.host.equals(IntraResilientDns.FIXED_HOST, ignoreCase = true)) {
            val directClient = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname.equals(IntraResilientDns.FIXED_HOST, ignoreCase = true)) {
                            listOf(InetAddress.getByAddress(hostname, IntraResilientDns.VERIFIED_FALLBACK_IPV4))
                        } else {
                            resilientDns.lookup(hostname)
                        }
                    }
                })
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .build()

            val directAttempt = runCatching {
                directClient.newCall(request.newBuilder().header("Connection", "close").build()).execute()
            }
            if (directAttempt.isSuccess) {
                return directAttempt.getOrThrow()
            }
            val err = directAttempt.exceptionOrNull()
            if (err is IOException) {
                lastError = err
            }
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
    }
}
