package app.pillion.android

import android.content.Context
import app.pillion.core.GoogleMapsDirection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Raw Google Maps notification information used only for on-bike diagnostics. */
internal data class GoogleMapsNotificationDebug(
    val seen: Boolean = false,
    val ongoing: Boolean = false,
    val postTime: Long = 0L,
    val lines: List<String> = emptyList(),
    val note: String = "No Google Maps notification seen",
)

/** Runtime Google Maps instruction shared by notification listener and both dashboard renderers. */
internal object GoogleMapsNavigationState {
    private const val PREFS = "pillion.settings"
    private const val KEY_ENABLED = "google_maps_overlay_enabled"

    private val current = AtomicReference<GoogleMapsDirection?>(null)
    private val debug = AtomicReference(GoogleMapsNotificationDebug())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun snapshot(context: Context): GoogleMapsDirection? =
        if (isEnabled(context)) current.get() else null

    fun debugSnapshot(): GoogleMapsNotificationDebug = debug.get()

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

    fun updateDebug(value: GoogleMapsNotificationDebug) {
        if (debug.getAndSet(value) != value) notifyListeners()
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
