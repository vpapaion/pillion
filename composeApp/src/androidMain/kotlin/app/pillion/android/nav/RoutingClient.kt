package app.pillion.android.nav

import app.pillion.core.AppInfo
import app.pillion.nav.GeoPoint
import app.pillion.nav.PlannedRoute
import app.pillion.nav.Polyline6
import app.pillion.nav.RouteManeuver
import app.pillion.nav.ValhallaManeuvers
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** A geocoding hit the rider can pick as destination. */
data class Place(val name: String, val detail: String, val point: GeoPoint)

/**
 * Blocking HTTP clients for the community OpenStreetMap services Pillion uses for its own
 * navigation. Call from a background thread only.
 *
 * - Routing: FOSSGIS Valhalla (`costing=motorcycle`), no API key; fair-use, so we only request a
 *   route when the rider asks for one or genuinely leaves it (throttled re-routing).
 * - Search: OSM Nominatim — max 1 request/second, identifying User-Agent, no autocomplete.
 */
object RoutingClient {
    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private val userAgent get() = "Pillion-Tracer7/${AppInfo.VERSION} (+https://github.com/vpapaion/pillion)"

    @Volatile private var lastSearchAt = 0L

    fun route(from: GeoPoint, to: GeoPoint, destinationName: String, headingDegrees: Float? = null): PlannedRoute {
        val language = valhallaLanguage()
        return try {
            requestRoute(from, to, destinationName, headingDegrees, language)
        } catch (e: RoutingException) {
            // Unknown narrative language is a 400 — retry once in English rather than failing.
            if (language != "en-US" && e.httpCode == 400) {
                requestRoute(from, to, destinationName, headingDegrees, "en-US")
            } else {
                throw e
            }
        }
    }

    private fun requestRoute(
        from: GeoPoint,
        to: GeoPoint,
        destinationName: String,
        heading: Float?,
        language: String,
    ): PlannedRoute {
        val origin = JSONObject().put("lat", from.lat).put("lon", from.lon).put("type", "break")
        if (heading != null) {
            // Prefer leaving in the current direction of travel (avoids "turn around" on re-route).
            origin.put("heading", heading.toInt().mod(360)).put("heading_tolerance", 60)
        }
        val body = JSONObject()
            .put("locations", JSONArray().put(origin).put(JSONObject().put("lat", to.lat).put("lon", to.lon).put("type", "break")))
            .put("costing", "motorcycle")
            .put("units", "kilometers")
            .put("language", language)
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", language))
        val json = http(VALHALLA, method = "POST", body = body.toString())
        return parseValhalla(JSONObject(json), to, destinationName)
    }

    internal fun parseValhalla(root: JSONObject, destination: GeoPoint, destinationName: String): PlannedRoute {
        val trip = root.getJSONObject("trip")
        if (trip.optInt("status", 0) != 0) {
            throw RoutingException(trip.optString("status_message", "No route"), 0)
        }
        val legs = trip.getJSONArray("legs")
        val points = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<RouteManeuver>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val offset = points.size
            points += Polyline6.decode(leg.getString("shape"))
            val list = leg.getJSONArray("maneuvers")
            for (m in 0 until list.length()) {
                val item = list.getJSONObject(m)
                val type = item.optInt("type", 0)
                val streets = item.optJSONArray("street_names")
                val street = streets?.takeIf { it.length() > 0 }?.optString(0)?.takeIf { it.isNotBlank() }
                maneuvers += RouteManeuver(
                    maneuver = ValhallaManeuvers.map(type),
                    instruction = item.optString("verbal_succinct_transition_instruction")
                        .ifBlank { item.optString("instruction") },
                    street = street,
                    beginIndex = offset + item.optInt("begin_shape_index", 0),
                    isArrival = ValhallaManeuvers.isArrival(type),
                    isDeparture = ValhallaManeuvers.isDeparture(type),
                )
            }
        }
        if (points.size < 2) throw RoutingException("Route has no geometry", 0)
        val summary = trip.optJSONObject("summary")
        return PlannedRoute(
            points = points,
            maneuvers = maneuvers,
            totalMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * 1_000.0,
            totalSeconds = summary?.optDouble("time", 0.0) ?: 0.0,
            destination = destination,
            destinationName = destinationName,
        )
    }

    fun search(query: String, near: GeoPoint?): List<Place> {
        // Nominatim policy: at most one request per second.
        synchronized(this) {
            val wait = 1_100L - (System.currentTimeMillis() - lastSearchAt)
            if (wait > 0) Thread.sleep(wait)
            lastSearchAt = System.currentTimeMillis()
        }
        val params = buildString {
            append("?format=jsonv2&limit=8&addressdetails=0")
            append("&accept-language=").append(enc(Locale.getDefault().toLanguageTag() + ",en"))
            append("&q=").append(enc(query))
            if (near != null) {
                // Prefer (not restrict to) results around the rider.
                val d = 1.5
                append("&viewbox=").append(enc("${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}"))
            }
        }
        val array = JSONArray(http(NOMINATIM + params))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(",") }
            Place(
                name = name,
                detail = full.removePrefix(name).trimStart(',', ' '),
                point = GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
            )
        }
    }

    /**
     * Extracts a destination from text shared by Google Maps ("Share" → Pillion) or a geo: URI.
     * Returns the coordinates when the text or its (redirect-followed) link contains them, else null
     * plus a best-effort name the caller can search for.
     */
    fun resolveShared(text: String): Pair<Place?, String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val label = lines.firstOrNull { !it.startsWith("http") } ?: ""
        coordinatesIn(text)?.let { return Place(label.ifBlank { "Shared location" }, "", it) to label }
        val link = Regex("""https?://\S+""").find(text)?.value
        if (link != null) {
            runCatching { followRedirects(link) }.getOrNull()?.let { finalUrl ->
                coordinatesIn(URLDecoder.decode(finalUrl, "UTF-8"))?.let {
                    val name = label.ifBlank { placeNameIn(finalUrl) ?: "Shared location" }
                    return Place(name, "", it) to name
                }
                if (label.isBlank()) placeNameIn(finalUrl)?.let { return null to it }
            }
        }
        return null to label
    }

    internal fun coordinatesIn(text: String): GeoPoint? {
        val patterns = listOf(
            Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),          // exact place pin
            Regex("""geo:(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
            Regex("""[?&](?:q|query|daddr|destination|ll)=(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)"""),
            Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),               // map viewport centre
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -85.0..85.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) return GeoPoint(lat, lon)
        }
        return null
    }

    private fun placeNameIn(url: String): String? =
        Regex("""/place/([^/@]+)""").find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8").replace('+', ' ') }

    private fun followRedirects(start: String): String {
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", userAgent)
                c.connectTimeout = 6_000
                c.readTimeout = 6_000
                val code = c.responseCode
                val next = c.getHeaderField("Location")
                if (code !in 300..399 || next == null) return url
                url = URL(URL(url), next).toString()
            } finally {
                c.disconnect()
            }
        }
        return url
    }

    private fun http(url: String, method: String = "GET", body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw RoutingException(message, code)
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Valhalla narrative locales are BCP-47 like "el-GR"; fall back to en-US for anything else. */
    private fun valhallaLanguage(): String {
        val l = Locale.getDefault()
        return when (l.language) {
            "el" -> "el-GR"
            "de" -> "de-DE"
            "fr" -> "fr-FR"
            "it" -> "it-IT"
            "es" -> "es-ES"
            "nl" -> "nl-NL"
            "pt" -> "pt-PT"
            else -> "en-US"
        }
    }
}

class RoutingException(message: String, val httpCode: Int) : Exception(message)
