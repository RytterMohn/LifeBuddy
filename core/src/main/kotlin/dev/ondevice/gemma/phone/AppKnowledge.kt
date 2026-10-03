package dev.ondevice.gemma.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale

@Serializable
data class AppSkillDescriptor(
    val id: String, val title: String, val description: String,
    val packages: Set<String>, val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(), val revision: Int = 1, val learned: Boolean = false,
)

/** Metadata is indexed once. Bodies are read only after selection, with a bounded working cache. */
class AppSkillLibrary(val descriptors: List<AppSkillDescriptor>, private val readBody: (String) -> String) {
    private val byId = descriptors.associateBy { it.id }
    private val terms = descriptors.associate { it.id to KnowledgeSearch.terms(it.title + " " + it.description + " " + it.tags.joinToString(" ")) }
    private val bodies = object : LinkedHashMap<String, String>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8
    }
    init {
        require(byId.size == descriptors.size)
        require(descriptors.all { Regex("[a-z0-9][a-z0-9._-]{0,79}").matches(it.id) && it.packages.isNotEmpty() && it.revision > 0 })
    }
    fun get(id: String) = byId[id]
    fun eligible(skill: AppSkillDescriptor, allowed: Set<String>) = skill.packages.any { it in allowed }
    @Synchronized fun body(id: String): String {
        require(id in byId)
        return bodies.getOrPut(id) { readBody(id).also { require(it.length in 1..3200) { "技能正文超出单项预算" } } }
    }
    fun search(query: String, allowed: Set<String>, packageName: String = "", limit: Int = 3): List<AppSkillDescriptor> {
        val wanted = KnowledgeSearch.terms(query)
        return descriptors.asSequence().filter { eligible(it, allowed) && (packageName.isBlank() || packageName in it.packages) }
            .map { it to (KnowledgeSearch.score(query, wanted, it.title, terms.getValue(it.id)) +
                if (it.aliases.any { alias -> KnowledgeSearch.contains(query, alias) }) 12 else 0) }
            .filter { query.isBlank() || it.second > 0 }
            .map { it.first to (it.second + if(it.first.learned) 8 else 0) }
            .sortedWith(compareByDescending<Pair<AppSkillDescriptor, Int>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceIn(1, 3)).map { it.first }.toList()
    }
    companion object {
        val bundled: AppSkillLibrary by lazy {
            val loader = AppSkillLibrary::class.java.classLoader!!
            val index = loader.getResourceAsStream("phone-skills/index.json")!!.bufferedReader().use { it.readText() }
            AppSkillLibrary(Json.decodeFromString(index)) { id ->
                loader.getResourceAsStream("phone-skills/$id.md")!!.bufferedReader().use { it.readText() }
            }
        }
    }
}

/** Unicode bigrams work for Chinese without a network tokenizer; names/aliases outrank fuzzy matches. */
internal object KnowledgeSearch {
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).trim()
    fun contains(query: String, name: String) = name.length >= 2 && normalized(query).contains(normalized(name))
    fun terms(value: String): Set<String> = buildSet {
        Regex("[a-z0-9_.-]+|[\\p{IsHan}]+").findAll(normalized(value)).forEach { match ->
            val word = match.value
            if (word.first().code > 127) { if (word.length == 1) add(word) else addAll(word.windowed(2)) }
            else add(word)
        }
    }
    fun score(query: String, wanted: Set<String>, name: String, indexed: Set<String>): Int {
        val q = normalized(query); val n = normalized(name)
        return when {
            q.isNotBlank() && q == n -> 1000
            q.length >= 2 && n.contains(q) -> 100
            n.length >= 2 && q.contains(n) -> 80
            else -> wanted.count { it in indexed }
        }
    }
}

data class PhoneKnowledgeContext(val apps: Map<String, String>, val text: String, val skillIds: List<String>)

/** The authorization map stays complete in the runner; only the model-facing catalog is narrowed. */
class PhoneKnowledge(val library: AppSkillLibrary = AppSkillLibrary.bundled) {
    private val aliases = library.descriptors.flatMap { d -> d.packages.map { it to d.aliases } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.flatten().distinct() }

    fun rankedApps(query: String, apps: Map<String, String>): List<Pair<String, String>> {
        val wanted = KnowledgeSearch.terms(query)
        return apps.map { (pkg, name) ->
            val score = maxOf(KnowledgeSearch.score(query, wanted, pkg, KnowledgeSearch.terms(pkg)),
                KnowledgeSearch.score(query, wanted, name, KnowledgeSearch.terms(name)),
                aliases[pkg].orEmpty().maxOfOrNull { KnowledgeSearch.score(query, wanted, it, KnowledgeSearch.terms(it)) } ?: 0)
            Triple(pkg, name, score)
        }.filter { query.isBlank() || it.third > 0 }
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.first })
            .map { it.first to it.second }
    }

    fun searchApps(query: String, cursor: String, apps: Map<String, String>): String {
        val ranked = rankedApps(query, apps)
        val offset = cursor.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val found = ranked.drop(offset).take(8)
        return buildJsonObject {
            put("matches", JsonArray(found.map { (pkg, name) -> buildJsonObject { put("packageName", pkg); put("name", name.take(100)) } }))
            put("total", ranked.size)
            put("nextCursor", if (offset.toLong() + found.size < ranked.size) (offset + found.size).toString() else "")
            put("note", "只搜索当前允许应用；空查询可分页浏览。候选为空时换应用全名或别名，不能据此认为整个目录为空。")
        }.toString()
    }

    fun read(action: PhoneAction, allowed: Map<String, String>): String = when (action.type) {
        PhoneActionType.LIST_APPS -> searchApps("", "", allowed)
        PhoneActionType.SEARCH_APPS -> searchApps(action.query, action.cursor, allowed)
        PhoneActionType.SEARCH_SKILLS -> buildJsonObject {
            put("skills", JsonArray(library.search(action.query, allowed.keys, action.packageName).map { d -> buildJsonObject {
                put("id", d.id); put("title", d.title.take(100)); put("description", d.description.take(300))
                put("packages", JsonArray(d.packages.filter { it in allowed }.map(::JsonPrimitive)))
                put("revision", d.revision)
            } }))
            put("note", "最多 3 项；用 phone_load_skill 按 id 读取；没有技能仍可通过当前页面和通用工具完成任务。")
        }.toString()
        PhoneActionType.LOAD_SKILL -> {
            val skill = library.get(action.skillId)
            if (skill == null || !library.eligible(skill, allowed.keys)) "未找到当前应用范围内的技能；请搜索已有技能或用通用工具观察。"
            else { library.body(skill.id); "已载入 ${skill.id}：${skill.title}。正文按当前应用与任务需要放在应用技能区，旧步骤不重复保存正文。" }
        }
        else -> error("不是应用知识工具")
    }

    fun context(input: PlannerInput): PhoneKnowledgeContext {
        val current = input.screen.packageName
        val latest = input.followUps.lastOrNull()?.text ?: input.goal
        val query = input.goal + " " + input.followUps.joinToString(" ") { it.text }
        val mentioned = rankedApps(latest, input.allowedApps).take(4)
        val previous = input.previousTurns.lastOrNull()
        val candidates = linkedMapOf<String, String>()
        fun add(pkg: String) { input.allowedApps[pkg]?.let { if (candidates.size < 8) candidates[pkg] = it.take(100) } }
        mentioned.forEach { add(it.first) }
        add(current)
        input.messageRequest?.let { add(it.packageName) }
        // Same-conversation references survive a new execution, even when the new goal is just "again".
        if (mentioned.isEmpty()) {
            input.previousTurns.lastOrNull { it.messageRequest != null }?.messageRequest?.let { add(it.packageName) }
            previous?.goal?.let { rankedApps(it, input.allowedApps).take(2).forEach { pair -> add(pair.first) } }
        }
        input.steps.asReversed().filter { it.dispatched && it.action.type == PhoneActionType.OPEN_APP }.take(2).forEach { add(it.action.packageName) }
        if (input.allowedApps.size <= 8) input.allowedApps.keys.forEach(::add)
        val explicit = input.steps.asReversed().filter { it.dispatched && it.action.type == PhoneActionType.LOAD_SKILL && it.observation.startsWith("已载入") }
            .mapNotNull { library.get(it.action.skillId) }.filter { library.eligible(it, input.allowedApps.keys) }
        // A just-requested guide must be visible on the very next planning call, even before opening its App.
        val justLoaded = input.steps.lastOrNull()?.takeIf { it.dispatched && it.action.type == PhoneActionType.LOAD_SKILL && it.observation.startsWith("已载入") }
            ?.let { library.get(it.action.skillId) }?.takeIf { library.eligible(it, input.allowedApps.keys) }
        justLoaded?.packages?.forEach(::add)
        val currentMatches = library.search(query, input.allowedApps.keys, current)
        val matched = library.search(query, candidates.keys)
        // Prefer current-App knowledge; do not drag entire prior App guides across every transition.
        val currentGuides = explicit.filter { current in it.packages } + currentMatches
        val selected = (listOfNotNull(justLoaded) + if (currentGuides.isNotEmpty()) currentGuides else
            explicit.filter { d -> d.packages.any { it in candidates } } + matched).distinctBy { it.id }.take(2)
        val included = mutableListOf<String>()
        val text = buildString {
            selected.forEach { skill ->
                val block = "技能 ${skill.id} / 修订 ${skill.revision}：${skill.title}\n${library.body(skill.id)}\n"
                if (length + block.length <= 6000) { append(block); included += skill.id }
            }
        }
        return PhoneKnowledgeContext(candidates, text, included)
    }
}
