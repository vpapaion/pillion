package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import app.pillion.core.DashboardCropControl
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream

/**
 * Keeps the Bluetooth/NaviLite stream useful after the phone display turns off without ADB, Wi-Fi,
 * or a virtual display. While the phone is interactive this delegates to normal MediaProjection.
 * While the phone display is off it sends a locally-rendered 480x240 Google Maps directions card
 * built from [GoogleMapsNavigationListenerService].
 */
class ScreenOffGoogleMapsScreenSource(
    context: Context,
    private val mirror: ScreenSource,
    private val quality: Int = 65,
) : ScreenSource, DashboardCropControl {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val renderer = GoogleMapsOverlayRenderer(appContext)
    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 210, 125)
        textSize = 14f
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
    private var removeNavigationListener: (() -> Unit)? = null

    override fun start() {
        mirror.start()
        if (removeNavigationListener == null) {
            removeNavigationListener = GoogleMapsNavigationState.addListener {
                cachedScreenOffFrame = null
                lastDirectionSignature = null
            }
        }
    }

    override fun latestFrame(): ByteArray? {
        if (powerManager?.isInteractive != false) return mirror.latestFrame()
        return screenOffFrame()
    }

    private fun screenOffFrame(): ByteArray {
        val direction = GoogleMapsNavigationState.snapshot(appContext)
        val signature = direction?.let { "${it.maneuver}|${it.distance}|${it.instruction}" } ?: "waiting"
        val cached = cachedScreenOffFrame
        if (cached != null && signature == lastDirectionSignature) return cached

        canvas.drawColor(Color.rgb(7, 10, 14))
        val panelDrawn = renderer.draw(canvas, WIDTH, HEIGHT)

        val rightX = 170f
        labelPaint.textSize = 12f
        canvas.drawText("PILLION • SCREEN OFF", rightX, 28f, labelPaint)
        if (panelDrawn && direction != null) {
            titlePaint.textSize = 19f
            drawWrapped(direction.instruction, rightX, 62f, 292f, 3, titlePaint, 25f)
            detailPaint.textSize = 12f
            canvas.drawText("Google Maps directions via Bluetooth", rightX, 218f, detailPaint)
        } else {
            titlePaint.textSize = 19f
            canvas.drawText("Waiting for", rightX, 88f, titlePaint)
            canvas.drawText("Google Maps navigation", rightX, 116f, titlePaint)
            detailPaint.textSize = 12f
            canvas.drawText("Start a route in Google Maps.", rightX, 154f, detailPaint)
            canvas.drawText("The phone display may stay off.", rightX, 174f, detailPaint)
        }

        val encoded = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), out)
            out.toByteArray()
        }
        lastDirectionSignature = signature
        cachedScreenOffFrame = encoded
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
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
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
        const val WIDTH = 480
        const val HEIGHT = 240
    }
}
