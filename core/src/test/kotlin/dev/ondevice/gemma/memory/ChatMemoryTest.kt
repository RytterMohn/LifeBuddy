package dev.ondevice.gemma.memory

import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMemoryTest {

    @Test
    fun `窗口裁剪保留最近消息`() {
        val memory = ChatMemory(maxTokens = 100)
        repeat(20) { i ->
            memory.addUser("这是第${i}条很长很长的用户消息内容，用来撑大窗口")
            memory.addModel("这是第${i}条很长很长的模型回复内容，用来撑大窗口")
        }
        assertTrue(memory.size < 40, "应裁剪到预算内，实际 ${memory.size} 条")
        // 最新的 user 消息必须保留
        val last = memory.window().lastOrNull { it.role == Role.USER }
        assertEquals("这是第19条很长很长的用户消息内容，用来撑大窗口", last?.content)
    }

    @Test
    fun `清空`() {
        val memory = ChatMemory(maxTokens = 100)
        memory.addUser("hi")
        memory.clear()
        assertEquals(0, memory.size)
        assertTrue(memory.window().isEmpty())
    }

    @Test
    fun `顺序保持`() {
        val memory = ChatMemory(maxTokens = 10000)
        memory.addUser("u1")
        memory.addModel("m1")
        memory.addUser("u2")
        assertEquals(
            listOf("u1", "m1", "u2"),
            memory.window().map { it.content },
        )
    }
}

private fun assertTrue(condition: Boolean, message: String? = null) {
    if (!condition) throw AssertionError(message ?: "expected true")
}
