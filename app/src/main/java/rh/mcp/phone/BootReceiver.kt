package rh.mcp.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ToolSettings.serverEnabled(context)) return
        runCatching {
            val service = Intent(context, PhoneMcpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        }.onFailure { RuntimeLog.event(context, "failed to start MCP after boot", it) }
    }
}

class ServiceRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ToolSettings.serverEnabled(context)) return
        runCatching {
            val service = Intent(context, PhoneMcpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        }.onFailure { RuntimeLog.event(context, "failed to recover MCP service", it) }
    }

    companion object {
        private const val REQUEST_CODE = 9077

        fun schedule(context: Context, delayMs: Long) {
            if (delayMs < 0) {
                cancel(context)
                return
            }
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = Intent(context, ServiceRecoveryReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending)
            else alarm.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = Intent(context, ServiceRecoveryReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags).also {
                alarm.cancel(it)
                it.cancel()
            }
        }
    }
}
