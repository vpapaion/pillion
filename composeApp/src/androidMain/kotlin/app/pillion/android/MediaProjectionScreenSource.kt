package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.pillion.core.DashboardCropControl
import app.pillion.core.MirrorFocus
import app.pillion.core.MirrorZoom
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream

/**
 * A [ScreenSource] backed by MediaProjection. Mirrors the display into a 480x240 [ImageReader]
 * and, on demand, crops/scales the most recent frame before compressing it to JPEG.
 *
 * Crop position is read from [MirrorViewportState] for every frame, so dashboard buttons can move
 * the visible area immediately while mirroring continues.
 */
class MediaProjectionScreenSource(
    private val context: Context,
    private val projection: MediaProjection,
    private val quality: Int = DEFAULT_QUALITY,
    zoomPercent: Int = MirrorZoom.DEFAULT_PERCENT,
    focus: MirrorFocus = MirrorFocus.DEFAULT,
) : ScreenSource, DashboardCropControl {

    private val thread = HandlerThread("pillion-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var latest: Bitmap? = null

    private val output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(output)
    private val source = Rect()
    private val destination = Rect(0, 0, WIDTH, HEIGHT)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint().apply { color = Color.argb(190, 0, 0, 0) }
    private val overlayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val googleMapsOverlay = GoogleMapsOverlayRenderer(context)

    init {
        MirrorViewportState.initialize(context, zoomPercent, focus)
    }

    override fun start() {
        if (display != null) return // idempotent: capture may be pre-started by the service
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.w(TAG, "screen: projection stopped by the system") }
        }, handler)
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> capture(ir) }, handler)
        reader = r
        val dpi = context.resources.displayMetrics.densityDpi
        display = projection.createVirtualDisplay(
            "pillion", WIDTH, HEIGHT, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        val viewport = MirrorViewportState.snapshot()
        Log.d(
            TAG,
            "screen: virtual display created (${WIDTH}x$HEIGHT), zoom=${viewport.zoomPercent}% " +
                "crop=${viewport.xPercent},${viewport.yPercent}",
        )
    }

    private fun capture(ir: ImageReader) {
        val image = ir.acquireLatestImage() ?: return
        try {
            val first = latest == null
            latest = toBitmap(image)
            if (first) Log.d(TAG, "screen: first frame captured")
        } catch (t: Throwable) {
            Log.w(TAG, "screen: dropped a frame", t)
        } finally {
            image.close()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * WIDTH
        val full = Bitmap.createBitmap(
            WIDTH + if (pixelStride > 0) rowPadding / pixelStride else 0,
            HEIGHT, Bitmap.Config.ARGB_8888,
        )
        full.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, WIDTH, HEIGHT)
    }

    override fun latestFrame(): ByteArray? {
        val bitmap = latest ?: return null
        val viewport = MirrorViewportState.snapshot()
        val cropWidth = MirrorZoom.cropSize(WIDTH, viewport.zoomPercent)
        val cropHeight = MirrorZoom.cropSize(HEIGHT, viewport.zoomPercent)
        val cropLeft = viewport.cropLeft(WIDTH, cropWidth)
        val cropTop = viewport.cropTop(HEIGHT, cropHeight)
        source.set(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)

        canvas.drawBitmap(bitmap, source, destination, imagePaint)
        val mapsOverlayVisible = googleMapsOverlay.draw(canvas, WIDTH, HEIGHT)
        drawOverlay(viewport, mapsOverlayVisible)

        return ByteArrayOutputStream().use { out ->
            output.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    override fun cycleCropPreset(delta: Int) {
        MirrorViewportState.cyclePreset(context, delta)
    }

    private fun drawOverlay(viewport: MirrorViewportSnapshot, mapsOverlayVisible: Boolean) {
        if (!viewport.overlayVisible()) return
        val text = viewport.message ?: return
        val padding = 8f
        val textWidth = overlayTextPaint.measureText(text)
        val left = if (mapsOverlayVisible) 164f else 8f
        val top = 8f
        val bottom = top + 28f
        canvas.drawRoundRect(left, top, left + textWidth + padding * 2, bottom, 7f, 7f, overlayPaint)
        canvas.drawText(text, left + padding, top + 20f, overlayTextPaint)
    }

    override fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        thread.quitSafely()
        latest = null
        runCatching { output.recycle() }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 240
        const val DEFAULT_QUALITY = 40
        const val TAG = "Pillion"
    }
}
