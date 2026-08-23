package app.pillion.android

import android.os.SystemClock
import android.util.Log
import app.pillion.core.ScreenSource

/**
 * A [ScreenSource] that the engine pulls from, switching between the phone **mirror** (while
 * unlocked, or while the panel is blanked by screen-off mirroring) and the **dash** stream (while
 * locked) so one Bluetooth session serves both. The dash helper only encodes while [promote]d (see
 * [DashStreamScreenSource]/`DashServer`), so the idle source costs no extra battery.
 *
 * Single responsibility: choose which underlying source feeds the engine right now.
 */
class SwitchableScreenSource(
    private val mirror: ScreenSource,
    private val dash: DashStreamScreenSource,
) : ScreenSource {

    @Volatile private var useDash = false
    @Volatile private var promotedAt = 0L
    @Volatile private var frozenLogged = false

    override fun start() {
        mirror.start()
        dash.start() // connects to the helper's loopback socket; stays idle until promoted
    }

    /**
     * While promoted, the mirror is only a *bridge*: display 0 stops being composed a moment after
     * the phone locks, so from then on `mirror.latestFrame()` returns the same frozen bitmap
     * forever. Handing that to the engine is worse than handing it nothing — the dash sits on a
     * stale image while the UI cheerfully reports 15 fps. So the fallback is time-boxed to the
     * hand-over window; after that a dead dash reads as *no frame*, the engine pauses, and
     * [CaptureService] can react (it falls back to screen-off mirroring).
     */
    override fun latestFrame(): ByteArray? {
        if (!useDash) return mirror.latestFrame()
        dash.latestFrame()?.let {
            frozenLogged = false
            return it
        }
        if (SystemClock.elapsedRealtime() - promotedAt <= HANDOVER_GRACE_MS) return mirror.latestFrame()
        if (!frozenLogged) {
            frozenLogged = true
            Log.w(TAG, "dash: no dash frames after hand-over — not sending the frozen mirror frame")
        }
        return null
    }

    override fun stop() {
        runCatching { mirror.stop() }
        runCatching { dash.stop() }
    }

    /** Phone locked: promote the foreground app to the dash and stream it. */
    fun promote(component: String) {
        promotedAt = SystemClock.elapsedRealtime()
        frozenLogged = false
        dash.promote(component)
        useDash = true
    }

    /** Phone unlocked (or the dash failed): return to mirroring and move the app back to the phone. */
    fun demote() {
        useDash = false
        promotedAt = 0L
        dash.demote()
    }

    /** True while the dash helper is actually delivering fresh frames. */
    fun isDashFresh(): Boolean = dash.isFresh()

    /** Blank the phone's panel while keeping display 0 rendering, so the mirror survives it. */
    fun blank() = dash.blank()

    /** Restore the phone's panel. */
    fun unblank() = dash.unblank()

    /** End the session: stop mirroring and tell the (detached) helper to release the display + exit. */
    fun quit() {
        runCatching { mirror.stop() }
        runCatching { dash.quit() }
    }

    private companion object {
        const val TAG = "Pillion"

        /** How long the mirror may cover for the dash right after a promote, before it counts as dead. */
        const val HANDOVER_GRACE_MS = 1_500L
    }
}
