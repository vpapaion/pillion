package app.pillion.android

import android.content.Context
import app.pillion.core.MirrorFocus
import app.pillion.core.MirrorZoom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Current crop viewport shared by the Bluetooth/NaviLite and USB/SDL rendering paths. */
internal data class MirrorViewportSnapshot(
    val zoomPercent: Int,
    val xPercent: Int,
    val yPercent: Int,
    val message: String? = null,
    val messageUntilMs: Long = 0L,
) {
    fun cropLeft(fullWidth: Int, cropWidth: Int): Int =
        cropStart(fullWidth, cropWidth, xPercent)

    fun cropTop(fullHeight: Int, cropHeight: Int): Int =
        cropStart(fullHeight, cropHeight, yPercent)

    private fun cropStart(fullSize: Int, cropSize: Int, percent: Int): Int {
        val remaining = (fullSize - cropSize).coerceAtLeast(0)
        return (remaining * percent.coerceIn(0, 100) / 100f)
            .roundToInt()
            .coerceIn(0, remaining)
    }

    fun overlayVisible(nowMs: Long = System.currentTimeMillis()): Boolean =
        message != null && nowMs < messageUntilMs
}

/**
 * Thread-safe runtime crop controller. Dashboard button events update it and active encoders read it
 * for every frame, so position and zoom change immediately without restarting screen capture.
 */
internal object MirrorViewportState {
    private const val PREFS = "pillion.settings"
    private const val KEY_ZOOM = "mirror_zoom_percent"
    private const val KEY_FOCUS = "mirror_focus"
    private const val KEY_CROP_X = "mirror_crop_x_percent"
    private const val KEY_CROP_Y = "mirror_crop_y_percent"
    private const val KEY_CUSTOM = "mirror_crop_custom"
    private const val OVERLAY_MS = 2200L

    private val state = AtomicReference(
        MirrorViewportSnapshot(
            zoomPercent = MirrorZoom.DEFAULT_PERCENT,
            xPercent = MirrorFocus.DEFAULT.xPercent,
            yPercent = MirrorFocus.DEFAULT.yPercent,
        ),
    )
    private val listeners = CopyOnWriteArrayList<(MirrorViewportSnapshot) -> Unit>()
    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context, requestedZoom: Int, requestedFocus: MirrorFocus) {
        appContext = context.applicationContext
        if (initialized) return
        val prefs = prefs(context)
        val custom = prefs.getBoolean(KEY_CUSTOM, false) &&
            prefs.contains(KEY_CROP_X) && prefs.contains(KEY_CROP_Y)
        val focus = MirrorFocus.fromName(prefs.getString(KEY_FOCUS, requestedFocus.name))
        state.set(
            MirrorViewportSnapshot(
                zoomPercent = MirrorZoom.clamp(prefs.getInt(KEY_ZOOM, requestedZoom)),
                xPercent = if (custom) prefs.getInt(KEY_CROP_X, focus.xPercent).coerceIn(0, 100) else focus.xPercent,
                yPercent = if (custom) prefs.getInt(KEY_CROP_Y, focus.yPercent).coerceIn(0, 100) else focus.yPercent,
            ),
        )
        initialized = true
    }

    fun snapshot(): MirrorViewportSnapshot = state.get()

    fun setZoom(context: Context, percent: Int, showMessage: Boolean = true) {
        ensureInitialized(context)
        val zoom = MirrorZoom.clamp(percent)
        prefs(context).edit().putInt(KEY_ZOOM, zoom).apply()
        update(showMessage.thenMessage("Zoom $zoom%")) { it.copy(zoomPercent = zoom) }
    }

    fun adjustZoom(context: Context, deltaPercent: Int) {
        setZoom(context, snapshot().zoomPercent + deltaPercent)
    }

    fun setFocusPreset(context: Context, focus: MirrorFocus, showMessage: Boolean = true) {
        ensureInitialized(context)
        prefs(context).edit()
            .putString(KEY_FOCUS, focus.name)
            .putInt(KEY_CROP_X, focus.xPercent)
            .putInt(KEY_CROP_Y, focus.yPercent)
            .putBoolean(KEY_CUSTOM, false)
            .apply()
        update(showMessage.thenMessage("Crop: ${focus.label}")) {
            it.copy(xPercent = focus.xPercent, yPercent = focus.yPercent)
        }
    }

    fun nudge(context: Context, dxPercent: Int, dyPercent: Int) {
        ensureInitialized(context)
        val current = snapshot()
        val x = (current.xPercent + dxPercent).coerceIn(0, 100)
        val y = (current.yPercent + dyPercent).coerceIn(0, 100)
        prefs(context).edit()
            .putInt(KEY_CROP_X, x)
            .putInt(KEY_CROP_Y, y)
            .putBoolean(KEY_CUSTOM, true)
            .apply()
        update("Crop: $x%, $y%") { it.copy(xPercent = x, yPercent = y) }
    }

    fun center(context: Context) = setFocusPreset(context, MirrorFocus.CENTER)

    fun cyclePreset(context: Context, delta: Int) {
        ensureInitialized(context)
        if (delta == 0) return
        val order = listOf(
            MirrorFocus.CENTER,
            MirrorFocus.TOP_LEFT,
            MirrorFocus.TOP_RIGHT,
            MirrorFocus.BOTTOM_RIGHT,
            MirrorFocus.BOTTOM_LEFT,
        )
        val current = snapshot()
        val nearest = order.indices.minByOrNull { index ->
            val focus = order[index]
            val dx = focus.xPercent - current.xPercent
            val dy = focus.yPercent - current.yPercent
            dx * dx + dy * dy
        } ?: 0
        val direction = if (delta > 0) 1 else -1
        val next = (nearest + direction + order.size) % order.size
        setFocusPreset(context, order[next])
    }

    fun addListener(listener: (MirrorViewportSnapshot) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot())
        return { listeners -= listener }
    }

    private fun ensureInitialized(context: Context) {
        if (!initialized) {
            val prefs = prefs(context)
            initialize(
                context,
                prefs.getInt(KEY_ZOOM, MirrorZoom.DEFAULT_PERCENT),
                MirrorFocus.fromName(prefs.getString(KEY_FOCUS, null)),
            )
        }
    }

    private fun update(message: String?, transform: (MirrorViewportSnapshot) -> MirrorViewportSnapshot) {
        while (true) {
            val old = state.get()
            val changed = transform(old)
            val next = if (message == null) {
                changed.copy(message = null, messageUntilMs = 0L)
            } else {
                changed.copy(message = message, messageUntilMs = System.currentTimeMillis() + OVERLAY_MS)
            }
            if (state.compareAndSet(old, next)) {
                listeners.forEach { listener -> runCatching { listener(next) } }
                return
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Boolean.thenMessage(message: String): String? = if (this) message else null
}
