package com.lantaservice.intra.data.network

import android.content.Context
import android.net.Network
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Multi-stage resilient DNS resolver with in-memory TTL cache and optional Android Network binding:
 * 1. System / specific-Network DNS (preferring IPv4 ahead of IPv6)
 * 2. Direct UDP port 53 A-record lookup against public resolvers (77.88.8.8, 8.8.8.8)
 * 3. Direct DNS-over-HTTPS (JSON) against 8.8.8.8 / 1.1.1.1
 * 4. Last-good cached or verified fallback IPv4 for intra.lantaservice.com (89.208.212.47)
 */
class IntraResilientDns(context: Context) : Dns {

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memCache = ConcurrentHashMap<String, CachedDnsEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        return lookupOnNetwork(hostname = hostname, network = null)
    }

    fun lookupOnNetwork(hostname: String, network: Network?): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase(Locale.ROOT)
        if (cleanHost.isEmpty()) throw UnknownHostException("Empty host")

        val cacheKey = "${cleanHost}|${network?.toString() ?: "default"}"
        val now = System.currentTimeMillis()
        val cachedEntry = memCache[cacheKey]
        if (cachedEntry != null && now < cachedEntry.expiresAtMs && cachedEntry.addresses.isNotEmpty()) {
            return cachedEntry.addresses
        }

        // Stage 1: System or bound-network DNS (preferring IPv4)
        val stage1 = runCatching {
            if (network != null) {
                network.getAllByName(hostname).toList()
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }.getOrNull().orEmpty()

        if (stage1.isNotEmpty()) {
            val ordered = orderIpv4First(stage1)
            cacheResolved(cacheKey, cleanHost, ordered)
            return ordered
        }

        // Stage 2: Direct UDP port 53 A-record query
        val udpAddrs = resolveViaUdpDns(hostname, network)
        if (udpAddrs.isNotEmpty()) {
            cacheResolved(cacheKey, cleanHost, udpAddrs)
            return udpAddrs
        }

        // Stage 3: Direct DoH JSON query by numeric IP
        val dohAddrs = resolveViaDohJson(hostname, network)
        if (dohAddrs.isNotEmpty()) {
            cacheResolved(cacheKey, cleanHost, dohAddrs)
            return dohAddrs
        }

        // Stage 4: Cached / verified fallback IPv4 for fixed intra.lantaservice.com server
        if (cleanHost == FIXED_HOST) {
            val bytes = loadCachedIpv4() ?: VERIFIED_FALLBACK_IPV4
            return listOf(InetAddress.getByAddress(hostname, bytes))
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\"")
    }

    private fun orderIpv4First(list: List<InetAddress>): List<InetAddress> {
        val ipv4 = list.filterIsInstance<Inet4Address>()
        val others = list.filterNot { it is Inet4Address }
        return ipv4 + others
    }

    private fun cacheResolved(cacheKey: String, cleanHost: String, addresses: List<InetAddress>) {
        memCache[cacheKey] = CachedDnsEntry(
            addresses = addresses,
            expiresAtMs = System.currentTimeMillis() + MEM_CACHE_TTL_MS
        )
        if (cleanHost == FIXED_HOST) {
            val firstIpv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull()
            if (firstIpv4 != null) {
                cacheLastGoodIpv4(firstIpv4.address)
            }
        }
    }

    private fun resolveViaUdpDns(hostname: String, network: Network?): List<InetAddress> {
        val txId = Random.nextInt(1, 0xFFFF)
        val query = buildDnsQueryPacket(hostname, txId)

        for (dnsIp in PUBLIC_DNS_IPV4) {
            val resolvedBytes = runCatching {
                val dnsBytes = parseIpv4Bytes(dnsIp) ?: return@runCatching emptyList<ByteArray>()
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1200
                    if (network != null) {
                        network.bindSocket(socket)
                    }
                    val serverAddr = InetAddress.getByAddress(dnsBytes)
                    val outPacket = DatagramPacket(query, query.size, serverAddr, 53)
                    socket.send(outPacket)

                    val buf = ByteArray(512)
                    val inPacket = DatagramPacket(buf, buf.size)
                    socket.receive(inPacket)
                    parseDnsARecords(buf, inPacket.length, expectedTxId = txId)
                }
            }.getOrNull().orEmpty()

            if (resolvedBytes.isNotEmpty()) {
                return resolvedBytes.map { InetAddress.getByAddress(hostname, it) }
            }
        }
        return emptyList()
    }

    private fun resolveViaDohJson(hostname: String, network: Network?): List<InetAddress> {
        val dohClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .apply {
                if (network != null) {
                    socketFactory(network.socketFactory)
                }
            }
            .build()

        val dohUrls = listOf(
            "https://8.8.8.8/resolve?name=$hostname&type=A",
            "https://1.1.1.1/dns-query?name=$hostname&type=A"
        )
        for (url in dohUrls) {
            val ips = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<ByteArray>()
                    val body = resp.body?.string().orEmpty()
                    parseDohJsonARecords(body)
                }
            }.getOrNull().orEmpty()

            if (ips.isNotEmpty()) {
                return ips.map { InetAddress.getByAddress(hostname, it) }
            }
        }
        return emptyList()
    }

    private fun cacheLastGoodIpv4(bytes: ByteArray) {
        if (bytes.size != 4) return
        val ipStr = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        prefs.edit().putString(KEY_CACHED_IPV4, ipStr).apply()
    }

    private fun loadCachedIpv4(): ByteArray? {
        val saved = prefs.getString(KEY_CACHED_IPV4, null) ?: return null
        return parseIpv4Bytes(saved)
    }

    companion object {
        const val FIXED_HOST = "intra.lantaservice.com"
        private const val PREFS_NAME = "intra_dns_cache"
        private const val KEY_CACHED_IPV4 = "cached_intra_ipv4"
        private const val MEM_CACHE_TTL_MS = 10 * 60_000L

        val VERIFIED_FALLBACK_IPV4 = byteArrayOf(
            89,
            208.toByte(),
            212.toByte(),
            47
        )

        private val PUBLIC_DNS_IPV4 = listOf(
            "77.88.8.8",
            "8.8.8.8"
        )

        private val dohJson = Json { ignoreUnknownKeys = true }

        fun buildDnsQueryPacket(hostname: String, transactionId: Int): ByteArray {
            val out = ByteArrayOutputStream(64)
            out.write((transactionId ushr 8) and 0xFF)
            out.write(transactionId and 0xFF)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)

            hostname.trim('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                val len = bytes.size.coerceAtMost(63)
                out.write(len)
                out.write(bytes, 0, len)
            }
            out.write(0x00)
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x01)
            return out.toByteArray()
        }

        fun parseDnsARecords(
            packet: ByteArray,
            length: Int,
            expectedTxId: Int
        ): List<ByteArray> {
            if (length < 12 || length > packet.size) return emptyList()
            val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
            if (txId != expectedTxId) return emptyList()

            val rcode = packet[3].toInt() and 0x0F
            if (rcode != 0) return emptyList()

            val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
            val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

            var pos = 12
            repeat(qdCount) {
                pos = skipDnsName(packet, pos, length)
                if (pos < 0 || pos + 4 > length) return emptyList()
                pos += 4
            }

            val results = mutableListOf<ByteArray>()
            repeat(anCount) {
                pos = skipDnsName(packet, pos, length)
                if (pos < 0 || pos + 10 > length) return results
                val type = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                val clazz = ((packet[pos + 2].toInt() and 0xFF) shl 8) or (packet[pos + 3].toInt() and 0xFF)
                val rdLength = ((packet[pos + 8].toInt() and 0xFF) shl 8) or (packet[pos + 9].toInt() and 0xFF)
                pos += 10
                if (pos + rdLength > length) return results
                if (type == 1 && clazz == 1 && rdLength == 4) {
                    results += packet.copyOfRange(pos, pos + 4)
                }
                pos += rdLength
            }
            return results
        }

        private fun skipDnsName(packet: ByteArray, start: Int, limit: Int): Int {
            var pos = start
            var steps = 0
            while (pos < limit && steps++ < 64) {
                val len = packet[pos].toInt() and 0xFF
                if (len == 0) return pos + 1
                if ((len and 0xC0) == 0xC0) {
                    return if (pos + 2 <= limit) pos + 2 else -1
                }
                pos += 1 + len
            }
            return -1
        }

        fun parseDohJsonARecords(jsonBody: String): List<ByteArray> {
            if (jsonBody.isBlank()) return emptyList()
            return runCatching {
                val root = dohJson.parseToJsonElement(jsonBody).jsonObject
                val answers = root["Answer"]?.jsonArray ?: return emptyList()
                answers.mapNotNull { elem ->
                    val obj = elem as? JsonObject ?: return@mapNotNull null
                    val type = obj["type"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    if (type != 1) return@mapNotNull null
                    val ipText = obj["data"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    parseIpv4Bytes(ipText)
                }
            }.getOrDefault(emptyList())
        }

        fun parseIpv4Bytes(ipText: String): ByteArray? {
            val parts = ipText.trim().split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val num = parts[i].toIntOrNull() ?: return null
                if (num !in 0..255) return null
                bytes[i] = num.toByte()
            }
            return bytes
        }
    }
}
