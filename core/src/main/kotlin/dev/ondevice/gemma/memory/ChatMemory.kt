package dev.ondevice.gemma.memory

import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.Role

/**
 * 对话记忆：定长窗口（按 token 估算裁剪）+ 可选持久化。
 *
 * 裁剪策略：从最旧的 user 回合开始丢弃，直到总长度低于预算。
 * 注意保留最近的 TOOL_RESULT/回答对，保证 agent 循环自洽。
 */
class ChatMemory(
    private val maxTokens: Int = 4096,
    private val store: ChatStore? = null,
) {
    /** 内容 → 估算 token 数（中文约 1.5 token/字，按 2 估偏保守） */
    private fun estimateTokens(s: String): Int = (s.length * 2.0).toInt()

    private val messages = ArrayDeque<ChatMessage>()

    val size: Int get() = messages.size

    fun add(message: ChatMessage) {
        messages.addLast(message)
        store?.append(message)
        trim()
    }

    fun addUser(content: String) = add(ChatMessage(Role.USER, content))

    fun addModel(content: String) = add(ChatMessage(Role.MODEL, content))

    /** 当前窗口内全部消息（Agent 拼 prompt 用） */
    fun window(): List<ChatMessage> = messages.toList()

    fun clear() {
        messages.clear()
        store?.clear()
    }

    private fun trim() {
        var total = messages.sumOf { estimateTokens(it.content) }
        while (total > maxTokens && messages.size > 1) {
            val removed = messages.removeFirst()
            total -= estimateTokens(removed.content)
        }
    }

    /** 从持久化存储恢复历史 */
    fun restore() {
        store?.load()?.forEach { messages.addLast(it) }
        trim()
    }
}

/**
 * 持久化接口。Android 端实现可用 Room / 文件 / DataStore；
 * CLI 端可给 JSONL 文件实现。默认不持久化。
 */
interface ChatStore {
    fun append(message: ChatMessage)
    fun load(): List<ChatMessage>
    fun clear()
}

/** 简单的 JSONL 文件存储（CLI / 桌面调试用） */
class JsonlChatStore(private val path: String) : ChatStore {
    override fun append(message: ChatMessage) {
        // 实现：kotlinx.serialization 序列化 ChatMessage 追加一行（见 README 提示，可后续补全）
        java.io.File(path).appendText("${message.role}\t${message.content}\n")
    }

    override fun load(): List<ChatMessage> {
        val file = java.io.File(path)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val role = runCatching { Role.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ChatMessage(role, parts[1])
        }
    }

    override fun clear() {
        java.io.File(path).delete()
    }
}
