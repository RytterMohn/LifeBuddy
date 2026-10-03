package dev.ondevice.gemma.app.runtime

import android.content.Context
import dev.ondevice.gemma.app.data.AgentSettings
import dev.ondevice.gemma.app.data.RunStore
import dev.ondevice.gemma.app.data.AgentMemoryStore
import dev.ondevice.gemma.app.data.LearningStore
import dev.ondevice.gemma.app.data.AutoSkillStore
import dev.ondevice.gemma.app.data.ExtensionStore
import dev.ondevice.gemma.app.i18n.AppLanguage
import dev.ondevice.gemma.learning.AutoSkillBook
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.app.phone.PhoneAccessibilityService
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** Process-level owner; leaving the chat Activity never cancels or restarts a task. */
object PhoneController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceRef = WeakReference<PhoneAccessibilityService>(null)
    private val service: PhoneAccessibilityService? get() = serviceRef.get()
    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private lateinit var store: RunStore
    private var activeSkillStore: AutoSkillStore? = null
    private val _run = MutableStateFlow(PhoneRun())
    val run = _run.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    val busy: Boolean get() = job?.isCompleted == false

    fun initialize(context: Context) {
        if (::store.isInitialized) return
        store = RunStore(context)
        _run.value = store.load()
    }

    fun attach(value: PhoneAccessibilityService) {
        initialize(value)
        serviceRef = WeakReference(value)
        _connected.value = true
    }

    fun detach(value: PhoneAccessibilityService) {
        if (service !== value) return
        pause("无障碍服务已断开，任务停止；请检查外部操作结果")
        serviceRef.clear()
        _connected.value = false
    }

    private fun publish(value: PhoneRun) {
        // Persist first: a storage failure must prevent the next external action.
        store.save(value)
        if(value.status in setOf(RunStatus.COMPLETED, RunStatus.PAUSED, RunStatus.FAILED)) activeSkillStore?.record(value)
        _run.value = value
        service?.showOverlay(value, null, null)
    }

    fun start(context: Context, goal: String, apps: Map<String, String>, cloud: Boolean, messageRequest: MessageRequest? = null, resume: PhoneRun? = null,
              returnToAgent: Boolean = true, conversation: PhoneRun? = null, knowledge: PhoneKnowledge? = null, learnFromTask: Boolean = true,
              autoSkillStore: AutoSkillStore? = null) {
        check(!busy) { "已有任务正在执行" }
        require(goal.isNotBlank() && goal.length <= 2000) { "任务须为 1–2000 字符" }
        messageRequest?.validate()
        require(resume == null || (cloud && resume.canContinueWithReply())) { "这条任务无法继续，请新建任务" }
        require(conversation == null || (cloud && resume == null && messageRequest == null && conversation.canAcceptTurn())) { "请等待当前操作结束" }
        val continuingQuestion = resume ?: conversation?.takeIf { it.canContinueWithReply() }
        val nextTurn = conversation?.takeUnless { it.canContinueWithReply() }
        require(messageRequest == null || (cloud && messageRequest.packageName in apps)) { "消息应用尚未获准用于本次任务" }
        store.kind = if (cloud) "cloud" else "practice"
        val driver = service ?: error("请先开启LifeBuddy 无障碍服务")
        val settings = AgentSettings(context)
        val extensions = if (cloud) ExtensionStore.get(context).session(allowTools = true) else null
        val learning = if (cloud && learnFromTask && settings.learningEnabled()) LearningStore.get(context) else null
        val skillStore=if(learning!=null) autoSkillStore ?: AutoSkillStore.get(context) else null
        activeSkillStore=skillStore
        val taskKnowledge=knowledge ?: PhoneKnowledge(if(skillStore==null) AppSkillLibrary.bundled else runCatching {
            AutoSkillBook.library(skillStore.snapshot()) { pkg ->
                runCatching { val info=context.packageManager.getPackageInfo(pkg,0)
                    if(Build.VERSION.SDK_INT>=28) info.longVersionCode.toString() else info.versionCode.toString() }.getOrNull()
            }
        }.getOrDefault(AppSkillLibrary.bundled))
        val planner: PhonePlanner = if (cloud) {
            val config = settings.read()
            CloudPhonePlanner.validate(config)
            CloudPhonePlanner(config, Json.encodeToString(AgentMemoryStore.get(context).read()), learning != null, knowledge = taskKnowledge,
                extensions = extensions, responseLanguage = { AppLanguage.modelLanguage }) { input ->
                learning?.habitContext(input.goal + input.followUps.joinToString { it.text }, input.screen.packageName, input.allowedApps.keys)
                    ?: dev.ondevice.gemma.learning.LearningContext("", emptyList())
            }
        } else PracticePlanner(context.packageName)
        val allowed = if (cloud) apps + (context.packageName to "LifeBuddy 练习页面")
            else mapOf(context.packageName to "LifeBuddy 练习页面")
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(10 * 60_000L) {
                    PhoneRunner(planner, driver, approve = { action, screen ->
                        val pending = CompletableDeferred<Boolean>()
                        approval = pending
                        driver.showOverlay(_run.value, action, screen)
                        try { pending.await() } finally { approval = null }
                    }, publish = ::publish, confirmEveryAction = !cloud, learning = learning, settleBeforePlanning = true, knowledge = taskKnowledge, captureSkills=skillStore!=null, extensions=extensions)
                        .run(goal, allowed, messageRequest, continuingQuestion, nextTurn)
                }
            } catch (_: TimeoutCancellationException) {
                recordTerminal(RunStatus.PAUSED, "已达到 10 分钟运行上限；请检查当前页面")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_run.value.hasUnconfirmedExtension()) {
                    recordTerminal(RunStatus.PAUSED, "外部调用中断，结果待确认；不会自动重试，请先检查服务中的结果 / External call interrupted; outcome unknown. Check the service before retrying.")
                } else if (MessagePolicy.sendAttempted(_run.value.steps)) {
                    recordTerminal(RunStatus.PAUSED, "已尝试发送，但后续核对中断，结果待确认。请检查聊天页；这不代表发送失败，不会自动重发。")
                } else if(_run.value.steps.any { it.dispatchAttempted && it.action.type in SystemPhoneActions.creates }) {
                    recordTerminal(RunStatus.PAUSED,"已请求系统创建闹钟或计时器，但后续核对中断。请到时钟查看；不会自动重复创建。")
                } else recordTerminal(RunStatus.FAILED, error.message ?: "任务失败，请检查设置与当前页面")
            } finally {
                approval?.cancel()
                approval = null
                driver.hideOverlay()
                if (returnToAgent && _run.value.id.isNotBlank()) {
                    runCatching { driver.returnToAgent(_run.value.id) }
                }
                skillStore?.kick()
            }
        }.also { it.start() }
    }

    fun approve() { approval?.complete(true) }

    /** Only the signature-protected debug diagnostics use this; never exposes an external driver API. */
    internal fun diagnosticService(): PhoneAccessibilityService {
        val driver = checkNotNull(service)
        check(driver.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && !busy)
        return driver
    }

    internal fun reloadSavedRun() {
        check(!busy)
        _run.value = store.load()
    }

    fun pause(message: String = "用户暂停") {
        if (!busy) return
        job?.cancel()
        approval?.cancel()
        recordTerminal(RunStatus.PAUSED, message)
        service?.hideOverlay()
    }

    fun stop() {
        if (!busy) return
        job?.cancel()
        approval?.cancel()
        recordTerminal(RunStatus.CANCELLED, "用户已停止任务；已经执行的外部动作不会自动撤销")
        service?.hideOverlay()
    }

    private fun recordTerminal(status: RunStatus, message: String) {
        val value = _run.value.copy(status = status, message = message)
        _run.value = value
        runCatching { store.save(value) }
        activeSkillStore?.record(value)
    }

    fun forgetDeletedRun(id: String) {
        check(!busy) { "请先停止当前任务" }
        if (_run.value.id == id) _run.value = PhoneRun()
    }

    fun clear() {
        check(!busy) { "请先停止当前任务" }
        store.clear()
        _run.value = PhoneRun()
    }
}

/** Deterministic offline practice, explicitly not a local language model. */
private class PracticePlanner(private val ownPackage: String) : PhonePlanner {
    override suspend fun next(input: PlannerInput): PhoneAction {
        val screen = input.screen
        val saved = screen.nodes.firstOrNull { it.text == "已保存：第一版验证" }
        if (saved != null) return PhoneAction(screen.id, PhoneActionType.FINISH, "练习文字已保存并读回", evidence = saved.text)
        val field = screen.nodes.firstOrNull { it.editable }
        if (field == null) return PhoneAction(screen.id, PhoneActionType.OPEN_APP, "打开内置练习页面", packageName = ownPackage)
        if (field.text != "第一版验证") return PhoneAction(screen.id, PhoneActionType.TYPE, "填写练习文字", field.id, text = "第一版验证")
        val save = screen.nodes.firstOrNull { it.text == "保存练习" && it.clickable }
            ?: return PhoneAction(screen.id, PhoneActionType.ASK_USER, "没有找到保存按钮，请回到练习页面")
        return PhoneAction(screen.id, PhoneActionType.TAP, "保存练习并检查结果", nodeId = save.id)
    }
}
