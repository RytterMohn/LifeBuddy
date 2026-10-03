package dev.ondevice.gemma.learning

import dev.ondevice.gemma.phone.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class NavigationHint(val packageName: String, val version: String, val appName: String = "", val label: String = "", val resourceId: String = "")

@Serializable
data class LearnedStep(val action: PhoneActionType, val label: String = "", val resourceId: String = "", val direction: String = "")

@Serializable
data class LearningEntry(
    val id: String, val key: String, val kind: String, val text: String,
    val packageName: String = "", val version: String = "", val appName: String = "",
    val steps: List<LearnedStep> = emptyList(), val status: String = "pending",
    val sourceRuns: List<String> = emptyList(), val successes: Int = 0, val failures: Int = 0,
    val recentFailures: Int = 0, val assessedRuns: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LearningState(val entries: List<LearningEntry> = emptyList(), val dismissed: Set<String> = emptySet())

data class LearningContext(val text: String, val ids: List<String>)

/** Task-scoped evidence, not a recording service or an executable macro. */
object LearningBook {
    const val LIMIT = 64
    private val stable = Regex("平时|通常|一般|习惯|喜欢|偏好|总是|每次|以后|一直|prefer|usually|always", RegexOption.IGNORE_CASE)
    private val temporary = Regex("这次|今天|暂时|仅本次|this time|today", RegexOption.IGNORE_CASE)
    private val privateData = Regex("[0-9]{6,}|[\\w.+-]+@[\\w.-]+|密码|验证码|密钥|口令|身份证|银行卡|住址|收货地址|家庭地址|password|api.?key|bearer|token|过敏|病史|疾病|宗教|政治立场", RegexOption.IGNORE_CASE)
    private val risky = Regex("跳过.{0,8}确认|忽略.{0,8}(规则|指令)|无需确认|自动付款|自动支付|ignore.{0,12}instruction|skip.{0,12}confirm", RegexOption.IGNORE_CASE)
    private val navActions = setOf(PhoneActionType.TAP, PhoneActionType.TYPE, PhoneActionType.SUBMIT_SEARCH, PhoneActionType.SCROLL, PhoneActionType.BACK)
    fun safe(text: String) = text.isNotBlank() && !privateData.containsMatchIn(text) && !risky.containsMatchIn(text) && text.none { Character.getType(it) == Character.FORMAT.toInt() }
    private fun key(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun habitKey(quote: String, pkg: String) = key("habit|$pkg|${quote.trim()}")
    private fun add(state: LearningState, entry: LearningEntry): LearningState {
        if (entry.key in state.dismissed) return state
        val old = state.entries.find { it.key == entry.key }
        if (old != null) {
            val next = old.copy(sourceRuns = (old.sourceRuns + entry.sourceRuns).distinct().takeLast(12), updatedAt = entry.updatedAt)
            return state.copy(entries = state.entries.map { if (it.id == old.id) next else it })
        }
        // A full notebook never silently evicts an approved preference or experience.
        if (state.entries.size >= LIMIT) return state
        return state.copy(entries = state.entries + entry)
    }

    fun proposeHabit(state: LearningState, userStatements: List<String>, quote: String, pkg: String, runId: String, now: Long = System.currentTimeMillis()): LearningState {
        require(quote.length in 2..160 && userStatements.any { quote in it }) { "偏好须来自本次用户原话" }
        require(stable.containsMatchIn(quote) && !temporary.containsMatchIn(quote) && safe(quote)) { "只记录稳定、非敏感的偏好候选；本次临时要求不作为习惯" }
        val k = habitKey(quote, pkg)
        return add(state, LearningEntry(UUID.randomUUID().toString(), k, "habit", quote.trim(), packageName = pkg, sourceRuns = listOf(runId), updatedAt = now))
    }

    /** Capture only labels used for navigation. Never save typed values, message bodies or node IDs. */
    fun navigation(action: PhoneAction, screen: ScreenSnapshot, goal: String, appName: String): NavigationHint? {
        if (action.type !in navActions || screen.appVersion.isBlank()) return null
        val node = screen.nodes.find { it.id == action.nodeId }
        if (node != null && (MessagePolicy.isSendControl(node, screen) || MessagePolicy.isRestrictedControl(node, screen))) return null
        val label = if (action.type == PhoneActionType.TYPE || node?.editable == true) "" else {
            val children = screen.nodes.filter { it.clickTargetId == node?.id }
            (listOfNotNull(node) + children).flatMap { listOf(it.text, it.description, it.hint) }
                .firstOrNull { it.length in 1..40 && safe(it) && !(it.length > 3 && it in goal) }.orEmpty()
        }
        val rid = node?.resourceId.orEmpty().takeIf { it.length <= 120 && safe(it) && it.startsWith(screen.packageName + ":id/") }.orEmpty()
        return NavigationHint(screen.packageName, screen.appVersion, appName.take(60), label, rid)
    }

    fun recordOutcome(state: LearningState, run: PhoneRun, now: Long = System.currentTimeMillis()): LearningState {
        val success = run.status == RunStatus.COMPLETED && run.steps.lastOrNull()?.action?.type == PhoneActionType.FINISH
        val failed = run.status == RunStatus.FAILED || (run.status == RunStatus.PAUSED && run.steps.lastOrNull()?.action?.type != PhoneActionType.ASK_USER &&
            !run.message.contains("用户") && !run.message.contains("无障碍服务已断开"))
        if (!success && !failed) return state
        val assessmentId = run.executionId.ifBlank { run.id }
        var result = state.copy(entries = state.entries.map { entry ->
            val referencedHere = run.steps.any { entry.id in it.action.experienceIds && it.navigation?.packageName == entry.packageName && it.navigation.version == entry.version }
            if (entry.kind != "app" || !referencedHere || assessmentId in entry.assessedRuns) entry
            else entry.copy(successes = entry.successes + if (success) 1 else 0, failures = entry.failures + if (failed) 1 else 0,
                recentFailures = if (success) 0 else entry.recentFailures + 1,
                status = if (failed && entry.recentFailures >= 1 && entry.status == "active") "review" else entry.status,
                assessedRuns = (entry.assessedRuns + assessmentId).takeLast(20), updatedAt = now)
        })
        // Conversation payloads and recipients are not navigation knowledge.
        if (!success || run.messageRequest != null || run.id.isBlank()) return result
        val grouped = run.steps.filter { it.dispatched && it.navigation != null && it.action.type in navActions }.groupBy { it.navigation!!.packageName }
        for ((pkg, steps) in grouped) {
            val hints = steps.mapNotNull { it.navigation }
            if (steps.size !in 2..12 || hints.any { it.version.isBlank() } || hints.map { it.version }.distinct().size != 1 || pkg == "dev.ondevice.gemma.app") continue
            val route = steps.map { step -> LearnedStep(step.action.type, step.navigation!!.label, step.navigation.resourceId,
                if (step.action.type == PhoneActionType.SCROLL) step.action.direction else "") }
            if (route.none { it.label.isNotBlank() || it.resourceId.isNotBlank() }) continue
            val sample = hints.first()
            val purpose = if (route.any { it.action == PhoneActionType.SUBMIT_SEARCH } || route.any { it.label.contains("搜索") }) "搜索内容" else "页面操作"
            val title = "${sample.appName.ifBlank { pkg }} · $purpose"
            val k = key("app|$pkg|${sample.version}|${Json.encodeToString(route)}")
            result = add(result, LearningEntry(UUID.randomUUID().toString(), k, "app", title, pkg, sample.version, sample.appName,
                route, "active", sourceRuns = listOf(run.id), updatedAt = now))
        }
        return result
    }

    fun change(state: LearningState, id: String, operation: String, text: String = ""): LearningState {
        val entry = state.entries.firstOrNull { it.id == id } ?: error("经验不存在，请刷新列表")
        if (operation == "delete") return state.copy(entries = state.entries.filterNot { it.id == id }, dismissed = (state.dismissed + entry.key).toList().takeLast(128).toSet())
        require(operation in setOf("activate", "disable", "edit"))
        if (operation == "edit") require(entry.kind == "habit" && text.length in 2..160 && safe(text)) { "请填写非敏感的偏好内容" }
        return state.copy(entries = state.entries.map { if (it.id != id) it else it.copy(
            text = if (operation == "edit") text.trim() else it.text,
            status = if (operation == "disable") "disabled" else "active", recentFailures = if (operation == "activate") 0 else it.recentFailures,
            updatedAt = System.currentTimeMillis()) })
    }

    fun context(state: LearningState, goal: String, currentPackage: String, allowed: Set<String>, version: (String) -> String?): LearningContext {
        val habits = state.entries.filter { it.kind == "habit" && it.status == "active" && (it.packageName.isEmpty() || (it.packageName == currentPackage && it.packageName in allowed)) }.sortedByDescending { it.updatedAt }.take(8)
        val skills = state.entries.filter { it.kind == "app" && it.status == "active" && it.packageName in allowed && it.version == version(it.packageName) }
            .filter { it.packageName == currentPackage || (it.appName.isNotBlank() && goal.contains(it.appName)) }
            .sortedByDescending { (if (it.packageName == currentPackage) 10 else 0) + it.successes - it.failures }.take(2)
        val text = StringBuilder()
        if (habits.isNotEmpty()) text.appendLine("用户已确认的偏好（仅限标注应用，当前要求优先）：" + Json.encodeToString(habits.map { mapOf("preference" to it.text, "app" to it.packageName) }))
        val included = mutableListOf<String>()
        for (skill in skills) {
            val block = "同版本 App 的已完成任务经验（仅供参考，必须重新观察并匹配当前节点，不能照序重放）：" +
                Json.encodeToString(mapOf("id" to skill.id, "app" to skill.packageName, "title" to skill.text, "steps" to Json.encodeToString(skill.steps))) + "\n"
            if (text.length + block.length <= 5000) { text.append(block); included += skill.id }
        }
        return LearningContext(text.toString(), included)
    }
}
