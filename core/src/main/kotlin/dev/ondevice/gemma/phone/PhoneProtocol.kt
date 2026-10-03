package dev.ondevice.gemma.phone

import kotlinx.serialization.Serializable
import dev.ondevice.gemma.learning.NavigationHint
import dev.ondevice.gemma.learning.SkillStepEvidence
import dev.ondevice.gemma.learning.SkillCompletion

@Serializable
data class ScreenNode(
    val id: String,
    val text: String = "",
    val description: String = "",
    val resourceId: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val hint: String = "",
    val clickTargetId: String = "",
    val longClickable: Boolean = false,
    val canSubmitSearch: Boolean = false,
    val contextHeader: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val stateDescription: String = "",
    val rangeMin: Float? = null,
    val rangeMax: Float? = null,
    val rangeValue: Float? = null,
    val canSetProgress: Boolean = false,
    val rangeIsInteger: Boolean = false,
)

@Serializable
data class ScreenSnapshot(
    val id: String,
    val packageName: String,
    val capturedAt: Long,
    val fingerprint: String,
    val nodes: List<ScreenNode>,
    val notice: String = "",
    val appVersion: String = "",
    val systemTargets: List<SystemTarget> = emptyList(),
    val localTime: String = "",
) {
    fun containsText(value: String): Boolean = value.isNotBlank() &&
        nodes.any { value in it.text || value in it.description || value in it.stateDescription }
}

@Serializable
enum class PhoneActionType {
    LIST_APPS, SEARCH_APPS, SEARCH_SKILLS, LOAD_SKILL, READ_SCREEN, FIND_NODES, OPEN_APP, TAP, LONG_PRESS, TYPE, SUBMIT_SEARCH,
    SCROLL, BACK, WAIT, PREPARE_MESSAGE, COMPOSE_SMS, SEND_MESSAGE, VERIFY_MESSAGE, NOTE_PREFERENCE, RESPOND, FINISH, ASK_USER,
    OPEN_SETTINGS, SET_ALARM, SET_TIMER, SET_CHECKED, SET_PROGRESS,
    SEARCH_EXTENSIONS, READ_EXTENSION, CALL_EXTENSION
}

@Serializable
data class MessageRequest(
    val channel: String, val packageName: String, val recipient: String, val body: String,
    val draftOnly: Boolean = true,
) {
    fun validate() {
        // qq/wechat remain readable for old histories; new plans use app for any chat application.
        require(channel in setOf("app", "sms", "qq", "wechat")) { "消息渠道须为聊天应用或短信" }
        require(packageName.isNotBlank() && recipient.isNotBlank() && recipient.length <= 80)
        require(body.isNotBlank() && body.length <= 400) { "本版消息正文须为 1–400 字符" }
        if (channel == "sms") require(Regex("\\+?[0-9]{3,20}").matches(recipient)) { "短信收件人请填写单个电话号码" }
    }
    fun goal() = "使用${when (channel) { "sms" -> "短信"; "qq" -> "QQ"; "wechat" -> "微信"; else -> "聊天应用（$packageName）" }}，${if (draftOnly) "准备草稿，不发送" else "核对对象和正文后直接发送一次"}。收件人：$recipient。正文原文：$body"
}

@Serializable
data class PhoneAction(
    val snapshotId: String,
    val type: PhoneActionType,
    val reason: String,
    val nodeId: String = "",
    val text: String = "",
    val packageName: String = "",
    val direction: String = "forward",
    val evidence: String = "",
    val query: String = "",
    val recipientNodeId: String = "",
    val inputNodeId: String = "",
    val channel: String = "",
    val recipient: String = "",
    val draftOnly: Boolean = true,
    val experienceIds: List<String> = emptyList(),
    val cursor: String = "",
    val skillId: String = "",
    val destination: String = "",
    val hour: String = "",
    val minute: String = "",
    val days: String = "",
    val seconds: String = "",
    val value: String = "",
    val checked: Boolean = false,
    val extensionId: String = "",
    val resource: String = "",
    val argumentsJson: String = "",
)

fun PhoneAction.messageRequest() = MessageRequest(channel, packageName, recipient, text, draftOnly)

@Serializable
data class PhoneFollowUp(val text: String, val afterStep: Int)

@Serializable
data class PhoneRequestMetrics(
    val networkMs: Long = 0, val responseHeadersMs: Long = 0,
    val estimatedInputTokens: Int = 0, val toolCount: Int = 0,
    val outputTokens: Int? = null,
    val skillIds: List<String> = emptyList(),
    val appCandidates: Int = 0,
    val knowledgeChars: Int = 0,
)

@Serializable
data class PhoneStepTiming(
    val planningMs: Long = 0, val observeMs: Long = 0,
    val executeMs: Long = 0, val settleMs: Long = 0,
    val request: PhoneRequestMetrics? = null,
)

@Serializable
data class PhoneStep(
    val number: Int,
    val action: PhoneAction,
    val dispatched: Boolean = false,
    val observation: String = "等待执行",
    val matchingMessagesBefore: Int = 0,
    val navigation: NavigationHint? = null,
    val timing: PhoneStepTiming? = null,
    // Persisted before invoking SEND_MESSAGE; an interrupted call must never be replayed.
    val dispatchAttempted: Boolean = false,
    val sourcePage: PhonePageContext? = null,
    val skillEvidence: SkillStepEvidence? = null,
    val skillCompletion: SkillCompletion? = null,
    val skillFailure: Boolean = false,
    val systemVerified: Boolean = false,
    val extensionSucceeded: Boolean = false,
)

@Serializable
enum class RunStatus { IDLE, RUNNING, WAITING_APPROVAL, PAUSED, COMPLETED, FAILED, CANCELLED }

@Serializable
enum class MessageOutcome { UNCONFIRMED, VERIFIED }

@Serializable
data class PhoneRun(
    val id: String = "",
    val goal: String = "",
    val status: RunStatus = RunStatus.IDLE,
    val message: String = "准备开始",
    val steps: List<PhoneStep> = emptyList(),
    val allowedPackages: Set<String> = emptySet(),
    val plannerRequests: Int = 0,
    val replans: Int = 0,
    val elapsedMs: Long = 0,
    val messageRequest: MessageRequest? = null,
    val followUps: List<PhoneFollowUp> = emptyList(),
    val messageOutcome: MessageOutcome? = null,
    val executionId: String = "",
    val previousTurns: List<PhoneTurn> = emptyList(),
)

fun PhoneRun.canAcceptTurn() = id.isNotBlank() && status in setOf(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)

fun PhoneRun.canContinueWithReply() = status == RunStatus.PAUSED &&
    steps.lastOrNull()?.action?.type == PhoneActionType.ASK_USER && followUps.size < 8 &&
    !MessagePolicy.sendAttempted(steps)

fun PhoneRun.hasUnconfirmedSend() = MessagePolicy.sendAttempted(steps) &&
    messageOutcome != MessageOutcome.VERIFIED && status != RunStatus.COMPLETED

fun PhoneRun.hasUnconfirmedExtension() = steps.any {
    it.action.type == PhoneActionType.CALL_EXTENSION && it.dispatchAttempted && !it.dispatched
}

data class PlannerInput(
    val goal: String,
    val screen: ScreenSnapshot,
    val allowedApps: Map<String, String>,
    val steps: List<PhoneStep>,
    val feedback: String = "",
    val messageRequest: MessageRequest? = null,
    val followUps: List<PhoneFollowUp> = emptyList(),
    val previousTurns: List<PhoneTurn> = emptyList(),
)

fun interface PhonePlanner {
    suspend fun next(input: PlannerInput): PhoneAction
    val lastMetrics: PhoneRequestMetrics? get() = null
}

fun interface PhoneLearning {
    fun proposePreference(statements: List<String>, quote: String, packageName: String, runId: String): String
}

/** A model format failure is safe to re-plan: no device action has been dispatched. */
class PhonePlanningException(message: String) : IllegalArgumentException(message)

/** Policy is enforced locally, independent of model instructions. */
object PhonePolicy {
    fun validate(action: PhoneAction, screen: ScreenSnapshot, allowed: Set<String>, request: MessageRequest? = null, steps: List<PhoneStep> = emptyList()): String? {
        if (action.snapshotId != screen.id) return "页面快照已过期，请重新观察"
        if (screen.packageName !in allowed) return "当前应用未获授权"
        if (action.reason.isBlank() || action.reason.length > 1000) return "动作说明无效"
        if (MessagePolicy.sendAttempted(steps) && action.type in deviceActions + PhoneActionType.PREPARE_MESSAGE) {
            return "本轮消息已尝试发送，只能核对结果；用户在对话中明确要求再次发送后，才开启下一轮操作"
        }
        val node = screen.nodes.find { it.id == action.nodeId }
        return when (action.type) {
            PhoneActionType.SEARCH_EXTENSIONS -> if (action.query.length > 200 || runCatching { dev.ondevice.gemma.extensions.ExtensionCatalog.cursor(action.cursor) }.isFailure) "扩展搜索参数无效 / Invalid extension search" else null
            PhoneActionType.READ_EXTENSION -> if (action.extensionId.length !in 1..240 || action.resource.length > 240 || runCatching { dev.ondevice.gemma.extensions.ExtensionCatalog.cursor(action.cursor) }.isFailure) "扩展读取参数无效 / Invalid extension reference" else null
            PhoneActionType.CALL_EXTENSION -> if (action.extensionId.length !in 1..240 || action.argumentsJson.length > 16_000 || runCatching { kotlinx.serialization.json.Json.parseToJsonElement(action.argumentsJson) is kotlinx.serialization.json.JsonObject }.getOrDefault(false).not()) "工具参数须为 JSON 对象 / Tool arguments must be a JSON object" else null
            PhoneActionType.OPEN_SETTINGS, PhoneActionType.SET_ALARM, PhoneActionType.SET_TIMER,
            PhoneActionType.SET_CHECKED, PhoneActionType.SET_PROGRESS -> SystemPhoneActions.validate(action,screen,allowed,steps)
            PhoneActionType.OPEN_APP -> if (action.packageName !in allowed) "目标应用未获授权" else null
            PhoneActionType.TAP -> when {
                node?.clickable != true -> "目标节点不存在或不可点击"
                MessagePolicy.isSendControl(node, screen) -> "发送按钮必须使用 phone_send_message，并核对收件人和正文"
                MessagePolicy.isRestrictedControl(node, screen) -> "此操作需要你在应用中手动完成"
                !MessagePolicy.hasVisibleLabel(node, screen) -> "按钮没有可核对的文字或描述，请手动接管"
                else -> null
            }
            PhoneActionType.LONG_PRESS -> when {
                node?.longClickable != true -> "目标节点不支持长按"
                MessagePolicy.isSendControl(node, screen) || MessagePolicy.isRestrictedControl(node, screen) -> "此按钮不能通过长按操作"
                else -> null
            }
            PhoneActionType.SUBMIT_SEARCH -> if (node?.editable != true || !node.canSubmitSearch) "该输入框未暴露可验证的搜索动作，请点击可见搜索按钮" else null
            PhoneActionType.TYPE -> when {
                node?.editable != true -> "目标节点不存在或不可输入"
                action.text.length > 4000 -> "单次输入超过 4000 字符"
                else -> null
            }
            PhoneActionType.SCROLL -> when {
                node?.scrollable != true -> "目标节点不可滚动"
                action.direction !in setOf("forward", "backward") -> "滚动方向无效"
                else -> null
            }
            PhoneActionType.COMPOSE_SMS -> when {
                request?.channel != "sms" -> "请先调用 phone_prepare_message 整理短信对象和正文"
                request.packageName !in allowed -> "短信应用未获授权"
                else -> null
            }
            PhoneActionType.PREPARE_MESSAGE -> when {
                action.packageName !in allowed -> "消息应用不在本次可用范围"
                MessagePolicy.sendAttempted(steps) -> "本次消息已尝试发送，不能重新准备或重发"
                runCatching { action.messageRequest().validate() }.isFailure -> "消息信息不完整或无效，请向用户询问缺失的信息"
                else -> null
            }
            PhoneActionType.SEND_MESSAGE -> MessagePolicy.validateSend(action, screen, request, steps)
            PhoneActionType.FIND_NODES -> if (action.query.isBlank() || action.query.length > 100) "查找关键词须为 1–100 字符" else null
            PhoneActionType.SEARCH_APPS -> when {
                action.query.length > 200 -> "应用查询过长"
                action.cursor.isNotEmpty() && (action.cursor.toIntOrNull()?.let { it >= 0 } != true) -> "无效的应用分页游标"
                else -> null
            }
            PhoneActionType.SEARCH_SKILLS -> when {
                action.query.length > 200 -> "技能查询过长"
                action.packageName.isNotBlank() && action.packageName !in allowed -> "技能所属应用不在本次范围"
                else -> null
            }
            PhoneActionType.LOAD_SKILL -> if (!Regex("[a-z0-9][a-z0-9._-]{0,79}").matches(action.skillId)) "技能标识无效" else null
            PhoneActionType.VERIFY_MESSAGE -> if (request == null) "没有待核对的消息任务" else null
            PhoneActionType.NOTE_PREFERENCE -> when {
                action.text.length !in 2..160 -> "偏好候选须为 2–160 字符"
                action.packageName.isNotEmpty() && action.packageName !in allowed -> "偏好所属应用不在本次范围"
                else -> null
            }
            PhoneActionType.RESPOND -> when {
                action.text.isBlank() || action.text.length > 6000 -> "回复内容无效"
                request != null || steps.any { it.dispatched && it.action.type in deviceActions } -> "已开始手机操作，请核对页面结果或请求用户接管"
                else -> null
            }
            PhoneActionType.FINISH -> when {
                !screen.containsText(action.evidence) -> "缺少当前页面可核对的完成证据"
                SystemPhoneActions.completionError(screen,steps)!=null -> SystemPhoneActions.completionError(screen,steps)
                request != null && !MessagePolicy.completed(screen, request, steps) -> "消息任务尚无可核对的完成证据；不能把草稿或点击当作已发送"
                else -> null
            }
            else -> null
        }
    }

    val deviceActions = setOf(
        PhoneActionType.OPEN_APP, PhoneActionType.TAP, PhoneActionType.TYPE, PhoneActionType.LONG_PRESS,
        PhoneActionType.SCROLL, PhoneActionType.BACK, PhoneActionType.SUBMIT_SEARCH,
        PhoneActionType.COMPOSE_SMS, PhoneActionType.SEND_MESSAGE,
        PhoneActionType.OPEN_SETTINGS, PhoneActionType.SET_ALARM, PhoneActionType.SET_TIMER, PhoneActionType.SET_CHECKED, PhoneActionType.SET_PROGRESS,
    )

    fun needsApproval(action: PhoneAction, confirmEveryAction: Boolean = true): Boolean =
        confirmEveryAction && action.type in deviceActions

    fun observation(action: PhoneAction, before: ScreenSnapshot, after: ScreenSnapshot): String = when {
        action.type in SystemPhoneActions.creates -> "系统已接受创建请求；仍须核对时钟页面，不自动重复创建"
        action.type == PhoneActionType.OPEN_SETTINGS -> "已请求打开系统入口；继续检查当前页面并完成目标"
        action.type in SystemPhoneActions.controls -> if(SystemPhoneActions.controlMatches(action,before,after)) "已读回目标控件的新状态" else "控件修改结果尚未核对"
        action.type == PhoneActionType.SEND_MESSAGE -> "发送按钮已点击；尚需核对聊天页新增内容，不代表对方已收到"
        action.type == PhoneActionType.COMPOSE_SMS -> "已请求打开短信草稿；尚需核对收件人及正文，未发送"
        action.type == PhoneActionType.TYPE && action.text.isNotEmpty() &&
            after.nodes.any { it.editable && it.text == action.text } -> "已读回输入内容"
        action.type == PhoneActionType.OPEN_APP && after.packageName == action.packageName -> "已进入目标应用"
        before.fingerprint != after.fingerprint -> "界面发生变化，下一步需继续检查任务结果"
        else -> "界面未发生可观察变化；不代表任务完成"
    }
}
