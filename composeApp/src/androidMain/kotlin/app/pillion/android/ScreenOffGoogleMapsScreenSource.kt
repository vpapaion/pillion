package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import android.os.SystemClock
import app.pillion.android.nav.RouteNavigationState
import app.pillion.core.DashboardCropControl
import app.pillion.core.GoogleMapsDirection
import app.pillion.nav.NavFormat
import java.util.Calendar
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
 *
 * **Pillion navigation.** While a Pillion route is active ([RouteNavigationState]) the dash always
 * shows Pillion's own map + turn panel — screen on or off, like StreetCross — because that frame is
 * rendered independently of the phone display. Ending the route returns to normal mirroring (via
 * the same fresh-frame verification as a screen-on).
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
    private val liveMap = ScreenOffLiveMap(appContext)
    private val missingDirectionPanelPaint = Paint().apply { color = Color.argb(220, 7, 10, 14) }
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
    private var removeRouteListener: (() -> Unit)? = null
    private val stripPaint = Paint().apply { color = Color.argb(215, 9, 14, 18) }
    private val stripText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val navLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(95, 170, 255)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }

    // Screen-on resume verification (see class doc): armed the moment the screen turns interactive
    // after having been off, cleared once a genuinely new mirror frame is confirmed.
    @Volatile private var wasInteractive = true
    @Volatile private var verifyingResume = false
    @Volatile private var resumeBaselineGeneration = -1L
    @Volatile private var resumeDeadlineAt = 0L
    @Volatile private var captureConfirmedDead = false

    override fun start() {
        mirror.start()
        RouteNavigationState.restore(appContext)
        liveMap.start()
        if (removeRouteListener == null) {
            removeRouteListener = RouteNavigationState.addListener {
                cachedScreenOffFrame = null
                lastDirectionSignature = null
            }
        }
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
        DiagnosticLog.e(TAG, "screen-off directions: latestFrame failed — keeping session alive", t)
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
        if (RouteNavigationState.hasDestination()) {
            // Treat like screen-off so that ending the route re-verifies the mirror before use.
            wasInteractive = false
            verifyingResume = false
            return navigationFrame()
        }
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
            DiagnosticLog.d(TAG, "screen-off directions: screen on — verifying live capture before resuming mirror")
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
                DiagnosticLog.w(TAG, "screen-off directions: MediaProjection was stopped by the system")
            }
            return screenOffFrame(paused = true)
        }
        if (fresh.frameGeneration() != resumeBaselineGeneration) {
            DiagnosticLog.d(TAG, "screen-off directions: fresh mirror frame confirmed — resuming live mirror")
            verifyingResume = false
            return mirror.latestFrame()
        }
        if (SystemClock.elapsedRealtime() >= resumeDeadlineAt) {
            return screenOffFrame(paused = true)
        }
        // Still within the grace window: keep showing directions rather than a possibly-stale frame.
        return screenOffFrame(paused = false)
    }

    /** Pillion's own route guidance: heading-up map, route line, turn panel, distance/time/ETA. */
    private fun navigationFrame(): ByteArray {
        val status = RouteNavigationState.status
        val signature = "nav|" + when (status) {
            is RouteNavigationState.Status.Active -> status.guidance?.let {
                "${it.next?.beginIndex}|${it.metersToNext.toInt() / 10}|${it.remainingMeters.toInt() / 50}"
            } + "|" + status.route.points.size
            else -> status.toString()
        } + "|mapRevision=${liveMap.revision()}"
        val cached = cachedScreenOffFrame
        if (cached != null && signature == lastDirectionSignature) return cached

        val hasLiveMap = liveMap.draw(canvas, WIDTH, HEIGHT)
        when (status) {
            is RouteNavigationState.Status.Active -> {
                val g = status.guidance
                val next = g?.next
                if (next != null) {
                    val text = if (next.isArrival) status.route.destinationName
                        else next.street ?: next.instruction
                    renderer.drawPanel(
                        canvas, WIDTH, HEIGHT,
                        GoogleMapsDirection(text, NavFormat.distance(g.metersToNext), next.maneuver),
                        "PILLION NAV",
                    )
                    drawTripStrip(g.remainingMeters, g.remainingSeconds)
                } else {
                    navMessage("PILLION NAV", "Route ready", status.route.destinationName)
                }
            }
            is RouteNavigationState.Status.Calculating ->
                navMessage("PILLION NAV", "Calculating route…", status.destinationName)
            is RouteNavigationState.Status.Failed ->
                navMessage("PILLION NAV", "No route yet", status.message)
            is RouteNavigationState.Status.Arrived ->
                navMessage("PILLION NAV", "Arrived", status.destinationName)
            RouteNavigationState.Status.Idle -> Unit
        }
        if (!hasLiveMap && status !is RouteNavigationState.Status.Active) {
            detailPaint.textSize = 11f
            canvas.drawText("Waiting for GPS…", 172f, 140f, detailPaint)
        }

        val encoded = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), out)
            out.toByteArray()
        }
        lastDirectionSignature = signature
        cachedScreenOffFrame = encoded
        cachedPaused = false
        return encoded
    }

    private fun navMessage(label: String, title: String, detail: String) {
        canvas.drawRect(0f, 0f, 162f, HEIGHT.toFloat(), missingDirectionPanelPaint)
        canvas.drawText(label, 10f, 16f, navLabelPaint)
        titlePaint.textSize = 16f
        drawWrapped(title, 10f, 56f, 146f, 2, titlePaint, 20f)
        detailPaint.textSize = 12f
        drawWrapped(detail, 10f, 110f, 146f, 5, detailPaint, 16f)
    }

    /** "12.4 km · 18 min · 14:32" across the top of the map area. */
    private fun drawTripStrip(remainingMeters: Double, remainingSeconds: Double) {
        val now = Calendar.getInstance()
        val etaMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) +
            (remainingSeconds / 60.0).toInt()
        val text = NavFormat.distance(remainingMeters) + "  ·  " + NavFormat.duration(remainingSeconds) +
            "  ·  " + NavFormat.clock(etaMinutes)
        val w = stripText.measureText(text) + 16f
        canvas.drawRoundRect(WIDTH - w - 4f, 4f, WIDTH - 4f, 26f, 8f, 8f, stripPaint)
        canvas.drawText(text, WIDTH - 12f, 20f, stripText)
    }

    private fun screenOffFrame(paused: Boolean): ByteArray {
        val direction = GoogleMapsNavigationState.snapshot(appContext)
        val debug = GoogleMapsNavigationState.debugSnapshot()
        val debugText = debug.lines.take(3).joinToString("|")
        val signature = (
            direction?.let { "${it.maneuver}|${it.distance}|${it.instruction}" }
                ?: "${debug.seen}|${debug.ongoing}|${debug.note}|$debugText"
            ) + "|paused=$paused|mapRevision=${liveMap.revision()}"
        val cached = cachedScreenOffFrame
        if (cached != null && signature == lastDirectionSignature && paused == cachedPaused) return cached

        // A genuine GPS-positioned map is drawn independently of the Android display and stays
        // up to date through Bluetooth while the phone panel is locked. Without location access
        // we retain the old full-screen Google Maps direction card as a safe fallback.
        val hasLiveMap = liveMap.draw(canvas, WIDTH, HEIGHT)
        val panelDrawn = if (hasLiveMap) renderer.draw(canvas, WIDTH, HEIGHT)
            else renderer.drawFullScreen(canvas, WIDTH, HEIGHT)

        // Paused gets the literal, actionable message up top — clear of the arrow/distance below —
        // instead of a status footer that would collide with them.
        val label = if (paused) "MAP CAPTURE PAUSED — REOPEN PILLION" else "PILLION • SCREEN OFF"
        canvas.drawText(label, 12f, 14f, if (paused) pausedLabelPaint else labelPaint)

        if (!panelDrawn && hasLiveMap) {
            // Navigation notification missing: keep the live map unobstructed on the right.
            canvas.drawRect(0f, 20f, 162f, 218f, missingDirectionPanelPaint)
            titlePaint.textSize = 15f
            canvas.drawText("LIVE GPS MAP", 9f, 52f, titlePaint)
            detailPaint.textSize = 12f
            canvas.drawText("No turn data", 9f, 82f, detailPaint)
            canvas.drawText("from Google Maps", 9f, 100f, detailPaint)
            canvas.drawText("Location updates", 9f, 151f, detailPaint)
            canvas.drawText("continue via BT", 9f, 169f, detailPaint)
        } else if (!panelDrawn) {
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
        removeRouteListener?.invoke()
        removeRouteListener = null
        runCatching { liveMap.stop() }
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
