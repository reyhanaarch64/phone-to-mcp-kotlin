package rh.mcp.phone

import android.app.Application

class PhoneMcpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}
