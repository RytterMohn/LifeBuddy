package dev.ondevice.gemma.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException

/**
 * 工具注册表 + 工具调用解析器。
 *
 * 解析兼容两种格式：
 *   Gemma 3 : {"function": "name", "arguments": {...}}
 *   OpenAI  : {"name": "name", "arguments": "{\"k\":\"v\"}"}（arguments 为 JSON 字符串）
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    /** 注册单个工具（重名覆盖） */
    fun register(tool: Tool): ToolRegistry = apply { tools[tool.schema.name] = tool }

    fun registerAll(vararg tools: Tool): ToolRegistry = apply { tools.forEach { register(it) } }

    fun all(): List<Tool> = tools.values.toList()

    /** 给模型看的 Schema 列表（JSON 数组） */
    fun schemas(): List<ToolSchema> = tools.values.map { it.schema }

    fun has(name: String): Boolean = name in tools

    /** 执行工具，异常兜底为 JSON 错误结果，避免打断 agent 循环 */
    suspend fun execute(name: String, args: JsonObject): String {
        val tool = tools[name] ?: return errorJson("unknown tool: $name")
        return try {
            tool.executor.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "tool failed")
        }
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }.toString()

    /** 从模型输出解析所有 <tool_call> 块，支持同一回合多次调用 */
    fun parseToolCalls(text: String): List<ToolCall> {
        if (text.isEmpty()) return emptyList()
        val results = mutableListOf<ToolCall>()
        // 宽松捕获：JSON 或裸函数名（如 <tool_call>get_time</tool_call>）
        val regex = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in regex.findAll(text)) {
            parseSingleCall(m.groupValues[1])?.let { results += it }
        }
        // 未闭合块：模型可能被 <end_of_turn> 截断（如 <tool_call>{...} 无 </tool_call>）
        val openIdx = text.lastIndexOf("<tool_call>")
        if (openIdx >= 0) {
            val closeIdx = text.indexOf("</tool_call>", openIdx)
            if (closeIdx < 0) {
                parseSingleCall(text.substring(openIdx + "<tool_call>".length))?.let { results += it }
            }
        }
        return results
    }

    private fun parseSingleCall(raw: String): ToolCall? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val openBrace = trimmed.indexOf('{')
        val closeBrace = trimmed.lastIndexOf('}')
        // 裸函数名形式（无 JSON）：<tool_call>get_time</tool_call>
        if (openBrace < 0 || closeBrace <= openBrace) {
            val name = trimmed.substringBefore('<').trim()
            return if (name.isEmpty() || !has(name)) null else ToolCall(name, JsonObject(emptyMap()))
        }
        // 只取第一对花括号内的 JSON，容忍前后杂质（如 <end_of_turn>）
        val jsonText = trimmed.substring(openBrace, closeBrace + 1)
        return try {
            val obj = Json.parseToJsonElement(jsonText).jsonObject
            val fn = obj["function"]?.jsonPrimitive?.content
                ?: obj["name"]?.jsonPrimitive?.content
                ?: return null
            val rawArgs = obj["arguments"]
        val args = when {
            rawArgs == null -> JsonObject(emptyMap())
            rawArgs is JsonObject -> rawArgs
            else -> try {
                // OpenAI 风格：arguments 是 JSON 字符串
                Json.parseToJsonElement(rawArgs.jsonPrimitive.content).jsonObject
            } catch (_: Exception) {
                return null
            }
        }
            ToolCall(fn, args)
        } catch (_: Exception) {
            null
        }
    }
}
