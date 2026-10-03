package dev.ondevice.gemma.memory

import kotlin.test.*

class MemoryBookTest {
    @Test fun `replace and delete use stable ids and preserve unrelated facts`() {
        val first = MemoryBook.update(emptyList(), "add", "profile", "", "偏好中文", "test")
        val second = MemoryBook.update(first, "add", "notes", "", "手机是红米", "test")
        val changed = MemoryBook.update(second, "replace", "profile", first.single().id, "偏好简体中文", "test")
        assertEquals(first.single().id, changed.last().id)
        assertEquals(2, changed.size)
        assertEquals("手机是红米", MemoryBook.update(changed, "remove", "", first.single().id, "", "test").single().text)
    }
    @Test fun `overflow does not silently evict and duplicate write is idempotent`() {
        var entries = MemoryBook.update(emptyList(), "add", "profile", "", "甲".repeat(600), "test")
        assertEquals(entries, MemoryBook.update(entries, "add", "profile", "", "甲".repeat(600), "test"))
        entries = MemoryBook.update(entries, "add", "profile", "", "乙".repeat(600), "test")
        assertFails { MemoryBook.update(entries, "add", "profile", "", "丙".repeat(600), "test") }
        assertEquals(2, entries.size)
    }
    @Test fun `credentials and invisible controls are not memories`() {
        for (value in listOf("密码：abcd", "sk-abcdefghijklmnopqrst", "偏好\u202e中文")) {
            assertFails { MemoryBook.update(emptyList(), "add", "notes", "", value, "test") }
        }
    }
    @Test fun `add cannot duplicate an existing id`() {
        val entries = MemoryBook.update(emptyList(), "add", "profile", "", "中文", "test")
        assertFails { MemoryBook.update(entries, "add", "profile", entries.single().id, "英文", "test") }
    }
    @Test fun `unknown replace id cannot create another fact`() {
        assertFails { MemoryBook.update(emptyList(), "replace", "profile", "missing", "中文", "test") }
    }
}
