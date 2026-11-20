package rh.mcp.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

class PhoneNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        activeHistory.clear()
        activeNotifications?.forEach(::remember)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = remember(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeHistory.removeIf { it.optString("key") == sbn.key }
    }

    private fun remember(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val record = JSONObject().put("key", sbn.key).put("package", sbn.packageName)
            .put("title", title).put("text", text).put("postedAt", sbn.postTime)
        activeHistory.removeIf { it.optString("key") == sbn.key }
        activeHistory.addFirst(record)
        while (activeHistory.size > MAX_HISTORY) activeHistory.removeLast()
    }

    companion object {
        @Volatile private var instance: PhoneNotificationListener? = null
        private val activeHistory = ConcurrentLinkedDeque<JSONObject>()
        private const val MAX_HISTORY = 100

        fun read(limit: Int): JSONObject {
            val service = instance
            val result = JSONArray()
            if (service != null) {
                service.activeNotifications?.forEach { sbn ->
                    if (result.length() >= limit.coerceIn(1, MAX_HISTORY)) return@forEach
                    val extras = sbn.notification.extras
                    result.put(JSONObject().put("package", sbn.packageName)
                        .put("title", extras.getCharSequence("android.title")?.toString().orEmpty())
                        .put("text", extras.getCharSequence("android.text")?.toString().orEmpty())
                        .put("postedAt", sbn.postTime))
                }
            }
            return JSONObject().put("listenerConnected", service != null)
                .put("active", result)
                .put("capturedHistory", JSONArray().also { history ->
                    activeHistory.take(limit.coerceIn(1, MAX_HISTORY)).forEach(history::put)
                })
                .put("historyStartsWhenListenerConnected", true)
        }
    }
}
