package rh.mcp.phone

import org.json.JSONArray
import org.json.JSONObject

object ToolRegistry {
    private fun schema(vararg fields: Field): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        fields.forEach { field ->
            val property = JSONObject().put("type", field.type)
            if (field.description.isNotBlank()) property.put("description", field.description)
            if (field.enum.isNotEmpty()) property.put("enum", JSONArray(field.enum))
            properties.put(field.name, property)
            if (field.required) required.put(field.name)
        }
        val result = JSONObject().put("type", "object").put("properties", properties)
        if (required.length() > 0) result.put("required", required)
        return result
    }

    private data class Field(
        val name: String,
        val type: String,
        val description: String = "",
        val required: Boolean = false,
        val enum: List<String> = emptyList(),
    )

    private fun f(name: String, type: String, description: String = "", required: Boolean = false) =
        Field(name, type, description, required)

    private fun choice(name: String, description: String, values: List<String>, required: Boolean = false) =
        Field(name, "string", description, required, values)

    private fun patchSchema(): JSONObject {
        val operation = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("op", JSONObject().put("type", "string").put("enum", JSONArray(listOf(
                    "replace", "replace_first", "regex", "line_replace", "line_range", "insert",
                    "delete_lines", "replace_context", "prepend", "append",
                ))))
                .put("find", JSONObject().put("type", "string"))
                .put("replace", JSONObject().put("type", "string"))
                .put("pattern", JSONObject().put("type", "string"))
                .put("line", JSONObject().put("type", "integer"))
                .put("start_line", JSONObject().put("type", "integer"))
                .put("end_line", JSONObject().put("type", "integer"))
                .put("content", JSONObject().put("type", "string"))
                .put("context_before", JSONObject().put("type", "string"))
                .put("context_after", JSONObject().put("type", "string"))
                .put("occurrence", JSONObject().put("type", "string"))
                .put("expected", JSONObject().put("type", "string"))
                .put("expected_count", JSONObject().put("type", "integer")))
            .put("required", JSONArray().put("op"))
        return schema(
            choice("root", "Root filesystem.", listOf("shared", "internal"), true),
            f("path", "string", "Path file UTF-8.", true),
            f("patches", "array", "Operasi patch berurutan.", true),
            f("dry_run", "boolean", "Hitung perubahan tanpa menulis."),
            f("backup", "boolean", "Buat satu backup sebelum commit; default true."),
            f("expected_sha256", "string", "Hash file yang diharapkan sebelum patch."),
        ).also { it.getJSONObject("properties").getJSONObject("patches").put("items", operation) }
    }

    private fun jsonBatchSchema(): JSONObject {
        val action = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("operation", JSONObject().put("type", "string").put("enum", JSONArray(listOf(
                    "get", "set", "add", "insert", "remove", "merge", "rename", "find", "get_structure",
                ))))
                .put("key", JSONObject().put("type", "string").put("description", "JSON Pointer atau dot path."))
                .put("value", JSONObject())
                .put("index", JSONObject().put("type", "integer"))
                .put("new_key", JSONObject().put("type", "string"))
                .put("query", JSONObject().put("type", "string"))
                .put("merge_mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("deep", "shallow"))))
                .put("create_missing", JSONObject().put("type", "boolean")))
            .put("required", JSONArray().put("operation"))
        val file = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("root", JSONObject().put("type", "string").put("enum", JSONArray(listOf("shared", "internal"))))
                .put("path", JSONObject().put("type", "string"))
                .put("actions", JSONObject().put("type", "array").put("items", action))
                .put("dry_run", JSONObject().put("type", "boolean"))
                .put("backup", JSONObject().put("type", "boolean"))
                .put("pretty_print", JSONObject().put("type", "boolean")))
            .put("required", JSONArray().put("path").put("actions"))
        return schema(f("files", "array", "1 sampai 10 file JSON dengan actions independen.", true))
            .also {
                it.getJSONObject("properties").getJSONObject("files")
                    .put("minItems", 1).put("maxItems", 10).put("items", file)
            }
    }

    val all: List<ToolDef> = listOf(
        ToolDef("device.info", "Informasi perangkat melalui section model, android, storage, memory, display, cpu, battery, atau network.", schema(choice("section", "Section informasi; default all.", listOf("all", "model", "android", "storage", "memory", "display", "cpu", "battery", "network"))), "device", ToolDef.Access.READ, true),
        ToolDef("time.now", "Waktu perangkat dalam ISO-8601 dan Unix epoch milliseconds.", schema(), "device", ToolDef.Access.READ, true),
        ToolDef("file.read", "Baca file pada root shared atau internal; encoding utf8 atau base64.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Path relatif terhadap root.", true), choice("encoding", "Encoding output.", listOf("utf8", "base64")), f("maxBytes", "integer", "Batas byte, default 2 MiB.")), "files", ToolDef.Access.READ, true),
        ToolDef("file.write", "Buat atau timpa file secara atomic.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Path relatif.", true), f("content", "string", "Isi file.", true), choice("encoding", "utf8 atau base64.", listOf("utf8", "base64")), choice("mode", "overwrite atau create.", listOf("overwrite", "create"))), "files", ToolDef.Access.WRITE),
        ToolDef("file.append", "Tambahkan isi ke akhir file.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Path relatif.", true), f("content", "string", "Konten tambahan.", true), choice("encoding", "utf8 atau base64.", listOf("utf8", "base64"))), "files", ToolDef.Access.WRITE),
        ToolDef("file.patch", "Terapkan operasi patch teks dengan guard, backup, dry-run, dan diff.", patchSchema(), "files", ToolDef.Access.WRITE),
        ToolDef("file.list", "Daftar file dan direktori; output memiliki metadata dasar.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Direktori relatif.", true), f("recursive", "boolean"), f("limit", "integer")), "files", ToolDef.Access.READ, true),
        ToolDef("file.stat", "Periksa tipe, ukuran, waktu perubahan, dan akses path.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Path relatif.", true)), "files", ToolDef.Access.READ, true),
        ToolDef("file.mkdir", "Buat direktori beserta parent yang belum ada.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Path relatif.", true)), "files", ToolDef.Access.WRITE),
        ToolDef("file.copy", "Salin file atau direktori rekursif; source tidak boleh menjadi parent tujuan.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("from", "string", "Sumber relatif.", true), f("to", "string", "Tujuan relatif.", true), f("overwrite", "boolean")), "files", ToolDef.Access.WRITE),
        ToolDef("file.move", "Pindahkan file atau direktori tanpa menimpa tujuan secara default.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("from", "string", "Sumber relatif.", true), f("to", "string", "Tujuan relatif.", true), f("overwrite", "boolean")), "files", ToolDef.Access.WRITE),
        ToolDef("file.delete", "Hapus file atau direktori; recursive harus dinyatakan eksplisit.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("path", "string", "Target relatif.", true), f("recursive", "boolean")), "files", ToolDef.Access.WRITE),
        ToolDef("zip.create", "Buat ZIP dari file atau direktori.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("source", "string", "Path sumber.", true), f("zip", "string", "Path ZIP tujuan.", true)), "files", ToolDef.Access.WRITE),
        ToolDef("zip.extract", "Ekstrak ZIP dengan proteksi zip-slip dan batas ukuran.", schema(choice("root", "Root filesystem.", listOf("shared", "internal"), true), f("zip", "string", "Path arsip.", true), f("destination", "string", "Direktori tujuan.", true)), "files", ToolDef.Access.WRITE),
        ToolDef("json.batch", "Manipulasi struktur 1-10 file JSON secara independen; dukung dry-run dan backup.", jsonBatchSchema(), "json", ToolDef.Access.WRITE),
        ToolDef("terminal.info", "Status backend shell aktif dan ketersediaan Android/Termux.", schema(), "terminal", ToolDef.Access.READ, true),
        ToolDef("terminal.set_backend", "Pilih backend terminal global; Termux perlu terpasang dan RUN_COMMAND diizinkan.", schema(choice("backend", "Backend baru.", listOf("android", "termux"), true)), "terminal", ToolDef.Access.WRITE),
        ToolDef("terminal.exec", "Jalankan command pada backend aktif tanpa fallback otomatis.", schema(f("command", "string", "Command shell.", true), f("cwd", "string", "Working directory backend aktif."), f("timeoutMs", "integer", "Timeout 1000-300000 ms.")), "terminal", ToolDef.Access.WRITE),
        ToolDef("notification.send", "Kirim notifikasi lokal kepada pengguna.", schema(f("id", "integer", "ID notifikasi."), f("title", "string", "Judul.", true), f("message", "string", "Isi notifikasi.", true)), "notifications", ToolDef.Access.WRITE),
        ToolDef("notification.read", "Baca notifikasi aktif yang tersedia melalui Notification Listener.", schema(f("limit", "integer", "Batas hasil, default 100.")), "notifications", ToolDef.Access.READ),
        ToolDef("tts.speak", "Ucapkan teks menggunakan Android TextToSpeech engine.", schema(f("text", "string", "Teks yang diucapkan.", true), f("language", "string", "Locale BCP-47, default system."), choice("queue", "Tambahkan ke antrean atau ganti antrean.", listOf("add", "flush"))), "tts", ToolDef.Access.WRITE),
        ToolDef("tts.stop", "Hentikan ucapan dan antrean TTS aplikasi.", schema(), "tts", ToolDef.Access.WRITE),
        ToolDef("download.url", "Unduh URL dengan Android DownloadManager.", schema(f("url", "string", "URL HTTP(S).", true), f("title", "string", "Nama tampilan.")), "download", ToolDef.Access.WRITE),
        ToolDef("server.status", "Status MCP server, autentikasi, port, dan tunnel.", schema(), "admin", ToolDef.Access.READ, true),
        ToolDef("mcp.tools_enabled", "Daftar tool MCP yang diaktifkan.", schema(), "admin", ToolDef.Access.READ, true),
    ).associateBy { it.name }.values.toList()

    private val byName = all.associateBy { it.name }
    fun get(name: String): ToolDef? = byName[name]
    fun enabled(context: android.content.Context) = all.filter { ToolSettings.isEnabled(context, it) }
}
