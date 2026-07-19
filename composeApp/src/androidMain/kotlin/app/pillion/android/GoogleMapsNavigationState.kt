package app.pillion.android

import android.content.Context
import app.pillion.core.GoogleMapsDirection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Runtime Google Maps instruction shared by notification listener and both dashboard renderers. */
internal object GoogleMapsNavigationState {
    private const val PREFS = "pillion.settings"
    private const val KEY_ENABLED = "google_maps_overlay_enabled"

    private val current = AtomicReference<GoogleMapsDirection?>(null)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun snapshot(context: Context): GoogleMapsDirection? =
        if (isEnabled(context)) current.get() else null

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun refreshEnabled(context: Context) {
        if (!isEnabled(context)) clear()
        notifyListeners()
    }

    fun update(direction: GoogleMapsDirection?) {
        if (current.getAndSet(direction) != direction) notifyListeners()
    }

    fun clear() = update(null)

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        listener()
        return { listeners -= listener }
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching(listener) }
    }
}
