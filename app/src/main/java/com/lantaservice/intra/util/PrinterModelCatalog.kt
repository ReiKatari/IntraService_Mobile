package com.lantaservice.intra.util

import android.content.Context
import androidx.compose.runtime.Immutable
import com.lantaservice.intra.data.network.IntraResilientDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

@Immutable
@Serializable
data class PrinterCatalogEntry(
    val brand: String,
    val model: String,
    val category: String
) {
    val fullName: String
        get() = if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
}

/**
 * Pure JVM fast searchable index and matcher over the official printer/MFP catalog.
 */
object PrinterModelCatalogEngine {

    data class IndexedEntry(
        val entry: PrinterCatalogEntry,
        val fullLower: String,
        val modelLower: String,
        val compactLower: String,
        val compactModelLower: String
    )

    private val catalogJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val priorityBrands = listOf(
        "HP", "Kyocera", "Lexmark", "Xerox", "Canon", "Ricoh",
        "Konica Minolta", "Pantum", "Катюша", "Brother", "Epson",
        "Samsung", "Sharp", "Toshiba", "OKI", "Zebra", "TSC"
    )

    fun parseCatalogJson(rawJson: String, json: Json = catalogJson): List<PrinterCatalogEntry> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PrinterCatalogEntry.serializer()), rawJson)
                .filter { it.brand.isNotBlank() && it.model.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun buildIndex(entries: List<PrinterCatalogEntry>): List<IndexedEntry> {
        return entries.map { entry ->
            val fullLower = entry.fullName.lowercase(Locale.ROOT)
            val modelLower = entry.model.lowercase(Locale.ROOT)
            IndexedEntry(
                entry = entry,
                fullLower = fullLower,
                modelLower = modelLower,
                compactLower = compact(fullLower),
                compactModelLower = compact(modelLower)
            )
        }
    }

    fun availableBrands(entries: List<PrinterCatalogEntry>): List<String> {
        val present = entries.map { it.brand.trim() }.filter { it.isNotEmpty() }.distinct()
        val rank = priorityBrands.withIndex().associate { (idx, name) -> name.lowercase(Locale.ROOT) to idx }
        return present.sortedWith(
            compareBy<String>(
                { rank[it.lowercase(Locale.ROOT)] ?: Int.MAX_VALUE },
                { it.lowercase(Locale.ROOT) }
            )
        )
    }

    fun search(
        indexed: List<IndexedEntry>,
        query: String,
        brandFilter: String? = null,
        limit: Int = 60
    ): List<PrinterCatalogEntry> {
        val brandFiltered = if (brandFilter.isNullOrBlank()) {
            indexed
        } else {
            val bf = brandFilter.trim()
            indexed.filter { it.entry.brand.equals(bf, ignoreCase = true) }
        }

        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) {
            return brandFiltered.take(limit).map { it.entry }
        }

        val queryTokens = cleanQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val compactQuery = compact(cleanQuery)

        val scored = ArrayList<Pair<Int, PrinterCatalogEntry>>(minOf(brandFiltered.size, limit * 2))
        for (item in brandFiltered) {
            val matchesTokens = queryTokens.all { tok ->
                item.fullLower.contains(tok) || item.entry.category.lowercase(Locale.ROOT).contains(tok)
            }
            val matchesCompact = compactQuery.length >= 2 && item.compactLower.contains(compactQuery)
            if (!matchesTokens && !matchesCompact) continue

            val score = when {
                item.modelLower == cleanQuery || item.fullLower == cleanQuery -> 0
                item.modelLower.startsWith(cleanQuery) || item.fullLower.startsWith(cleanQuery) -> 1
                item.compactModelLower.startsWith(compactQuery) || item.compactLower.startsWith(compactQuery) -> 2
                matchesTokens -> 3
                else -> 4
            }
            scored += score to item.entry
        }

        return scored
            .sortedWith(compareBy<Pair<Int, PrinterCatalogEntry>>({ it.first }, { it.second.fullName.lowercase(Locale.ROOT) }))
            .take(limit)
            .map { it.second }
    }

    fun matchModelFromText(
        entries: List<PrinterCatalogEntry>,
        rawText: String
    ): String? {
        if (entries.isEmpty() || rawText.isBlank()) return null
        val normalizedRaw = rawText.lowercase(Locale.ROOT)
        val compactRaw = compact(normalizedRaw)
        if (compactRaw.length < 4) return null

        // Prefer longest exact model-code matches containing digits + letters (e.g. mx622ade, m2040dn, lbp226dw)
        val sortedBySpecificity = entries.sortedByDescending { it.model.length }
        for (entry in sortedBySpecificity) {
            val brandLower = entry.brand.lowercase(Locale.ROOT)
            val modelLower = entry.model.lowercase(Locale.ROOT)
            val compactModel = compact(modelLower)
            if (normalizedRaw.contains(modelLower) && (normalizedRaw.contains(brandLower) || modelLower.length >= 5)) {
                return entry.fullName
            }
            val hasAlphaAndDigit = compactModel.any { it.isLetter() } && compactModel.any { it.isDigit() }
            if (hasAlphaAndDigit && compactModel.length >= 6 && compactRaw.contains(compactModel)) {
                return entry.fullName
            }
        }
        return null
    }

    private fun compact(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }
}

object PrinterModelCatalog {

    private const val REMOTE_CATALOG_URL =
        "https://raw.githubusercontent.com/ReiKatari/IntraService_Mobile/main/app/src/main/assets/printer_models_catalog.json"
    private const val PREFS_NAME = "intra_printer_catalog_prefs"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val CACHE_FILE_NAME = "printer_models_catalog_cache.json"
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    private val _entries = MutableStateFlow<List<PrinterCatalogEntry>>(emptyList())
    val entries: StateFlow<List<PrinterCatalogEntry>> = _entries.asStateFlow()

    fun currentEntries(): List<PrinterCatalogEntry> = _entries.value

    fun ensureLoaded(context: Context) {
        if (loaded && _entries.value.isNotEmpty()) return
        synchronized(loadLock) {
            if (loaded && _entries.value.isNotEmpty()) return
            val appCtx = context.applicationContext
            val bundledRaw = runCatching {
                appCtx.assets.open("printer_models_catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("")
            val bundledList = PrinterModelCatalogEngine.parseCatalogJson(bundledRaw)

            val cacheFile = File(appCtx.filesDir, CACHE_FILE_NAME)
            val cachedList = if (cacheFile.exists()) {
                val cachedRaw = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrDefault("")
                PrinterModelCatalogEngine.parseCatalogJson(cachedRaw)
            } else {
                emptyList()
            }

            _entries.value = if (cachedList.size >= bundledList.size && cachedList.isNotEmpty()) {
                cachedList
            } else {
                bundledList
            }
            loaded = true
        }
    }

    fun refreshCatalogInBackgroundAsync(context: Context, resilientDns: IntraResilientDns) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        if (System.currentTimeMillis() - lastSync < REFRESH_INTERVAL_MS) return

        scope.launch {
            runCatching {
                ensureLoaded(appCtx)
                val client = OkHttpClient.Builder()
                    .dns(resilientDns)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(REMOTE_CATALOG_URL)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val remoteEntries = PrinterModelCatalogEngine.parseCatalogJson(body)
                    if (remoteEntries.size >= 200) {
                        File(appCtx.filesDir, CACHE_FILE_NAME).writeText(body, Charsets.UTF_8)
                        _entries.value = remoteEntries
                        loaded = true
                        prefs.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()
                    }
                }
            }
        }
    }
}
