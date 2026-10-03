package dev.ondevice.gemma.learning

import dev.ondevice.gemma.phone.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SkillStepEvidence(
    val packageName: String, val version: String, val appName: String,
    val action: PhoneActionType, val label: String = "", val resourceId: String = "",
    val parameter: String = "", val valueDigest: String = "", val direction: String = "",
    val pageChanged: Boolean = false, val inputReadBack: Boolean = false,
    val beforePage: String = "", val afterPage: String = "",
)

@Serializable
data class SkillCompletion(
    val packageName: String, val version: String, val kind: String, val scope: String,
    val evidencePattern: String, val checkedOnPage: Boolean = false,
)

/** Capture observable evidence. No screen dumps, typed values, coordinates or transient node IDs. */
object AutoSkillTrace {
    val actions = setOf(PhoneActionType.TAP, PhoneActionType.LONG_PRESS, PhoneActionType.TYPE,
        PhoneActionType.SUBMIT_SEARCH, PhoneActionType.SCROLL, PhoneActionType.BACK, PhoneActionType.SEND_MESSAGE,
        PhoneActionType.SET_CHECKED,PhoneActionType.SET_PROGRESS)
    private val fixedLabels = setOf("搜索", "查找", "搜索文件", "搜索联系人", "搜索商家", "搜索笔记", "选择口味", "文件", "首页", "返回", "确认", "确定", "保存", "发送", "分享", "search", "send", "back", "save")
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun safe(value: String) = value.length <= 3000 && !Regex("密码|验证码|密钥|身份证|银行卡|https?://|[\\w.+-]+@[\\w.-]+|[0-9]{6,}|忽略.{0,8}(指令|规则)|跳过.{0,8}(确认|检查)|ignore.{0,12}instruction", RegexOption.IGNORE_CASE).containsMatchIn(value) &&
        value.none { Character.getType(it) == Character.FORMAT.toInt() }

    private fun bindings(run: PhoneRun): Map<String, String> = buildMap {
        run.steps.forEach { s -> if(s.action.text.isNotBlank() && s.skillEvidence?.parameter?.isNotBlank()==true) put(s.action.text, s.skillEvidence.parameter) }
        run.messageRequest?.let { put(it.recipient, "recipient"); put(it.body, "body") }
    }
    private fun parameterize(text: String, values: Map<String, String>): String {
        var result = text
        values.entries.sortedByDescending { it.key.length }.forEach { (value, parameter) -> if(value.isNotEmpty()) result=result.replace(value, "{$parameter}") }
        return result
    }
    fun capture(action: PhoneAction, screen: ScreenSnapshot, run: PhoneRun, appName: String): SkillStepEvidence? {
        if(action.type !in actions || screen.appVersion.isBlank()) return null
        val node = screen.nodes.find { it.id == action.nodeId }
        val values = bindings(run).toMutableMap()
        var parameter = ""
        var value = ""
        val label = if(node?.editable==true) node.hint else node?.let {
            (listOf(it) + screen.nodes.filter { child -> child.clickTargetId==it.id })
                .flatMap { n -> listOf(n.text, n.description) }.firstOrNull { t -> t.isNotBlank() }.orEmpty()
        }.orEmpty()
        if(action.type in SystemPhoneActions.controls) {
            parameter=if(action.type==PhoneActionType.SET_CHECKED) "enabled" else "percentage"
            value=if(action.type==PhoneActionType.SET_CHECKED) action.checked.toString() else action.value
        } else if(action.type == PhoneActionType.TYPE) {
            value=action.text
            parameter=values[value] ?: when {
                node?.canSubmitSearch==true || Regex("搜索|查找|search", RegexOption.IGNORE_CASE).containsMatchIn(node?.hint.orEmpty()) -> "query"
                else -> "input${run.steps.mapNotNull { it.skillEvidence?.parameter }.filter { it.startsWith("input") }.distinct().size+1}"
            }
        } else if(action.type in setOf(PhoneActionType.TAP, PhoneActionType.LONG_PRESS) && label.isNotBlank()) {
            parameter=values[label].orEmpty()
            if(parameter.isBlank() && label!=appName && label.lowercase() !in fixedLabels && (label in run.goal || run.followUps.any { label in it.text })) {
                parameter="choice${run.steps.mapNotNull { it.skillEvidence?.parameter }.filter { it.startsWith("choice") }.distinct().size+1}"
            }
            if(parameter.isNotBlank()) value=label
        }
        if(value.isNotBlank()) values[value]=parameter
        val cleanLabel=parameterize(label, values).take(80).takeIf(::safe).orEmpty()
        val rid=node?.resourceId.orEmpty().takeIf { it.startsWith(screen.packageName+":id/") && it.length<=120 &&
            Regex("[A-Za-z0-9_.:/-]+").matches(it) && safe(it) && values.keys.none { v -> v.length>=3 && it.contains(v) } }.orEmpty()
        return SkillStepEvidence(screen.packageName, screen.appVersion, appName.take(60).takeIf(::safe).orEmpty(), action.type,
            if(parameter.isNotBlank() && action.type in setOf(PhoneActionType.TAP,PhoneActionType.LONG_PRESS)) "{$parameter}" else cleanLabel, rid, parameter,
            if(value.isBlank()) "" else digest(value), if(action.type==PhoneActionType.SCROLL) action.direction else "", beforePage=digest(screen.fingerprint))
    }
    fun after(evidence: SkillStepEvidence?, action: PhoneAction, before: ScreenSnapshot, after: ScreenSnapshot): SkillStepEvidence? = evidence?.copy(
        pageChanged=before.packageName!=after.packageName || before.fingerprint!=after.fingerprint,
        inputReadBack=action.type==PhoneActionType.TYPE && after.packageName==before.packageName && after.nodes.any { it.editable && it.text==action.text },
        afterPage=digest(after.fingerprint))

    fun completion(run: PhoneRun, screen: ScreenSnapshot, evidence: String): SkillCompletion? {
        if(screen.appVersion.isBlank() || !screen.containsText(evidence)) return null
        val request=run.messageRequest
        val steps=run.steps.filter { it.dispatched && it.skillEvidence?.packageName==screen.packageName }.mapNotNull { it.skillEvidence }
        if(steps.isEmpty()) return null
        val kind=when {
            request!=null -> if(MessagePolicy.completed(screen, request, run.steps)) { if(request.draftOnly) "message_draft" else "message_send" } else return null
            !screen.nodes.any { !it.editable && (evidence in it.text || evidence in it.description || evidence in it.stateDescription) } -> return null
            steps.any { it.parameter=="query" && it.inputReadBack } -> {
                if(steps.none { it.action==PhoneActionType.SUBMIT_SEARCH || (it.action==PhoneActionType.TAP && it.label.lowercase() in setOf("搜索", "查找", "search")) }) return null
                "search"
            }
            steps.any { it.pageChanged } -> "navigate"
            else -> return null
        }
        val inputHint=steps.lastOrNull { it.parameter=="query" }?.label.orEmpty()
        val scope=when(kind) {
            "search" -> listOf("联系人", "文件", "商家", "笔记", "商品").firstOrNull { it in inputHint } ?: "内容"
            "message_draft", "message_send" -> "消息"
            else -> steps.firstOrNull { it.label.isNotBlank() && !it.label.startsWith("{") && it.action in SystemPhoneActions.controls+PhoneActionType.TAP }?.label ?: "页面操作"
        }
        val pattern=if(steps.any { it.parameter.startsWith("choice") }) "与本次选择参数对应的当前页面结果" else
            parameterize(evidence, bindings(run)).replace(Regex("(?<![A-Za-z])\\d+(?![A-Za-z0-9}])"), "{count}").take(160)
        return SkillCompletion(screen.packageName, screen.appVersion, kind, scope,
            if(safe(pattern)) pattern else "与本次目标对应的当前结果", true)
    }
}
