package dev.ondevice.gemma.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次解析出的工具调用 */
data class ToolCall(
    val name: String,
    val arguments: JsonObject,
)

/** 工具执行的声明式 JSON Schema（对齐 Gemma 3 function calling） */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parameters)
    }
}

/** 工具执行器：参数是解析好的 JSON 对象，返回字符串（通常为 JSON 文本） */
fun interface ToolExecutor {
    suspend fun execute(args: JsonObject): String
}

/** 一个可注册工具 = Schema（给模型看）+ 执行器（模型调用后跑） */
data class Tool(
    val schema: ToolSchema,
    val executor: ToolExecutor,
)
