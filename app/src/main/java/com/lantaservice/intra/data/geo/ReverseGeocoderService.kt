package com.lantaservice.intra.data.geo

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.lantaservice.intra.BuildConfig
import com.lantaservice.intra.data.network.IntraResilientDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

@Serializable
data class ParsedRussianAddress(
    val postalCode: String = "",
    val country: String = "",
    val region: String = "",
    val district: String = "",
    val locality: String = "",
    val street: String = "",
    val houseNumber: String = "",
    val fullAddress: String = "",
    val suburb: String = "",
    val placeName: String = "",
    val providerName: String = "",
    val entrance: String = ""
)

/**
 * Pure JVM parser formatting complete Russian postal addresses
 * (postal code, region, district, locality, street, house number, entrance)
 * from OpenStreetMap Nominatim, Komoot Photon, and Overpass API JSON responses.
 */
object ReverseGeocoderParser {

    fun formatRussianFullAddress(
        postalCode: String,
        region: String,
        district: String,
        locality: String,
        street: String,
        houseNumber: String,
        country: String = "",
        entrance: String = ""
    ): String {
        val parts = mutableListOf<String>()
        val cleanPost = postalCode.trim()
        val cleanCountry = country.trim()
        val cleanRegion = region.trim()
        val cleanDistrict = district.trim()
        val cleanLocality = locality.trim()
        val cleanStreet = street.trim()
        val cleanHouse = houseNumber.trim()
        val cleanEntrance = entrance.trim()

        if (cleanPost.isNotEmpty()) {
            parts += cleanPost
        }
        if (cleanCountry.isNotEmpty() &&
            !cleanCountry.equals("Россия", ignoreCase = true) &&
            !cleanCountry.equals("Russia", ignoreCase = true)
        ) {
            parts += cleanCountry
        }
        if (cleanRegion.isNotEmpty() && !cleanRegion.equals(cleanLocality, ignoreCase = true)) {
            parts += cleanRegion
        }
        if (cleanDistrict.isNotEmpty() &&
            !cleanDistrict.equals(cleanRegion, ignoreCase = true) &&
            !cleanDistrict.equals(cleanLocality, ignoreCase = true)
        ) {
            parts += cleanDistrict
        }
        if (cleanLocality.isNotEmpty()) {
            parts += cleanLocality
        }
        if (cleanStreet.isNotEmpty() && !cleanStreet.equals(cleanLocality, ignoreCase = true)) {
            parts += cleanStreet
        }
        if (cleanHouse.isNotEmpty()) {
            val lower = cleanHouse.lowercase(Locale.ROOT)
            val houseFormatted = if (
                lower.startsWith("д.") ||
                lower.startsWith("д ") ||
                lower.startsWith("дом ") ||
                lower.startsWith("стр.") ||
                lower.startsWith("к.")
            ) {
                cleanHouse
            } else {
                "д. $cleanHouse"
            }
            parts += houseFormatted
        }
        if (cleanEntrance.isNotEmpty()) {
            val lowerEnt = cleanEntrance.lowercase(Locale.ROOT)
            parts += if (lowerEnt.startsWith("подъезд") || lowerEnt.startsWith("под.")) {
                cleanEntrance
            } else {
                "подъезд $cleanEntrance"
            }
        }
        return parts
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")
    }

    fun parseNominatimJson(rawJson: String, json: Json): ParsedRussianAddress? {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val addressObj = root["address"] as? JsonObject

        val postalCode = addressObj?.str("postcode").orEmpty()
        val country = addressObj?.str("country").orEmpty()
        val region = listOfNotNull(
            addressObj?.str("state"),
            addressObj?.str("region"),
            addressObj?.str("province")
        ).firstOrNull().orEmpty()
        val district = listOfNotNull(
            addressObj?.str("state_district"),
            addressObj?.str("county")
        ).firstOrNull().orEmpty()
        val locality = listOfNotNull(
            addressObj?.str("city"),
            addressObj?.str("town"),
            addressObj?.str("village"),
            addressObj?.str("settlement"),
            addressObj?.str("hamlet"),
            addressObj?.str("municipality"),
            addressObj?.str("city_district")
        ).firstOrNull().orEmpty()
        val street = listOfNotNull(
            addressObj?.str("road"),
            addressObj?.str("pedestrian"),
            addressObj?.str("footway"),
            addressObj?.str("square"),
            addressObj?.str("street"),
            addressObj?.str("suburb"),
            addressObj?.str("neighbourhood"),
            addressObj?.str("industrial"),
            addressObj?.str("commercial"),
            root.str("name")
        ).firstOrNull().orEmpty()
        val houseNumber = listOfNotNull(
            addressObj?.str("house_number"),
            addressObj?.str("house_name")
        ).firstOrNull().orEmpty()
        val suburb = listOfNotNull(
            addressObj?.str("city_district"),
            addressObj?.str("suburb"),
            addressObj?.str("quarter"),
            addressObj?.str("borough"),
            addressObj?.str("neighbourhood")
        ).firstOrNull { !it.equals(locality, ignoreCase = true) }.orEmpty()
        val placeName = listOfNotNull(
            root.str("name"),
            addressObj?.str("amenity"),
            addressObj?.str("office"),
            addressObj?.str("shop"),
            addressObj?.str("building"),
            addressObj?.str("commercial"),
            addressObj?.str("industrial")
        ).firstOrNull {
            !it.equals(street, ignoreCase = true) &&
                !it.equals(locality, ignoreCase = true) &&
                !it.equals(houseNumber, ignoreCase = true)
        }.orEmpty()

        val composed = formatRussianFullAddress(
            postalCode = postalCode,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = houseNumber,
            country = country
        )
        val fallbackDisplay = root.str("display_name").orEmpty()
        val fullAddress = composed.ifBlank { fallbackDisplay }
        if (fullAddress.isBlank()) return null

        return ParsedRussianAddress(
            postalCode = postalCode,
            country = country,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = houseNumber,
            fullAddress = sanitizeDashes(fullAddress),
            suburb = sanitizeDashes(suburb),
            placeName = sanitizeDashes(placeName),
            providerName = "OpenStreetMap Nominatim"
        )
    }

    fun parsePhotonJson(rawJson: String, json: Json): ParsedRussianAddress? {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val features = root["features"] as? JsonArray ?: return null
        val propList = features.mapNotNull { (it as? JsonObject)?.get("properties") as? JsonObject }
        val firstProps = propList.firstOrNull() ?: return null
        val houseProps = propList.firstOrNull { !it.str("housenumber").isNullOrBlank() } ?: firstProps

        val postalCode = houseProps.str("postcode") ?: firstProps.str("postcode").orEmpty()
        val country = houseProps.str("country") ?: firstProps.str("country").orEmpty()
        val region = houseProps.str("state") ?: firstProps.str("state").orEmpty()
        val district = listOfNotNull(
            houseProps.str("county"),
            houseProps.str("district"),
            firstProps.str("county"),
            firstProps.str("district")
        ).firstOrNull().orEmpty()
        val locality = listOfNotNull(
            houseProps.str("city"),
            houseProps.str("locality"),
            houseProps.str("town"),
            houseProps.str("village"),
            firstProps.str("city"),
            firstProps.str("locality"),
            firstProps.str("town"),
            firstProps.str("village")
        ).firstOrNull().orEmpty()
        val street = listOfNotNull(
            houseProps.str("street"),
            firstProps.str("street"),
            houseProps.str("name"),
            firstProps.str("name")
        ).firstOrNull().orEmpty()
        val houseNumber = houseProps.str("housenumber").orEmpty()
        val suburb = (houseProps.str("district") ?: firstProps.str("district")).orEmpty()
            .takeIf { !it.equals(district, ignoreCase = true) && !it.equals(locality, ignoreCase = true) }
            .orEmpty()
        val placeName = (houseProps.str("name") ?: firstProps.str("name")).orEmpty()
            .takeIf { !it.equals(street, ignoreCase = true) && !it.equals(locality, ignoreCase = true) }
            .orEmpty()

        val fullAddress = formatRussianFullAddress(
            postalCode = postalCode,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = houseNumber,
            country = country
        )
        if (fullAddress.isBlank()) return null

        return ParsedRussianAddress(
            postalCode = postalCode,
            country = country,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = houseNumber,
            fullAddress = sanitizeDashes(fullAddress),
            suburb = sanitizeDashes(suburb),
            placeName = sanitizeDashes(placeName),
            providerName = "Komoot Photon"
        )
    }

    /**
     * Parses OSM Overpass API elements around target coordinates to recover exact
     * building house number (`addr:housenumber`) and nearest entrance (`entrance` + `ref`/`addr:flats`)
     * when road-level geocoders only return the street name.
     */
    fun parseOverpassBuildingAndEntrance(
        rawJson: String,
        targetLat: Double,
        targetLon: Double,
        json: Json
    ): ParsedRussianAddress? {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val elements = root["elements"] as? JsonArray ?: return null

        var bestBuildingDist = Double.MAX_VALUE
        var bestHouse = ""
        var bestStreet = ""
        var bestCity = ""
        var bestPostcode = ""
        var bestPlaceName = ""

        var bestEntranceDist = Double.MAX_VALUE
        var bestEntrance = ""
        var entranceHouseHint = ""

        for (el in elements) {
            val obj = el as? JsonObject ?: continue
            val tags = obj["tags"] as? JsonObject ?: continue
            val center = obj["center"] as? JsonObject
            val elLat = (obj["lat"] as? JsonPrimitive)?.doubleOrNull
                ?: (center?.get("lat") as? JsonPrimitive)?.doubleOrNull
                ?: continue
            val elLon = (obj["lon"] as? JsonPrimitive)?.doubleOrNull
                ?: (center?.get("lon") as? JsonPrimitive)?.doubleOrNull
                ?: continue

            val dist = GeoAntiSpoofSelector.haversineMeters(targetLat, targetLon, elLat, elLon)

            val entType = tags.str("entrance")
            if (entType != null && dist <= 55.0 && dist < bestEntranceDist) {
                val ref = tags.str("ref")?.takeIf {
                    it.isNotBlank() && !it.equals("yes", true) && !it.equals("main", true)
                }.orEmpty()
                val flats = tags.str("addr:flats")?.takeIf { it.isNotBlank() }.orEmpty()
                val entLabel = when {
                    ref.isNotEmpty() && flats.isNotEmpty() -> "$ref (кв. $flats)"
                    ref.isNotEmpty() -> ref
                    flats.isNotEmpty() -> "кв. $flats"
                    else -> ""
                }
                if (entLabel.isNotEmpty()) {
                    bestEntranceDist = dist
                    bestEntrance = entLabel
                    tags.str("addr:housenumber")?.let { entranceHouseHint = it }
                }
            }

            val house = tags.str("addr:housenumber")
            if (!house.isNullOrBlank() && dist <= 80.0 && dist < bestBuildingDist) {
                bestBuildingDist = dist
                bestHouse = house
                bestStreet = tags.str("addr:street").orEmpty()
                bestCity = tags.str("addr:city").orEmpty()
                bestPostcode = tags.str("addr:postcode").orEmpty()
                bestPlaceName = tags.str("name")?.takeIf {
                    !it.equals(bestStreet, true) && !it.equals(house, true)
                }.orEmpty()
            }
        }

        val resolvedHouse = bestHouse.ifBlank { entranceHouseHint }
        if (resolvedHouse.isBlank() && bestEntrance.isBlank()) return null

        val composed = formatRussianFullAddress(
            postalCode = bestPostcode,
            region = "",
            district = "",
            locality = bestCity,
            street = bestStreet,
            houseNumber = resolvedHouse,
            entrance = bestEntrance
        )
        return ParsedRussianAddress(
            postalCode = bestPostcode,
            locality = bestCity,
            street = bestStreet,
            houseNumber = resolvedHouse,
            fullAddress = sanitizeDashes(composed),
            placeName = sanitizeDashes(bestPlaceName),
            providerName = "OpenStreetMap Overpass",
            entrance = sanitizeDashes(bestEntrance)
        )
    }

    fun mergeAddresses(
        primary: ParsedRussianAddress?,
        secondary: ParsedRussianAddress?
    ): ParsedRussianAddress? {
        if (primary == null) return secondary
        if (secondary == null) return primary

        val postal = primary.postalCode.ifBlank { secondary.postalCode }
        val country = primary.country.ifBlank { secondary.country }
        val region = primary.region.ifBlank { secondary.region }
        val district = primary.district.ifBlank { secondary.district }
        val locality = primary.locality.ifBlank { secondary.locality }
        val street = primary.street.ifBlank { secondary.street }
        val house = primary.houseNumber.ifBlank { secondary.houseNumber }
        val suburb = primary.suburb.ifBlank { secondary.suburb }
        val placeName = primary.placeName.ifBlank { secondary.placeName }
        val entrance = primary.entrance.ifBlank { secondary.entrance }
        val recomposed = formatRussianFullAddress(
            postalCode = postal,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = house,
            country = country,
            entrance = entrance
        )
        val fullAddress = recomposed.ifBlank { primary.fullAddress.ifBlank { secondary.fullAddress } }
        return ParsedRussianAddress(
            postalCode = postal,
            country = country,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = house,
            fullAddress = sanitizeDashes(fullAddress),
            suburb = sanitizeDashes(suburb),
            placeName = sanitizeDashes(placeName),
            providerName = primary.providerName.ifBlank { secondary.providerName },
            entrance = sanitizeDashes(entrance)
        )
    }

    fun sanitizeDashes(text: String): String {
        return text
            .replace('\u2014', '-')
            .replace('\u2013', '-')
            .replace('\u2015', '-')
            .replace('\u2012', '-')
    }

    private fun JsonObject.str(key: String): String? {
        val el = this[key] as? JsonPrimitive ?: return null
        if (el is JsonNull) return null
        return el.content.trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Free reverse-geocoding pipeline requiring zero API keys:
 * 1. OpenStreetMap Nominatim (detailed RU address components)
 * 2. Komoot Photon OSM mirror
 * 3. Android platform Geocoder (Russian locale)
 */
class ReverseGeocoderService(
    private val context: Context,
    private val json: Json,
    resilientDns: IntraResilientDns
) {

    // Standalone client with short timeouts and zero IntraService Basic Auth / cookie headers
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cacheFile = File(context.filesDir, "geo_address_cache.json")
    private val diskLock = Any()
    private val cacheSerializer = MapSerializer(String.serializer(), ParsedRussianAddress.serializer())
    private val addressCache = ConcurrentHashMap<String, ParsedRussianAddress>().apply {
        putAll(loadCacheFromDisk())
    }

    private fun coordKey(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.4f_%.4f", lat, lon)
    }

    fun getCachedAddress(lat: Double, lon: Double): ParsedRussianAddress? {
        if (!GeoAntiSpoofSelector.isValidCoordinate(lat, lon)) return null
        return addressCache[coordKey(lat, lon)]
    }

    suspend fun resolveAddress(
        lat: Double,
        lon: Double,
        onFastResult: (suspend (ParsedRussianAddress) -> Unit)? = null
    ): ParsedRussianAddress? = withContext(Dispatchers.IO) {
        if (!GeoAntiSpoofSelector.isValidCoordinate(lat, lon)) return@withContext null
        val key = coordKey(lat, lon)
        addressCache[key]?.let { return@withContext it }

        val resolved = coroutineScope {
            var emittedFast = false
            val fastEmitLock = Any()

            val nominatimJob = async {
                withTimeoutOrNull(2600L) { queryNominatim(lat, lon) }
            }
            val overpassJob = async {
                withTimeoutOrNull(2500L) { queryOverpassBuildingAndEntrance(lat, lon) }
            }
            val photonJob = async {
                val res = withTimeoutOrNull(2000L) { queryPhoton(lat, lon) }
                if (res != null && onFastResult != null && !nominatimJob.isCompleted) {
                    val shouldEmit = synchronized(fastEmitLock) {
                        if (!emittedFast) {
                            emittedFast = true
                            true
                        } else false
                    }
                    if (shouldEmit) runCatching { onFastResult(res) }
                }
                res
            }
            val androidJob = async {
                val res = withTimeoutOrNull(2000L) { queryAndroidGeocoder(lat, lon) }
                if (res != null && onFastResult != null && !nominatimJob.isCompleted) {
                    val shouldEmit = synchronized(fastEmitLock) {
                        if (!emittedFast) {
                            emittedFast = true
                            true
                        } else false
                    }
                    if (shouldEmit) runCatching { onFastResult(res) }
                }
                res
            }

            val nominatim = nominatimJob.await()
            val overpass = runCatching { overpassJob.await() }.getOrNull()

            val baseAddr = if (nominatim != null && nominatim.postalCode.isNotBlank() && nominatim.houseNumber.isNotBlank()) {
                photonJob.cancel()
                androidJob.cancel()
                nominatim
            } else {
                val photon = runCatching { photonJob.await() }.getOrNull()
                val androidAddr = if (nominatim == null && photon == null) {
                    runCatching { androidJob.await() }.getOrNull()
                } else {
                    if (androidJob.isCompleted) runCatching { androidJob.await() }.getOrNull() else null
                }
                val secondary = ReverseGeocoderParser.mergeAddresses(photon, androidAddr)
                ReverseGeocoderParser.mergeAddresses(nominatim, secondary)
            }

            // Always enrich houseNumber / entrance / placeName with Overpass nearest building & entrance
            ReverseGeocoderParser.mergeAddresses(baseAddr, overpass)
        }

        if (resolved != null && resolved.fullAddress.isNotBlank()) {
            while (addressCache.size >= 80) {
                val firstKey = addressCache.keys.firstOrNull() ?: break
                addressCache.remove(firstKey)
            }
            addressCache[key] = resolved
            saveCacheToDisk()
        }
        resolved
    }

    private fun loadCacheFromDisk(): Map<String, ParsedRussianAddress> {
        synchronized(diskLock) {
            if (!cacheFile.exists()) return emptyMap()
            val raw = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (raw.isBlank()) return emptyMap()
            return runCatching { json.decodeFromString(cacheSerializer, raw) }.getOrDefault(emptyMap())
        }
    }

    private fun saveCacheToDisk() {
        synchronized(diskLock) {
            runCatching {
                val snapshotMap = LinkedHashMap<String, ParsedRussianAddress>()
                addressCache.entries.take(80).forEach { (k, v) -> snapshotMap[k] = v }
                cacheFile.writeText(json.encodeToString(cacheSerializer, snapshotMap), Charsets.UTF_8)
            }
        }
    }

    private fun queryNominatim(lat: Double, lon: Double): ParsedRussianAddress? {
        val url = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&addressdetails=1&accept-language=ru&zoom=18",
            lat,
            lon
        )
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "IntraService-Mobile/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept", "application/json")
            .get()
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                ReverseGeocoderParser.parseNominatimJson(body, json)
            }
        }.getOrNull()
    }

    private fun queryPhoton(lat: Double, lon: Double): ParsedRussianAddress? {
        val url = String.format(
            Locale.US,
            "https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f&limit=5",
            lat,
            lon
        )
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "IntraService-Mobile/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept", "application/json")
            .get()
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                ReverseGeocoderParser.parsePhotonJson(body, json)
            }
        }.getOrNull()
    }

    private fun queryOverpassBuildingAndEntrance(lat: Double, lon: Double): ParsedRussianAddress? {
        val ql = String.format(
            Locale.US,
            "[out:json][timeout:3];(nwr[\"addr:housenumber\"](around:75,%.6f,%.6f);node[\"entrance\"](around:55,%.6f,%.6f););out center tags;",
            lat, lon, lat, lon
        )
        val endpoints = listOf(
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass-api.de/api/interpreter"
        )
        for (endpoint in endpoints) {
            val formBody = FormBody.Builder().add("data", ql).build()
            val req = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "IntraService-Mobile/${BuildConfig.VERSION_NAME} (Android)")
                .header("Accept", "application/json")
                .post(formBody)
                .build()
            val parsed = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    ReverseGeocoderParser.parseOverpassBuildingAndEntrance(body, lat, lon, json)
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private suspend fun queryAndroidGeocoder(lat: Double, lon: Double): ParsedRussianAddress? {
        if (!Geocoder.isPresent()) return null
        val ruLocale = Locale.Builder().setLanguage("ru").setRegion("RU").build()
        val geocoder = Geocoder(context, ruLocale)

        val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(4500L) {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        geocoder.getFromLocation(lat, lon, 5) { list ->
                            if (cont.isActive) cont.resume(list)
                        }
                    }.onFailure {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            }.orEmpty()
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 5).orEmpty()
            }.getOrDefault(emptyList())
        }

        val addr = addresses.firstOrNull { !it.subThoroughfare.isNullOrBlank() }
            ?: addresses.firstOrNull()
            ?: return null
        val postal = addr.postalCode.orEmpty().trim()
        val region = addr.adminArea.orEmpty().trim()
        val district = addr.subAdminArea.orEmpty().trim()
        val locality = (addr.locality ?: addr.subLocality).orEmpty().trim()
        val street = addr.thoroughfare.orEmpty().trim()
        val house = addr.subThoroughfare.orEmpty().trim()
        val country = addr.countryName.orEmpty().trim()

        val composed = ReverseGeocoderParser.formatRussianFullAddress(
            postalCode = postal,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = house,
            country = country
        )
        val lineFallback = if (addr.maxAddressLineIndex >= 0) {
            addr.getAddressLine(0).orEmpty().trim()
        } else {
            ""
        }
        val full = composed.ifBlank { lineFallback }
        if (full.isBlank()) return null

        return ParsedRussianAddress(
            postalCode = postal,
            country = country,
            region = region,
            district = district,
            locality = locality,
            street = street,
            houseNumber = house,
            fullAddress = ReverseGeocoderParser.sanitizeDashes(full),
            suburb = ReverseGeocoderParser.sanitizeDashes(addr.subLocality.orEmpty().trim().takeIf { !it.equals(locality, true) }.orEmpty()),
            placeName = ReverseGeocoderParser.sanitizeDashes(addr.featureName.orEmpty().trim().takeIf { !it.equals(house, true) && !it.equals(street, true) }.orEmpty()),
            providerName = "Android Geocoder"
        )
    }
}
