package app.pillion.android.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.pillion.android.AndroidSettingsStore
import app.pillion.android.DiagnosticLog
import app.pillion.core.ThemeMode
import app.pillion.nav.GeoPoint
import app.pillion.nav.NavFormat
import app.pillion.ui.PillionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks a destination for Pillion's own turn-by-turn (rendered on the dash even with the phone
 * locked). Reachable from the Home screen, from Google Maps' "Share" sheet, and from geo: links.
 */
class RoutePlannerActivity : ComponentActivity() {

    private val sharedIntent = mutableStateOf<Intent?>(null)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.attach(applicationContext)
        RouteNavigationState.restore(applicationContext)
        // Pillion navigation is drawn by the screen-off/live-map source, so make sure the next
        // Bluetooth session uses it (and asks for location).
        AndroidSettingsStore(applicationContext).setScreenOffDirectionsEnabled(true)
        if (!hasLocation()) {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
        sharedIntent.value = intent?.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_VIEW }
        setContent {
            PillionTheme(ThemeMode.SYSTEM) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlannerScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedIntent.value = intent
    }

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) != false

    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(direct) }.onFailure { runCatching { startActivity(fallback) } }
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun currentPosition(): GeoPoint? {
        RouteNavigationState.lastKnownPosition()?.let { return it }
        if (!hasLocation()) return null
        val manager = getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    @Composable
    private fun PlannerScreen() {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf(RouteNavigationState.status) }
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Place>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var batteryOk by remember { mutableStateOf(batteryUnrestricted()) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // Re-check after returning from the system dialog.
            while (true) {
                kotlinx.coroutines.delay(1_500)
                batteryOk = batteryUnrestricted()
            }
        }

        DisposableEffect(Unit) {
            val remove = RouteNavigationState.addListener { status = RouteNavigationState.status }
            onDispose { remove() }
        }

        fun start(place: Place) {
            results = emptyList()
            error = null
            RouteNavigationState.navigateTo(applicationContext, place, currentPosition())
        }

        fun search(text: String) {
            if (text.isBlank()) return
            busy = true
            error = null
            scope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching { RoutingClient.search(text.trim(), currentPosition()) }
                }
                busy = false
                found.onSuccess {
                    results = it
                    if (it.isEmpty()) error = "Nothing found for “$text”."
                }.onFailure { error = "Search failed: ${it.message}" }
            }
        }

        val incoming = sharedIntent.value
        LaunchedEffect(incoming) {
            val i = incoming ?: return@LaunchedEffect
            sharedIntent.value = null
            val text = when (i.action) {
                Intent.ACTION_SEND -> listOfNotNull(
                    i.getStringExtra(Intent.EXTRA_SUBJECT),
                    i.getStringExtra(Intent.EXTRA_TEXT),
                ).joinToString("\n")
                else -> i.dataString.orEmpty()
            }
            if (text.isBlank()) return@LaunchedEffect
            busy = true
            val (place, label) = withContext(Dispatchers.IO) {
                runCatching { RoutingClient.resolveShared(text) }.getOrElse { null to "" }
            }
            busy = false
            when {
                place != null -> start(place)
                label.isNotBlank() -> {
                    // geo:0,0?q=address → search the query part; shared text → search its name.
                    val q = Regex("""[?&]q=([^&]+)""").find(label)?.groupValues?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: label
                    query = q
                    search(q)
                }
                else -> error = "Couldn't read a location from the shared link."
            }
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text("Navigate on the dash", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Pillion draws the route and turns itself, so the Tracer keeps updating with the phone locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            StatusCard(status, onStop = { RouteNavigationState.stop(applicationContext) })
            if (!batteryOk) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Battery saver may freeze the dash", fontWeight = FontWeight.Bold)
                        Text(
                            "With the screen off, Android can throttle GPS for this app. Allow it to run " +
                                "unrestricted (on Xiaomi also: App info → Battery saver → No restrictions).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { requestBatteryExemption() }) { Text("Don't restrict battery") }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Destination") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search(query) }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { search(query) }, enabled = !busy) { Text("Search") }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(results) { place ->
                    Column(
                        Modifier.fillMaxWidth().clickable { start(place) }.padding(vertical = 10.dp),
                    ) {
                        Text(place.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (place.detail.isNotBlank()) {
                            Text(
                                place.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Text(
                "Tip: in Google Maps, open a place → Share → Pillion. " +
                    "Routing: OpenStreetMap / Valhalla (motorcycle). Search: Nominatim.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun StatusCard(status: RouteNavigationState.Status, onStop: () -> Unit) {
        val (title, detail) = when (status) {
            RouteNavigationState.Status.Idle -> "No active route" to
                "Pick a destination below. Start the Bluetooth session in Pillion as usual."
            is RouteNavigationState.Status.Calculating -> "Calculating route…" to
                (status.destinationName + "\nThe route is computed from your GPS position once the bike session runs.")
            is RouteNavigationState.Status.Failed -> "Route not available" to
                (status.destinationName + " — " + status.message)
            is RouteNavigationState.Status.Arrived -> "Arrived" to status.destinationName
            is RouteNavigationState.Status.Active -> {
                val g = status.guidance
                ("To " + status.route.destinationName) to (
                    NavFormat.distance(g?.remainingMeters ?: status.route.totalMeters) + " · " +
                        NavFormat.duration(g?.remainingSeconds ?: status.route.totalSeconds) +
                        (g?.let { gg -> gg.next?.let { "\nNext: " + (it.street ?: it.instruction) + " in " + NavFormat.distance(gg.metersToNext) } } ?: "")
                    )
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                val navigating = status is RouteNavigationState.Status.Active ||
                    status is RouteNavigationState.Status.Calculating ||
                    status is RouteNavigationState.Status.Failed
                if (navigating) {
                    OutlinedButton(onClick = onStop) { Text("Stop navigation") }
                }
            }
        }
    }
}
