package app.pillion.nav

import app.pillion.core.NavigationManeuver
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 position. */
data class GeoPoint(val lat: Double, val lon: Double)

/** One routing instruction, anchored to the route shape at [beginIndex]. */
data class RouteManeuver(
    val maneuver: NavigationManeuver,
    val instruction: String,
    val street: String?,
    val beginIndex: Int,
    val isArrival: Boolean = false,
    val isDeparture: Boolean = false,
)

/** A computed route: its geometry, manoeuvres and the router's own totals. */
data class PlannedRoute(
    val points: List<GeoPoint>,
    val maneuvers: List<RouteManeuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    val destination: GeoPoint,
    val destinationName: String,
)

/** Live guidance derived from the latest GPS fix along a [PlannedRoute]. */
data class RouteGuidance(
    val next: RouteManeuver?,
    val metersToNext: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val offRouteMeters: Double,
    val arrived: Boolean,
    /** Index of the route segment the rider was snapped to (start point of that segment). */
    val segmentIndex: Int,
)

/** Decoder for Valhalla's precision-6 encoded polyline (Google polyline algorithm, 1e6 scale). */
object Polyline6 {
    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = when (precision) {
            5 -> 1e5
            else -> 1e6
        }
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            if (index >= encoded.length) break
            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            out += GeoPoint(lat / factor, lon / factor)
        }
        return out
    }
}

/** Valhalla maneuver `type` → the coarse arrow set the Tracer panel draws. */
object ValhallaManeuvers {
    fun map(type: Int): NavigationManeuver = when (type) {
        4, 5, 6 -> NavigationManeuver.DESTINATION
        9, 18, 20, 23, 37 -> NavigationManeuver.SLIGHT_RIGHT
        10 -> NavigationManeuver.RIGHT
        11 -> NavigationManeuver.SHARP_RIGHT
        12, 13 -> NavigationManeuver.U_TURN
        14 -> NavigationManeuver.SHARP_LEFT
        15 -> NavigationManeuver.LEFT
        16, 19, 21, 24, 38 -> NavigationManeuver.SLIGHT_LEFT
        26, 27 -> NavigationManeuver.ROUNDABOUT
        else -> NavigationManeuver.STRAIGHT
    }

    fun isArrival(type: Int) = type in 4..6
    fun isDeparture(type: Int) = type in 1..3
}

/** Compact, rider-friendly distance/time strings for a 480×240 dash. */
object NavFormat {
    fun distance(meters: Double): String {
        if (meters.isNaN() || meters < 0) return "—"
        return when {
            meters < 300 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} m"
            meters < 1_000 -> "${(meters / 50).roundToInt() * 50} m"
            meters < 10_000 -> {
                val tenths = (meters / 100).roundToInt()
                "${tenths / 10}.${tenths % 10} km"
            }
            else -> "${(meters / 1_000).roundToInt()} km"
        }
    }

    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "—"
        val minutes = (seconds / 60).roundToInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** "14:05" for an arrival [minutesOfDay] (local), wrapped to 24 h. */
    fun clock(minutesOfDay: Int): String {
        val m = ((minutesOfDay % 1440) + 1440) % 1440
        val h = m / 60
        val mm = m % 60
        return "${if (h < 10) "0" else ""}$h:${if (mm < 10) "0" else ""}$mm"
    }
}

/**
 * Snaps GPS fixes onto a [PlannedRoute] and reports the next manoeuvre, remaining distance and
 * how far the rider is from the line (for re-routing). Pure Kotlin — unit-tested in commonTest.
 */
class RouteTracker(val route: PlannedRoute) {
    private val n = route.points.size
    private val cumulative = DoubleArray(n)
    private var lastSegment = 0

    init {
        for (i in 1 until n) {
            cumulative[i] = cumulative[i - 1] + haversine(route.points[i - 1], route.points[i])
        }
    }

    val lengthMeters: Double get() = if (n == 0) 0.0 else cumulative[n - 1]

    fun update(position: GeoPoint): RouteGuidance {
        if (n < 2) {
            val d = if (n == 1) haversine(position, route.points[0]) else 0.0
            return RouteGuidance(route.maneuvers.lastOrNull(), d, d, 0.0, d, d < ARRIVAL_METERS, 0)
        }
        // Search near the last match first (cheap and avoids snapping to a parallel return leg),
        // then fall back to the whole route when the rider seems far from that window.
        var best = nearest(position, (lastSegment - 3).coerceAtLeast(0), (lastSegment + 250).coerceAtMost(n - 2))
        if (best.distance > LOCAL_SEARCH_METERS) {
            val global = nearest(position, 0, n - 2)
            if (global.distance < best.distance) best = global
        }
        lastSegment = best.segment

        val along = cumulative[best.segment] +
            best.t * (cumulative[best.segment + 1] - cumulative[best.segment])
        val remaining = (lengthMeters - along).coerceAtLeast(0.0)
        val next = route.maneuvers.firstOrNull { !it.isDeparture && it.beginIndex > best.segment }
            ?: route.maneuvers.lastOrNull()
        val toNext = next?.let {
            (cumulative[it.beginIndex.coerceIn(0, n - 1)] - along).coerceAtLeast(0.0)
        } ?: remaining
        val seconds = if (lengthMeters > 0) route.totalSeconds * remaining / lengthMeters else 0.0
        return RouteGuidance(
            next = next,
            metersToNext = toNext,
            remainingMeters = remaining,
            remainingSeconds = seconds,
            offRouteMeters = best.distance,
            arrived = remaining < ARRIVAL_METERS && best.distance < OFF_ROUTE_METERS,
            segmentIndex = best.segment,
        )
    }

    private class Match(val segment: Int, val t: Double, val distance: Double)

    private fun nearest(p: GeoPoint, from: Int, to: Int): Match {
        var bestSeg = from
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        val cosLat = cos(p.lat * PI / 180.0)
        for (i in from..to) {
            val a = route.points[i]
            val b = route.points[i + 1]
            // Local equirectangular metres around p — accurate to well under a metre at these scales.
            val ax = (a.lon - p.lon) * cosLat * M_PER_DEG
            val ay = (a.lat - p.lat) * M_PER_DEG
            val bx = (b.lon - p.lon) * cosLat * M_PER_DEG
            val by = (b.lat - p.lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            if (d < bestD) {
                bestD = d
                bestSeg = i
                bestT = t
            }
        }
        return Match(bestSeg, bestT, bestD)
    }

    companion object {
        const val ARRIVAL_METERS = 30.0
        const val OFF_ROUTE_METERS = 50.0
        private const val LOCAL_SEARCH_METERS = 60.0
        private const val M_PER_DEG = 111_320.0

        fun haversine(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = (b.lat - a.lat) * PI / 180.0
            val dLon = (b.lon - a.lon) * PI / 180.0
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(a.lat * PI / 180.0) * cos(b.lat * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
