package com.faaz.island

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    companion object {
        var instance: NotifListener? = null
    }

    private val seen = HashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        IslandBus.onListenerReady?.invoke()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sink = IslandBus.onNotification ?: return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music is shown separately

        // skip silent / low-priority notifications
        try {
            val r = Ranking()
            if (currentRanking?.getRanking(sbn.key, r) == true &&
                r.importance < NotificationManager.IMPORTANCE_DEFAULT
            ) return
        } catch (e: Exception) {
        }

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        // don't repeat the same notification when an app just refreshes it
        val sig = "$title|$text"
        if (seen[sbn.key] == sig) return
        if (seen.size > 300) seen.clear()
        seen[sbn.key] = sig

        sink(
            Alert(
                kind = Alert.Kind.NOTIFICATION,
                title = title.ifBlank { appName(sbn.packageName) },
                text = text,
                icon = loadIcon(sbn),
                intent = n.contentIntent,
                pkg = sbn.packageName,
                key = sbn.key,
                autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0
            )
        )
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun loadIcon(sbn: StatusBarNotification): Bitmap? = try {
        val size = (resources.displayMetrics.density * 48).toInt()
        val d = sbn.notification.getLargeIcon()?.loadDrawable(this)
            ?: packageManager.getApplicationIcon(sbn.packageName)
        d.toBmp(size)
    } catch (e: Exception) {
        null
    }
}
