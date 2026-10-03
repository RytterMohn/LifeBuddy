package dev.ondevice.gemma.prompts

import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.tools.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Gemma3FormatTest {

    @Test
    fun `prompt 包含系统回合与历史`() {
        val tools = listOf(
            ToolSchema("get_time", "获取时间", buildJsonObject {}),
        )
        val history = listOf(
            ChatMessage(Role.USER, "现在几点？"),
            ChatMessage(Role.MODEL, "答案"),
        )
        val prompt = Gemma3Format.buildPrompt("系统提示", history, tools)
        assertTrue(prompt.startsWith("<start_of_turn>user\n系统提示"), "应以 system 回合开头")
        assertTrue(prompt.contains("# 可用工具"))
        assertTrue(prompt.contains("get_time"))
        assertTrue(prompt.contains("现在几点？"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"), "应以 model 回合开头等待续写")
    }

    @Test
    fun `工具结果渲染为 tool_response`() {
        val history = listOf(
            ChatMessage(Role.USER, "现在几点？"),
            ChatMessage(Role.MODEL, "<tool_call>\n{\"function\": \"get_time\", \"arguments\": {}}\n</tool_call>"),
            ChatMessage(Role.TOOL_RESULT, """{"now": "12:00"}""", toolCallId = "get_time"),
        )
        val prompt = Gemma3Format.buildPrompt("系统提示", history, emptyList())
        assertTrue(prompt.contains("<tool_response>"), prompt)
        assertTrue(prompt.contains(""""now": "12:00""""), prompt)
        // 交替顺序 user → model → user(tool_response)，结尾是 model 生成提示
        val seq = prompt.split("<start_of_turn>").filter { it.isNotBlank() }
        assertEquals(5, seq.size, "回合数: $seq")
        assertTrue(seq[3].startsWith("user\n<tool_response>"), seq[3])
    }

    @Test
    fun `lastUserTurn 提取`() {
        val prompt = """
            <start_of_turn>user
            系统内容<end_of_turn>
            <start_of_turn>user
            真正的问题<end_of_turn>
            <start_of_turn>model
        """.trimIndent()
        assertEquals("真正的问题", Gemma3Format.lastUserTurn(prompt))
    }
}
