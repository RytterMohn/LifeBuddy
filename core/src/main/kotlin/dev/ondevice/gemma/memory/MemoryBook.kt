package dev.ondevice.gemma.memory

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MemoryEntry(val id: String, val category: String, val text: String, val source: String, val updatedAt: Long)

/** Bounded, curated facts. Updating an existing ID replaces a fact; overflow never evicts silently. */
object MemoryBook {
    const val PROFILE_LIMIT = 1600
    const val NOTES_LIMIT = 2400

    fun update(entries: List<MemoryEntry>, operation: String, category: String, id: String, text: String,
               source: String, now: Long = System.currentTimeMillis()): List<MemoryEntry> {
        require(operation in setOf("add", "replace", "remove")) { "记忆操作须为 add、replace 或 remove" }
        if (operation == "add") require(id.isBlank()) { "新增记忆的 ID 必须为空；修改已有记忆请使用 replace" }
        val existing = entries.find { it.id == id }
        if (operation != "add") require(existing != null) { "记忆 ID 不存在，请重新读取记忆列表" }
        if (operation == "remove") return entries.filterNot { it.id == id }
        require(category in setOf("profile", "notes")) { "记忆分类无效" }
        val clean = text.trim()
        require(clean.length in 1..600) { "每条记忆须为 1–600 字符" }
        require(!sensitive.containsMatchIn(clean) && clean.none { Character.getType(it) == Character.FORMAT.toInt() }) {
            "请勿在长期记忆中保存密钥、密码、验证码或隐藏控制字符"
        }
        if (entries.any { it.id != id && it.text == clean && it.category == category }) return entries
        val entry = MemoryEntry(existing?.id ?: UUID.randomUUID().toString(), category, clean, source.take(120), now)
        val result = entries.filterNot { operation == "replace" && it.id == id } + entry
        require(result.size <= 32) { "记忆最多 32 条，请先合并或删除旧记忆" }
        require(result.filter { it.category == "profile" }.sumOf { it.text.length } <= PROFILE_LIMIT &&
            result.filter { it.category == "notes" }.sumOf { it.text.length } <= NOTES_LIMIT) { "记忆容量已满，请先替换或删除旧记忆" }
        return result
    }

    private val sensitive = Regex("(?i)(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._-]+|(?:api[_ -]?key|密码|验证码|密钥|token|password)\\s*[:：=]\\s*\\S+)")
}
