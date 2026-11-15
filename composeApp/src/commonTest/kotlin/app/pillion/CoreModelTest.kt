package app.pillion

import app.pillion.core.DashResolution
import app.pillion.core.GoogleMapsDirectionParser
import app.pillion.core.MirrorFocus
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorZoom
import app.pillion.core.NavigationManeuver
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreModelTest {

    @Test
    fun dash_resolution_from_name_is_robust() {
        assertEquals(DashResolution.Wide, DashResolution.fromName("Wide"))
        assertEquals(DashResolution.DEFAULT, DashResolution.fromName(null))
        assertEquals(DashResolution.DEFAULT, DashResolution.fromName("nonsense"))
        assertEquals(DashResolution.Balanced, DashResolution.DEFAULT)
    }

    @Test
    fun dash_resolution_label_matches_dimensions() {
        assertEquals("1280 x 640", DashResolution.Wide.label)
        assertEquals(1280, DashResolution.Wide.width)
        assertEquals(640, DashResolution.Wide.height)
    }

    @Test
    fun mirror_settings_defaults_are_sane() {
        val s = MirrorSettings()
        assertEquals(40, s.quality)
        assertEquals(15, s.maxFps)
        assertEquals(DashResolution.DEFAULT, s.dashResolution)
        assertEquals(175, s.zoomPercent)
        assertEquals(MirrorFocus.CENTER, s.focus)
    }

    @Test
    fun mirror_zoom_clamps_and_centres_crop() {
        assertEquals(100, MirrorZoom.clamp(80))
        assertEquals(200, MirrorZoom.clamp(240))
        assertEquals(400, MirrorZoom.cropSize(480, 120))
        assertEquals(200, MirrorZoom.cropSize(240, 120))
        assertEquals(40, MirrorZoom.cropStart(480, 400))
        assertEquals(20, MirrorZoom.cropStart(240, 200))
    }

    @Test
    fun mirror_focus_positions_the_crop_at_each_requested_area() {
        assertEquals(0, MirrorFocus.TOP_LEFT.cropLeft(480, 240))
        assertEquals(0, MirrorFocus.TOP_LEFT.cropTop(240, 120))
        assertEquals(240, MirrorFocus.TOP_RIGHT.cropLeft(480, 240))
        assertEquals(0, MirrorFocus.TOP_RIGHT.cropTop(240, 120))
        assertEquals(120, MirrorFocus.CENTER.cropLeft(480, 240))
        assertEquals(60, MirrorFocus.CENTER.cropTop(240, 120))
        assertEquals(0, MirrorFocus.BOTTOM_LEFT.cropLeft(480, 240))
        assertEquals(120, MirrorFocus.BOTTOM_LEFT.cropTop(240, 120))
        assertEquals(240, MirrorFocus.BOTTOM_RIGHT.cropLeft(480, 240))
        assertEquals(120, MirrorFocus.BOTTOM_RIGHT.cropTop(240, 120))
        assertEquals(MirrorFocus.CENTER, MirrorFocus.fromName("unknown"))
        assertEquals(MirrorFocus.TOP_RIGHT, MirrorFocus.fromName("top_right"))
    }
    @Test
    fun google_maps_parser_extracts_english_turn_and_distance() {
        val direction = GoogleMapsDirectionParser.parse(
            listOf("Google Maps", "In 350 m", "Turn right onto Korinthou"),
        )
        assertEquals("350 m", direction?.distance)
        assertEquals("Turn right onto Korinthou", direction?.instruction)
        assertEquals(NavigationManeuver.RIGHT, direction?.maneuver)
    }

    @Test
    fun google_maps_parser_understands_greek_navigation_text() {
        val direction = GoogleMapsDirectionParser.parse(
            listOf("Σε 1,2 χλμ.", "Στρίψτε αριστερά στη Νέα Εθνική Οδό"),
        )
        assertEquals("1,2 χλμ", direction?.distance)
        assertEquals(NavigationManeuver.LEFT, direction?.maneuver)
    }

    @Test
    fun google_maps_parser_ignores_non_navigation_notification() {
        assertEquals(null, GoogleMapsDirectionParser.parse(listOf("Google Maps", "Location sharing is active")))
    }

}
