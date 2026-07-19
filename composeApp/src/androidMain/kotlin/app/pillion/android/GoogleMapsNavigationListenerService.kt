package app.pillion.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.pillion.core.GoogleMapsDirection
import app.pillion.core.GoogleMapsDirectionParser

/**
 * Reads only Google Maps' ongoing navigation notification and exposes a compact turn instruction to
 * Pillion's renderers. Notification contents never leave the device.
 */
class GoogleMapsNavigationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshFromActiveNotifications()
        Log.d(TAG, "Google Maps notification listener connected")
    }

    override fun onListenerDisconnected() {
        GoogleMapsNavigationState.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != GOOGLE_MAPS_PACKAGE) return
        val direction = parse(sbn)
        if (direction != null) {
            GoogleMapsNavigationState.update(direction)
            Log.d(TAG, "Google Maps turn: ${direction.maneuver} ${direction.distance.orEmpty()} ${direction.instruction}")
        } else {
            refreshFromActiveNotifications()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == GOOGLE_MAPS_PACKAGE) refreshFromActiveNotifications()
    }

    private fun refreshFromActiveNotifications() {
        val direction = runCatching {
            activeNotifications
                .asSequence()
                .filter { it.packageName == GOOGLE_MAPS_PACKAGE }
                .sortedByDescending { it.postTime }
                .mapNotNull(::parse)
                .firstOrNull()
        }.getOrNull()
        GoogleMapsNavigationState.update(direction)
    }

    private fun parse(sbn: StatusBarNotification): GoogleMapsDirection? {
        if (!sbn.isOngoing) return null
        val notification = sbn.notification
        val extras = notification.extras
        val lines = buildList {
            addText(extras.getCharSequence(Notification.EXTRA_TITLE))
            addText(extras.getCharSequence(Notification.EXTRA_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { addText(it) }
            notification.tickerText?.let { addText(it) }
        }
        return GoogleMapsDirectionParser.parse(lines)
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
