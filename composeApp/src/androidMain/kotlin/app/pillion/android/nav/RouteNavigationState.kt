package app.pillion.android.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import app.pillion.android.DiagnosticLog
import app.pillion.nav.GeoPoint
import app.pillion.nav.PlannedRoute
import app.pillion.nav.RouteGuidance
import app.pillion.nav.RouteTracker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pillion's own turn-by-turn session, shared by the route planner screen and the Bluetooth dash
 * renderer. The destination is persisted so a service restart (or a reboot of the phone mid-ride)
 * resumes guidance; the route itself is recomputed from the current position when needed.
 *
 * Fed by [app.pillion.android.ScreenOffLiveMap]'s GPS listener, so guidance runs inside the
 * foreground service and keeps working with the phone locked.
 */
object RouteNavigationState {
    private const val PREFS = "pillion.navigation"
    private const val TAG = "Pillion"
    private const val OFF_ROUTE_FIXES = 3
    private const val REROUTE_MIN_INTERVAL_MS = 20_000L

    sealed interface Status {
        data object Idle : Status
        data class Calculating(val destinationName: String) : Status
        data class Active(val route: PlannedRoute, val guidance: RouteGuidance?) : Status
        data class Arrived(val destinationName: String) : Status
        data class Failed(val destinationName: String, val message: String) : Status
    }

    @Volatile var status: Status = Status.Idle
        private set

    @Volatile private var tracker: RouteTracker? = null
    @Volatile private var destination: GeoPoint? = null
    @Volatile private var destinationName: String = ""
    @Volatile private var lastFix: Location? = null
    @Volatile private var offRouteCount = 0
    @Volatile private var lastRouteRequestAt = 0L
    private val requesting = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pillion-routing").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var restored = false
    @Volatile private var appContext: Context? = null

    /** True while Pillion is guiding (or about to) — the dash then shows Pillion's own map. */
    fun hasDestination(): Boolean = destination != null

    fun lastKnownPosition(): GeoPoint? = lastFix?.let { GeoPoint(it.latitude, it.longitude) }

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Restores a persisted destination (once per process). */
    fun restore(context: Context) {
        appContext = context.applicationContext
        if (restored) return
        restored = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat")) return
        destination = GeoPoint(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble())
        destinationName = p.getString("name", "").orEmpty()
        setStatus(Status.Calculating(destinationName))
        lastFix?.let { requestRoute(it, reason = "restore") }
    }

    fun navigateTo(context: Context, place: Place, from: GeoPoint?) {
        appContext = context.applicationContext
        destination = place.point
        destinationName = place.name
        tracker = null
        offRouteCount = 0
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("lat", place.point.lat.toFloat())
            .putFloat("lon", place.point.lon.toFloat())
            .putString("name", place.name)
            .apply()
        restored = true
        setStatus(Status.Calculating(place.name))
        val origin = lastFix ?: from?.let { Location("planner").apply { latitude = it.lat; longitude = it.lon } }
        if (origin != null) requestRoute(origin, reason = "new destination")
    }

    fun stop(context: Context) {
        destination = null
        tracker = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        setStatus(Status.Idle)
    }

    /** Called for every GPS fix while the dash session is running. */
    fun onLocation(location: Location) {
        lastFix = location
        val dest = destination ?: return
        val t = tracker
        if (t == null) {
            if (status !is Status.Failed || SystemClock.elapsedRealtime() - lastRouteRequestAt > REROUTE_MIN_INTERVAL_MS) {
                requestRoute(location, reason = "first fix")
            }
            return
        }
        val guidance = t.update(GeoPoint(location.latitude, location.longitude))
        if (guidance.arrived) {
            DiagnosticLog.d(TAG, "route: arrived at $destinationName")
            destination = null
            tracker = null
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
            setStatus(Status.Arrived(destinationName))
            return
        }
        val threshold = maxOf(RouteTracker.OFF_ROUTE_METERS, if (location.hasAccuracy()) location.accuracy * 1.5 else 0.0)
        offRouteCount = if (guidance.offRouteMeters > threshold) offRouteCount + 1 else 0
        setStatus(Status.Active(t.route, guidance))
        if (offRouteCount >= OFF_ROUTE_FIXES &&
            SystemClock.elapsedRealtime() - lastRouteRequestAt > REROUTE_MIN_INTERVAL_MS
        ) {
            DiagnosticLog.d(TAG, "route: off route by ${guidance.offRouteMeters.toInt()} m — re-routing to $dest")
            requestRoute(location, reason = "off route")
        }
    }

    private fun requestRoute(from: Location, reason: String) {
        val dest = destination ?: return
        if (!requesting.compareAndSet(false, true)) return
        lastRouteRequestAt = SystemClock.elapsedRealtime()
        val name = destinationName
        val heading = if (from.hasBearing() && from.hasSpeed() && from.speed > 3f) from.bearing else null
        worker.execute {
            try {
                val route = RoutingClient.route(GeoPoint(from.latitude, from.longitude), dest, name, heading)
                if (destination == dest) {
                    val newTracker = RouteTracker(route)
                    tracker = newTracker
                    offRouteCount = 0
                    val guidance = lastFix?.let { newTracker.update(GeoPoint(it.latitude, it.longitude)) }
                    DiagnosticLog.d(TAG, "route ($reason): ${route.points.size} pts, ${route.totalMeters.toInt()} m")
                    setStatus(Status.Active(route, guidance))
                }
            } catch (e: Exception) {
                DiagnosticLog.w(TAG, "route ($reason) failed", e)
                // Keep guiding on the old route if we had one; otherwise surface the error.
                if (destination == dest && tracker == null) {
                    setStatus(Status.Failed(name, e.message ?: "Routing failed"))
                }
            } finally {
                requesting.set(false)
            }
        }
    }

    private fun setStatus(value: Status) {
        status = value
        listeners.forEach { runCatching(it) }
    }
}
