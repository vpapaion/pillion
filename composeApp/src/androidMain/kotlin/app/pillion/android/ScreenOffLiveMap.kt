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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import app.pillion.android.nav.RouteNavigationState
import app.pillion.nav.GeoPoint
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Independently renders a live GPS-centered OpenStreetMap map into the existing NaviLite JPEG
 * stream. No WebView, MediaProjection, privileged display, Wi-Fi pairing, or ADB is used.
 *
 * When Pillion's own navigation is active ([RouteNavigationState]) the remaining route is drawn on
 * top of the tiles, the view turns heading-up while moving and zooms in ahead of each turn — the
 * StreetCross-style layout. Without a Pillion route it is a north-up location map and Google Maps
 * notifications provide separate (best-effort) turn hints through GoogleMapsOverlayRenderer.
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
    private val workers = Executors.newFixedThreadPool(4)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val tileDir = File(appContext.filesDir, "pillion-osm-visible-tiles").apply { mkdirs() }
    // Avoid an anonymous java.util.LinkedHashMap subclass: K2 multiplatform actualization can
    // crash resolving the inherited get() override on that JVM-local anonymous class.
    private val inMemory = LinkedHashMap<String, Bitmap>(64, 0.75f, true)
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
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(12, 60, 140)
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val destinationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 50, 50) }
    @Volatile private var started = false
    /** Last trustworthy heading; kept while stopped so the map doesn't spin at traffic lights. */
    @Volatile private var heldBearing: Float? = null
    /**
     * Zoom only changes with hysteresis and a minimum dwell: every change needs a whole new tile
     * set, and flapping between levels (e.g. riding at ~80 km/h, or approaching turns) used to
     * leave the map blank while moving — it only filled in once the bike stopped.
     */
    private var currentZoom = 15
    private var zoomChangedAt = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.latitude !in -85.0511..85.0511) return
            if (location.time <= 0L) return
            latestLocation.set(Location(location))
            if (location.hasBearing() && location.hasSpeed() && location.speed > 2.5f) heldBearing = location.bearing
            runCatching { RouteNavigationState.onLocation(Location(location)) }
                .onFailure { DiagnosticLog.w(TAG, "route guidance update failed", it) }
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
                    manager.requestLocationUpdates(provider, 1_000L, 2f, listener, Looper.getMainLooper())
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

        val nav = RouteNavigationState.status as? RouteNavigationState.Status.Active
        val toNext = nav?.guidance?.metersToNext
        val zoom = stableZoom(if (fix.hasSpeed()) fix.speed else 0f, toNext)
        val world = 1 shl zoom
        val centerWorld = project(fix.latitude, fix.longitude, zoom)
        val worldX = centerWorld.first
        val worldY = centerWorld.second

        // Heading-up only with a Pillion route (StreetCross layout); the marker then sits low so
        // more of the road ahead is visible. Otherwise keep the familiar north-up map.
        val bearing = if (nav != null) heldBearing else null
        val centerX = width * 0.67f
        val centerY = if (bearing != null) height * 0.70f else height * 0.50f

        canvas.save()
        if (bearing != null) canvas.rotate(-bearing, centerX, centerY)
        // When rotated, cover the full circle the viewport can sweep, not just the rectangle.
        val left: Double
        val right: Double
        val up: Double
        val down: Double
        if (bearing != null) {
            val reach = hypot(maxOf(centerX, width - centerX).toDouble(), maxOf(centerY, height - centerY).toDouble())
            left = reach; right = reach; up = reach; down = reach
        } else {
            left = centerX.toDouble(); right = (width - centerX).toDouble()
            up = centerY.toDouble(); down = (height - centerY).toDouble()
        }
        val leftTile = floor((worldX - left) / TILE_SIZE).toInt()
        val rightTile = floor((worldX + right) / TILE_SIZE).toInt()
        val topTile = floor((worldY - up) / TILE_SIZE).toInt()
        val bottomTile = floor((worldY + down) / TILE_SIZE).toInt()

        for (y in topTile..bottomTile) {
            if (y < 0 || y >= world) continue
            for (x in leftTile..rightTile) {
                val normalizedX = ((x % world) + world) % world
                val key = zoom.toString() + "-" + normalizedX + "-" + y
                val tile = getOrRequestTile(key, zoom, normalizedX, y)
                val dx = (x * TILE_SIZE - worldX + centerX).toFloat()
                val dy = (y * TILE_SIZE - worldY + centerY).toFloat()
                val dest = RectF(dx, dy, dx + TILE_SIZE.toFloat(), dy + TILE_SIZE.toFloat())
                if (tile != null) {
                    canvas.drawBitmap(tile, null, dest, tilePaint)
                } else {
                    // While the exact tile loads, upscale a cached lower-zoom tile so the map is
                    // never blank (slightly blurry beats an empty dash at speed).
                    drawParentFallback(canvas, zoom, normalizedX, y, dest)
                }
            }
        }

        // Keep a coarse (zoom-2) tile under the rider loaded too: one request covers 16 detail tiles
        // and gives drawParentFallback something to show whenever detail tiles lag behind.
        if (zoom >= 2) {
            val cz = zoom - 2
            val ctx = ((floor(worldX / TILE_SIZE).toInt() shr 2) % (1 shl cz) + (1 shl cz)) % (1 shl cz)
            val cty = floor(worldY / TILE_SIZE).toInt() shr 2
            if (cty in 0 until (1 shl cz)) getOrRequestTile(cz.toString() + "-" + ctx + "-" + cty, cz, ctx, cty)
        }

        if (nav != null) drawRoute(canvas, nav, zoom, worldX, worldY, centerX, centerY, width, height)
        canvas.restore()

        // Position marker: points up in heading-up mode, otherwise rotated to the GPS bearing.
        canvas.save()
        if (bearing == null && fix.hasBearing()) canvas.rotate(fix.bearing, centerX, centerY)
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

    private fun stableZoom(speed: Float, toNext: Double?): Int {
        val now = SystemClock.elapsedRealtime()
        val nearTurn = toNext != null && toNext < 200.0 && speed < 20f
        val stillNearTurn = toNext != null && toNext < 400.0
        val target = when (currentZoom) {
            16 -> if (stillNearTurn) 16 else if (speed > 25f) 14 else 15
            14 -> if (nearTurn) 16 else if (speed > 18f) 14 else 15
            else -> if (nearTurn) 16 else if (speed > 25f) 14 else 15
        }
        if (target != currentZoom && (zoomChangedAt == 0L || now - zoomChangedAt > 8_000L)) {
            DiagnosticLog.d(TAG, "live map zoom ${currentZoom} -> $target (speed ${"%.1f".format(speed)} m/s)")
            currentZoom = target
            zoomChangedAt = now
        } else if (zoomChangedAt == 0L) {
            zoomChangedAt = now
        }
        return currentZoom
    }

    private fun drawParentFallback(canvas: Canvas, zoom: Int, x: Int, y: Int, dest: RectF) {
        for (up in 1..3) {
            val pz = zoom - up
            if (pz < 0) return
            val px = x shr up
            val py = y shr up
            val key = pz.toString() + "-" + px + "-" + py
            val parent = synchronized(inMemory) { inMemory[key] } ?: run {
                val file = File(tileDir, key + ".png")
                if (file.isFile && file.length() > 128L) {
                    BitmapFactory.decodeFile(file.absolutePath)?.also { rememberTile(key, it) }
                } else null
            } ?: continue
            val sub = parent.width shr up
            if (sub <= 0) return
            val sx = (x - (px shl up)) * sub
            val sy = (y - (py shl up)) * sub
            canvas.drawBitmap(parent, Rect(sx, sy, sx + sub, sy + sub), dest, tilePaint)
            return
        }
    }

    /** Web-Mercator world pixel coordinates at [zoom]. */
    private fun project(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val world = (1 shl zoom).toDouble()
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val x = (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * world
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * world
        return Pair(x * TILE_SIZE, y * TILE_SIZE)
    }

    /** Remaining route (from the rider's snapped segment onwards) plus the destination pin. */
    private fun drawRoute(
        canvas: Canvas,
        nav: RouteNavigationState.Status.Active,
        zoom: Int,
        worldX: Double,
        worldY: Double,
        centerX: Float,
        centerY: Float,
        width: Int,
        height: Int,
    ) {
        val points = nav.route.points
        val from = (nav.guidance?.segmentIndex ?: 0).coerceIn(0, points.size - 1)
        val fix = latestLocation.get()
        val limit = maxOf(width, height) * 3.0
        val path = Path()
        var begun = false
        var lastX = 0f
        var lastY = 0f
        fun add(p: GeoPoint, force: Boolean) {
            val (wx, wy) = project(p.lat, p.lon, zoom)
            val sx = (wx - worldX + centerX).toFloat()
            val sy = (wy - worldY + centerY).toFloat()
            if (!begun) {
                path.moveTo(sx, sy); begun = true; lastX = sx; lastY = sy
            } else if (force || abs(sx - lastX) + abs(sy - lastY) >= 2f) {
                path.lineTo(sx, sy); lastX = sx; lastY = sy
            }
        }
        // Start the line at the rider so it never appears to begin behind them.
        if (fix != null) add(GeoPoint(fix.latitude, fix.longitude), true)
        for (i in from + 1 until points.size) {
            val p = points[i]
            add(p, i == points.size - 1)
            // Stop once the line has run well off-screen; the rest is irrelevant this frame.
            if (abs(lastX - centerX) > limit || abs(lastY - centerY) > limit) break
        }
        canvas.drawPath(path, routeCasing)
        canvas.drawPath(path, routeLine)

        val (dx, dy) = project(nav.route.destination.lat, nav.route.destination.lon, zoom)
        val px = (dx - worldX + centerX).toFloat()
        val py = (dy - worldY + centerY).toFloat()
        if (abs(px - centerX) < limit && abs(py - centerY) < limit) {
            canvas.drawCircle(px, py, 8f, positionOutline)
            canvas.drawCircle(px, py, 7f, destinationPaint)
        }
    }

    private fun getOrRequestTile(key: String, zoom: Int, x: Int, y: Int): Bitmap? {
        synchronized(inMemory) { inMemory[key] }?.let { return it }
        val file = File(tileDir, key + ".png")
        if (file.isFile && file.length() > 128L) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                rememberTile(key, it)
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
                        "Pillion-Tracer7/" + app.pillion.core.AppInfo.VERSION + " (+https://github.com/vpapaion/pillion)",
                    )
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 10_000
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        if (code == 429 || code == 403) failures[key] = 10 // server asks us to back off
                        throw IllegalStateException("OSM HTTP " + code)
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
                    rememberTile(key, tile)
                    failures.remove(key)
                    retryAfter.remove(key)
                    revision.incrementAndGet()
                } catch (error: Exception) {
                    // Mobile data on a moving bike drops out briefly; retry soon instead of leaving
                    // a hole in the map for minutes. 4 s, 8 s, 16 s, 32 s, then once a minute.
                    val n = (failures[key] ?: 0) + 1
                    failures[key] = n
                    val delay = minOf(60_000L, 4_000L shl (n - 1).coerceAtMost(4))
                    retryAfter[key] = SystemClock.elapsedRealtime() + delay
                    DiagnosticLog.w(TAG, "map tile $key failed (#$n, retry in ${delay / 1000}s): ${error.message}")
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

    private fun rememberTile(key: String, tile: Bitmap) {
        synchronized(inMemory) {
            inMemory[key] = tile
            while (inMemory.size > 48) {
                val iterator = inMemory.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
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
