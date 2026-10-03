package dev.ondevice.gemma.skills

import java.io.File

/**
 * 技能包（对齐 pi / Agent Skills 规范）：
 *   skills/<name>/SKILL.md，frontmatter 声明 name / description / when_to_use，
 *   正文是加载后注入上下文的工作流说明。
 *
 * 渐进式披露：系统提示中只列出技能名与描述，模型判断需要时
 * 通过 read_skill 工具（或直接由 harness 预载）读取全文。
 */
data class Skill(
    val name: String,
    val description: String,
    val whenToUse: String,
    val body: String,
    /** 技能目录下的辅助资源/脚本路径 */
    val baseDir: File? = null,
)

/** 技能库：扫描指定目录，解析 SKILL.md */
class SkillLibrary(private val roots: List<File>) {

    private val skills: Map<String, Skill> by lazy {
        roots.flatMap { discover(it) }.associateBy { it.name }
    }

    fun all(): List<Skill> = skills.values.toList()

    fun get(name: String): Skill? = skills[name]

    /** 只给描述列表，用于注入系统提示（渐进式披露第一步） */
    fun summaries(): String {
        if (skills.isEmpty()) return ""
        return skills.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    private fun discover(root: File): List<Skill> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val md = File(dir, "SKILL.md")
            if (!md.exists()) return@mapNotNull null
            parse(md, dir)
        } ?: emptyList()
    }

    private fun parse(md: File, dir: File): Skill? {
        val text = md.readText()
        // 极简 frontmatter 解析：--- 块内的 key: value
        val fmMatch = Regex("^---\\s*\\n(.*?)\\n---", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(text) ?: return null
        val fm = fmMatch.groupValues[1].lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

        val name = fm["name"] ?: return null
        val description = fm["description"] ?: return null
        val body = text.substring(fmMatch.range.last + 1).trim()
        return Skill(
            name = name,
            description = description,
            whenToUse = fm["when_to_use"] ?: "",
            body = body,
            baseDir = dir,
        )
    }

    companion object {
        /** 解析单个 SKILL.md（供 Android assets 流式读取复用） */
        fun parseText(text: String, name: String, description: String): Skill? {
            val fmMatch = Regex("^---\\s*\\n(.*?)\\n---", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(text) ?: return Skill(name, description, "", text.trim())
            val body = text.substring(fmMatch.range.last + 1).trim()
            return Skill(name, description, "", body)
        }
    }
}
