package app.pillion.android

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.pillion.core.GoogleMapsDirection
import app.pillion.core.GoogleMapsDirectionParser

/**
 * Reads Google Maps notifications and exposes a compact turn instruction to Pillion's renderers.
 * Diagnostic text is kept locally so real-bike testing can reveal notification formats that the
 * parser does not yet understand.
 */
class GoogleMapsNavigationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshFromActiveNotifications()
        Log.d(TAG, "Google Maps notification listener connected")
    }

    override fun onListenerDisconnected() {
        GoogleMapsNavigationState.clear()
        GoogleMapsNavigationState.updateDebug(
            GoogleMapsNotificationDebug(note = "Notification listener disconnected"),
        )
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != GOOGLE_MAPS_PACKAGE) return
        inspectNotification(sbn, updateDirection = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == GOOGLE_MAPS_PACKAGE) refreshFromActiveNotifications()
    }

    private fun refreshFromActiveNotifications() {
        val maps = runCatching {
            activeNotifications
                .asSequence()
                .filter { it.packageName == GOOGLE_MAPS_PACKAGE }
                .sortedWith(
                    compareByDescending<StatusBarNotification> { it.isOngoing }
                        .thenByDescending { it.postTime },
                )
                .toList()
        }.getOrElse {
            GoogleMapsNavigationState.updateDebug(
                GoogleMapsNotificationDebug(note = "Could not read active notifications: ${it.javaClass.simpleName}"),
            )
            emptyList()
        }

        if (maps.isEmpty()) {
            GoogleMapsNavigationState.clear()
            GoogleMapsNavigationState.updateDebug(
                GoogleMapsNotificationDebug(note = "No Google Maps notification seen"),
            )
            return
        }

        var parsed: GoogleMapsDirection? = null
        var newestDebug: GoogleMapsNotificationDebug? = null
        for (sbn in maps) {
            val lines = extractLines(sbn.notification)
            if (newestDebug == null) newestDebug = debugFor(sbn, lines)
            if (parsed == null) parsed = GoogleMapsDirectionParser.parse(lines)
        }
        GoogleMapsNavigationState.update(parsed)
        GoogleMapsNavigationState.updateDebug(
            newestDebug ?: GoogleMapsNotificationDebug(note = "Google Maps notification had no readable text"),
        )
    }

    private fun inspectNotification(sbn: StatusBarNotification, updateDirection: Boolean) {
        val lines = extractLines(sbn.notification)
        val direction = GoogleMapsDirectionParser.parse(lines)
        GoogleMapsNavigationState.updateDebug(debugFor(sbn, lines))
        if (updateDirection) {
            if (direction != null) {
                GoogleMapsNavigationState.update(direction)
                Log.d(TAG, "Google Maps turn: ${direction.maneuver} ${direction.distance.orEmpty()} ${direction.instruction}")
            } else {
                refreshFromActiveNotifications()
            }
        }
        Log.d(
            TAG,
            "Google Maps notification: ongoing=${sbn.isOngoing} lines=${lines.joinToString(" | ")}",
        )
    }

    private fun debugFor(
        sbn: StatusBarNotification,
        lines: List<String>,
    ): GoogleMapsNotificationDebug = GoogleMapsNotificationDebug(
        seen = true,
        ongoing = sbn.isOngoing,
        postTime = sbn.postTime,
        lines = lines.take(8),
        note = if (lines.isEmpty()) "Google Maps notification seen, but no readable text fields" else
            "Google Maps notification detected (${if (sbn.isOngoing) "ongoing" else "not ongoing"})",
    )

    private fun extractLines(notification: Notification): List<String> = buildList {
        val extras = notification.extras
        addText(extras.getCharSequence(Notification.EXTRA_TITLE))
        addText(extras.getCharSequence(Notification.EXTRA_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { addText(it) }
        notification.tickerText?.let { addText(it) }
        notification.actions?.forEach { action -> addText(action.title) }
        addBundleTexts(extras)
    }
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }
        .distinct()

    private fun MutableList<String>.addBundleTexts(bundle: Bundle) {
        bundle.keySet().sorted().forEach { key ->
            when (val value = bundle.get(key)) {
                is CharSequence -> addText(value)
                is Array<*> -> value.forEach { item -> if (item is CharSequence) addText(item) }
                is Iterable<*> -> value.forEach { item -> if (item is CharSequence) addText(item) }
            }
        }
    }

    private fun MutableList<String>.addText(value: CharSequence?) {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) add(text)
    }

    private companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val TAG = "Pillion"
    }
}
