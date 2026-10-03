package dev.ondevice.gemma.app

import android.app.Application
import android.app.Instrumentation
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import dev.ondevice.gemma.app.data.*
import dev.ondevice.gemma.app.ui.ChatViewModel
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Test APK only. Storage fixtures live outside production history; API tests use synthetic conversations. */
class HistorySmokeInstrumentation : Instrumentation() {
    private var realApi = false
    private var inspectUi = false
    private var cancelTest = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); realApi = arguments?.getString("realApi") == "true"; inspectUi = arguments?.getString("inspectUi") == "true"; cancelTest = arguments?.getString("cancelTest") == "true"; start() }
    override fun onStart() {
        Thread {
            val output = Bundle()
            var stage = "storage"
            try {
                if (cancelTest) {
                    stage = "cancelled_stream_history"
                    runBlocking { withTimeout(90000) {
                        val history = HistoryStore.get(targetContext)
                        val previous = history.selectedId
                        var vm: ChatViewModel? = null
                        runOnMainSync {
                            vm = ChatViewModel(targetContext.applicationContext as Application)
                            vm!!.selectCloud(true); vm!!.clear()
                            vm!!.send("请写一篇一千字的中文植物介绍，用于测试停止生成。")
                        }
                        while (vm!!.busy.value && vm!!.streamingFlow.value.isNullOrEmpty()) delay(25)
                        check(vm!!.busy.value) { "response_finished_before_cancel" }
                        runOnMainSync { vm!!.stop() }
                        while (vm!!.busy.value) delay(25)
                        val id = vm!!.conversationId.value
                        val saved = history.load(id)!!
                        check(saved.messages.last().state == "stopped" && saved.messages.last().content.isNotEmpty())
                        check(saved.messages.none { it.state == "streaming" } && saved.context == "[]")
                        runOnMainSync { vm = ChatViewModel(targetContext.applicationContext as Application); vm!!.openConversation(id) }
                        check(vm!!.messagesFlow.value.last().state == "stopped")
                        history.delete(id); history.selectedId = previous
                    } }
                    output.putString("result", "PASS: actual cloud generation cancelled after first visible delta; partial reply survives ViewModel recreation; incomplete context excluded; synthetic test record cleaned up")
                    finish(0, output)
                    return@Thread
                }
                if (inspectUi) {
                    val history = HistoryStore.get(targetContext)
                    val titles = setOf("Markdown 排版演示", "计算与工具测试", "另一条独立对话", "在练习页面填写并保存第一版验证", "Open the Agent practice page, type CloudAPI_OK_2, save the p")
                    output.putString("historyMetadata", history.conversations.value.joinToString(" | ") { "${it.kind}: ${it.title}" })
                    stage = "history_titles"
                    check(history.conversations.value.all { it.title in titles })
                    stage = "practice_task_content"
                    history.conversations.value.filter { !ConversationKind.isChat(it.kind) }.forEach { summary ->
                        val task = history.load(summary.id)!!.run!!
                        check(task.goal == "在练习页面填写并保存第一版验证" || task.goal.startsWith("Open the Agent practice page, type CloudAPI_OK_2,"))
                        check(task.allowedPackages.all { it == targetContext.packageName })
                        check(task.steps.all { it.action.text.isEmpty() || it.action.text in setOf("第一版验证", "CloudAPI_OK_2") })
                    }
                    stage = "selected_synthetic_markdown"
                    val record = history.load(history.selectedId)!!
                    check(record.summary.title == "Markdown 排版演示")
                    val users = record.messages.filter { it.role == Role.USER }
                    check(users.size == 1 && users.single().content == "请用中文演示 Markdown 格式：标题叫“手机上的小助手”，一句含加粗的介绍，两条列表，一个两列两行的功能表，以及一行 Kotlin 代码块。内容简短。")
                    check(record.messages.all { it.role == Role.USER || (it.role == Role.MODEL && !it.isTool) })
                    output.putString("result", "PASS: selected conversation contains only the synthetic Markdown prompt and generated answer; sidebar titles are synthetic tests; phone history contains only the built-in practice task")
                    finish(0, output)
                    return@Thread
                }
                val checks = mutableListOf<String>()
                val root = File(targetContext.cacheDir, "history-storage-test").apply { deleteRecursively(); mkdirs() }
                val isolated = object : ContextWrapper(targetContext) { override fun getNoBackupFilesDir() = root }
                var store = HistoryStore(isolated, "test-history.db")
                val first = store.createChat(ConversationKind.CLOUD, "标题一")
                store.append(first, Role.USER, "中文内容：银杏 100%_literal '")
                val draft = store.append(first, Role.MODEL, "", state = "streaming")
                store.updateMessage(draft.id, "已收到的流式内容", "streaming")
                val second = store.createChat(ConversationKind.CLOUD, "标题二")
                store.append(second, Role.USER, "第二条独立内容")
                check(store.search("银杏").single().id == first)
                check(store.search("%_literal '").single().id == first)
                check(store.search("不存在").isEmpty())
                store.append(second, Role.MODEL, "计算器", isTool = true, detail = "unique-tool-result")
                check(store.search("unique-tool-result").single().id == second)
                store.rename(first, "修改后的标题")
                check(store.load(first)!!.messages.size == 2)
                store.close()
                store = HistoryStore(isolated, "test-history.db")
                check(store.load(first)!!.summary.title == "修改后的标题")
                check(store.load(first)!!.messages.last().state == "interrupted")
                check(store.load(first)!!.messages.last().content == "已收到的流式内容")
                checks += "messages, literal search, rename and interrupted stream survive database reopen"
                val final = store.append(second, Role.MODEL, "", state = "streaming")
                store.updateMessage(final.id, "最终结果", "complete", "[[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":\"ok\"}]]")
                val saved = store.load(second)!!
                check(saved.messages.last().content == "最终结果" && saved.context.contains("hello"))
                store.delete(first)
                check(store.load(first) == null && store.search("银杏").isEmpty())
                SQLiteDatabase.openDatabase(File(root, "test-history.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM messages WHERE conversation_id=?", arrayOf(first)).use { c -> c.moveToFirst(); check(c.getInt(0) == 0) }
                }
                checks += "answer and context committed together; deletion cascades messages"
                repeat(125) { index -> store.createChat(ConversationKind.LOCAL, "保留记录 $index") }
                check(store.conversations.value.size == 126)
                val running = PhoneRun(id = "running-fixture", goal = "中断任务", status = RunStatus.WAITING_APPROVAL,
                    steps = listOf(PhoneStep(1, PhoneAction("s", PhoneActionType.TYPE, "输入测试", text = "你好"))))
                store.saveRun(running, ConversationKind.TASK)
                val legacy = running.copy(id = "legacy-fixture", status = RunStatus.COMPLETED, goal = "在练习页面填写并保存第一版验证")
                File(root, "last-phone-run.json").writeText(Json.encodeToString(legacy))
                store.migrateLegacyRun(isolated)
                check(store.load(legacy.id)!!.summary.kind == ConversationKind.PRACTICE)
                check(!File(root, "last-phone-run.json").exists())
                store.close(); store = HistoryStore(isolated, "test-history.db")
                check(store.load(running.id)!!.run!!.status == RunStatus.PAUSED)
                check(store.load(legacy.id)!!.run!!.steps.single().action.text == "你好")
                store.delete(legacy.id); store.migrateLegacyRun(isolated)
                check(store.load(legacy.id) == null)
                checks += "125 older conversations retained; multiple tasks retained; legacy migration once; interrupted task paused without replay"
                store.close(); root.deleteRecursively()

                if (realApi) runBlocking { withTimeout(180000) {
                    val app = targetContext.applicationContext as Application
                    var vm: ChatViewModel? = null
                    fun onMain(block: () -> Unit) = runOnMainSync(block)
                    suspend fun send(text: String) {
                        onMain { vm!!.send(text) }
                        while (vm!!.busy.value) delay(100)
                        check(vm!!.historyNotice.value.isEmpty())
                        check(vm!!.messagesFlow.value.last().content.let { !it.startsWith("未完成：") })
                    }
                    stage = "real_tools_and_history"
                    onMain { vm = ChatViewModel(app); vm!!.selectCloud(true); vm!!.clear() }
                    send("请记住本次会话的暗号是“银杏”。请实际调用 calculator 计算 128*37，用一句中文汇总。")
                    val firstId = vm!!.conversationId.value
                    val history = vm!!.history
                    check(history.load(firstId)!!.messages.any { it.isTool && "4736" in it.detail })
                    history.rename(firstId, "计算与工具测试")
                    onMain { vm!!.clear() }
                    stage = "second_conversation"
                    send("请记住本次会话的暗号是“海棠”。只回复确认。")
                    val secondId = vm!!.conversationId.value
                    history.rename(secondId, "另一条独立对话")
                    stage = "restored_context_isolation"
                    onMain { vm = ChatViewModel(app); vm!!.openConversation(firstId) }
                    val beforeTools = vm!!.messagesFlow.value.count { it.isTool }
                    send("本次会话的暗号是什么？请仅回复暗号。")
                    check("银杏" in vm!!.messagesFlow.value.last().content && "海棠" !in vm!!.messagesFlow.value.last().content)
                    check(vm!!.messagesFlow.value.count { it.isTool } == beforeTools)
                    onMain { vm!!.openConversation(secondId) }
                    send("本次会话的暗号是什么？请仅回复暗号。")
                    check("海棠" in vm!!.messagesFlow.value.last().content && "银杏" !in vm!!.messagesFlow.value.last().content)
                    checks += "real API: calculator 4736 saved; two restored conversations recall distinct passphrases; no historical tool replay"
                    stage = "markdown_history"
                    onMain { vm!!.clear() }
                    send("请用中文演示 Markdown 格式：标题叫“手机上的小助手”，一句含加粗的介绍，两条列表，一个两列两行的功能表，以及一行 Kotlin 代码块。内容简短。")
                    history.rename(vm!!.conversationId.value, "Markdown 排版演示")
                    checks += "real streamed Markdown reply persisted"
                } }
                File(targetContext.noBackupFilesDir, "history-smoke-result.json").writeText(buildJsonObject {
                    put("success", true); put("realApi", realApi); putJsonArray("checks") { checks.forEach { add(it) } }
                }.toString())
                output.putString("result", "PASS: " + checks.joinToString("; "))
                finish(0, output)
            } catch (error: Exception) {
                output.putString("result", "FAIL at $stage: ${error.javaClass.simpleName}")
                finish(1, output)
            }
        }.start()
    }
}
