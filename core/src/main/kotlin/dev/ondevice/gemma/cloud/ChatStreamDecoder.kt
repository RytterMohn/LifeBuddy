package dev.ondevice.gemma.cloud

import kotlinx.serialization.json.*

/** SSE framing is independent of TCP packet boundaries; callers supply complete UTF-8 lines. */
class SseEventDecoder {
    private val data = StringBuilder()
    private var firstLine = true
    fun line(raw: String): String? {
        val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
        firstLine = false
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            return data.toString().removeSuffix("\n").also { data.clear() }
        }
        if (line.startsWith("data:")) {
            data.append(line.substring(5).removePrefix(" ")).append('\n')
            require(data.length <= 262144) { "流式事件过大" }
        }
        return null
    }
}

/** Assemble tool fragments, but expose only text deltas until a valid terminal event arrives. */
class ChatStreamDecoder {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private class Call {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
        var type = ""
    }
    private val calls = sortedMapOf<Int, Call>()
    private var finish: String? = null
    private var done = false
    private var size = 0

    fun event(data: String): String {
        check(!done) { "流式响应已结束" }
        if (data == "[DONE]") {
            check(finish == "stop" || finish == "tool_calls") { "流式响应中断或输出未完成" }
            done = true
            return ""
        }
        size += data.length
        require(size <= 2 * 1024 * 1024) { "流式响应过大" }
        try {
            val root = Json.parseToJsonElement(data).jsonObject
            require(root["error"] == null)
            val choices = root.getValue("choices").jsonArray
            if (choices.isEmpty()) return "" // Optional usage event.
            require(choices.size == 1 && choices.single().jsonObject["index"]?.jsonPrimitive?.int == 0)
            require(finish == null)
            val choice = choices.single().jsonObject
            val delta = choice.getValue("delta").jsonObject
            delta.string("role")?.let { require(it == "assistant") }
            val text = delta.string("content").orEmpty()
            content.append(text)
            reasoning.append(delta.string("reasoning_content").orEmpty())
            delta["tool_calls"]?.takeUnless { it == JsonNull }?.jsonArray?.forEach { value ->
                val fragment = value.jsonObject
                val index = fragment.getValue("index").jsonPrimitive.int
                require(index in 0..7)
                val call = calls.getOrPut(index) { Call() }
                // Empty names/IDs in subsequent gateway chunks must not erase the first fragment.
                call.id.append(fragment.string("id").orEmpty())
                fragment.string("type")?.takeIf { it.isNotBlank() }?.let {
                    require(it == "function" && (call.type.isEmpty() || call.type == it))
                    call.type = it
                }
                fragment["function"]?.takeUnless { it == JsonNull }?.jsonObject?.let { function ->
                    call.name.append(function.string("name").orEmpty())
                    call.arguments.append(function.string("arguments").orEmpty())
                }
            }
            require(content.length + reasoning.length + calls.values.sumOf { it.id.length + it.name.length + it.arguments.length } <= 262144)
            choice.string("finish_reason")?.takeIf { it.isNotEmpty() }?.let {
                require(it == "stop" || it == "tool_calls")
                require(it != "tool_calls" || calls.isNotEmpty())
                finish = it
            }
            return text
        } catch (_: Exception) {
            throw IllegalArgumentException("流式响应不完整或格式不兼容，请重试")
        }
    }

    fun response(): String {
        check(done && finish != null) { "流式连接中断，未收到完整结束标记，请重试" }
        calls.values.forEach { require(it.id.isNotBlank() && it.name.isNotBlank() && it.type == "function") { "工具调用信息不完整" } }
        return buildJsonObject {
            putJsonArray("choices") { addJsonObject {
                put("finish_reason", finish)
                putJsonObject("message") {
                    put("role", "assistant"); put("content", content.toString())
                    if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
                    if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                        calls.values.forEach { call -> addJsonObject {
                            put("id", call.id.toString()); put("type", call.type)
                            putJsonObject("function") { put("name", call.name.toString()); put("arguments", call.arguments.toString()) }
                        } }
                    }
                }
            } }
        }.toString()
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString)
        return value.content
    }
}
