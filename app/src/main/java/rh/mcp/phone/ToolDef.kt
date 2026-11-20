package rh.mcp.phone

import org.json.JSONObject

data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val category: String,
    val access: Access,
    val defaultEnabled: Boolean = false,
) {
    enum class Access { READ, WRITE }

    fun toJson() = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", inputSchema)
}
