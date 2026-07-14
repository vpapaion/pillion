package app.pillion.android

import android.content.Context
import app.pillion.core.DashResolution
import app.pillion.core.MirrorFocus
import app.pillion.core.MirrorZoom
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode

/** [SettingsStore] backed by SharedPreferences. Single responsibility: persist preferences. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("pillion.settings", Context.MODE_PRIVATE)

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    override fun dashEnabled(): Boolean = prefs.getBoolean(KEY_DASH_ENABLED, false)

    override fun setDashEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DASH_ENABLED, enabled).apply()
    }

    override fun dashResolution(): DashResolution =
        DashResolution.fromName(prefs.getString(KEY_DASH_RESOLUTION, null))

    override fun setDashResolution(resolution: DashResolution) {
        prefs.edit().putString(KEY_DASH_RESOLUTION, resolution.name).apply()
    }

    override fun mirrorZoomPercent(): Int =
        MirrorZoom.clamp(prefs.getInt(KEY_MIRROR_ZOOM, MirrorZoom.DEFAULT_PERCENT))

    override fun setMirrorZoomPercent(percent: Int) {
        prefs.edit().putInt(KEY_MIRROR_ZOOM, MirrorZoom.clamp(percent)).apply()
    }

    override fun mirrorFocus(): MirrorFocus =
        MirrorFocus.fromName(prefs.getString(KEY_MIRROR_FOCUS, null))

    override fun setMirrorFocus(focus: MirrorFocus) {
        prefs.edit().putString(KEY_MIRROR_FOCUS, focus.name).apply()
    }

    override fun selectedBikeId(): String? = prefs.getString(KEY_BIKE, null)

    override fun setSelectedBikeId(id: String) {
        prefs.edit().putString(KEY_BIKE, id).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DASH_ENABLED = "dash_enabled"
        const val KEY_DASH_RESOLUTION = "dash_resolution"
        const val KEY_MIRROR_ZOOM = "mirror_zoom_percent"
        const val KEY_MIRROR_FOCUS = "mirror_focus"
        const val KEY_BIKE = "selected_bike_id"
    }
}
