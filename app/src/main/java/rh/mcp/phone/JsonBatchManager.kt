package rh.mcp.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.File

class JsonBatchManager(private val context: Context) {
    fun execute(args: JSONObject): JSONObject {
        val files = args.optJSONArray("files") ?: throw IllegalArgumentException("files wajib berupa array")
        require(files.length() in 1..10) { "files harus berisi 1 sampai 10 item" }
        val seen = mutableSetOf<String>()
        val results = JSONArray()
        var succeeded = 0
        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index)
            val result = runCatching {
                requireNotNull(item) { "files[$index] harus object" }
                val root = item.optString("root", "shared")
                val path = item.getString("path")
                require(path.isNotBlank()) { "path wajib diisi" }
                val target = FileOps.resolve(context, root, path)
                require(seen.add(target.canonicalPath)) { "file yang sama muncul lebih dari sekali dalam batch" }
                process(item, root, path, target)
            }.getOrElse { error ->
                JSONObject()
                    .put("path", item?.optString("path", "") ?: "")
                    .put("root", item?.optString("root", "shared") ?: "shared")
                    .put("status", "error")
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            if (result.optString("status") == "success") succeeded++
            results.put(result)
        }
        val failed = files.length() - succeeded
        return JSONObject().put("success", failed == 0).put("totalFiles", files.length())
            .put("succeeded", succeeded).put("failed", failed).put("results", results)
    }

    private fun process(item: JSONObject, root: String, path: String, file: File): JSONObject {
        require(file.isFile) { "file JSON tidak ditemukan" }
        require(file.length() <= MAX_BYTES) { "file melebihi batas $MAX_BYTES byte" }
        val original = file.readText(Charsets.UTF_8)
        var document: Any = JSONTokener(original).nextValue()
        val actions = item.optJSONArray("actions") ?: throw IllegalArgumentException("actions wajib berupa array")
        require(actions.length() in 1..100) { "actions harus berisi 1 sampai 100 operasi" }
        val actionResults = JSONArray()
        var mutated = false
        for (index in 0 until actions.length()) {
            val action = actions.optJSONObject(index) ?: throw IllegalArgumentException("actions[$index] harus object")
            val op = action.optString("operation").lowercase()
            val key = action.optString("key", "")
            val result = runCatching {
                val output = executeAction(document, op, key, action)
                document = output.first
                mutated = mutated || output.second
                output.third.put("index", index).put("operation", op).put("key", key)
            }.getOrElse { throw IllegalArgumentException("actions[$index] $op $key: ${it.message}", it) }
            actionResults.put(result)
        }
        val dryRun = item.optBoolean("dry_run", false)
        val pretty = item.optBoolean("pretty_print", true)
        val output = if (mutated) serialize(document, pretty) else original
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "hasil melebihi batas $MAX_BYTES byte" }
        var backup: String? = null
        if (mutated && output != original && !dryRun) {
            if (item.optBoolean("backup", true)) {
                val backupFile = File(file.parentFile, "${file.name}.bak-${System.currentTimeMillis()}")
                file.copyTo(backupFile)
                backup = backupFile.name
            }
            atomicReplace(file, output)
        }
        return JSONObject().put("root", root).put("path", path).put("status", "success")
            .put("dryRun", dryRun).put("readOnly", !mutated).put("backup", backup ?: JSONObject.NULL)
            .put("operations", actionResults).put("operationCount", actionResults.length())
            .put("bytesBefore", original.toByteArray(Charsets.UTF_8).size)
            .put("bytesAfter", output.toByteArray(Charsets.UTF_8).size).put("changed", output != original)
    }

    private fun executeAction(
        document: Any,
        operation: String,
        key: String,
        action: JSONObject,
    ): Triple<Any, Boolean, JSONObject> {
        val result = JSONObject()
        val path = parsePath(key)
        return when (operation) {
            "get" -> {
                result.put("found", true).put("value", getAt(document, path))
                Triple(document, false, result)
            }
            "set" -> {
                require(action.has("value")) { "value wajib ada" }
                val updated = setAt(document, path, action.get("value"), action.optBoolean("create_missing"))
                Triple(updated, true, result.put("changed", true))
            }
            "add", "insert" -> {
                require(action.has("value")) { "value wajib ada" }
                val target = getAt(document, path) as? JSONArray ?: error("$operation hanya untuk array")
                val array = copyArray(target)
                if (operation == "add") array.put(action.get("value"))
                else {
                    val index = action.optInt("index", array.length())
                    require(index in 0..array.length()) { "index di luar rentang" }
                    val resultArray = JSONArray()
                    for (i in 0..array.length()) {
                        if (i == index) resultArray.put(action.get("value"))
                        if (i < array.length()) resultArray.put(array.get(i))
                    }
                    val updated = setAt(document, path, resultArray, false)
                    result.put("length", resultArray.length())
                    return Triple(updated, true, result)
                }
                val updated = setAt(document, path, array, false)
                Triple(updated, true, result.put("length", array.length()))
            }
            "remove" -> Triple(removeAt(document, path), true, result.put("removed", true))
            "merge" -> {
                val current = getAt(document, path) as? JSONObject ?: error("target merge harus object")
                val incoming = action.optJSONObject("value") ?: error("value merge harus object")
                val merged = merge(current, incoming, action.optString("merge_mode", "deep") == "deep")
                Triple(setAt(document, path, merged, false), true, result.put("mergedKeys", incoming.length()))
            }
            "rename" -> {
                val newKey = action.optString("new_key")
                require(newKey.isNotBlank()) { "new_key wajib diisi" }
                Triple(renameAt(document, path, newKey), true, result.put("renamed", true).put("newKey", newKey))
            }
            "find" -> {
                val query = action.optString("query")
                require(query.isNotBlank()) { "query wajib untuk find" }
                val matches = JSONArray()
                findKeys(document, query, "", matches, MAX_FIND_RESULTS)
                Triple(document, false, result.put("matches", matches).put("count", matches.length())
                    .put("truncated", matches.length() >= MAX_FIND_RESULTS))
            }
            "get_structure" -> {
                val target = if (path.isEmpty()) document else getAt(document, path)
                Triple(document, false, result.put("structure", describe(target, 0)))
            }
            else -> error("operation tidak didukung: $operation")
        }
    }

    private fun parsePath(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        if (path.startsWith("/")) return path.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
        val tokens = Regex("""([^.\[\]]+)|\[(\d+)]""").findAll(path).map {
            it.groupValues[1].ifEmpty { it.groupValues[2] }
        }.toList()
        require(tokens.isNotEmpty()) { "path JSON tidak valid" }
        return tokens
    }

    private fun getAt(root: Any, path: List<String>): Any {
        var value = root
        path.forEach { segment ->
            value = when (value) {
                is JSONObject -> value.get(segment)
                is JSONArray -> value.get(segment.toIntOrNull() ?: error("array index harus integer"))
                else -> error("parent JSON bukan object atau array")
            }
        }
        return value
    }

    private fun setAt(root: Any, path: List<String>, value: Any, createMissing: Boolean): Any {
        if (path.isEmpty()) return value
        var current = root
        for (segment in path.dropLast(1)) {
            current = when (current) {
                is JSONObject -> {
                    if (!current.has(segment)) {
                        require(createMissing) { "path parent tidak ditemukan: $segment" }
                        current.put(segment, JSONObject())
                    }
                    current.get(segment)
                }
                is JSONArray -> current.get(segment.toInt())
                else -> error("parent JSON bukan object atau array")
            }
        }
        val last = path.last()
        when (current) {
            is JSONObject -> current.put(last, value)
            is JSONArray -> current.put(last.toInt(), value)
            else -> error("target parent bukan object atau array")
        }
        return root
    }

    private fun removeAt(root: Any, path: List<String>): Any {
        require(path.isNotEmpty()) { "tidak bisa menghapus root JSON" }
        val parent = getAt(root, path.dropLast(1))
        val key = path.last()
        when (parent) {
            is JSONObject -> require(parent.remove(key) != null) { "key tidak ditemukan" }
            is JSONArray -> {
                val index = key.toInt()
                require(index in 0 until parent.length()) { "index tidak ditemukan" }
                val replacement = JSONArray()
                for (i in 0 until parent.length()) if (i != index) replacement.put(parent.get(i))
                setAt(root, path.dropLast(1), replacement, false)
            }
            else -> error("target parent bukan object atau array")
        }
        return root
    }

    private fun renameAt(root: Any, path: List<String>, newKey: String): Any {
        require(path.isNotEmpty()) { "tidak bisa rename root JSON" }
        val parent = getAt(root, path.dropLast(1)) as? JSONObject ?: error("rename hanya untuk key object")
        val oldKey = path.last()
        require(parent.has(oldKey) && !parent.has(newKey)) { "key lama tidak ada atau key baru sudah ada" }
        val value = parent.get(oldKey)
        val updated = JSONObject()
        val keys = parent.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            updated.put(if (key == oldKey) newKey else key, if (key == oldKey) value else parent.get(key))
        }
        return setAt(root, path.dropLast(1), updated, false)
    }

    private fun merge(target: JSONObject, incoming: JSONObject, deep: Boolean): JSONObject {
        val result = JSONObject(target.toString())
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val old = result.opt(key)
            val new = incoming.get(key)
            result.put(key, if (deep && old is JSONObject && new is JSONObject) merge(old, new, true) else new)
        }
        return result
    }

    private fun findKeys(value: Any, query: String, path: String, output: JSONArray, limit: Int) {
        if (output.length() >= limit) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext() && output.length() < limit) {
                    val key = keys.next()
                    val childPath = "$path/$key"
                    if (key.contains(query, true)) output.put(JSONObject().put("key", key).put("path", childPath))
                    findKeys(value.get(key), query, childPath, output, limit)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) findKeys(value.get(i), query, "$path/$i", output, limit)
        }
    }

    private fun describe(value: Any, depth: Int): JSONObject {
        if (depth > 8) return JSONObject().put("type", "truncated")
        return when (value) {
            is JSONObject -> JSONObject().put("type", "object").put("keys", JSONArray().also { keys ->
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    keys.put(JSONObject().put("name", key).put("value", describe(value.get(key), depth + 1)))
                }
            })
            is JSONArray -> JSONObject().put("type", "array").put("length", value.length())
                .put("items", JSONArray().also { items ->
                    for (i in 0 until minOf(value.length(), 20)) items.put(describe(value.get(i), depth + 1))
                })
            JSONObject.NULL -> JSONObject().put("type", "null")
            else -> JSONObject().put("type", when (value) {
                is String -> "string"; is Number -> "number"; is Boolean -> "boolean"; else -> "unknown"
            })
        }
    }

    private fun copyArray(array: JSONArray) = JSONArray(array.toString())
    private fun serialize(value: Any, pretty: Boolean): String = when (value) {
        is JSONObject -> if (pretty) value.toString(2) else value.toString()
        is JSONArray -> if (pretty) value.toString(2) else value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun atomicReplace(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) error("gagal mengganti file")
        if (!temp.renameTo(target)) error("atomic replace gagal")
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024L
        private const val MAX_FIND_RESULTS = 200
    }
}
