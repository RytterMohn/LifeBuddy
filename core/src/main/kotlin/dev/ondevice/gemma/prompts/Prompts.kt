package dev.ondevice.gemma.prompts

import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.tools.ToolSchema
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Gemma 3 instruct 提示词格式：
 *   <start_of_turn>user ... <end_of_turn>
 *   <start_of_turn>model ... <end_of_turn>
 *
 * 工具协议：
 *   模型输出 <tool_call>{"function":"...","arguments":{...}}</tool_call> 请求调用，
 *   工具结果以 <tool_response>...</tool_response> 包在 user 回合回填。
 */
object Gemma3Format {
    const val TURN_START = "<start_of_turn>"
    const val TURN_END = "<end_of_turn>"
    const val TOOL_CALL_OPEN = "<tool_call>"
    const val TOOL_CALL_CLOSE = "</tool_call>"
    const val TOOL_RESPONSE_OPEN = "<tool_response>"
    const val TOOL_RESPONSE_CLOSE = "</tool_response>"

    /** 默认系统提示词：移动端助手定位 + 工具使用约束 */
    val defaultSystemPrompt: String = """
        你是一个运行在手机上的轻量级 AI 助手。
        回答要简洁、准确、口语化，优先使用提供的工具获取实时信息。
        调用工具时严格遵循 <tool_call> JSON 格式，一次可以调用多个工具。
        工具返回结果后，基于结果给出最终回答；不要重复输出工具调用。
    """.trimIndent()

    /**
     * 组装完整 prompt：
     *   1. system 回合（含工具 Schema 列表 + 调用说明）
     *   2. 历史对话（TOOL_RESULT 包在 user 回合的 <tool_response> 里）
     *   3. 以 model 回合开头，等模型续写
     */
    fun buildPrompt(
        system: String,
        history: List<ChatMessage>,
        tools: List<ToolSchema>,
    ): String {
        val sb = StringBuilder()
        sb.append(TURN_START).append("user\n").append(system)
        if (tools.isNotEmpty()) {
            sb.append("\n\n# 可用工具\n")
            sb.append(Json.encodeToString(ListSerializer(JsonObject.serializer()), tools.map { it.toJson() }))
            sb.append(
                "\n\n当需要调用工具时，只输出以下格式（可一次输出多个）：\n" +
                    "$TOOL_CALL_OPEN\n{\"function\": \"工具名\", \"arguments\": {\"参数名\": 值}}\n$TOOL_CALL_CLOSE\n" +
                    "示例：要获取当前时间时输出：\n" +
                    "$TOOL_CALL_OPEN\n{\"function\": \"get_time\", \"arguments\": {}}\n$TOOL_CALL_CLOSE\n"
            )
        }
        sb.append(TURN_END).append("\n")

        for (msg in history) {
            sb.append(renderMessage(msg))
        }
        sb.append(TURN_START).append("model\n")
        return sb.toString()
    }

    private fun renderMessage(msg: ChatMessage): String {
        val sb = StringBuilder()
        when (msg.role) {
            Role.USER -> sb.append(TURN_START).append("user\n").append(msg.content)
            Role.MODEL -> sb.append(TURN_START).append("model\n").append(msg.content)
            Role.TOOL_RESULT -> sb
                .append(TURN_START).append("user\n")
                .append(TOOL_RESPONSE_OPEN).append("\n")
                .append(msg.content)
                .append("\n").append(TOOL_RESPONSE_CLOSE).append("\n")
            Role.SYSTEM -> return "" // system 已在 buildPrompt 头部注入
        }
        sb.append(TURN_END).append("\n")
        return sb.toString()
    }

    /** 从 prompt 文本中提取最后一个 user 回合（MockEngine 测试用） */
    fun lastUserTurn(prompt: String): String {
        val idx = prompt.lastIndexOf(TURN_START + "user")
        if (idx < 0) return ""
        val body = prompt.substring(idx + (TURN_START + "user").length)
        return body.substringBefore(TURN_END).trim()
    }
}
