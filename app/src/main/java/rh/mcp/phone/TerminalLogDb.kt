package rh.mcp.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class TerminalLogEntry(
    val id: Long,
    val startedAt: Long,
    val backend: String,
    val shell: String,
    val command: String,
    val cwd: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val completed: Boolean,
    val timedOut: Boolean,
    val error: String?,
)

class TerminalLogDb(context: Context) :
    SQLiteOpenHelper(context, "terminal.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE executions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                backend TEXT NOT NULL,
                shell TEXT NOT NULL,
                command TEXT NOT NULL,
                cwd TEXT NOT NULL,
                stdout TEXT NOT NULL DEFAULT '',
                stderr TEXT NOT NULL DEFAULT '',
                exit_code INTEGER NOT NULL DEFAULT -1,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                timed_out INTEGER NOT NULL DEFAULT 0,
                error TEXT
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE executions ADD COLUMN backend TEXT NOT NULL DEFAULT 'termux'")
            db.execSQL("ALTER TABLE executions ADD COLUMN shell TEXT NOT NULL DEFAULT '/data/data/com.termux/files/usr/bin/bash'")
        }
    }

    fun insert(entry: TerminalLogEntry): Long {
        val values = ContentValues().apply {
            put("started_at", entry.startedAt)
            put("backend", entry.backend)
            put("shell", entry.shell)
            put("command", entry.command)
            put("cwd", entry.cwd)
        }
        return writableDatabase.insertOrThrow("executions", null, values)
    }

    fun finish(id: Long, result: JSONObject) {
        val values = ContentValues().apply {
            put("stdout", result.optString("stdout"))
            put("stderr", result.optString("stderr"))
            put("exit_code", result.optInt("exitCode", -1))
            put("duration_ms", result.optLong("durationMs"))
            put("completed", if (result.optBoolean("completed")) 1 else 0)
            put("timed_out", if (result.optBoolean("timedOut")) 1 else 0)
            if (result.has("error")) put("error", result.optString("error"))
        }
        writableDatabase.update("executions", values, "id=?", arrayOf(id.toString()))
    }

    fun recent(limit: Int = 100): List<TerminalLogEntry> {
        val rows = mutableListOf<TerminalLogEntry>()
        readableDatabase.rawQuery(
            "SELECT id,started_at,backend,shell,command,cwd,stdout,stderr,exit_code,duration_ms,completed,timed_out,error FROM executions ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += TerminalLogEntry(
                id = cursor.getLong(0),
                startedAt = cursor.getLong(1),
                backend = cursor.getString(2),
                shell = cursor.getString(3),
                command = cursor.getString(4),
                cwd = cursor.getString(5),
                stdout = cursor.getString(6),
                stderr = cursor.getString(7),
                exitCode = cursor.getInt(8),
                durationMs = cursor.getLong(9),
                completed = cursor.getInt(10) != 0,
                timedOut = cursor.getInt(11) != 0,
                error = cursor.getString(12),
            )
        }
        return rows
    }

    fun asJsonArray(limit: Int = 100): JSONArray = JSONArray().also { result ->
        recent(limit).forEach { row ->
            result.put(JSONObject()
                .put("id", row.id).put("startedAt", row.startedAt)
                .put("backend", row.backend).put("shell", row.shell)
                .put("command", row.command).put("cwd", row.cwd)
                .put("stdout", row.stdout).put("stderr", row.stderr)
                .put("exitCode", row.exitCode).put("durationMs", row.durationMs)
                .put("completed", row.completed).put("timedOut", row.timedOut)
                .put("error", row.error ?: JSONObject.NULL))
        }
    }
}
