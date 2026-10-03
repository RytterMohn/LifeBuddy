package dev.ondevice.gemma.learning

import dev.ondevice.gemma.phone.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SkillRevision(
    val number: Int, val version: String, val steps: List<SkillStepEvidence>, val completion: SkillCompletion,
    val fingerprint: String, val sourceRuns: List<String>, val executions: List<String>, val variants: List<String>,
    val successes: Int = 1, val failures: Int = 0, val consecutiveFailures: Int = 0,
    val assessedExecutions: List<String> = emptyList(), val notes: String = "", val createdAt: Long = System.currentTimeMillis(),
)
@Serializable
data class AutoSkill(
    val id: String, val family: String, val packageName: String, val appName: String, val title: String,
    val status: String = "trial", val revisions: List<SkillRevision>, val updatedAt: Long = System.currentTimeMillis(),
) {
    val current: SkillRevision get() = revisions.last()
    val reference: String get() = "$id.r${current.number}"
}
@Serializable
data class SkillJob(val id: String, val family: String, val packageName: String, val appName: String,
    val executionId: String, val conversationId: String, val steps: List<SkillStepEvidence>, val completion: SkillCompletion,
    val variant: String, val fingerprint: String)
@Serializable
data class AutoSkillState(val schema: Int = 1, val skills: List<AutoSkill> = emptyList(), val jobs: List<SkillJob> = emptyList(),
    val dismissed: Set<String> = emptySet(), val processed: List<String> = emptyList())

/** Pure state transitions: durable queue, evidence-bound compilation, revision history and tombstones. */
object AutoSkillBook {
    const val LIMIT = 256
    fun validate(state: AutoSkillState) {
        require(state.schema==1 && state.skills.size<=LIMIT && state.jobs.size<=32)
        require(state.skills.map { it.id }.distinct().size==state.skills.size && state.jobs.map { it.id }.distinct().size==state.jobs.size)
        state.skills.forEach { s ->
            require(s.status in setOf("trial", "verified", "review", "disabled") && s.revisions.size in 1..5)
            require(s.id.matches(Regex("learned\\.[a-f0-9]{32}")) && s.revisions.all { it.steps.size in 1..20 && it.completion.checkedOnPage && it.notes.length<=1200 })
        }
    }
    private fun candidate(run: PhoneRun): SkillJob? {
        if(run.status!=RunStatus.COMPLETED || run.steps.lastOrNull()?.action?.type!=PhoneActionType.FINISH || run.id.isBlank()) return null
        val completion=run.steps.last().skillCompletion?.takeIf { it.checkedOnPage } ?: return null
        if(completion.packageName=="dev.ondevice.gemma.app") return null
        // A whole-task success does not certify every earlier App segment. Learn only the final verified segment.
        val boundary=run.steps.indexOfLast { it.dispatched && it.action.type in setOf(PhoneActionType.OPEN_APP,PhoneActionType.OPEN_SETTINGS) }
        val observed=run.steps.drop((boundary+1).coerceAtLeast(0)).filter { it.dispatched }
            .mapNotNull { it.skillEvidence }.filter { it.packageName==completion.packageName && it.version==completion.version }
        val trace=mutableListOf<SkillStepEvidence>()
        observed.forEach { step ->
            val back=if(step.action==PhoneActionType.BACK && step.afterPage.isNotBlank()) trace.indexOfLast { it.beforePage==step.afterPage } else -1
            // Remove only a proven navigation detour. Never erase typing, submissions or side effects as "undo".
            if(back>=0 && trace.drop(back).all { it.action in setOf(PhoneActionType.TAP,PhoneActionType.BACK,PhoneActionType.SCROLL) }) {
                while(trace.size>back) trace.removeAt(trace.lastIndex)
            } else trace+=step
        }
        if(trace.size !in 2..20 || trace.none { it.label.isNotBlank() || it.resourceId.isNotBlank() } ||
            trace.any { it.action==PhoneActionType.TYPE && !it.inputReadBack }) return null
        if(completion.kind.startsWith("message") && run.messageRequest==null) return null
        val execution=run.executionId.ifBlank { run.id }
        val family=AutoSkillTrace.digest("${completion.packageName}|${completion.kind}|${completion.scope}")
        val clean=trace.map {
            val sanitized=it.copy(valueDigest="", pageChanged=false, inputReadBack=false,beforePage="",afterPage="")
            if(it.action==PhoneActionType.SUBMIT_SEARCH || (it.action==PhoneActionType.TAP && it.label.lowercase() in setOf("搜索","查找","search")))
                sanitized.copy(action=PhoneActionType.SUBMIT_SEARCH,label="",resourceId="") else sanitized
        }.fold(mutableListOf<SkillStepEvidence>()) { result, step ->
            // An IME submission followed by the visible search button is one semantic search.
            // Never coalesce typing, sends, or submissions separated by another action.
            if(step.action!=PhoneActionType.SUBMIT_SEARCH || result.lastOrNull()?.action!=PhoneActionType.SUBMIT_SEARCH) result+=step
            result
        }
        // Result wording/counts and equivalent submit controls do not create a different skill.
        val fingerprint=AutoSkillTrace.digest(Json.encodeToString(clean)+Json.encodeToString(completion.copy(evidencePattern="")))
        val variant=AutoSkillTrace.digest(trace.map { it.valueDigest }.filter { it.isNotBlank() }.joinToString("|"))
        return SkillJob("$execution|${completion.packageName}", family, completion.packageName, trace.first().appName,
            execution, run.id, clean, completion, variant, fingerprint)
    }
    fun record(state: AutoSkillState, run: PhoneRun): AutoSkillState {
        val execution=run.executionId.ifBlank { run.id }
        // Only local target/page mismatch is negative route evidence; cancellation, questions and API errors are not.
        val failed=run.status in setOf(RunStatus.PAUSED, RunStatus.FAILED)
        val failure=if(failed) run.steps.lastOrNull()?.takeIf { it.skillFailure && it.skillEvidence!=null } else null
        var result=if(failure==null) state else state.copy(skills=state.skills.map { skill ->
            val revision=skill.current
            if(skill.reference !in failure.timing?.request?.skillIds.orEmpty() || failure.skillEvidence?.packageName!=skill.packageName ||
                failure.skillEvidence.version!=revision.version || execution in revision.assessedExecutions) skill
            else skill.copy(status=if(skill.status=="disabled") "disabled" else if(revision.consecutiveFailures>=1) "review" else skill.status,
                revisions=skill.revisions.dropLast(1)+revision.copy(failures=revision.failures+1, consecutiveFailures=revision.consecutiveFailures+1,
                    assessedExecutions=(revision.assessedExecutions+execution).takeLast(128)))
        })
        val job=candidate(run) ?: return result
        if(job.family in result.dismissed || result.skills.any { it.family==job.family && it.status=="disabled" } ||
            job.id in result.processed || result.jobs.any { it.id==job.id } || result.skills.any { job.executionId in it.current.executions } || result.jobs.size>=32) return result
        return result.copy(jobs=result.jobs+job)
    }
    fun processNext(state: AutoSkillState, now: Long = System.currentTimeMillis()): AutoSkillState {
        val job=state.jobs.firstOrNull() ?: return state
        var result=state.copy(jobs=state.jobs.drop(1), processed=(state.processed+job.id).distinct().takeLast(512))
        val old=state.skills.find { it.family==job.family }
        if(job.family in state.dismissed || old?.status=="disabled" || (old==null && state.skills.size>=LIMIT)) return result
        val same=old!=null && old.current.fingerprint==job.fingerprint && old.status!="review"
        val revision=if(same) {
            val r=old!!.current
            r.copy(sourceRuns=(r.sourceRuns+job.conversationId).distinct().takeLast(16), executions=(r.executions+job.executionId).distinct().takeLast(128),
                variants=(r.variants+job.variant).distinct().takeLast(16), successes=r.successes+1, consecutiveFailures=0)
        } else SkillRevision((old?.current?.number ?: 0)+1, job.completion.version, job.steps, job.completion, job.fingerprint,
            listOf(job.conversationId), listOf(job.executionId), listOf(job.variant), notes=old?.current?.notes.orEmpty(), createdAt=now)
        val status=if(revision.successes>=2 && revision.variants.size>=2) "verified" else "trial"
        val purpose=when(job.completion.kind) { "search" -> "搜索${job.completion.scope}"; "message_send" -> "发送消息"; "message_draft" -> "准备消息草稿"; else -> job.completion.scope }
        val skill=AutoSkill(old?.id ?: "learned.${job.family.take(32)}", job.family, job.packageName, job.appName,
            old?.title ?: "${job.appName.ifBlank { job.packageName }} · $purpose", status,
            ((if(same) old!!.revisions.dropLast(1) else old?.revisions.orEmpty())+revision).takeLast(5), now)
        if(render(skill).length>3200) return result
        result=result.copy(skills=result.skills.filterNot { it.family==job.family }+skill)
        return result
    }
    fun change(state: AutoSkillState, id: String, operation: String, notes: String = ""): AutoSkillState {
        val skill=state.skills.single { it.id==id }
        if(operation=="delete") return state.copy(skills=state.skills.filterNot { it.id==id }, jobs=state.jobs.filterNot { it.family==skill.family }, dismissed=state.dismissed+skill.family)
        require(operation in setOf("disable", "activate", "edit"))
        if(operation=="edit") require(notes.length<=1200 && AutoSkillTrace.safe(notes)) { "补充说明须为不含敏感信息的 1200 字以内文本" }
        val revision=skill.current
        val updated=when(operation) {
            "disable" -> skill.copy(status="disabled")
            "activate" -> skill.copy(status="trial", revisions=skill.revisions.dropLast(1)+revision.copy(consecutiveFailures=0))
            else -> skill.copy(status=if(skill.status=="disabled") "disabled" else "trial", revisions=(skill.revisions+revision.copy(number=revision.number+1,
                notes=notes, successes=0, failures=0, consecutiveFailures=0, variants=emptyList(), executions=emptyList(), assessedExecutions=emptyList())).takeLast(5))
        }.copy(updatedAt=System.currentTimeMillis())
        require(render(updated).length<=3200)
        return state.copy(skills=state.skills.map { if(it.id==id) updated else it })
    }
    fun render(skill: AutoSkill): String = buildString {
        val r=skill.current
        appendLine("自动学习技能：${skill.title}。${if(skill.status=="verified") "已在不同参数的任务中验证" else "试用：单次经验，不保证适用于其他页面"}。")
        appendLine("适用：${skill.packageName}，已验证版本 ${r.version}。前置条件：已进入可访问页面；需要登录时由用户处理。")
        val parameters=r.steps.map { it.parameter }.filter { it.isNotBlank() }.distinct()
        if(parameters.isNotEmpty()) appendLine("参数：${parameters.joinToString { "{$it}" }}；全部取自本次用户目标，不复用历史值。")
        r.steps.forEachIndexed { i, s ->
            val verb=when(s.action) {
                PhoneActionType.TYPE -> "在输入框填写 {${s.parameter}} 并读回"
                PhoneActionType.SET_CHECKED -> "按本次目标将开关设为 {enabled} 并读回；已符合要求时不切换"
                PhoneActionType.SET_PROGRESS -> "按本次目标将滑块设为 {percentage}% 并读回"
                PhoneActionType.SUBMIT_SEARCH -> "提交搜索并观察实际结果；输入法未提交时，使用当前可见的搜索按钮"
                PhoneActionType.SCROLL -> "向 ${s.direction} 滚动后重新观察"
                PhoneActionType.BACK -> "返回上一页并重新观察"
                PhoneActionType.SEND_MESSAGE -> "仅当本次用户要求发送，整理消息后核对对象和完整正文，用专用发送工具发送一次并核对新增消息"
                PhoneActionType.LONG_PRESS -> "定位并长按目标"
                else -> "定位并点击目标"
            }
            val target=if(s.label.isNotBlank()) "；文字/提示 ${Json.encodeToString(s.label)}" else ""
            val rid=if(s.label.isBlank() && s.resourceId.isNotBlank()) "；资源特征 ${s.resourceId}" else ""
            appendLine("${i+1}. $verb$target$rid")
        }
        appendLine("完成条件：${when(r.completion.kind) {
            "search" -> "查询已提交，当前页出现实际结果或明确无结果，不能把输入框回显当作成功"
            "message_draft" -> "当前应用、顶部对象、输入框正文均与本次目标匹配；不发送"
            "message_send" -> "当前会话新增指定正文、输入框清空且无发送失败状态；不能声称对方收到"
            else -> "当前页面的结果与本次目标对应，不能仅凭点击被接受判断完成"
        }}。曾观察的结果形式：${Json.encodeToString(r.completion.evidencePattern)}。")
        appendLine("恢复：特征不匹配时停止照路径走，重新观察并用通用工具探索；结果不可核对则询问用户。只能用当前节点，不重放坐标、旧节点或旧输入。")
        if(r.notes.isNotBlank()) appendLine("用户补充说明（优先于上述历史路径，仍须遵循本次目标）：${r.notes}")
    }
    fun library(state: AutoSkillState, bundled: AppSkillLibrary = AppSkillLibrary.bundled, version: (String) -> String?): AppSkillLibrary {
        val eligible=state.skills.filter { it.status in setOf("trial", "verified") && it.current.version==version(it.packageName) }
        val descriptors=eligible.map { skill -> AppSkillDescriptor(skill.reference, skill.title,
            "自动学习的${skill.current.completion.scope}流程；${if(skill.status=="verified") "已复用验证" else "试用"}；每步匹配当前页面。",
            setOf(skill.packageName), listOf(skill.appName).filter { it.isNotBlank() },
            listOf(skill.current.completion.scope, when(skill.current.completion.kind) { "search" -> "搜索 查找"; "message_send" -> "发送 消息"; "message_draft" -> "草稿 消息"; else -> "操作" }),
            skill.current.number, learned=true) }
        return AppSkillLibrary(bundled.descriptors+descriptors) { id -> eligible.find { it.reference==id }?.let(::render) ?: bundled.body(id) }
    }
}
