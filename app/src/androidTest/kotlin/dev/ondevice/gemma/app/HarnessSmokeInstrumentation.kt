package dev.ondevice.gemma.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import dev.ondevice.gemma.app.data.*
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.cloud.*
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Only synthetic fixtures. Production conversation and memory data are never read by this suite. */
class HarnessSmokeInstrumentation : Instrumentation() {
    private var realApi = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); realApi = arguments?.getString("realApi") == "true"; start() }
    override fun onStart() {
        Thread {
            val output = Bundle()
            var stage = "storage"
            val checks = mutableListOf<String>()
            val metrics = mutableListOf<HarnessStats>()
            val root = File(targetContext.cacheDir, "harness-alpha06-test").apply { deleteRecursively(); mkdirs() }
            val isolated = object : ContextWrapper(targetContext) { override fun getNoBackupFilesDir() = root }
            val history = HistoryStore(isolated, "harness-test.db")
            try {
                val store = AgentMemoryStore(isolated)
                var id = history.createChat(ConversationKind.CLOUD, "Harness synthetic A")
                val firstId = id
                history.append(id, Role.USER, "中文关键词：银杏 100%_literal '")
                val secondId = history.createChat(ConversationKind.CLOUD, "Harness synthetic B")
                history.append(secondId, Role.USER, "另一条会话的隔离内容")
                val memory = ConversationMemory(store, history, { id })
                check(history.searchMessages(firstId, "%_literal '").size == 1)
                check(history.searchMessages(secondId, "银杏").isEmpty())
                history.append(firstId, Role.MODEL, "计算器", isTool = true, detail = "fixture-calculation-4736")
                check(history.searchMessages(firstId, "fixture-calculation-4736").single().jsonObject["isTool"] == JsonPrimitive(true))
                fun update(text: String, user: String): JsonObject = Json.parseToJsonElement(memory.update(buildJsonObject {
                    put("operation", "add"); put("category", "profile"); put("id", ""); put("text", text)
                }, user)).jsonObject
                check(update("虚构事实", "请记住我的偏好")["saved"] == JsonPrimitive(false))
                check(update("偏好中文", "偏好中文")["saved"] == JsonPrimitive(false))
                check(update("偏好中文", "请记住：偏好中文")["saved"] == JsonPrimitive(true))
                check(AgentMemoryStore(isolated).read().single().text == "偏好中文")
                store.update("remove", "profile", store.read().single().id, "", "test")
                check(store.read().isEmpty())
                checks += "memory provenance, persistence, removal and literal scoped history search"

                if (realApi) runBlocking { withTimeout(300_000) {
                    val config = AgentSettings(targetContext).read()
                    CloudApiClient.validate(config)
                    val client = CloudApiClient(config)
                    stage = "real_memory_write"
                    val events = mutableListOf<ChatToolResult>()
                    val request = "请调用 memory_update 记住这条长期偏好：我喜欢先看到简短结论，再看具体步骤。"
                    history.append(id, Role.USER, request)
                    val agent = CloudChatAgent(config.model, client, memory)
                    agent.chat(request, onTool = events::add)
                    check(events.any { it.name == "memory_update" } && store.read().any { "结论" in it.text && "步骤" in it.text })
                    metrics += agent.lastStats
                    stage = "real_cross_session_memory"
                    id = secondId
                    val fresh = CloudChatAgent(config.model, client, memory)
                    val recalled = fresh.chat("我偏好的回答顺序是什么？用一句话回答。")
                    check("结论" in recalled && "步骤" in recalled)
                    metrics += fresh.lastStats
                    stage = "real_memory_remove"
                    fresh.chat("请调用 memory_update，忘记我关于回答顺序的长期记忆。")
                    check(store.read().isEmpty())
                    metrics += fresh.lastStats
                    checks += "real API: explicit memory write, new-session recall and removal"

                    stage = "real_compaction"
                    id = firstId
                    val fixture = JsonArray((0 until 8).map { i ->
                        val user = if (i == 0) "本项目代号为杉木-731。输出格式约定为三条中文列表。" else "阶段 $i 已核对，继续前面的约定。"
                        history.append(id, Role.USER, user); history.append(id, Role.MODEL, "已确认")
                        JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", user) }, buildJsonObject { put("role", "assistant"); put("content", "已确认") }))
                    })
                    // Short fixture forces the same compaction path without spending dozens of API calls.
                    val long = CloudChatAgent(config.model, client, memory, HarnessLimits(maxTurns = 8))
                    long.restoreState(fixture)
                    val answer = long.chat("本项目的代号和输出格式约定是什么？")
                    check("杉木-731" in answer && "三" in answer && "中文" in answer)
                    check(long.lastStats.compactions == 1)
                    metrics += long.lastStats
                    val restored = CloudChatAgent(config.model, client, memory)
                    restored.restoreState(Json.parseToJsonElement(long.snapshotState().toString()))
                    stage = "real_history_retrieval"
                    val retrievalEvents = mutableListOf<ChatToolResult>()
                    val retrieval = restored.chat("请调用 session_search，用关键词“100%_literal”查找当前会话原始记录，并告诉我包含的中文关键词。", onTool = retrievalEvents::add)
                    check(retrievalEvents.any { it.name == "session_search" && "银杏" in it.result })
                    check("银杏" in retrieval)
                    metrics += restored.lastStats
                    checks += "real API: eight-turn checkpoint retains early constraints; restored agent retrieves original evidence"

                    stage = "real_phone_planning"
                    // Uses synthetic accessibility text; no external action and no personal screen data.
                    val screen = ScreenSnapshot("fixture-alpha06", targetContext.packageName, 0, "fixture", listOf(ScreenNode("n0", "已保存：HARNESS_ALPHA06_OK")))
                    val planned = CloudPhonePlanner(config).next(PlannerInput("确认 HARNESS_ALPHA06_OK 已保存，若页面有证据则完成。", screen, mapOf(targetContext.packageName to "练习页面"), emptyList()))
                    check(planned.type == PhoneActionType.FINISH && PhonePolicy.validate(planned, screen, setOf(targetContext.packageName)) == null)
                    checks += "real API: phone planner returns complete native tool call with exact visible evidence (synthetic screen)"
                } }
                val evidence = buildJsonObject {
                    put("success", true); put("realApi", realApi)
                    putJsonArray("checks") { checks.forEach { add(it) } }
                    put("metrics", Json.encodeToJsonElement(metrics))
                }
                File(targetContext.noBackupFilesDir, "harness-alpha06-result.json").writeText(evidence.toString())
                output.putString("result", "PASS: " + checks.joinToString("; "))
                finish(0, output)
            } catch (error: Exception) {
                output.putString("result", "FAIL at $stage: ${error.javaClass.simpleName}")
                finish(1, output)
            } finally { history.close(); root.deleteRecursively() }
        }.start()
    }
}
