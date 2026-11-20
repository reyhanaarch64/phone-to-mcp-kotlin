package rh.mcp.phone

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.Locale

object DeviceInfo {
    fun get(context: Context, requested: String): JSONObject {
        val section = requested.ifBlank { "all" }.lowercase(Locale.US)
        val supported = setOf("all", "model", "android", "storage", "memory", "display", "cpu", "battery", "network")
        require(section in supported) { "section tidak dikenal: $section" }
        val sections = listOf("model", "android", "storage", "memory", "display", "cpu", "battery", "network")
        val result = JSONObject()
        sections.filter { section == "all" || section == it }.forEach { key ->
            result.put(key, runCatching { section(context, key) }
                .getOrElse { JSONObject().put("available", false).put("reason", it.message ?: "unavailable") })
        }
        return result.put("section", section)
    }

    private fun section(context: Context, section: String): JSONObject = when (section) {
        "model" -> JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
            .put("model", Build.MODEL).put("device", Build.DEVICE).put("product", Build.PRODUCT)
            .put("hardware", Build.HARDWARE)
        "android" -> JSONObject().put("release", Build.VERSION.RELEASE).put("apiLevel", Build.VERSION.SDK_INT)
            .put("securityPatch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else JSONObject.NULL)
            .put("fingerprint", Build.FINGERPRINT)
        "storage" -> {
            val internal = StatFs(context.filesDir.absolutePath)
            val result = JSONObject().put("internalTotalBytes", internal.totalBytes)
                .put("internalAvailableBytes", internal.availableBytes)
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val shared = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                result.put("sharedTotalBytes", shared.totalBytes).put("sharedAvailableBytes", shared.availableBytes)
            } else result.put("sharedAvailable", false)
            result
        }
        "memory" -> {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            JSONObject().put("totalBytes", memory.totalMem).put("availableBytes", memory.availMem)
                .put("usedBytes", memory.totalMem - memory.availMem)
                .put("lowMemory", memory.lowMemory)
        }
        "display" -> {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = manager.defaultDisplay
            val metrics = DisplayMetrics().also(display::getRealMetrics)
            val mode = display.mode
            JSONObject().put("widthPixels", metrics.widthPixels).put("heightPixels", metrics.heightPixels)
                .put("density", metrics.density).put("densityDpi", metrics.densityDpi)
                .put("refreshRateHz", display.refreshRate).put("modeWidth", mode.physicalWidth)
                .put("modeHeight", mode.physicalHeight)
        }
        "cpu" -> JSONObject().put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("architecture", System.getProperty("os.arch") ?: JSONObject.NULL)
        "battery" -> {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            JSONObject().put("percent", if (level >= 0 && scale > 0) level * 100.0 / scale else JSONObject.NULL)
                .put("status", intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: JSONObject.NULL)
                .put("health", intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: JSONObject.NULL)
                .put("temperatureC", (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0)
                .put("voltageMv", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: JSONObject.NULL)
                .put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
        }
        "network" -> {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            val links = network?.let(manager::getLinkProperties)
            val addresses = JSONArray()
            links?.linkAddresses?.forEach { address ->
                if (!address.address.isLoopbackAddress) {
                    addresses.put(JSONObject().put("address", address.address.hostAddress)
                        .put("ipv4", address.address is Inet4Address).put("prefixLength", address.prefixLength))
                }
            }
            JSONObject().put("connected", capabilities != null)
                .put("wifi", capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                .put("ethernet", capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                .put("addresses", addresses)
        }
        else -> JSONObject().put("available", false)
    }
}
