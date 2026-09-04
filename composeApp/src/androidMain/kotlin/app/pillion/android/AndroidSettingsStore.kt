package app.pillion.android

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import app.pillion.core.DashResolution
import app.pillion.core.MirrorFocus
import app.pillion.core.MirrorZoom
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode

/** [SettingsStore] backed by SharedPreferences. Single responsibility: persist preferences. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("pillion.settings", Context.MODE_PRIVATE)

    init {
        MirrorViewportState.initialize(
            appContext,
            prefs.getInt(KEY_MIRROR_ZOOM, MirrorZoom.DEFAULT_PERCENT),
            MirrorFocus.fromName(prefs.getString(KEY_MIRROR_FOCUS, null)),
        )
    }

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
        MirrorViewportState.setZoom(appContext, percent)
    }

    override fun mirrorFocus(): MirrorFocus =
        MirrorFocus.fromName(prefs.getString(KEY_MIRROR_FOCUS, null))

    override fun setMirrorFocus(focus: MirrorFocus) {
        MirrorViewportState.setFocusPreset(appContext, focus)
    }

    override fun googleMapsOverlayEnabled(): Boolean =
        prefs.getBoolean(KEY_GOOGLE_MAPS_OVERLAY, false)

    override fun setGoogleMapsOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOOGLE_MAPS_OVERLAY, enabled).apply()
        GoogleMapsNavigationState.refreshEnabled(appContext)
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(appContext, GoogleMapsNavigationListenerService::class.java),
                )
            }
        }
    }

    override fun screenOffDirectionsEnabled(): Boolean =
        prefs.getBoolean(KEY_SCREEN_OFF_DIRECTIONS, false)

    override fun setScreenOffDirectionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_OFF_DIRECTIONS, enabled).apply()
        if (enabled) setGoogleMapsOverlayEnabled(true)
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
        const val KEY_GOOGLE_MAPS_OVERLAY = "google_maps_overlay_enabled"
        const val KEY_SCREEN_OFF_DIRECTIONS = "screen_off_directions_enabled"
        const val KEY_BIKE = "selected_bike_id"
    }
}
