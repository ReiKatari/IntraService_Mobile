package com.lantaservice.intra.data.geo

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
@Serializable
enum class GeoSource(val label: String) {
    GPS("GPS / ГЛОНАСС"),
    FUSED("Комплексная геолокация"),
    NETWORK_WIFI_CELL("Wi-Fi / сотовая сеть"),
    NETWORK_ANTI_JAMMER("Wi-Fi / сотовая сеть (защита от помех GPS)"),
    EXIF_PHOTO("Геометка фото"),
    PRE_ENTRY_CACHE("Последняя точка до входа на объект"),
    PASSIVE_LAST_KNOWN("Последняя известная точка"),
    UNAVAILABLE("Не определено")
}

@Immutable
@Serializable
data class GeoCandidate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val provider: String,
    val isMock: Boolean = false
)

@Immutable
@Serializable
data class GeoAddressSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtEpochMs: Long,
    val capturedAtText: String,
    val source: GeoSource,
    val sourceLabel: String = source.label,
    val postalCode: String = "",
    val country: String = "",
    val region: String = "",
    val district: String = "",
    val locality: String = "",
    val street: String = "",
    val houseNumber: String = "",
    val fullAddress: String = "",
    val addressResolved: Boolean = false,
    val jammerSuspected: Boolean = false,
    val warningNote: String? = null
) {
    fun formatCoordinates(): String {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    }

    fun formatSummaryForComment(): String {
        val lines = mutableListOf<String>()
        val addressPart = fullAddress.trim()
        if (addressPart.isNotEmpty()) {
            lines += "Геолокация: $addressPart"
        } else {
            lines += "Геолокация: адрес уточняется (офлайн)"
        }
        val accText = if (accuracyMeters > 0f) "~${accuracyMeters.roundToInt()} м" else "не указана"
        lines += "Координаты: ${formatCoordinates()} (точность $accText, источник: $sourceLabel, время: $capturedAtText)"
        warningNote?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
            lines += "Примечание по навигации: $note"
        }
        return lines.joinToString("\n")
    }
}

data class SelectedGeoResult(
    val candidate: GeoCandidate,
    val source: GeoSource,
    val jammerSuspected: Boolean,
    val warningNote: String? = null
)

/**
 * Pure JVM anti-jammer and anti-spoof geolocation candidate selector.
 * Combines GNSS (GPS/GLONASS), Wi-Fi + Cellular Network fixes, EXIF photo hint,
 * and pre-entry cached breadcrumbs when GNSS is jammed or spoofed.
 */
object GeoAntiSpoofSelector {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MAX_DIVERGENCE_METERS = 1500.0
    private const val MAX_NETWORK_ACCURACY_FOR_CHECK = 500f
    private const val MAX_PLAUSIBLE_SPEED_MPS = 83.3 // 300 km/h
    private const val TELEPORT_MIN_DISTANCE_METERS = 2000.0
    private const val TELEPORT_MAX_WINDOW_MS = 15L * 60L * 1000L
    private const val PRE_ENTRY_MAX_AGE_MS = 2L * 60L * 60L * 1000L

    private val dtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return false
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
        if (abs(lat) < 1e-6 && abs(lon) < 1e-6) return false
        return true
    }

    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun selectBestCandidate(
        candidates: List<GeoCandidate>,
        lastVerified: GeoCandidate?,
        exifCandidate: GeoCandidate? = null,
        nowMs: Long = System.currentTimeMillis()
    ): SelectedGeoResult? {
        val validLive = candidates.filter { isValidCoordinate(it.latitude, it.longitude) }
        val nonMockLive = validLive.filter { !it.isMock }
        val pool = if (nonMockLive.isNotEmpty()) nonMockLive else validLive

        val gps = pickBestForProvider(pool, "gps")
        val fused = pickBestForProvider(pool, "fused")
        val network = pickBestForProvider(pool, "network")

        // Check 1: GNSS spoofing/jamming via divergence from Wi-Fi / Cellular fix
        if (gps != null && network != null && network.accuracyMeters <= MAX_NETWORK_ACCURACY_FOR_CHECK) {
            val divergence = haversineMeters(
                gps.latitude,
                gps.longitude,
                network.latitude,
                network.longitude
            )
            if (divergence > MAX_DIVERGENCE_METERS) {
                return SelectedGeoResult(
                    candidate = network,
                    source = GeoSource.NETWORK_ANTI_JAMMER,
                    jammerSuspected = true,
                    warningNote = "Обнаружены помехи или подмена GPS (расхождение с базовыми станциями ${divergence.roundToInt()} м) - координаты определены по Wi-Fi и сотовым вышкам"
                )
            }
        }

        // Check 2: Impossible teleportation speed relative to last verified pre-entry point
        val gpsTeleported = isImpossibleJump(gps, lastVerified)
        val fusedTeleported = isImpossibleJump(fused, lastVerified)

        val validGps = if (gpsTeleported) null else gps
        val validFused = if (fusedTeleported) null else fused

        if ((gpsTeleported || fusedTeleported) && network != null && !isImpossibleJump(network, lastVerified)) {
            return SelectedGeoResult(
                candidate = network,
                source = GeoSource.NETWORK_ANTI_JAMMER,
                jammerSuspected = true,
                warningNote = "Зафиксирован резкий скачок спутникового сигнала (возможна глушилка/спуфинг) - использована триангуляция Wi-Fi и сотовой сети"
            )
        }

        // Check 3: Direct GNSS available and clean
        if (validGps != null && validFused != null) {
            val pickGps = validGps.accuracyMeters <= validFused.accuracyMeters
            return if (pickGps) {
                SelectedGeoResult(validGps, GeoSource.GPS, jammerSuspected = false)
            } else {
                SelectedGeoResult(validFused, GeoSource.FUSED, jammerSuspected = false)
            }
        }
        if (validGps != null) {
            return SelectedGeoResult(validGps, GeoSource.GPS, jammerSuspected = false)
        }
        if (validFused != null) {
            return SelectedGeoResult(validFused, GeoSource.FUSED, jammerSuspected = false)
        }

        // Check 4: GNSS absent (e.g., indoor shielding or GPS jammer) -> Wi-Fi / Cell tower triangulation
        if (network != null && !isImpossibleJump(network, lastVerified)) {
            return SelectedGeoResult(
                candidate = network,
                source = GeoSource.NETWORK_WIFI_CELL,
                jammerSuspected = false,
                warningNote = null
            )
        }

        // Check 5: EXIF coordinates embedded in photo
        if (exifCandidate != null && isValidCoordinate(exifCandidate.latitude, exifCandidate.longitude)) {
            return SelectedGeoResult(
                candidate = exifCandidate,
                source = GeoSource.EXIF_PHOTO,
                jammerSuspected = gpsTeleported,
                warningNote = "Координаты извлечены из метаданных фотографии"
            )
        }

        // Check 6: Pre-entry verified breadcrumb cached right before walking into jammed dead zone
        if (lastVerified != null && isValidCoordinate(lastVerified.latitude, lastVerified.longitude)) {
            val ageMs = (nowMs - lastVerified.timestampMs).coerceAtLeast(0L)
            val stamp = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(lastVerified.timestampMs),
                ZoneId.systemDefault()
            ).format(dtFormatter)
            return if (ageMs <= PRE_ENTRY_MAX_AGE_MS) {
                SelectedGeoResult(
                    candidate = lastVerified,
                    source = GeoSource.PRE_ENTRY_CACHE,
                    jammerSuspected = true,
                    warningNote = "На объекте нет сигнала навигации (глушилка или экранирование) - использована последняя подтвержденная точка до входа от $stamp"
                )
            } else {
                SelectedGeoResult(
                    candidate = lastVerified,
                    source = GeoSource.PASSIVE_LAST_KNOWN,
                    jammerSuspected = gpsTeleported,
                    warningNote = "Сигнал навигации недоступен - использована последняя известная точка от $stamp"
                )
            }
        }

        // Last resort: return whatever non-zero passive candidate was present even if flagged
        val fallbackAny = pool.minByOrNull { it.accuracyMeters }
        if (fallbackAny != null) {
            return SelectedGeoResult(
                candidate = fallbackAny,
                source = if (gpsTeleported || fusedTeleported) GeoSource.NETWORK_ANTI_JAMMER else GeoSource.PASSIVE_LAST_KNOWN,
                jammerSuspected = gpsTeleported || fusedTeleported,
                warningNote = if (gpsTeleported || fusedTeleported) {
                    "Возможны помехи GPS - рекомендуется перепроверить адрес"
                } else null
            )
        }
        return null
    }

    private fun pickBestForProvider(pool: List<GeoCandidate>, providerName: String): GeoCandidate? {
        return pool
            .filter { it.provider.equals(providerName, ignoreCase = true) }
            .sortedWith(compareBy<GeoCandidate> { it.accuracyMeters }.thenByDescending { it.timestampMs })
            .firstOrNull()
    }

    private fun isImpossibleJump(current: GeoCandidate?, previous: GeoCandidate?): Boolean {
        if (current == null || previous == null) return false
        if (!isValidCoordinate(current.latitude, current.longitude) ||
            !isValidCoordinate(previous.latitude, previous.longitude)
        ) {
            return false
        }
        val dtMs = current.timestampMs - previous.timestampMs
        if (dtMs <= 0L || dtMs > TELEPORT_MAX_WINDOW_MS) return false
        val distanceMeters = haversineMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude
        )
        if (distanceMeters < TELEPORT_MIN_DISTANCE_METERS) return false
        val dtSec = (dtMs / 1000.0).coerceAtLeast(1.0)
        val speedMps = distanceMeters / dtSec
        return speedMps > MAX_PLAUSIBLE_SPEED_MPS
    }
}
