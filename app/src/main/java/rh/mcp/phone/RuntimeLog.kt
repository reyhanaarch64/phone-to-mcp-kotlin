package rh.mcp.phone

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLog {
    private const val TAG = "PhoneToMcp"
    private const val MAX_FILE_BYTES = 1024 * 1024L
    private const val MAX_FILES = 5
    private val lock = Any()

    fun directory(): File = File(
        Environment.getExternalStorageDirectory(),
        "Android/media/rh.mcp.phone/logs",
    )

    fun event(context: Context, message: String, throwable: Throwable? = null) {
        Log.i(TAG, message, throwable)
        synchronized(lock) {
            runCatching {
                val dir = directory()
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create runtime log directory")
                val file = File(dir, "runtime.log")
                if (file.exists() && file.length() >= MAX_FILE_BYTES) rotate(dir)
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val line = buildString {
                    append(stamp).append(" pid=").append(android.os.Process.myPid())
                    append(' ').append(message.replace(Regex("[\\r\\n]+"), " "))
                    if (throwable != null) append(" error=").append(throwable.javaClass.simpleName)
                        .append(": ").append(throwable.message.orEmpty().replace(Regex("[\\r\\n]+"), " "))
                    append('\n')
                }
                FileOutputStream(File(dir, "runtime.log"), true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            }.onFailure { Log.e(TAG, "could not write runtime log", it) }
        }
    }

    fun writeCrashReport(context: Context, text: String): File? = synchronized(lock) {
        runCatching {
            val dir = File(directory(), "crashes")
            if (!dir.exists() && !dir.mkdirs()) error("cannot create crash report directory")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir, "crash-$stamp.txt").also { it.writeText(text, Charsets.UTF_8); trim(dir) }
        }.onFailure { Log.e(TAG, "could not write crash report", it) }.getOrNull()
    }

    private fun rotate(dir: File) {
        (MAX_FILES - 1 downTo 1).forEach { index ->
            val from = File(dir, "runtime.$index.log")
            if (from.exists()) from.renameTo(File(dir, "runtime.${index + 1}.log"))
        }
        File(dir, "runtime.log").renameTo(File(dir, "runtime.1.log"))
        trim(dir)
    }

    private fun trim(dir: File) {
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)?.forEach { it.delete() }
    }
}
