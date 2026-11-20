package rh.mcp.phone

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyUrlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_COPY_URL) return
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("MCP URL", url))
        Toast.makeText(context, "URL MCP disalin", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_COPY_URL = "rh.mcp.phone.action.COPY_URL"
        const val EXTRA_URL = "rh.mcp.phone.extra.URL"
    }
}
