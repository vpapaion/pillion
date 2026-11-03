package app.pillion.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Independently renders a live GPS-centered OpenStreetMap map into the existing NaviLite JPEG
 * stream. No WebView, MediaProjection, privileged display, Wi-Fi pairing, or ADB is used.
 *
 * This is a location map, not Google Maps route geometry. Google Maps notifications provide
 * separate (best-effort) turn hints through GoogleMapsOverlayRenderer.
 *
 * OSM tile usage: visible viewport only, HTTPS, identifying User-Agent, on-disk tile reuse for
 * at least seven days, attribution on every rendered frame. No background prefetch or downloads.
 * Tiles disclose the approximate viewport to OSM servers and require mobile data or Wi-Fi.
 */
internal class ScreenOffLiveMap(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val latestLocation = AtomicReference<Location?>(null)
    private val revision = AtomicLong(0L)
    private val workers = Executors.newFixedThreadPool(2)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val tileDir = File(appContext.filesDir, "pillion-osm-visible-tiles").apply { mkdirs() }
    private val inMemory = object : LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 32
    }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val positionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 160, 250) }
    private val positionOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val attributionBg = Paint().apply { color = Color.argb(210, 9, 14, 18) }
    private val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }
    @Volatile private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.latitude !in -85.0511..85.0511) return
            if (location.time <= 0L) return
            latestLocation.set(Location(location))
            revision.incrementAndGet()
        }
    }

    fun revision(): Long = revision.get()

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started) return
        started = true
        if (!hasPermission()) {
            DiagnosticLog.w(TAG, "screen-off GPS map: no location permission; directions fallback only")
            return
        }
        val manager = locationManager ?: return
        try {
            // Registration occurs while the user is starting the foreground navigation session.
            // CaptureService includes foregroundServiceType=location when location is granted.
            val providerNames = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (provider in providerNames) {
                if (!manager.isProviderEnabled(provider)) continue
                runCatching {
                    manager.getLastKnownLocation(provider)
                }.getOrNull()?.let { location ->
                    if (System.currentTimeMillis() - location.time in 0L..120_000L) {
                        val old = latestLocation.get()
                        if (old == null || location.time > old.time) {
                            latestLocation.set(Location(location))
                            revision.incrementAndGet()
                        }
                    }
                }
                runCatching {
                    manager.requestLocationUpdates(provider, 1_500L, 3f, listener, Looper.getMainLooper())
                }.onFailure { DiagnosticLog.w(TAG, "screen-off GPS provider unavailable: " + provider, it) }
            }
        } catch (e: SecurityException) {
            DiagnosticLog.w(TAG, "screen-off GPS permission unavailable", e)
        }
    }

    /**
     * Draws the map and the centered current-position marker. Returns false until a fresh GPS
     * fix is available. Calling this repeatedly never performs network I/O on the stream thread.
     */
    fun draw(canvas: Canvas, width: Int, height: Int): Boolean {
        canvas.drawColor(Color.rgb(23, 34, 44))
        val fix = latestLocation.get()
        if (fix == null || System.currentTimeMillis() - fix.time !in 0L..180_000L) {
            canvas.drawText(
                if (hasPermission()) "Waiting for GPS fix" else "Allow location for live map",
                166f, 117f, statusPaint,
            )
            drawAttribution(canvas, width, height)
            return false
        }

        val zoom = if (fix.hasSpeed() && fix.speed > 22f) 14 else 15
        val world = 1 shl zoom
        val latRad = Math.toRadians(fix.latitude.coerceIn(-85.0511, 85.0511))
        val longitude = fix.longitude.coerceIn(-180.0, 180.0)
        val tileX = (longitude + 180.0) / 360.0 * world
        val tileY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * world
        val centerX = width * 0.67f
        val centerY = height * 0.50f
        val worldX = tileX * TILE_SIZE
        val worldY = tileY * TILE_SIZE
        val leftTile = floor((worldX - centerX) / TILE_SIZE).toInt()
        val rightTile = floor((worldX + width - centerX) / TILE_SIZE).toInt()
        val topTile = floor((worldY - centerY) / TILE_SIZE).toInt()
        val bottomTile = floor((worldY + height - centerY) / TILE_SIZE).toInt()

        for (y in topTile..bottomTile) {
            if (y < 0 || y >= world) continue
            for (x in leftTile..rightTile) {
                val normalizedX = ((x % world) + world) % world
                val key = zoom.toString() + "-" + normalizedX + "-" + y
                val tile = getOrRequestTile(key, zoom, normalizedX, y)
                if (tile != null) {
                    val dx = (x * TILE_SIZE - worldX + centerX).toFloat()
                    val dy = (y * TILE_SIZE - worldY + centerY).toFloat()
                    canvas.drawBitmap(
                        tile, null, RectF(dx, dy, dx + TILE_SIZE, dy + TILE_SIZE), tilePaint,
                    )
                }
            }
        }

        // GPS marker is rotated to bearing only when GPS supplies a reliable heading.
        canvas.save()
        canvas.rotate(if (fix.hasBearing()) fix.bearing else 0f, centerX, centerY)
        val marker = Path().apply {
            moveTo(centerX, centerY - 16f)
            lineTo(centerX + 12f, centerY + 13f)
            lineTo(centerX, centerY + 8f)
            lineTo(centerX - 12f, centerY + 13f)
            close()
        }
        canvas.drawPath(marker, positionFill)
        canvas.drawPath(marker, positionOutline)
        canvas.restore()
        drawAttribution(canvas, width, height)
        return true
    }

    private fun getOrRequestTile(key: String, zoom: Int, x: Int, y: Int): Bitmap? {
        synchronized(inMemory) { inMemory[key] }?.let { return it }
        val file = File(tileDir, key + ".png")
        if (file.isFile && file.length() > 128L) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                synchronized(inMemory) { inMemory[key] = it }
                return it
            }
            file.delete()
        }

        // Only tiles in the frame currently being rendered are requested.
        if (SystemClock.elapsedRealtime() < (retryAfter[key] ?: 0L)) return null
        if (!pending.add(key)) return null
        try {
            workers.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(
                        "https://tile.openstreetmap.org/" + zoom + "/" + x + "/" + y + ".png",
                    ).openConnection() as HttpURLConnection
                    connection.setRequestProperty(
                        "User-Agent",
                        "PillionZoom-Tracer7/0.2.11 (+https://github.com/vpapaion/pillion)",
                    )
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 6_000
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("OSM HTTP " + connection.responseCode)
                    }
                    val temp = File(tileDir, key + ".part")
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                    val tile = BitmapFactory.decodeFile(file.absolutePath)
                        ?: throw IllegalStateException("Invalid OSM tile")
                    synchronized(inMemory) { inMemory[key] = tile }
                    revision.incrementAndGet()
                } catch (error: Exception) {
                    retryAfter[key] = SystemClock.elapsedRealtime() + 120_000L
                    DiagnosticLog.w(TAG, "screen-off map tile not available", error)
                } finally {
                    connection?.disconnect()
                    pending.remove(key)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.remove(key)
        }
        return null
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int) {
        val caption = "\u00a9 OpenStreetMap contributors"
        val textWidth = attribution.measureText(caption)
        canvas.drawRect(
            width - textWidth - 12f, height - 20f, width.toFloat(), height.toFloat(), attributionBg,
        )
        canvas.drawText(caption, width - 6f, height - 6f, attribution)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager?.removeUpdates(listener) }
        workers.shutdownNow()
        synchronized(inMemory) { inMemory.clear() }
    }

    companion object {
        private const val TILE_SIZE = 256.0
        private const val TAG = "Pillion"
    }
}
