package dev.ondevice.gemma.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Never extracts archives onto the filesystem, and bounds both compressed and inflated data. */
object SkillImporter {
    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    private const val MAX_FILE = 128 * 1024
    private const val MAX_EXPANDED = 2 * 1024 * 1024
    fun import(bytes: ByteArray, filename: String): List<ImportedSkill> {
        require(bytes.size <= MAX_IMPORT_BYTES) { "Skill package exceeds 2 MB / 技能包超过 2 MB" }
        val zip = bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()
        if (!zip) {
            require(filename.endsWith(".md", true)) { "Choose SKILL.md or a ZIP / 请选择 SKILL.md 或 ZIP" }
            require(bytes.size <= MAX_FILE)
            return listOf(parse(decode(bytes), emptyMap(), false))
        }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                require(++entries <= 128) { "Too many archive entries / 压缩包文件过多" }
                val name = entry.name
                require(name.length in 1..240 && !name.startsWith('/') && '\\' !in name && ':' !in name &&
                    name.split('/').none { it == ".." || it == "." } && name.none { it.code < 32 }) { "Unsafe archive path / 压缩包路径无效" }
                if (!entry.isDirectory) {
                    require(name !in files) { "Duplicate archive entry / 压缩包包含重名文件" }
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, MAX_FILE + 1 - out.size()))
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        if (out.size() > MAX_FILE) break
                    }
                    val data = out.toByteArray()
                    require(data.size <= MAX_FILE) { "A package file exceeds 128 KB / 包内文件超过 128 KB" }
                    total += data.size
                    require(total <= MAX_EXPANDED) { "Expanded archive too large / 解压后内容过大" }
                    files[name] = data
                }
                stream.closeEntry()
            }
        }
        val roots = files.keys.filter { it.substringAfterLast('/') == "SKILL.md" }
        require(roots.size in 1..20) { "ZIP must contain 1–20 SKILL.md files / ZIP 须包含 1–20 份 SKILL.md" }
        val imported = roots.map { root ->
            val prefix = root.removeSuffix("SKILL.md")
            val children = files.filterKeys { key -> key.startsWith(prefix) && key != root &&
                roots.none { other -> other != root && other.startsWith(prefix) && key.startsWith(other.removeSuffix("SKILL.md")) } }
            val resources = children.filterKeys { key -> key.substringAfterLast('.').lowercase() in setOf("md", "txt", "json", "yaml", "yml", "csv") }
                .mapKeys { it.key.removePrefix(prefix) }.mapValues { decode(it.value) }
            require(resources.size <= 32) { "Too many reference files / 参考文件过多" }
            parse(decode(files.getValue(root)), resources, children.keys.any {
                it.removePrefix(prefix).startsWith("scripts/") || it.substringAfterLast('.').lowercase() in setOf("py", "js", "sh", "ts", "mjs")
            })
        }
        require(imported.map { it.name }.distinct().size == imported.size) { "Duplicate skill names / 技能名称重复" }
        return imported
    }

    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").replace("\r\n", "\n")

    private fun parse(text: String, resources: Map<String, String>, hasScripts: Boolean): ImportedSkill {
        val lines = text.lines()
        require(lines.firstOrNull()?.trim() == "---") { "SKILL.md needs YAML name and description / SKILL.md 须含 name 和 description 头部" }
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" }
            ?: error("Unclosed YAML header / YAML 头部未结束")
        val metadata = linkedMapOf<String, String>()
        var index = 1
        while (index < end) {
            val line = lines[index++]
            if (line.isBlank() || line.trimStart().startsWith('#') || line.first().isWhitespace()) continue
            val match = Regex("([A-Za-z][A-Za-z0-9_-]*):\\s*(.*)").matchEntire(line)
                ?: error("Unsupported YAML header / 不支持的 YAML 头部")
            val (key, raw) = match.destructured
            require(key !in metadata) { "Duplicate YAML key / YAML 字段重复" }
            val continuation = mutableListOf<String>()
            while (index < end && (lines[index].isBlank() || lines[index].first().isWhitespace())) continuation += lines[index++]
            if (key !in setOf("name", "description", "compatibility")) { metadata[key] = ""; continue }
            val value = when {
                raw.trim() in setOf(">", ">-", ">+", "|", "|-", "|+") -> continuation.joinToString(if (raw.startsWith('>')) " " else "\n") { it.trim() }.trim()
                raw.trim().startsWith('"') -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw.trim()).let { (it as kotlinx.serialization.json.JsonPrimitive).content } }
                    .getOrElse { error("Unsupported quoted YAML value / YAML 引号内容无效") }
                raw.trim().startsWith('\'') -> raw.trim().let { require(it.endsWith('\'') && it.length >= 2); it.substring(1, it.length - 1).replace("''", "'") }
                else -> { require(raw.trim().firstOrNull() !in listOf('&', '*', '!', '[', '{')); (raw.substringBefore(" #") + " " + continuation.joinToString(" ") { it.trim() }).trim() }
            }
            metadata[key] = value
        }
        val name = metadata["name"].orEmpty()
        val description = metadata["description"].orEmpty()
        require(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(name) && name.length <= 64) { "Skill name must use lowercase letters, digits and hyphens / 技能名须为小写字母、数字和连字符" }
        require(description.isNotBlank() && description.length <= 1024) { "Skill description is missing or too long / 技能描述缺失或过长" }
        val body = lines.drop(end + 1).joinToString("\n").trim()
        require(body.isNotBlank()) { "Skill instructions are empty / 技能正文为空" }
        val digest = MessageDigest.getInstance("SHA-256").digest((text + resources.toSortedMap()).toByteArray()).joinToString("") { "%02x".format(it) }
        return ImportedSkill(name, description, body, resources, metadata["compatibility"].orEmpty().take(500), hasScripts, digest = digest)
    }
}
