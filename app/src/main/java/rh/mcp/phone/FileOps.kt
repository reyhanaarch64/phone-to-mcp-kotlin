package rh.mcp.phone

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileOps {
    private const val DEFAULT_READ_BYTES = 2 * 1024 * 1024
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 1000
    private const val MAX_EXTRACT_BYTES = 256L * 1024 * 1024

    fun base(context: Context, root: String): File {
        return when (root.lowercase()) {
            "internal" -> context.filesDir.canonicalFile
            "shared" -> {
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    throw SecurityException("All files access belum diaktifkan")
                }
                Environment.getExternalStorageDirectory().canonicalFile
            }
            else -> throw IllegalArgumentException("root harus shared atau internal")
        }
    }

    fun resolve(context: Context, root: String, path: String): File {
        require(path.isNotBlank()) { "path wajib diisi" }
        val rootDir = base(context, root)
        val target = File(rootDir, path).canonicalFile
        require(target == rootDir || target.path.startsWith(rootDir.path + File.separator)) {
            "path keluar dari root yang dipilih"
        }
        return target
    }

    fun execute(context: Context, name: String, args: JSONObject): JSONObject = when (name) {
        "file.read" -> read(context, args)
        "file.write" -> write(context, args)
        "file.append" -> append(context, args)
        "file.patch" -> patch(context, args)
        "file.list" -> list(context, args)
        "file.stat" -> stat(context, args)
        "file.mkdir" -> mkdir(context, args)
        "file.copy" -> copy(context, args)
        "file.move" -> move(context, args)
        "file.delete" -> delete(context, args)
        "zip.create" -> createZip(context, args)
        "zip.extract" -> extractZip(context, args)
        else -> throw IllegalArgumentException("operasi file tidak dikenal: $name")
    }

    private fun read(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val path = args.getString("path")
        val file = resolve(context, root, path)
        require(file.isFile) { "file tidak ditemukan atau bukan file" }
        val limit = args.optInt("maxBytes", DEFAULT_READ_BYTES).coerceIn(1, MAX_FILE_BYTES)
        require(file.length() <= limit) { "file melebihi batas $limit byte" }
        val bytes = file.readBytes()
        val encoding = args.optString("encoding", "utf8").lowercase()
        val result = JSONObject().put("root", root).put("path", path).put("bytes", bytes.size).put("encoding", encoding)
        when (encoding) {
            "base64" -> result.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            "utf8" -> result.put("content", decodeUtf8(bytes))
            else -> throw IllegalArgumentException("encoding harus utf8 atau base64")
        }
        return result
    }

    private fun write(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val path = args.getString("path")
        val target = resolve(context, root, path)
        val mode = args.optString("mode", "overwrite")
        require(mode == "overwrite" || mode == "create") { "mode harus overwrite atau create" }
        if (mode == "create") require(!target.exists()) { "file sudah ada" }
        val content = args.getString("content")
        val bytes = encoded(content, args.optString("encoding", "utf8"))
        require(bytes.size <= MAX_FILE_BYTES) { "data melebihi batas $MAX_FILE_BYTES byte" }
        atomicWrite(target, bytes)
        return JSONObject().put("path", path).put("bytesWritten", bytes.size).put("totalBytes", target.length())
    }

    private fun append(context: Context, args: JSONObject): JSONObject {
        val target = resolve(context, args.getString("root"), args.getString("path"))
        require(!target.exists() || target.isFile) { "target bukan file" }
        val bytes = encoded(args.getString("content"), args.optString("encoding", "utf8"))
        require((target.length() + bytes.size) <= MAX_FILE_BYTES) { "hasil melebihi batas $MAX_FILE_BYTES byte" }
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("gagal membuat direktori parent") }
        FileOutputStream(target, true).use { it.write(bytes) }
        return JSONObject().put("path", args.getString("path")).put("bytesWritten", bytes.size).put("totalBytes", target.length())
    }

    private fun stat(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val path = args.getString("path")
        val base = base(context, root)
        val target = resolve(context, root, path)
        val exists = target.exists()
        return JSONObject()
            .put("root", root).put("path", path).put("exists", exists)
            .put("type", when { !exists -> "missing"; target.isFile -> "file"; target.isDirectory -> "directory"; else -> "other" })
            .put("sizeBytes", if (target.isFile) target.length() else 0)
            .put("modifiedAt", if (exists) target.lastModified() else JSONObject.NULL)
            .put("canRead", exists && target.canRead()).put("canWrite", exists && target.canWrite())
            .put("relativePath", target.relativeTo(base).invariantSeparatorsPath)
    }

    private fun list(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val base = base(context, root)
        val start = resolve(context, root, args.getString("path"))
        require(start.isDirectory) { "bukan direktori" }
        val recursive = args.optBoolean("recursive", false)
        val limit = args.optInt("limit", 500).coerceIn(1, 5000)
        val queue = ArrayDeque<File>().apply { add(start) }
        val entries = JSONArray()
        while (queue.isNotEmpty() && entries.length() < limit) {
            val dir = queue.removeFirst()
            (dir.listFiles() ?: throw IllegalStateException("direktori tidak dapat dibaca: ${dir.name}"))
                .sortedBy { it.name.lowercase() }
                .forEach { child ->
                    if (entries.length() >= limit) return@forEach
                    entries.put(JSONObject()
                        .put("name", child.name)
                        .put("path", child.relativeTo(base).invariantSeparatorsPath)
                        .put("type", if (child.isDirectory) "directory" else "file")
                        .put("sizeBytes", if (child.isFile) child.length() else 0)
                        .put("modifiedAt", child.lastModified()))
                    if (recursive && child.isDirectory && entries.length() < limit) queue.add(child)
                }
        }
        return JSONObject().put("items", entries).put("count", entries.length()).put("truncated", queue.isNotEmpty())
    }

    private fun mkdir(context: Context, args: JSONObject): JSONObject {
        val target = resolve(context, args.getString("root"), args.getString("path"))
        val existed = target.isDirectory
        require(existed || target.mkdirs()) { "gagal membuat direktori" }
        return JSONObject().put("path", args.getString("path")).put("created", !existed).put("alreadyExisted", existed)
    }

    private fun copy(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val source = resolve(context, root, args.getString("from"))
        val target = resolve(context, root, args.getString("to"))
        require(source.exists()) { "source tidak ditemukan" }
        require(target != source && !(source.isDirectory && target.path.startsWith(source.path + File.separator))) {
            "tidak boleh menyalin direktori ke dirinya atau descendant"
        }
        val overwrite = args.optBoolean("overwrite", false)
        if (target.exists()) {
            require(overwrite) { "tujuan sudah ada; set overwrite=true untuk mengganti" }
            deleteTree(target)
        }
        copyTree(source, target)
        return JSONObject().put("from", args.getString("from")).put("to", args.getString("to")).put("copied", true)
    }

    private fun move(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val source = resolve(context, root, args.getString("from"))
        val target = resolve(context, root, args.getString("to"))
        require(source.exists()) { "source tidak ditemukan" }
        require(target != source && !(source.isDirectory && target.path.startsWith(source.path + File.separator))) {
            "tidak boleh memindahkan direktori ke dirinya atau descendant"
        }
        val overwrite = args.optBoolean("overwrite", false)
        if (target.exists()) {
            require(overwrite) { "tujuan sudah ada; set overwrite=true untuk mengganti" }
            deleteTree(target)
        }
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("gagal membuat direktori tujuan") }
        if (!source.renameTo(target)) {
            copyTree(source, target)
            deleteTree(source)
        }
        return JSONObject().put("from", args.getString("from")).put("to", args.getString("to")).put("moved", true)
    }

    private fun delete(context: Context, args: JSONObject): JSONObject {
        val target = resolve(context, args.getString("root"), args.getString("path"))
        require(target != base(context, args.getString("root"))) { "root filesystem tidak boleh dihapus" }
        if (!target.exists()) return JSONObject().put("path", args.getString("path")).put("deleted", false).put("missing", true)
        if (target.isDirectory && target.listFiles()?.isNotEmpty() == true) {
            require(args.optBoolean("recursive", false)) { "direktori tidak kosong; set recursive=true untuk menghapus isinya" }
        }
        val count = countTree(target)
        deleteTree(target)
        return JSONObject().put("path", args.getString("path")).put("deleted", true).put("itemsDeleted", count)
    }

    private fun createZip(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val source = resolve(context, root, args.getString("source"))
        val target = resolve(context, root, args.getString("zip"))
        require(source.exists() && source != target) { "source tidak valid" }
        require(!(source.isDirectory && target.path.startsWith(source.path + File.separator))) { "ZIP tidak boleh dibuat di dalam source directory" }
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("gagal membuat direktori tujuan") }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            val base = source.parentFile ?: source
            addZipEntry(zip, source, base)
        }
        return JSONObject().put("zip", args.getString("zip")).put("bytes", target.length())
    }

    private fun extractZip(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val zipFile = resolve(context, root, args.getString("zip"))
        val destination = resolve(context, root, args.getString("destination"))
        require(zipFile.isFile) { "ZIP tidak ditemukan" }
        if (!destination.exists() && !destination.mkdirs()) error("gagal membuat destination")
        val canonicalRoot = destination.canonicalPath
        var count = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = input.nextEntry ?: break
                count++
                require(count <= MAX_ZIP_ENTRIES) { "ZIP memiliki terlalu banyak entry" }
                val target = File(destination, entry.name).canonicalFile
                require(target.path == canonicalRoot || target.path.startsWith("$canonicalRoot${File.separator}")) {
                    "zip-slip ditolak: ${entry.name}"
                }
                if (entry.isDirectory) {
                    require(!target.exists() || target.isDirectory) { "entry ZIP menimpa file yang sudah ada: ${entry.name}" }
                    if (!target.exists() && !target.mkdirs()) error("gagal membuat direktori ekstraksi")
                } else {
                    require(!target.exists()) { "entry ZIP menimpa file yang sudah ada: ${entry.name}" }
                    target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("gagal membuat direktori entry") }
                    var entryBytes = 0L
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            entryBytes += n
                            total += n
                            require(entryBytes <= 64L * 1024 * 1024 && total <= MAX_EXTRACT_BYTES) {
                                "ZIP melewati batas ukuran ekstraksi"
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                    if (entry.time > 0) target.setLastModified(entry.time)
                }
                input.closeEntry()
            }
        }
        return JSONObject().put("entries", count).put("bytesExtracted", total).put("destination", args.getString("destination"))
    }

    private fun patch(context: Context, args: JSONObject): JSONObject {
        val root = args.getString("root")
        val path = args.getString("path")
        val target = resolve(context, root, path)
        require(target.isFile && target.length() <= MAX_FILE_BYTES) { "file tidak ditemukan atau melewati batas patch $MAX_FILE_BYTES byte" }
        val beforeBytes = target.readBytes()
        val before = decodeUtf8(beforeBytes)
        val expectedHash = args.optString("expected_sha256", "")
        if (expectedHash.isNotBlank()) {
            val actualHash = MessageDigest.getInstance("SHA-256").digest(beforeBytes).joinToString("") { "%02x".format(it) }
            require(expectedHash.equals(actualHash, true)) { "expected_sha256 tidak cocok; file berubah sejak dibaca" }
        }
        val patches = args.optJSONArray("patches") ?: throw IllegalArgumentException("patches wajib berupa array")
        require(patches.length() in 1..100) { "patches harus berisi 1 sampai 100 operasi" }
        var output = before
        val counts = JSONArray()
        for (index in 0 until patches.length()) {
            val operation = patches.optJSONObject(index) ?: throw IllegalArgumentException("patches[$index] harus object")
            val (updated, count) = applyPatch(output, operation)
            val expectedCount = operation.optInt("expected_count", -1)
            require(expectedCount < 0 || count == expectedCount) {
                "patches[$index]: expected_count=$expectedCount, ditemukan $count"
            }
            val expected = operation.optString("expected", "")
            if (expected.isNotEmpty()) require(output.contains(expected)) { "patches[$index]: expected text tidak ditemukan" }
            output = updated
            counts.put(count)
        }
        val dryRun = args.optBoolean("dry_run", false)
        val changed = before != output
        var backupPath: String? = null
        if (!dryRun && changed) {
            if (args.optBoolean("backup", true)) {
                val backup = File(target.parentFile, "${target.name}.bak-${System.currentTimeMillis()}")
                backup.writeBytes(beforeBytes)
                backupPath = backup.name
            }
            atomicWrite(target, output.toByteArray(Charsets.UTF_8))
        }
        val diff = unifiedDiff(before, output, path).take(64 * 1024)
        return JSONObject()
            .put("path", path).put("changed", changed).put("dry_run", dryRun)
            .put("backup_path", backupPath ?: JSONObject.NULL)
            .put("operations_applied", patches.length()).put("match_count", counts)
            .put("bytes_before", beforeBytes.size).put("bytes_after", output.toByteArray(Charsets.UTF_8).size)
            .put("diff", diff)
    }

    private fun applyPatch(text: String, patch: JSONObject): Pair<String, Int> {
        val replacement = patch.optString("replace", patch.optString("content", ""))
        return when (val op = patch.optString("op")) {
            "replace", "replace_first" -> {
                val find = patch.getString("find")
                require(find.isNotEmpty()) { "find tidak boleh kosong" }
                val count = countOccurrences(text, find)
                val occurrence = patch.optString("occurrence", if (op == "replace_first") "1" else "all")
                val output = when (occurrence) {
                    "all" -> text.replace(find, replacement)
                    else -> {
                        val index = occurrence.toIntOrNull()?.minus(1)
                            ?: throw IllegalArgumentException("occurrence harus nomor atau all")
                        require(index in 0 until count) { "occurrence tidak ditemukan" }
                        val position = findOccurrence(text, find, index)
                        text.replaceRange(position, position + find.length, replacement)
                    }
                }
                output to count
            }
            "regex" -> {
                val flags = if (patch.optBoolean("ignore_case")) setOf(RegexOption.IGNORE_CASE) else emptySet()
                val regex = Regex(patch.getString("pattern"), flags)
                val all = regex.findAll(text).toList()
                val occurrence = patch.optString("occurrence", "all")
                val output = if (occurrence == "all") regex.replace(text, replacement) else {
                    val selected = occurrence.toIntOrNull()?.minus(1)
                        ?: throw IllegalArgumentException("occurrence harus nomor atau all")
                    require(selected in all.indices) { "occurrence tidak ditemukan" }
                    val match = all[selected]
                    text.replaceRange(match.range, match.value.replace(Regex(match.value), replacement))
                }
                output to all.size
            }
            "prepend" -> (replacement + text) to 1
            "append" -> (text + replacement) to 1
            "replace_context" -> {
                val find = patch.getString("find")
                val before = patch.optString("context_before", "")
                val after = patch.optString("context_after", "")
                val matches = mutableListOf<Int>()
                var offset = text.indexOf(find)
                while (offset >= 0) {
                    val prefixMatches = before.isEmpty() || text.substring(0, offset).endsWith(before)
                    val suffixStart = offset + find.length
                    val suffixMatches = after.isEmpty() || text.substring(suffixStart).startsWith(after)
                    if (prefixMatches && suffixMatches) matches += offset
                    offset = text.indexOf(find, offset + find.length.coerceAtLeast(1))
                }
                require(matches.size == 1) { "replace_context harus cocok tepat satu kali; ditemukan ${matches.size}" }
                val start = matches.single()
                text.replaceRange(start, start + find.length, replacement) to 1
            }
            "insert" -> {
                val lines = text.split('\n').toMutableList()
                val line = patch.getInt("line")
                require(line in 1..(lines.size + 1)) { "line di luar rentang" }
                lines.add(line - 1, replacement)
                lines.joinToString("\n") to 1
            }
            "line_replace", "line_range", "delete_lines" -> {
                val lines = text.split('\n').toMutableList()
                val start = if (op == "line_replace") patch.getInt("line") else patch.getInt("start_line")
                val end = if (op == "line_replace") start else patch.getInt("end_line")
                require(start >= 1 && end >= start && end <= lines.size) { "line range di luar rentang" }
                repeat(end - start + 1) { lines.removeAt(start - 1) }
                if (op != "delete_lines") lines.add(start - 1, replacement)
                lines.joinToString("\n") to (end - start + 1)
            }
            else -> throw IllegalArgumentException("operasi patch tidak didukung: $op")
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun encoded(content: String, encoding: String): ByteArray = when (encoding.lowercase()) {
        "utf8" -> content.toByteArray(Charsets.UTF_8)
        "base64" -> Base64.decode(content, Base64.DEFAULT)
        else -> throw IllegalArgumentException("encoding harus utf8 atau base64")
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("gagal membuat direktori parent") }
        val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(target)) error("atomic replace gagal")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) error("gagal membuat direktori tujuan")
            (source.listFiles() ?: error("source directory tidak dapat dibaca")).forEach {
                copyTree(it, File(target, it.name))
            }
        } else {
            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("gagal membuat parent") }
            source.copyTo(target, overwrite = false)
        }
    }

    private fun countTree(file: File): Int =
        if (file.isDirectory) 1 + (file.listFiles()?.sumOf(::countTree) ?: 0) else 1

    private fun deleteTree(file: File) {
        if (file.isDirectory) (file.listFiles() ?: error("direktori tidak dapat dibaca")).forEach(::deleteTree)
        if (!file.delete() && file.exists()) error("gagal menghapus ${file.name}")
    }

    private fun addZipEntry(zip: ZipOutputStream, file: File, base: File) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: error("direktori tidak dapat dibaca")
            if (children.isEmpty()) {
                zip.putNextEntry(ZipEntry(file.relativeTo(base).invariantSeparatorsPath + "/"))
                zip.closeEntry()
            } else children.forEach { addZipEntry(zip, it, base) }
        } else {
            zip.putNextEntry(ZipEntry(file.relativeTo(base).invariantSeparatorsPath))
            FileInputStream(file).use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun countOccurrences(text: String, find: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(find, index)
            if (index < 0) return count
            count++
            index += find.length
        }
    }

    private fun findOccurrence(text: String, find: String, occurrence: Int): Int {
        var index = 0
        repeat(occurrence) {
            index = text.indexOf(find, index) + find.length
        }
        return text.indexOf(find, index)
    }

    private fun unifiedDiff(before: String, after: String, path: String): String {
        val oldLines = before.split('\n')
        val newLines = after.split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) suffix++
        val contextStart = (prefix - 3).coerceAtLeast(0)
        val oldEnd = (oldLines.size - suffix + 3).coerceAtMost(oldLines.size)
        val newEnd = (newLines.size - suffix + 3).coerceAtMost(newLines.size)
        val oldCount = oldEnd - contextStart
        val newCount = newEnd - contextStart
        return buildString {
            append("--- a/").append(path).append('\n')
            append("+++ b/").append(path).append('\n')
            append("@@ -").append(contextStart + 1).append(',').append(oldCount)
                .append(" +").append(contextStart + 1).append(',').append(newCount).append(" @@\n")
            for (i in contextStart until prefix) append(' ').append(oldLines[i]).append('\n')
            for (i in prefix until oldLines.size - suffix) append('-').append(oldLines[i]).append('\n')
            for (i in prefix until newLines.size - suffix) append('+').append(newLines[i]).append('\n')
            for (i in oldLines.size - suffix until oldEnd) append(' ').append(oldLines[i]).append('\n')
        }
    }
}
