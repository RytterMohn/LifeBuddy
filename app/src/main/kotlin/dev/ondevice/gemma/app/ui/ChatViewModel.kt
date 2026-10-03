package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.systemText
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ondevice.gemma.agent.Agent
import dev.ondevice.gemma.agent.AgentLoop
import dev.ondevice.gemma.config.AgentConfig
import dev.ondevice.gemma.llm.InferenceEngine
import dev.ondevice.gemma.llm.LlamaCppEngine
import dev.ondevice.gemma.app.data.*
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.cloud.CloudChatAgent
import dev.ondevice.gemma.app.i18n.AppLanguage
import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.Role
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File

typealias UiMessage = HistoryMessage

class ChatViewModel(private val app: Application) : AndroidViewModel(app) {
    val history = HistoryStore.get(app)
    val conversationId = MutableStateFlow("")
    val historyNotice = MutableStateFlow("")
    val modelStatus = MutableStateFlow(tr("未加载"))
    val harnessPhase = MutableStateFlow(tr("正在思考…"))
    private var engine: InferenceEngine? = null
    private var activeJob: Job? = null
    private val agent: Agent by lazy { createAgent() }
    private var cloudAgent: CloudChatAgent? = null
    private var cloudConfig: CloudConfig? = null
    private var cloudMode = true
    private var savedContext = "[]"
    private var localStatus = tr("本地模型尚未加载")
    val cloudReady = MutableStateFlow(false)
    val cloudDestination = MutableStateFlow("")
    private val messages = MutableStateFlow<List<UiMessage>>(emptyList())
    private val streaming = MutableStateFlow<String?>(null)
    val messagesFlow: StateFlow<List<UiMessage>> = messages
    val streamingFlow: StateFlow<String?> = streaming
    val busy = MutableStateFlow(false)

    init {
        refreshCloudConfig()
        history.load(history.selectedId)?.takeIf { ConversationKind.isChat(it.summary.kind) }?.let { openConversation(it.summary.id) }
    }

    fun refreshCloudConfig() {
        if (busy.value) return
        val config = AgentSettings(app).read()
        if (config != cloudConfig) {
            cloudConfig = config
            cloudAgent = if (runCatching { CloudApiClient.validate(config) }.isSuccess)
                CloudChatAgent(config.model.trim(), CloudApiClient(config), ConversationMemory(AgentMemoryStore.get(app), history,
                    { conversationId.value }, { if (AgentSettings(app).learningEnabled()) LearningStore.get(app).confirmedHabits() else "" }),
                    extensions = ExtensionStore.get(app).session(allowTools = false), responseLanguage = { AppLanguage.modelLanguage }) else null
            if (cloudMode) restoreContext()
        }
        cloudReady.value = cloudAgent != null
        cloudDestination.value = android.net.Uri.parse(config.baseUrl).host.orEmpty()
        if (cloudMode) modelStatus.value = if (cloudReady.value) tr("${config.model} · 聊天模式", "${config.model} · Chat") else tr("配置模型后，就可以开始对话")
    }

    fun selectCloud(value: Boolean) {
        if (busy.value) return
        if (value != cloudMode) {
            cloudMode = value
            clear()
        }
        history.selectedId = conversationId.value
        if (value) refreshCloudConfig() else { agent; modelStatus.value = localStatus }
    }

    fun openConversation(id: String) {
        if (busy.value) return
        val record = history.load(id) ?: return
        if (!ConversationKind.isChat(record.summary.kind)) return
        cloudMode = record.summary.kind == ConversationKind.CLOUD
        conversationId.value = id
        history.selectedId = id
        messages.value = record.messages
        streaming.value = null
        savedContext = record.context
        historyNotice.value = ""
        restoreContext()
        if (cloudMode) refreshCloudConfig() else { modelStatus.value = localStatus }
    }

    private fun restoreContext() {
        runCatching {
            val state = Json.parseToJsonElement(savedContext)
            if (cloudMode) cloudAgent?.restoreState(state)
            else agent.restoreMemory(state.jsonArray.map { element ->
                val message = element.jsonObject
                ChatMessage(Role.valueOf(message.getValue("role").jsonPrimitive.content),
                    message.getValue("content").jsonPrimitive.content, message["toolCallId"]?.jsonPrimitive?.contentOrNull)
            })
        }.onFailure {
            if (cloudMode) cloudAgent?.clear() else agent.clearMemory()
            historyNotice.value = tr("这条对话的模型上下文无法恢复，历史消息仍保留。下一次回复将从当前问题开始。")
        }
    }

    private fun contextSnapshot(): String = if (cloudMode) cloudAgent!!.snapshotState().toString() else JsonArray(
        agent.snapshotMemory().map { message -> buildJsonObject {
            put("role", message.role.name); put("content", message.content)
            message.toolCallId?.let { put("toolCallId", it) }
        } }
    ).toString()

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() || input.length > 4000 || busy.value) return
        if (cloudMode) { refreshCloudConfig(); if (cloudAgent == null) return }
        busy.value = true
        activeJob = viewModelScope.launch {
            var draft: UiMessage? = null
            val partial = StringBuilder()
            var lastDisplay = 0L
            var lastSave = 0L
            fun ensureDraft(): UiMessage = draft ?: history.append(conversationId.value, Role.MODEL, "", state = "streaming").also { draft = it }
            fun append(role: Role, content: String, isTool: Boolean = false, detail: String = "") {
                messages.value += history.append(conversationId.value, role, content, isTool, detail = detail)
            }
            fun flushPartial(state: String = "complete") {
                val pending = draft ?: if (partial.isNotEmpty()) ensureDraft() else null
                if (pending != null) {
                    if (partial.isNotEmpty()) {
                        history.updateMessage(pending.id, partial.toString(), state)
                        messages.value += pending.copy(content = partial.toString(), state = state)
                    } else history.removeEmptyDraft(pending.id)
                }
                draft = null
                partial.clear()
                streaming.value = ""
            }
            fun token(piece: String) {
                partial.append(piece)
                val now = SystemClock.elapsedRealtime()
                if (now - lastDisplay >= 40) { streaming.value = partial.toString(); lastDisplay = now }
            }
            fun tool(name: String, arguments: JsonObject, result: String) {
                flushPartial()
                val title = when (name) { "calculator" -> tr("计算器"); "get_time" -> tr("当前时间"); "memory_update" -> tr("维护长期记忆"); "session_search" -> tr("查找历史记录"); "extension_search" -> tr("搜索扩展"); "extension_read" -> tr("读取扩展"); else -> tr("设备信息") }
                append(Role.MODEL, title, isTool = true, detail = buildJsonObject {
                    put("name", name); put("arguments", arguments); put("result", result)
                }.toString())
            }
            try {
                historyNotice.value = ""
                if (conversationId.value.isEmpty()) conversationId.value = history.createChat(
                    if (cloudMode) ConversationKind.CLOUD else ConversationKind.LOCAL, input)
                append(Role.USER, input)
                ensureDraft()
                streaming.value = ""
                harnessPhase.value = tr("正在思考…")
                val answer = if (cloudMode) cloudAgent!!.chat(input, onText = { piece ->
                    token(piece)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSave >= 400) {
                        val pending = ensureDraft()
                        val content = partial.toString()
                        withContext(Dispatchers.IO) { history.updateMessage(pending.id, content, "streaming") }
                        lastSave = now
                    }
                }, onPhase = { harnessPhase.value = it }) { event -> tool(event.name, event.arguments, event.result) }
                else agent.chat(input, onToken = { piece ->
                    token(piece)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSave >= 400) {
                        history.updateMessage(ensureDraft().id, partial.toString(), "streaming"); lastSave = now
                    }
                }, onTool = { call, result -> tool(call.name, call.arguments, result) }).answer
                val detail = if (cloudMode) buildJsonObject { put("harness", Json.encodeToJsonElement(cloudAgent!!.lastStats)) }.toString() else ""
                val completed = ensureDraft().copy(content = answer, state = "complete", detail = detail)
                val nextContext = contextSnapshot()
                // Final answer and protocol context commit together, so restored tools stay paired.
                history.updateMessage(completed.id, answer, "complete", nextContext, detail)
                savedContext = nextContext
                messages.value += completed
                draft = null
            } catch (e: CancellationException) {
                runCatching {
                    if (partial.isEmpty()) partial.append(tr("已停止生成。"))
                    ensureDraft(); flushPartial("stopped")
                }.onFailure { historyNotice.value = tr("保存失败，请检查手机存储空间。") }
                restoreContext()
                throw e
            } catch (e: Throwable) {
                if (e !is Exception && e !is LinkageError) throw e
                runCatching {
                    flushPartial("interrupted")
                    append(Role.MODEL, if (e is LinkageError) tr("本地推理库未安装或版本不兼容，请切换云端对话。") else tr("未完成：${e.message ?: "请重试"}", "Could not complete: ${systemText(e.message ?: "Please try again")}"))
                }.onFailure { historyNotice.value = tr("保存失败，请检查手机存储空间后重试。") }
                restoreContext()
            } finally {
                streaming.value = null
                busy.value = false
            }
        }
    }

    fun stop() { engine?.cancel(); activeJob?.cancel() }
    override fun onCleared() { stop(); engine?.close(); super.onCleared() }

    /** New conversation: old messages remain in history. The empty draft is not listed. */
    fun clear() {
        if (busy.value) return
        conversationId.value = ""
        history.selectedId = ""
        savedContext = "[]"
        if (cloudMode) cloudAgent?.clear() else agent.clearMemory()
        messages.value = emptyList()
        streaming.value = null
        historyNotice.value = ""
    }

    /** 引擎装配：优先 llama.cpp，缺模型文件/so 时回退 Mock（README 有说明） */
    private fun createAgent(): Agent {
        val config = AgentConfig(contextWindow = 4096)
        val modelDir = File(app.getExternalFilesDir(null), "models")
        val candidate = listOf(
            "gemma-3-4b-it-Q4_K_M.gguf",
            "gemma-3-1b-it-Q4_K_M.gguf",
        ).map { File(modelDir, it) }.firstOrNull { it.exists() }

        return if (candidate != null) {
            // 真实引擎：libllama.so 需随 APK 打包（native/ 编译产物）
            try {
                val local = LlamaCppEngine(candidate.absolutePath, config)
                engine = local
                localStatus = tr("本地模型：${candidate.name}（首次发送时加载）", "Local model: ${candidate.name} (loads on first message)")
                AgentLoop.assemble(local, config)
            } catch (e: UnsatisfiedLinkError) {
                // 尚未编译原生库：回退 Mock，保证 UI 可跑通
                localStatus = tr("演示模式：缺少本地推理库")
                AgentLoop.mock(config)
            }
        } else {
            localStatus = tr("演示模式：未安装本地模型，回答为测试回显")
            AgentLoop.mock(config)
        }
    }
}
