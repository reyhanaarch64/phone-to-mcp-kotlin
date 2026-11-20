package rh.mcp.phone

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import org.json.JSONObject

object DownloadTools {
    fun enqueue(context: Context, args: JSONObject): JSONObject {
        val uri = Uri.parse(args.getString("url"))
        require(uri.scheme == "https" || uri.scheme == "http") { "URL harus menggunakan HTTP atau HTTPS" }
        require(!uri.host.isNullOrBlank()) { "URL tidak valid" }
        val filename = URLUtil.guessFileName(uri.toString(), null, null)
        val request = DownloadManager.Request(uri)
            .setTitle(args.optString("title").ifBlank { filename })
            .setDescription("Phone To MCP download")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        return JSONObject().put("status", "queued").put("downloadId", id)
            .put("filename", filename).put("destination", "${Environment.DIRECTORY_DOWNLOADS}/$filename")
            .put("completed", false)
    }
}
