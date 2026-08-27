package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.pillion.core.DashboardCropControl
import app.pillion.core.MirrorFreshness
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream

/**
 * Keeps the Bluetooth/NaviLite stream useful after the phone display turns off without ADB, Wi-Fi,
 * or a virtual display. While the phone is interactive this delegates to normal MediaProjection.
 * While the phone display is off it sends a locally-rendered 480x240 Google Maps directions card.
 *
 * **Screen-on freeze fix.** [PowerManager.isInteractive] flipping true is *not* proof the mirror is
 * live: Android can stop the underlying MediaProjection while the screen is off (locked, Doze, or
 * an OS-version quirk), and even when it survives, capture doesn't resume the instant the screen
 * comes back on. Trusting [mirror] the moment the screen turns on can hand the dash the same frozen
 * pre-lock bitmap forever. So on screen-on this source withholds the mirror until [mirror]'s
 * [MirrorFreshness] signal confirms a *newly captured* frame — never a merely *cached* one — and
 * falls back to a "paused" directions card instead if the projection turns out to be dead.
 */
class ScreenOffGoogleMapsScreenSource(
    context: Context,
    private val mirror: ScreenSource,
    private val quality: Int = 65,
) : ScreenSource, DashboardCropControl {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val freshness = mirror as? MirrorFreshness
    private val renderer = GoogleMapsOverlayRenderer(appContext)
    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 210, 125)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pausedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 165, 60)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 210, 216)
        textSize = 12f
        typeface = Typeface.DEFAULT
    }

    @Volatile private var lastDirectionSignature: String? = null
    @Volatile private var cachedScreenOffFrame: ByteArray? = null
    @Volatile private var cachedPaused = false
    private var removeNavigationListener: (() -> Unit)? = null

    // Screen-on resume verification (see class doc): armed the moment the screen turns interactive
    // after having been off, cleared once a genuinely new mirror frame is confirmed.
    @Volatile private var wasInteractive = true
    @Volatile private var verifyingResume = false
    @Volatile private var resumeBaselineGeneration = -1L
    @Volatile private var resumeDeadlineAt = 0L
    @Volatile private var captureConfirmedDead = false

    override fun start() {
        mirror.start()
        if (removeNavigationListener == null) {
            removeNavigationListener = GoogleMapsNavigationState.addListener {
                cachedScreenOffFrame = null
                lastDirectionSignature = null
            }
        }
    }

    override fun latestFrame(): ByteArray? = try {
        computeFrame()
    } catch (t: Throwable) {
        // MirrorEngine's streamLoop is a single uncaught-exception-away from Error state, which
        // stops the whole CaptureService — killing the Bluetooth session and forcing the rider to
        // manually reconnect. Never let a render failure here take the session down: log it and
        // keep the stream alive on a minimal fallback frame instead.
        Log.e(TAG, "screen-off directions: latestFrame failed — keeping session alive", t)
        runCatching { fallbackFrame() }.getOrNull()
    }

    private fun fallbackFrame(): ByteArray {
        canvas.drawColor(Color.rgb(7, 10, 14))
        canvas.drawText("PILLION", 12f, 20f, labelPaint)
        canvas.drawText("Recovering — hold on", 12f, 44f, titlePaint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            out.toByteArray()
        }
    }

    private fun computeFrame(): ByteArray? {
        val interactive = powerManager?.isInteractive != false
        if (!interactive) {
            wasInteractive = false
            verifyingResume = false
            return screenOffFrame(paused = false)
        }
        if (!wasInteractive) {
            // The screen just came back on. Do not trust the mirror yet — a cached frame is never
            // evidence the projection is alive. Verify a fresh capture before resuming (class doc).
            wasInteractive = true
            verifyingResume = true
            captureConfirmedDead = false
            resumeBaselineGeneration = freshness?.frameGeneration() ?: -1L
            resumeDeadlineAt = SystemClock.elapsedRealtime() + RESUME_GRACE_MS
            Log.d(TAG, "screen-off directions: screen on — verifying live capture before resuming mirror")
        }
        if (!verifyingResume) return mirror.latestFrame()

        val fresh = freshness ?: run {
            // No freshness signal available (shouldn't happen — MediaProjectionScreenSource always
            // implements it). Fail open rather than get stuck on the directions card forever.
            verifyingResume = false
            return mirror.latestFrame()
        }
        if (fresh.isCaptureStopped()) {
            if (!captureConfirmedDead) {
                captureConfirmedDead = true
                Log.w(TAG, "screen-off directions: MediaProjection was stopped by the system")
            }
            return screenOffFrame(paused = true)
        }
        if (fresh.frameGeneration() != resumeBaselineGeneration) {
            Log.d(TAG, "screen-off directions: fresh mirror frame confirmed — resuming live mirror")
            verifyingResume = false
            return mirror.latestFrame()
        }
        if (SystemClock.elapsedRealtime() >= resumeDeadlineAt) {
            return screenOffFrame(paused = true)
        }
        // Still within the grace window: keep showing directions rather than a possibly-stale frame.
        return screenOffFrame(paused = false)
    }

    private fun screenOffFrame(paused: Boolean): ByteArray {
        val direction = GoogleMapsNavigationState.snapshot(appContext)
        val debug = GoogleMapsNavigationState.debugSnapshot()
        val debugText = debug.lines.take(3).joinToString("|")
        val signature = (
            direction?.let { "${it.maneuver}|${it.distance}|${it.instruction}" }
                ?: "${debug.seen}|${debug.ongoing}|${debug.note}|$debugText"
            ) + "|paused=$paused"
        val cached = cachedScreenOffFrame
        if (cached != null && signature == lastDirectionSignature && paused == cachedPaused) return cached

        canvas.drawColor(Color.rgb(7, 10, 14))
        val panelDrawn = renderer.drawFullScreen(canvas, WIDTH, HEIGHT)

        // Paused gets the literal, actionable message up top — clear of the arrow/distance below —
        // instead of a status footer that would collide with them.
        val label = if (paused) "MAP CAPTURE PAUSED — REOPEN PILLION" else "PILLION • SCREEN OFF"
        canvas.drawText(label, 12f, 14f, if (paused) pausedLabelPaint else labelPaint)

        if (!panelDrawn) {
            val left = 16f
            val wrapWidth = WIDTH - left * 2
            titlePaint.textSize = 17f
            canvas.drawText(
                if (debug.seen) "Maps notification detected" else "Waiting for Google Maps",
                left, 48f, titlePaint,
            )
            detailPaint.textSize = 11f
            drawWrapped(debug.note, left, 72f, wrapWidth, 2, detailPaint, 16f)
            if (debug.lines.isNotEmpty()) {
                labelPaint.textSize = 10f
                canvas.drawText("RAW MAPS TEXT:", left, 110f, labelPaint)
                detailPaint.textSize = 10f
                var y = 126f
                debug.lines.take(3).forEach { line ->
                    y = drawWrappedReturningY(line, left, y, wrapWidth, 2, detailPaint, 14f)
                    y += 3f
                }
            } else {
                detailPaint.textSize = 11f
                canvas.drawText("Check Notification access for Pillion Zoom.", left, 132f, detailPaint)
                canvas.drawText("Then start an active route in Google Maps.", left, 150f, detailPaint)
            }
        }

        val encoded = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), out)
            out.toByteArray()
        }
        lastDirectionSignature = signature
        cachedScreenOffFrame = encoded
        cachedPaused = paused
        return encoded
    }

    private fun drawWrapped(
        text: String,
        x: Float,
        firstBaseline: Float,
        maxWidth: Float,
        maxLines: Int,
        paint: Paint,
        lineHeight: Float,
    ) {
        drawWrappedReturningY(text, x, firstBaseline, maxWidth, maxLines, paint, lineHeight)
    }

    private fun drawWrappedReturningY(
        text: String,
        x: Float,
        firstBaseline: Float,
        maxWidth: Float,
        maxLines: Int,
        paint: Paint,
        lineHeight: Float,
    ): Float {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return firstBaseline
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
                if (lines.size == maxLines - 1) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += current
        var y = firstBaseline
        lines.take(maxLines).forEach { line ->
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
        return y
    }

    override fun cycleCropPreset(delta: Int) {
        (mirror as? DashboardCropControl)?.cycleCropPreset(delta)
    }

    override fun stop() {
        removeNavigationListener?.invoke()
        removeNavigationListener = null
        runCatching { mirror.stop() }
        cachedScreenOffFrame = null
        runCatching { bitmap.recycle() }
    }

    private companion object {
        const val TAG = "Pillion"
        const val WIDTH = 480
        const val HEIGHT = 240

        /** How long screen-on gets to prove the mirror produced a fresh frame before we call it dead. */
        const val RESUME_GRACE_MS = 2_500L
    }
}
