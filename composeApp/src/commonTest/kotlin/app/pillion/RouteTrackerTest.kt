package app.pillion

import app.pillion.core.NavigationManeuver
import app.pillion.nav.GeoPoint
import app.pillion.nav.NavFormat
import app.pillion.nav.PlannedRoute
import app.pillion.nav.Polyline6
import app.pillion.nav.RouteManeuver
import app.pillion.nav.RouteTracker
import app.pillion.nav.ValhallaManeuvers
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteTrackerTest {

    @Test
    fun decodesGoogleReferencePolylineAtPrecision5() {
        val points = Polyline6.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, points.size)
        assertClose(38.5, points[0].lat); assertClose(-120.2, points[0].lon)
        assertClose(40.7, points[1].lat); assertClose(-120.95, points[1].lon)
        assertClose(43.252, points[2].lat); assertClose(-126.453, points[2].lon)
    }

    @Test
    fun decodesPrecision6() {
        // Same deltas as the reference string, interpreted at 1e6 → coordinates are 10× smaller.
        val points = Polyline6.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertClose(3.85, points[0].lat)
        assertClose(-12.02, points[0].lon)
    }

    @Test
    fun mapsValhallaTypes() {
        assertEquals(NavigationManeuver.RIGHT, ValhallaManeuvers.map(10))
        assertEquals(NavigationManeuver.LEFT, ValhallaManeuvers.map(15))
        assertEquals(NavigationManeuver.ROUNDABOUT, ValhallaManeuvers.map(26))
        assertEquals(NavigationManeuver.DESTINATION, ValhallaManeuvers.map(4))
        assertEquals(NavigationManeuver.STRAIGHT, ValhallaManeuvers.map(8))
        assertTrue(ValhallaManeuvers.isDeparture(1))
        assertTrue(ValhallaManeuvers.isArrival(5))
    }

    @Test
    fun formatsDistances() {
        assertEquals("120 m", NavFormat.distance(118.0))
        assertEquals("450 m", NavFormat.distance(462.0))
        assertEquals("1.2 km", NavFormat.distance(1_234.0))
        assertEquals("12 km", NavFormat.distance(12_400.0))
        assertEquals("1 h 5 min", NavFormat.duration(3_900.0))
        assertEquals("09:05", NavFormat.clock(545))
        assertEquals("00:10", NavFormat.clock(1_450))
    }

    /** An L-shaped route in Patras: ~1 km east, then ~1 km north, turning left at the corner. */
    private fun lRoute(): PlannedRoute {
        val points = buildList {
            for (i in 0..10) add(GeoPoint(38.24, 21.73 + i * 0.00115))   // east
            for (i in 1..10) add(GeoPoint(38.24 + i * 0.0009, 21.7415))  // north
        }
        return PlannedRoute(
            points = points,
            maneuvers = listOf(
                RouteManeuver(NavigationManeuver.STRAIGHT, "Head east", "Ermou", 0, isDeparture = true),
                RouteManeuver(NavigationManeuver.LEFT, "Turn left", "Maizonos", 10),
                RouteManeuver(NavigationManeuver.DESTINATION, "Arrive", null, 20, isArrival = true),
            ),
            totalMeters = 2_000.0,
            totalSeconds = 240.0,
            destination = points.last(),
            destinationName = "Test",
        )
    }

    @Test
    fun guidesToTheNextTurnThenToArrival() {
        val tracker = RouteTracker(lRoute())
        val start = tracker.update(GeoPoint(38.24, 21.7302))
        assertEquals("Maizonos", start.next?.street)
        assertTrue(start.metersToNext in 950.0..1_020.0, "toNext=${start.metersToNext}")
        assertTrue(start.offRouteMeters < 5.0)
        assertFalse(start.arrived)

        val afterTurn = tracker.update(GeoPoint(38.2445, 21.7415))
        assertTrue(afterTurn.next?.isArrival == true)
        assertTrue(afterTurn.remainingMeters in 450.0..560.0, "remaining=${afterTurn.remainingMeters}")
        assertTrue(afterTurn.remainingSeconds < start.remainingSeconds)

        val end = tracker.update(GeoPoint(38.249, 21.7415))
        assertTrue(end.arrived)
    }

    @Test
    fun reportsOffRouteDistance() {
        val tracker = RouteTracker(lRoute())
        // ~0.002° south of the eastbound leg ≈ 220 m away.
        val g = tracker.update(GeoPoint(38.238, 21.735))
        assertTrue(g.offRouteMeters > RouteTracker.OFF_ROUTE_METERS, "off=${g.offRouteMeters}")
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-6, "expected $expected but was $actual")
}
