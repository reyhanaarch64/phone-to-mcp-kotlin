package rh.mcp.phone

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

object CrashDiagnostics {
    private const val PREFS = "crash_diagnostics"
    private const val SESSION = "session"
    private const val CLEAN = "clean_stop"

    fun install(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(CLEAN, true).not()) {
            RuntimeLog.writeCrashReport(
                app,
                "Previous process stopped without a normal-stop marker.\n" +
                    "This does not distinguish a crash from force-stop or OS process termination.\n" +
                    "previous_session=${prefs.getString(SESSION, "unknown")}\n" +
                    "android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}\n",
            )
        }
        prefs.edit().putString(SESSION, UUID.randomUUID().toString()).putBoolean(CLEAN, false).apply()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = StringWriter().also { writer -> throwable.printStackTrace(PrintWriter(writer)) }.toString()
            RuntimeLog.writeCrashReport(
                app,
                "Uncaught exception in ${thread.name}\n" +
                    "package=${app.packageName}\nandroid=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}\n" +
                    "pid=${Process.myPid()}\n\n$stack",
            )
            previous?.uncaughtException(thread, throwable)
        }
        RuntimeLog.event(app, "process started")
    }

    fun markNormalStop(context: Context, reason: String) {
        RuntimeLog.event(context, "normal stop: $reason")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLEAN, true).apply()
    }

    fun event(context: Context, message: String, error: Throwable? = null) =
        RuntimeLog.event(context, message, error)
}
