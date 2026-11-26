package com.lantaservice.intra.data.geo

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.LocationPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-provider Android geolocation engine combining GNSS, Wi-Fi/Cell triangulation,
 * pre-entry breadcrumb caching, EXIF coordinates, and free reverse geocoding.
 */
class GeoLocationEngine(
    private val context: Context,
    private val json: Json,
    private val reverseGeocoderService: ReverseGeocoderService
) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val breadcrumbFile = File(context.filesDir, "last_good_geo.json")
    private val fileLock = Any()

    @Volatile
    private var lastVerifiedCandidate: GeoCandidate? = loadBreadcrumbFromDisk()

    @Volatile
    private var lastSnapshot: GeoAddressSnapshot? = null

    fun warmBreadcrumbAsync() {
        if (!LocationPermissionHelper.hasLocationPermission(context)) return
        engineScope.launch {
            runCatching {
                val collected = collectCandidatesFromProviders(timeoutMs = 2500L)
                val selected = GeoAntiSpoofSelector.selectBestCandidate(
                    candidates = collected,
                    lastVerified = lastVerifiedCandidate
                )
                if (selected != null && !selected.jammerSuspected) {
                    val src = selected.source
                    if (src == GeoSource.GPS || src == GeoSource.FUSED || src == GeoSource.NETWORK_WIFI_CELL) {
                        saveBreadcrumb(selected.candidate)
                    }
                }
            }
        }
    }

    suspend fun captureCurrentLocation(
        exifHint: Pair<Double, Double>? = null,
        timeoutMs: Long = 5000L
    ): GeoAddressSnapshot? = withContext(Dispatchers.IO) {
        val exifCandidate = exifHint?.let { (lat, lon) ->
            GeoCandidate(
                latitude = lat,
                longitude = lon,
                accuracyMeters = 35f,
                timestampMs = System.currentTimeMillis(),
                provider = "exif",
                isMock = false
            )
        }

        val liveCandidates = if (LocationPermissionHelper.hasLocationPermission(context)) {
            collectCandidatesFromProviders(timeoutMs = timeoutMs)
        } else {
            emptyList()
        }

        val selected = GeoAntiSpoofSelector.selectBestCandidate(
            candidates = liveCandidates,
            lastVerified = lastVerifiedCandidate,
            exifCandidate = exifCandidate
        ) ?: return@withContext lastSnapshot

        if (!selected.jammerSuspected &&
            (selected.source == GeoSource.GPS ||
                selected.source == GeoSource.FUSED ||
                selected.source == GeoSource.NETWORK_WIFI_CELL)
        ) {
            saveBreadcrumb(selected.candidate)
        }

        val lat = selected.candidate.latitude
        val lon = selected.candidate.longitude
        val resolvedAddr = reverseGeocoderService.resolveAddress(lat, lon)
        val nowText = DateAndTextFormatter.formatLocalDateTime(LocalDateTime.now())

        val snapshot = GeoAddressSnapshot(
            latitude = lat,
            longitude = lon,
            accuracyMeters = selected.candidate.accuracyMeters,
            capturedAtEpochMs = System.currentTimeMillis(),
            capturedAtText = nowText,
            source = selected.source,
            sourceLabel = selected.source.label,
            postalCode = resolvedAddr?.postalCode.orEmpty(),
            country = resolvedAddr?.country.orEmpty(),
            region = resolvedAddr?.region.orEmpty(),
            district = resolvedAddr?.district.orEmpty(),
            locality = resolvedAddr?.locality.orEmpty(),
            street = resolvedAddr?.street.orEmpty(),
            houseNumber = resolvedAddr?.houseNumber.orEmpty(),
            fullAddress = resolvedAddr?.fullAddress.orEmpty(),
            addressResolved = resolvedAddr != null && resolvedAddr.fullAddress.isNotBlank(),
            jammerSuspected = selected.jammerSuspected,
            warningNote = selected.warningNote
        )
        lastSnapshot = snapshot
        snapshot
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectCandidatesFromProviders(timeoutMs: Long): List<GeoCandidate> {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return emptyList()
        if (!LocationPermissionHelper.hasLocationPermission(context)) return emptyList()

        val nowMs = System.currentTimeMillis()
        val found = ConcurrentHashMap<String, GeoCandidate>()

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.distinct()

        // 1. Inspect recent cached fixes from each provider first
        providers.forEach { providerName ->
            runCatching {
                val loc = manager.getLastKnownLocation(providerName)
                if (loc != null) {
                    val ageMs = nowMs - loc.time
                    if (ageMs in -5_000L..180_000L) {
                        found[providerName] = loc.toGeoCandidate(providerName)
                    }
                }
            }
        }

        // If we already have both fresh GPS & Network fixes within 20s, skip active waiting
        val freshGps = found[LocationManager.GPS_PROVIDER]
        val freshNet = found[LocationManager.NETWORK_PROVIDER]
        if (freshGps != null && freshNet != null &&
            (nowMs - freshGps.timestampMs) <= 20_000L &&
            (nowMs - freshNet.timestampMs) <= 20_000L
        ) {
            return found.values.toList()
        }

        // 2. Request live simultaneous updates from GPS, Network, and Fused providers
        val activeProviders = providers.filter { it != LocationManager.PASSIVE_PROVIDER }
        val listeners = mutableListOf<Pair<String, LocationListener>>()

        withContext(Dispatchers.Main) {
            activeProviders.forEach { providerName ->
                runCatching {
                    val enabled = runCatching { manager.isProviderEnabled(providerName) }.getOrDefault(false)
                    if (!enabled && providerName != LocationManager.FUSED_PROVIDER) return@runCatching
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            val actualProvider = location.provider ?: providerName
                            found[actualProvider] = location.toGeoCandidate(actualProvider)
                            found[providerName] = location.toGeoCandidate(providerName)
                        }

                        @Deprecated("Deprecated in Android API")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    manager.requestLocationUpdates(
                        providerName,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    listeners += providerName to listener
                }
            }
        }

        try {
            val stepMs = 250L
            var elapsed = 0L
            while (elapsed < timeoutMs) {
                val hasGpsOrFused = found[LocationManager.GPS_PROVIDER]?.let {
                    it.accuracyMeters <= 35f && it.timestampMs >= nowMs - 10_000L
                } == true
                val hasNet = found[LocationManager.NETWORK_PROVIDER]?.let {
                    it.timestampMs >= nowMs - 10_000L
                } == true
                if (hasGpsOrFused && hasNet) {
                    break
                }
                delay(stepMs)
                elapsed += stepMs
            }
        } finally {
            withContext(Dispatchers.Main) {
                listeners.forEach { (_, listener) ->
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }

        // Fall back to older passive/lastKnown if live wait produced zero fixes
        if (found.isEmpty()) {
            providers.forEach { providerName ->
                runCatching {
                    manager.getLastKnownLocation(providerName)?.let { loc ->
                        found[providerName] = loc.toGeoCandidate(providerName)
                    }
                }
            }
        }

        return found.values.toList()
    }

    private fun Location.toGeoCandidate(fallbackProvider: String): GeoCandidate {
        val mockFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
        return GeoCandidate(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else 100f,
            timestampMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            provider = provider ?: fallbackProvider,
            isMock = mockFlag
        )
    }

    private fun loadBreadcrumbFromDisk(): GeoCandidate? {
        synchronized(fileLock) {
            if (!breadcrumbFile.exists()) return null
            val raw = runCatching { breadcrumbFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (raw.isBlank()) return null
            return runCatching { json.decodeFromString(GeoCandidate.serializer(), raw) }.getOrNull()
        }
    }

    private fun saveBreadcrumb(candidate: GeoCandidate) {
        if (!GeoAntiSpoofSelector.isValidCoordinate(candidate.latitude, candidate.longitude)) return
        lastVerifiedCandidate = candidate
        synchronized(fileLock) {
            runCatching {
                breadcrumbFile.writeText(
                    json.encodeToString(GeoCandidate.serializer(), candidate),
                    Charsets.UTF_8
                )
            }
        }
    }
}
