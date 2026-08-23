package app.pillion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.pillion.core.DashResolution
import app.pillion.core.MirrorEngine
import app.pillion.core.ScreenSource
import app.pillion.core.MirrorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Foreground service that hosts a mirroring session. MediaProjection requires a running
 * foreground service of type mediaProjection, so the engine lives here for its lifetime.
 * Single responsibility: own the Android service lifecycle and surface the engine's state.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: MirrorEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dashEnabled = false
    private var dashSwitch: SwitchableScreenSource? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var keyguardManager: KeyguardManager? = null
    private var keyguardListener: Any? = null
    @Volatile private var dashPromotedAtMs = 0L
    @Volatile private var dashSawLockedKeyguard = false
    /** True while the helper is holding the phone's panel off for screen-off mirroring. */
    @Volatile private var panelBlanked = false
    @Volatile private var panelBlankedAtMs = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            // Notification actions: arm/disarm screen-off mirroring on a live session. They must not
            // fall through to startSession() — that would try to reuse a spent projection grant.
            ACTION_BLANK -> { blankPhoneScreen("notification action"); return START_NOT_STICKY }
            ACTION_UNBLANK -> { unblankPhoneScreen("notification action"); return START_NOT_STICKY }
        }
        acquireWakeLock()
        _state.value = MirrorState.Connecting

        val quality = intent?.getIntExtra(EXTRA_QUALITY, 40) ?: 40
        val maxFps = intent?.getIntExtra(EXTRA_MAX_FPS, 15) ?: 15
        val dashResolution = dashResolutionFrom(intent)
        dashEnabled = intent?.getBooleanExtra(EXTRA_DASH_ENABLED, false) ?: false
        startSession(quality, maxFps, dashResolution)
        return START_NOT_STICKY
    }

    /**
     * One Bluetooth/NaviLite session. It always mirrors the phone via MediaProjection; when dash mode
     * is enabled it also spawns the privileged helper and switches the engine's source to the dash
     * (foreground app on a trusted display) whenever the phone is locked — **mirror while unlocked,
     * dash while locked**. The helper only encodes while locked, so it costs no extra battery idle.
     */
    private fun startSession(quality: Int, maxFps: Int, dashResolution: DashResolution) {
        // A screen-capture grant is single-use. If it's missing or stale (e.g. cleared when the app
        // crashed + restarted), starting a mediaProjection foreground service throws SecurityException
        // — which would HARD-CRASH the app. So validate first, and on a stale grant fall back to a
        // non-projection foreground type + a clean "re-grant" message instead of crashing.
        val data = resultData
        if (resultCode == 0 || data == null) {
            runCatching { startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            fail("Screen capture not granted — tap Start again"); return
        }
        // Must be foreground (mediaProjection) before acquiring the projection (Android 10+).
        try {
            startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: SecurityException) {
            Log.e(TAG, "media projection FGS rejected — stale capture grant", e)
            resultCode = 0; resultData = null
            runCatching { startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            fail("Screen capture expired — tap Start again"); return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            fail("screen capture denied"); return
        }
        // The grant is single-use; clear it so a later restart can't reuse a dead token (→ crash).
        resultCode = 0; resultData = null
        // Create the mirror display NOW, while the projection token is fresh.
        val mirror = MediaProjectionScreenSource(this, projection, quality)
        runCatching { mirror.start() }

        val source: ScreenSource = if (dashEnabled) {
            val switch = SwitchableScreenSource(mirror, DashStreamScreenSource())
            dashSwitch = switch
            // Spawn or reuse the helper. Starting it needs Wireless Debugging, but an already-running
            // helper serves over loopback and survives Wi-Fi loss.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    DashHelper.ensureRunning(
                        this@CaptureService,
                        quality,
                        dashResolution,
                        preferExisting = true,
                    )
                }
                    .onFailure {
                        Log.e(TAG, "dash: helper unavailable", it)
                        fail(it.message ?: "Dash helper unavailable")
                    }
            }
            registerScreenReceiver()
            registerKeyguardUnlockListener()
            // Self-heal: if the helper is killed (adbd restart on Wi-Fi/debug loss), respawn it over
            // the loopback channel so the dash recovers instead of freezing.
            DashHelper.startWatchdog(this, quality, dashResolution)
            switch
        } else {
            mirror
        }
        runEngine(source, maxFps)
    }

    /** Mirror while unlocked, dash while locked: promote the foreground app on lock, demote on unlock. */
    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val action = intent.action
                // onReceive runs on the main thread; promote/demote write to the helper socket, which
                // is network I/O (NetworkOnMainThreadException otherwise), so do it off the main thread.
                scope.launch(Dispatchers.IO) {
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (panelBlanked) {
                                // The panel was already dark, so this is a *real* screen-off: the user
                                // pressed power and the system is genuinely going to sleep. Give the
                                // panel its normal power mode back (otherwise the next press shows a
                                // dead screen) and let the dedicated-dash path below take over.
                                Log.i(TAG, "panel: real screen-off while blanked — disarming")
                                panelBlanked = false
                                panelBlankedAtMs = 0L
                                runCatching { dashSwitch?.unblank() }
                            }
                            if (dashPromotedAtMs != 0L) {
                                Log.d(TAG, "dash: screen off while already promoted; keeping dash active")
                                return@launch
                            }
                            val component = foregroundComponent()
                            Log.d(TAG, "dash: screen off; foreground=$component")
                            if (component == null) {
                                // No launchable foreground app to promote (usage access missing, or
                                // the app has no launcher activity). Rather than freeze, keep
                                // mirroring and just blank the panel.
                                Log.w(TAG, "dash: no foreground app; falling back to screen-off mirroring")
                                blankPhoneScreen("no foreground app to promote")
                            } else {
                                dashSwitch?.promote(component)
                                dashPromotedAtMs = System.currentTimeMillis()
                                dashSawLockedKeyguard = isKeyguardLockedNow()
                                scheduleLockStateSample(dashPromotedAtMs)
                                scheduleDashFallback(dashPromotedAtMs)
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            // Blanking wakes display 0 first (it may have been dozing), which echoes
                            // back as SCREEN_ON. Ignore that self-inflicted wake; a later one means
                            // the blank didn't take, so stop pretending the phone is off.
                            if (panelBlanked) {
                                val age = System.currentTimeMillis() - panelBlankedAtMs
                                if (age >= BLANK_WAKE_GRACE_MS) {
                                    Log.w(TAG, "panel: screen came back on (${age}ms) — disarming screen-off")
                                    unblankPhoneScreen("screen on")
                                } else {
                                    Log.d(TAG, "panel: ignoring blank wake (${age}ms)")
                                }
                            }
                            val promotedAt = dashPromotedAtMs
                            if (promotedAt != 0L) {
                                val ageMs = System.currentTimeMillis() - promotedAt
                                if (ageMs >= RETURN_TO_PHONE_GRACE_MS) {
                                    returnToPhone("screen on")
                                } else {
                                    // The helper briefly wakes the device during PROMOTE so the virtual display
                                    // can render. Ignore that synthetic wake; panel-off retries will blank display 0.
                                    Log.d(TAG, "dash: ignoring promotion wake (${ageMs}ms)")
                                    schedulePromotionWakeSettleCheck(promotedAt, ageMs)
                                }
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            unblankPhoneScreen("user present")
                            returnToPhone("user present", force = true)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    /**
     * Some devices do not send a useful SCREEN_ON transition after we blank display 0 directly.
     * Keyguard unlock is the higher-signal event, but the listener is API 33+ and permission-gated,
     * so it is registered defensively and the normal broadcasts remain as fallbacks.
     */
    private fun registerKeyguardUnlockListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(PERMISSION_SUBSCRIBE_KEYGUARD) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "dash: keyguard unlock listener unavailable; permission not granted")
            return
        }
        runCatching {
            val manager = getSystemService(KeyguardManager::class.java)
                ?: error("KeyguardManager unavailable")
            val listenerClass = Class.forName("android.app.KeyguardManager\$KeyguardLockedStateListener")
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onKeyguardLockedStateChanged") {
                    val locked = args?.firstOrNull() as? Boolean ?: return@newProxyInstance null
                    scope.launch(Dispatchers.IO) {
                        if (locked) {
                            if (dashPromotedAtMs != 0L) dashSawLockedKeyguard = true
                            Log.d(TAG, "dash: keyguard locked")
                        } else {
                            onKeyguardUnlocked()
                        }
                    }
                }
                null
            }
            val executor = Executor { runnable -> scope.launch(Dispatchers.IO) { runnable.run() } }
            manager.javaClass
                .getMethod("addKeyguardLockedStateListener", Executor::class.java, listenerClass)
                .invoke(manager, executor, listener)
            keyguardManager = manager
            keyguardListener = listener
            Log.d(TAG, "dash: keyguard unlock listener registered")
        }.onFailure {
            Log.d(TAG, "dash: keyguard unlock listener unavailable: ${it.javaClass.simpleName}")
            keyguardManager = null
            keyguardListener = null
        }
    }

    private fun unregisterKeyguardUnlockListener() {
        val manager = keyguardManager ?: return
        val listener = keyguardListener ?: return
        runCatching {
            val listenerClass = Class.forName("android.app.KeyguardManager\$KeyguardLockedStateListener")
            manager.javaClass
                .getMethod("removeKeyguardLockedStateListener", listenerClass)
                .invoke(manager, listener)
        }
        keyguardManager = null
        keyguardListener = null
    }

    private fun onKeyguardUnlocked() {
        val promotedAt = dashPromotedAtMs
        if (promotedAt == 0L) return
        val ageMs = System.currentTimeMillis() - promotedAt
        if (dashSawLockedKeyguard || ageMs >= RETURN_TO_PHONE_GRACE_MS) {
            returnToPhone("keyguard unlocked")
        } else {
            Log.d(TAG, "dash: ignoring keyguard-unlocked callback before lock settled (${ageMs}ms)")
        }
    }

    private fun scheduleLockStateSample(promotedAt: Long) {
        scope.launch(Dispatchers.IO) {
            Thread.sleep(LOCK_STATE_SAMPLE_DELAY_MS)
            if (dashPromotedAtMs == promotedAt && isKeyguardLockedNow()) {
                dashSawLockedKeyguard = true
                Log.d(TAG, "dash: keyguard locked after promotion")
            }
        }
    }

    private fun schedulePromotionWakeSettleCheck(promotedAt: Long, ageMs: Long) {
        val delayMs = (RETURN_TO_PHONE_GRACE_MS - ageMs).coerceAtLeast(0L) + 150L
        scope.launch(Dispatchers.IO) {
            Thread.sleep(delayMs)
            if (dashPromotedAtMs != promotedAt) return@launch
            if (dashSawLockedKeyguard && isKeyguardUnlockedNow()) {
                returnToPhone("unlock after promotion wake")
            } else {
                Log.d(TAG, "dash: promotion wake settled; keeping dash active")
            }
        }
    }

    /**
     * **Screen-off mirroring.** Ask the helper to blank the phone's panel while keeping display 0
     * awake, so MediaProjection keeps producing frames and the dash keeps updating with the phone
     * dark. A plain screen-off cannot do this: the system stops composing display 0, the mirror's
     * ImageReader goes silent, and the engine keeps re-sending the last captured frame — the dash
     * looks connected but frozen, which is the bug this fixes.
     */
    private fun blankPhoneScreen(reason: String) {
        val switch = dashSwitch ?: run {
            Log.w(TAG, "panel: screen-off requested ($reason) but the dash helper isn't running")
            updateNotification("Screen-off needs dash setup")
            return
        }
        panelBlanked = true
        panelBlankedAtMs = System.currentTimeMillis()
        Log.i(TAG, "panel: arming screen-off mirroring ($reason)")
        scope.launch(Dispatchers.IO) { runCatching { switch.blank() } }
        updateNotification("Phone screen off — still mirroring")
    }

    /** Give the phone's panel back. Safe to call when it was never blanked. */
    private fun unblankPhoneScreen(reason: String) {
        if (!panelBlanked) return
        panelBlanked = false
        panelBlankedAtMs = 0L
        Log.i(TAG, "panel: restoring phone screen ($reason)")
        val switch = dashSwitch ?: return
        scope.launch(Dispatchers.IO) { runCatching { switch.unblank() } }
        updateNotification("Streaming")
    }

    /**
     * The dedicated dash can fail silently: `am display move-stack` reports success while a
     * singleTask/singleInstance app (Google Maps mid-navigation) stays on display 0, or the trusted
     * display was never created on this build. Either way no frames arrive and the dash freezes. So
     * check whether real frames showed up, and if not, put the app back on the phone and blank the
     * panel instead — screen-off mirroring works regardless of the app's launch mode.
     */
    private fun scheduleDashFallback(promotedAt: Long) {
        scope.launch(Dispatchers.IO) {
            Thread.sleep(DASH_FALLBACK_MS)
            if (dashPromotedAtMs != promotedAt) return@launch
            val switch = dashSwitch ?: return@launch
            if (switch.isDashFresh()) return@launch
            Log.w(
                TAG,
                "dash: no dash frames ${DASH_FALLBACK_MS}ms after promote — " +
                    "falling back to screen-off mirroring",
            )
            switch.demote()
            dashPromotedAtMs = 0L
            dashSawLockedKeyguard = false
            Thread.sleep(DEMOTE_SETTLE_MS) // let the task land back on display 0 before blanking
            blankPhoneScreen("dash produced no frames")
        }
    }

    private fun returnToPhone(reason: String, force: Boolean = false) {
        if (!force && dashPromotedAtMs == 0L) return
        Log.d(TAG, "dash: $reason; returning to phone")
        dashPromotedAtMs = 0L
        dashSawLockedKeyguard = false
        dashSwitch?.demote()
    }

    private fun isKeyguardLockedNow(): Boolean {
        val manager = getSystemService(KeyguardManager::class.java) ?: return false
        return manager.isDeviceLocked || manager.isKeyguardLocked
    }

    private fun isKeyguardUnlockedNow(): Boolean {
        val manager = getSystemService(KeyguardManager::class.java) ?: return false
        return !manager.isDeviceLocked && !manager.isKeyguardLocked
    }

    /** The foreground app's launcher component to promote at lock (excludes Pillion). */
    private fun foregroundComponent(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60_000, now) }
            .onFailure { Log.w(TAG, "dash: usage query failed", it) }
            .getOrNull() ?: return null
        val event = UsageEvents.Event()
        var component: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != packageName) {
                val candidate = packageManager.getLaunchIntentForPackage(event.packageName)
                    ?.component
                    ?.flattenToString()
                if (candidate == null) {
                    Log.d(TAG, "dash: ignoring foreground package without launcher: ${event.packageName}")
                } else {
                    component = candidate
                    Log.d(TAG, "dash: foreground candidate=$component")
                }
            }
        }
        if (component == null) Log.w(TAG, "dash: UsageStats returned no launchable foreground app")
        return component
    }

    private fun runEngine(screen: ScreenSource, maxFps: Int) {
        val mirror = MirrorEngine(RfcommByteChannel(), screen, maxFps)
        engine = mirror
        scope.launch {
            mirror.state.collect { state ->
                _state.value = state
                when (state) {
                    is MirrorState.Streaming -> updateNotification("Streaming — ${state.kbPerFrame} KB/frame")
                    is MirrorState.Error -> { updateNotification(state.message); stopSelf() }
                    else -> {}
                }
            }
        }
        mirror.start(scope)
    }

    private fun dashResolutionFrom(intent: Intent?): DashResolution {
        val width = intent?.getIntExtra(EXTRA_DASH_WIDTH, DashResolution.DEFAULT.width)
            ?: DashResolution.DEFAULT.width
        val height = intent?.getIntExtra(EXTRA_DASH_HEIGHT, DashResolution.DEFAULT.height)
            ?: DashResolution.DEFAULT.height
        return DashResolution.values().firstOrNull { it.width == width && it.height == height }
            ?: DashResolution.DEFAULT
    }

    private fun killHelper() {
        runCatching { PillionAdb.getInstance(this).runShell("pkill -f app.pillion.server.DashServer") }
    }

    private fun fail(message: String) {
        _state.value = MirrorState.Error(message)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        DashHelper.stopWatchdog() // stop first, so it doesn't respawn the helper we're tearing down
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        unregisterKeyguardUnlockListener()
        // The helper is detached (orphaned to init), so it won't die on its own. Tell it to QUIT over
        // loopback — works with no network — to release the trusted display; pkill over ADB is a
        // best-effort backup (only reachable while wifi is up). Detached thread: must outlive cancel().
        val switch = dashSwitch
        val wasBlanked = panelBlanked
        panelBlanked = false
        if (dashEnabled) Thread {
            // Restore the panel BEFORE tearing the helper down, so a session that ends while the
            // phone is dark can never leave it dark. (The helper also unblanks on QUIT and when its
            // last client drops — belt and braces, because a black phone is unrecoverable-looking.)
            if (wasBlanked) runCatching { switch?.unblank() }
            runCatching { switch?.quit() }
            killHelper()
        }.start()
        scope.cancel()
        releaseWakeLock()
        _state.value = MirrorState.Idle
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keep the CPU running so the Bluetooth stream survives screen dimming / Doze during a ride. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillion:mirror").apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Foreground with the right service type: mediaProjection for mirror, connectedDevice for dash. */
    private fun startForegroundTyped(type: Int) {
        val notification = buildNotification("Connecting to dash…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION") // Notification.Builder(Context) and addAction(int, ...) for minSdk 24
    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pillion", NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setContentTitle("Pillion — mirroring to dash")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
        // The whole point of the feature lives here: you start the ride with the screen on (to open
        // Waze), then turn the phone dark from the shade without killing the stream.
        if (panelBlanked) {
            builder.addAction(
                android.R.drawable.ic_menu_view, "Screen on", serviceAction(ACTION_UNBLANK),
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_lock_idle_lock, "Screen off", serviceAction(ACTION_BLANK),
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACTION_STOP),
        )
        return builder.build()
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(this, CaptureService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "pillion"
        private const val TAG = "Pillion"
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000 // 3h safety cap
        private const val RETURN_TO_PHONE_GRACE_MS = 3_000L
        /** Ignore the SCREEN_ON echo caused by waking display 0 just before blanking it. */
        private const val BLANK_WAKE_GRACE_MS = 3_000L
        /** How long the dedicated dash gets to deliver a frame before we fall back to blanking. */
        private const val DASH_FALLBACK_MS = 3_000L
        private const val DEMOTE_SETTLE_MS = 400L
        private const val LOCK_STATE_SAMPLE_DELAY_MS = 250L
        private const val PERMISSION_SUBSCRIBE_KEYGUARD =
            "android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE"
        const val ACTION_STOP = "app.pillion.action.STOP"
        /** Blank the phone's panel while the mirror keeps running (notification action). */
        const val ACTION_BLANK = "app.pillion.action.BLANK"
        const val ACTION_UNBLANK = "app.pillion.action.UNBLANK"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_FPS = "maxFps"
        /** When true, the session switches to the dedicated dash display whenever the phone is locked. */
        const val EXTRA_DASH_ENABLED = "dashEnabled"
        const val EXTRA_DASH_WIDTH = "dashWidth"
        const val EXTRA_DASH_HEIGHT = "dashHeight"

        // Handed over by the Activity after the user grants screen capture.
        @Volatile var resultCode: Int = 0
        @Volatile var resultData: Intent? = null

        private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
        val state: StateFlow<MirrorState> = _state.asStateFlow()
    }
}
