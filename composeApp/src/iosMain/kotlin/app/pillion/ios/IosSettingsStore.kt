package app.pillion.ios

import app.pillion.core.DashResolution
import app.pillion.core.MirrorFocus
import app.pillion.core.MirrorZoom
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import platform.Foundation.NSUserDefaults

/** [SettingsStore] backed by NSUserDefaults. */
class IosSettingsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(defaults.stringForKey(THEME_KEY) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        defaults.setObject(mode.name, forKey = THEME_KEY)
    }

    // Dedicated dash mode is Android-only (ADB / virtual display); iOS streams via the broadcast
    // extension, so the UI never surfaces these. They're persisted only for interface completeness.
    override fun dashEnabled(): Boolean = defaults.boolForKey(DASH_ENABLED_KEY)

    override fun setDashEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = DASH_ENABLED_KEY)
    }

    override fun dashResolution(): DashResolution =
        DashResolution.fromName(defaults.stringForKey(DASH_RES_KEY))

    override fun setDashResolution(resolution: DashResolution) {
        defaults.setObject(resolution.name, forKey = DASH_RES_KEY)
    }

    override fun mirrorZoomPercent(): Int {
        val saved = defaults.integerForKey(MIRROR_ZOOM_KEY).toInt()
        return MirrorZoom.clamp(if (saved == 0) MirrorZoom.DEFAULT_PERCENT else saved)
    }

    override fun setMirrorZoomPercent(percent: Int) {
        defaults.setInteger(MirrorZoom.clamp(percent).toLong(), forKey = MIRROR_ZOOM_KEY)
    }

    override fun mirrorFocus(): MirrorFocus =
        MirrorFocus.fromName(defaults.stringForKey(MIRROR_FOCUS_KEY))

    override fun setMirrorFocus(focus: MirrorFocus) {
        defaults.setObject(focus.name, forKey = MIRROR_FOCUS_KEY)
    }

    override fun googleMapsOverlayEnabled(): Boolean = false

    override fun setGoogleMapsOverlayEnabled(enabled: Boolean) {
        // Android-only: iOS does not expose another app's navigation notification content.
    }

    override fun selectedBikeId(): String? = defaults.stringForKey(BIKE_KEY)

    override fun setSelectedBikeId(id: String) {
        defaults.setObject(id, forKey = BIKE_KEY)
    }

    private companion object {
        const val THEME_KEY = "theme_mode"
        const val DASH_ENABLED_KEY = "dash_enabled"
        const val DASH_RES_KEY = "dash_resolution"
        const val MIRROR_ZOOM_KEY = "mirror_zoom_percent"
        const val MIRROR_FOCUS_KEY = "mirror_focus"
        const val BIKE_KEY = "selected_bike_id"
    }
}
