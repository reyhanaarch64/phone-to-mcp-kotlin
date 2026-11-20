package rh.mcp.phone

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

object ToolSettings {
    private const val PREFS = "phone_mcp_settings"
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, tool: ToolDef): Boolean =
        prefs(context).getBoolean("tool.${tool.name}", tool.defaultEnabled)

    fun setEnabled(context: Context, tool: ToolDef, enabled: Boolean) {
        prefs(context).edit().putBoolean("tool.${tool.name}", enabled).apply()
    }

    fun authEnabled(context: Context) = prefs(context).getBoolean("auth_enabled", true)
    fun setAuthEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auth_enabled", enabled).apply()
    }

    fun token(context: Context): String {
        val settings = prefs(context)
        val current = settings.getString("mcp_token", null)
        if (!current.isNullOrBlank() && current.length >= 32) return current
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).also {
            settings.edit().putString("mcp_token", it).apply()
        }
    }

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).also {
            prefs(context).edit().putString("mcp_token", it).apply()
        }
    }

    fun serverEnabled(context: Context) = prefs(context).getBoolean("server_enabled", false)
    fun setServerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("server_enabled", enabled).apply()
    }

    fun tunnelEnabled(context: Context) = prefs(context).getBoolean("tunnel_enabled", false)
    fun setTunnelEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("tunnel_enabled", enabled).apply()
    }

    fun backend(context: Context) = prefs(context).getString("terminal_backend", "android") ?: "android"
    fun setBackend(context: Context, backend: String) {
        require(backend == "android" || backend == "termux")
        prefs(context).edit().putString("terminal_backend", backend).apply()
    }
}
