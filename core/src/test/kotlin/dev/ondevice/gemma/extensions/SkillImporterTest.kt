package dev.ondevice.gemma.extensions

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SkillImporterTest {
    private fun md(name: String = "daily-plan", body: String = "Make a short daily plan.") = "---\nname: $name\ndescription: Plan a day / 每日计划\n---\n$body"
    private fun archive(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()

    @Test fun `markdown supports Unicode body BOM CRLF and survives persistence`() {
        val skill = SkillImporter.import(("\uFEFF" + md(body = "步骤：先确定目标。\nKeep original text.").replace("\n", "\r\n")).toByteArray(), "SKILL.md").single()
        assertEquals("daily-plan", skill.name); assertContains(skill.body, "步骤")
        assertEquals(skill, Json.decodeFromString<ImportedSkill>(Json.encodeToString(skill)))
        assertEquals(64, skill.digest.length)
    }
    @Test fun `folded descriptions quoted values and nested metadata are supported`() {
        val text = "---\nname: 'trip-plan'\ndescription: >-\n  Plan trips\n  and budgets.\nmetadata:\n  author: sample\ncompatibility: \"Android tools\"\n---\nUse available tools."
        val skill = SkillImporter.import(text.toByteArray(), "SKILL.md").single()
        assertEquals("Plan trips and budgets.", skill.description); assertEquals("Android tools", skill.compatibility)
    }
    @Test fun `zip imports multiple skills and keeps references isolated`() {
        val skills = SkillImporter.import(archive("a/SKILL.md" to md("a"), "a/references/guide.md" to "Guide A",
            "a/scripts/main.py" to "print('never runs')", "b/SKILL.md" to md("b"), "b/references/guide.md" to "Guide B"), "bundle.zip")
        assertEquals(2, skills.size); assertTrue(skills[0].hasScripts)
        assertEquals(mapOf("references/guide.md" to "Guide A"), skills[0].resources)
        assertEquals(mapOf("references/guide.md" to "Guide B"), skills[1].resources)
    }
    @Test fun `unsafe paths and malformed packages are rejected before storage`() {
        for (name in listOf("../SKILL.md", "/SKILL.md", "a/../SKILL.md", "C:/SKILL.md", "a\\SKILL.md"))
            assertFails { SkillImporter.import(archive(name to md()), "bad.zip") }
        assertFails { SkillImporter.import("plain instructions".toByteArray(), "SKILL.md") }
        assertFails { SkillImporter.import(md("../evil").toByteArray(), "SKILL.md") }
        assertFails { SkillImporter.import(md("A").toByteArray(), "SKILL.md") }
        assertFails { SkillImporter.import(archive("a/SKILL.md" to md(), "b/SKILL.md" to md()), "duplicate.zip") }
        assertFails { SkillImporter.import(byteArrayOf(0xc3.toByte(), 0x28), "SKILL.md") }
    }
    @Test fun `archive bomb and oversized input are bounded`() {
        assertFails { SkillImporter.import(ByteArray(SkillImporter.MAX_IMPORT_BYTES + 1), "SKILL.md") }
        assertFails { SkillImporter.import(archive("a/SKILL.md" to md(body = "x".repeat(150_000))), "large.zip") }
        assertFails { SkillImporter.import(archive(*(1..130).map { "file$it.txt" to "a" }.toTypedArray()), "many.zip") }
    }
    @Test fun `reference reads paginate without exposing unrelated files`() {
        val skill = SkillImporter.import(archive("x/SKILL.md" to md(body = "a".repeat(13_000)), "x/references/doc.md" to "Reference"), "x.zip").single()
        var cursor = ""; var combined = ""
        do {
            val page = Json.parseToJsonElement(ExtensionCatalog.readSkill(skill, "", cursor)).jsonObject
            combined += page.getValue("content").jsonPrimitive.content
            assertTrue(page.getValue("content").jsonPrimitive.content.length <= ExtensionCatalog.PAGE_CHARS)
            cursor = page.getValue("nextCursor").jsonPrimitive.content
        } while (cursor.isNotEmpty())
        assertEquals(skill.body, combined)
        assertFails { ExtensionCatalog.readSkill(skill, "../secret", "") }
        assertFails { ExtensionCatalog.readSkill(skill.copy(enabled = false), "", "") }
    }
    @Test fun `a thousand plugin tools only disclose five summaries with no schema bodies`() {
        val schema = buildJsonObject { put("type", "object"); put("description", "SCHEMA_BODY_NEVER_PRELOADED") }
        val state = ExtensionState(listOf(ImportedSkill("writing", "Writing", "BODY_NEVER_PRELOADED")),
            listOf(McpServer("srv", "Tools", "https://example.test/mcp", tools = (1..1000).map { McpTool("tool$it", "Tool $it", schema) })))
        val seen = mutableSetOf<String>(); var cursor = ""
        do {
            val text = ExtensionCatalog.search(state, "", cursor, true)
            assertFalse(text.contains("NEVER_PRELOADED")); assertTrue(text.length < 2400)
            val result = Json.parseToJsonElement(text).jsonObject
            val items = result.getValue("items").jsonArray
            assertTrue(items.size <= 5); items.forEach { assertTrue(seen.add(it.jsonObject.getValue("id").jsonPrimitive.content)) }
            cursor = result.getValue("nextCursor").jsonPrimitive.content
        } while (cursor.isNotEmpty())
        assertEquals(1001, seen.size)
        val chat = Json.parseToJsonElement(ExtensionCatalog.search(state, "", "", false)).jsonObject
        assertEquals(1, chat.getValue("total").jsonPrimitive.int)
        val disabled = state.copy(skills = state.skills.map { it.copy(enabled = false) }, servers = state.servers.map { it.copy(enabled = false) })
        assertEquals(0, Json.parseToJsonElement(ExtensionCatalog.search(disabled, "", "", true)).jsonObject.getValue("total").jsonPrimitive.int)
    }
}
