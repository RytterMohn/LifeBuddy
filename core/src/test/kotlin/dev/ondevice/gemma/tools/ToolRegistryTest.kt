package dev.ondevice.gemma.tools

import dev.ondevice.gemma.tools.builtin.BuiltinTools
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRegistryTest {

    private fun registry() = ToolRegistry().registerAll(*BuiltinTools.all().toTypedArray())

    @Test
    fun `标准 JSON 形式`() {
        val text = """
            <tool_call>
            {"function": "calculator", "arguments": {"expression": "1+1"}}
            </tool_call>
        """.trimIndent()
        val calls = registry().parseToolCalls(text)
        assertEquals(1, calls.size)
        assertEquals("calculator", calls[0].name)
        assertEquals("1+1", calls[0].arguments["expression"]?.jsonPrimitive?.content)
    }

    @Test
    fun `裸函数名形式`() {
        val calls = registry().parseToolCalls("<tool_call>get_time</tool_call>")
        assertEquals(1, calls.size)
        assertEquals("get_time", calls[0].name)
        assertTrue(calls[0].arguments.isEmpty())
    }

    @Test
    fun `未闭合块（被 end_of_turn 截断）`() {
        val text = "<tool_call>\n{\"function\": \"get_time\", \"arguments\": {}}\n<end_of_turn>"
        val calls = registry().parseToolCalls(text)
        assertEquals(1, calls.size)
        assertEquals("get_time", calls[0].name)
    }

    @Test
    fun `单行 JSON 形式`() {
        val text = """<tool_call>{"function": "calculator", "arguments": {"expression": "(3+5)*2"}}</tool_call>"""
        val calls = registry().parseToolCalls(text)
        assertEquals(1, calls.size)
        assertEquals("(3+5)*2", calls[0].arguments["expression"]?.jsonPrimitive?.content)
    }

    @Test
    fun `同回合多个调用`() {
        val text = """
            <tool_call>{"function": "get_time", "arguments": {}}</tool_call>
            <tool_call>{"function": "system_info", "arguments": {}}</tool_call>
        """.trimIndent()
        val calls = registry().parseToolCalls(text)
        assertEquals(2, calls.size)
        assertEquals(listOf("get_time", "system_info"), calls.map { it.name })
    }

    @Test
    fun `未知工具名被忽略`() {
        assertTrue(registry().parseToolCalls("<tool_call>no_such_tool</tool_call>").isEmpty())
    }

    @Test
    fun `工具执行与错误兜底`() {
        val r = registry()
        val args = buildJsonObject { put("expression", "2*3") }
        val result = kotlinx.coroutines.runBlocking { r.execute("calculator", args) }
        assertTrue(result.contains("6.0"), "结果应含 6.0，实际: $result")

        val bad = kotlinx.coroutines.runBlocking { r.execute("nope", buildJsonObject {}) }
        assertTrue(bad.contains("unknown tool"))
    }
}
