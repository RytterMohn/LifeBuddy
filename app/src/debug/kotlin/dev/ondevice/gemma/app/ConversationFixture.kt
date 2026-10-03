package dev.ondevice.gemma.app

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.PackageManager
import dev.ondevice.gemma.app.data.ConversationKind
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Only callable through the signature-protected debug activity. Never targets a real messaging App. */
internal object ConversationFixture {
    private const val fixture = "dev.ondevice.gemma.fixture"
    private fun authorized(activity: Activity) {
        check(activity.packageManager.checkSignatures(activity.packageName, fixture) == PackageManager.SIGNATURE_MATCH)
        check(!PhoneController.busy)
    }

    private fun storageCheck(activity: Activity) {
        val name = "conversation-test-${UUID.randomUUID()}"
        val root = File(activity.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(activity) { override fun getNoBackupFilesDir() = root }
        try {
            val request = MessageRequest("qq", fixture, "测试联系人", "STORAGE_ONLY", false)
            val first = PhoneRun("original", "准备测试消息", RunStatus.PAUSED, "等待回复", messageRequest = request,
                followUps = listOf(PhoneFollowUp("QQ", 1)))
            val split1 = PhoneRun("split1", "再发一次", RunStatus.PAUSED, "缺少历史")
            val split2 = split1.copy(id = "split2")
            val unrelated = PhoneRun("unrelated", "独立测试", RunStatus.COMPLETED, "独立结果")
            HistoryStore(context, name).use { store ->
                listOf(first, split1, split2, unrelated).forEachIndexed { index, run -> store.saveRun(run, ConversationKind.TASK, index.toLong()) }
                store.append(split1.id, Role.USER, "附加记录")
                val merged = store.mergeTaskConversations(listOf(first.id, split1.id, split2.id))
                check(merged.id == first.id && merged.previousTurns.size == 2)
                check(store.load(first.id)!!.summary.title == first.goal)
                check(store.load(first.id)!!.messages.single().content == "附加记录")
                check(store.load(split1.id) == null && store.load(split2.id) == null)
                check(store.load(unrelated.id)!!.run == unrelated)
                check(store.search("STORAGE_ONLY").single().id == first.id)
                val backup = root.listFiles()!!.single { it.name.startsWith("conversation-merge-") }
                val snapshot = Json.parseToJsonElement(backup.readText()).jsonObject
                check(snapshot["conversations"]!!.jsonArray.size == 3 && snapshot["messages"]!!.jsonArray.size == 1)
            }
            HistoryStore(context, name).use { reopened ->
                val loaded = reopened.load(first.id)!!.run!!
                check(loaded.previousTurns.first().followUps.single().text == "QQ")
                check(reopened.selectedId == first.id && reopened.conversations.value.size == 2)
                val next = PhoneConversation.nextTurn(loaded, "继续", setOf(fixture)).copy(status = RunStatus.COMPLETED)
                reopened.saveRun(next, ConversationKind.TASK)
                check(reopened.conversations.value.size == 2 && reopened.load(first.id)!!.run!!.previousTurns.size == 3)
            }
            HistoryStore(context, name).use { reopened -> check(reopened.load(first.id)!!.run!!.goal == "继续") }
        } finally {
            root.deleteRecursively()
            activity.deleteSharedPreferences("$name-state")
        }
    }

    suspend fun regression(activity: Activity) {
        val history = HistoryStore.get(activity)
        val selected = history.selectedId
        var created = ""
        var stage = "storage"
        var result = "FAIL"
        var returned = 0
        val runs = mutableListOf<PhoneRun>()
        try {
            authorized(activity)
            storageCheck(activity)
            withTimeout(360_000) {
                withTimeout(15_000) { while (!PhoneController.connected.value) delay(100) }
                suspend fun execute(goal: String, previous: PhoneRun? = null): PhoneRun {
                    PhoneController.start(activity, goal, mapOf(fixture to "离线工具测试信箱（模拟 QQ）"), true, conversation = previous)
                    while (PhoneController.busy) {
                        val current = PhoneController.run.value
                        if (current.goal == goal && current.allowedPackages == setOf(fixture, activity.packageName)) created = current.id
                        File(activity.noBackupFilesDir, "conversation-alpha13-progress.json").writeText(buildJsonObject {
                            put("stage", stage); put("steps", current.steps.size); put("status", current.status.name)
                        }.toString())
                        check(current.status != RunStatus.WAITING_APPROVAL)
                        delay(150)
                    }
                    val current = PhoneController.run.value
                    check(current.id == created && current.status == RunStatus.COMPLETED)
                    runs += current
                    delay(700)
                    val page = PhoneController.diagnosticService().observe(setOf(activity.packageName))
                    check(page.packageName == activity.packageName && page.notice.contains("Agent 控制页") && history.selectedId == created)
                    returned++
                    return current
                }
                val request = MessageRequest("app", fixture, "测试联系人", "HARNESS_CONVERSATION_13", false)
                stage = "initial-send"
                val first = execute("用模拟 QQ（离线工具测试信箱）给测试联系人发送 HARNESS_CONVERSATION_13。这是本地模拟，不打开真实 QQ。")
                check(first.messageRequest == request && first.messageOutcome == MessageOutcome.VERIFIED)
                stage = "repeat-in-same-conversation"
                val second = execute("那是之前发的，再发一次", history.load(first.id)!!.run)
                check(second.id == first.id && second.executionId != first.executionId)
                check(second.previousTurns.single() == first.archiveTurn())
                check(second.messageRequest == request && second.messageOutcome == MessageOutcome.VERIFIED)
                check(second.steps.single { it.action.type == PhoneActionType.SEND_MESSAGE }.matchingMessagesBefore >= 1)
                check(listOf(first, second).all { run -> run.steps.count { it.action.type == PhoneActionType.SEND_MESSAGE && it.dispatched } == 1 })
                stage = "reply-without-send"
                val third = execute("谢谢，不用再发了", history.load(first.id)!!.run)
                check(third.previousTurns.size == 2 && third.id == first.id && third.messageRequest == null)
                check(third.steps.none { it.dispatched && it.action.type in PhonePolicy.deviceActions })
                check(third.steps.last().action.type == PhoneActionType.RESPOND)
                stage = "database-reopen"
                HistoryStore(activity).use { reopened ->
                    check(reopened.load(first.id)!!.run == third)
                    check(reopened.conversations.value.count { it.id == first.id } == 1)
                    check(reopened.search("HARNESS_CONVERSATION_13").single().id == first.id)
                }
                result = "PASS: three turns, one conversation, two simulated sends, zero approvals, third reply without send; local history survives reopen; merge preserves every turn and backs up affected rows"
            }
        } catch (error: Exception) { result = "FAIL at $stage: ${error.javaClass.simpleName}" }
        finally {
            if (created.isNotBlank() && PhoneController.busy && PhoneController.run.value.id == created) PhoneController.stop()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { while (PhoneController.busy) delay(100) } }
            if (created.isNotBlank() && runs.lastOrNull() != PhoneController.run.value) runs += PhoneController.run.value
            File(activity.noBackupFilesDir, "conversation-regression-alpha13.json").writeText(buildJsonObject {
                put("result", result); put("returnedToAgentCount", returned); put("runs", Json.encodeToJsonElement(runs))
            }.toString())
            if (created.isNotBlank() && !PhoneController.busy) {
                history.delete(created); PhoneController.forgetDeletedRun(created); history.selectedId = selected
                PhoneController.diagnosticService().returnToAgent(selected)
            }
        }
    }

    /** Explicit IDs are supplied for a user-requested history repair. No planner or device execution. */
    suspend fun repair(activity: Activity, rawIds: String) {
        var result = "FAIL"
        var conversationId = ""
        var turns = 0
        try {
            authorized(activity)
            val ids = rawIds.split(',').map { UUID.fromString(it).toString() }
            val merged = HistoryStore.get(activity).mergeTaskConversations(ids)
            conversationId = merged.id; turns = merged.previousTurns.size + 1
            PhoneController.reloadSavedRun()
            PhoneController.diagnosticService().returnToAgent(merged.id)
            result = "PASS: backed up and merged only the specified records; no model or send executed"
        } catch (error: Exception) { result = "FAIL: ${error.javaClass.simpleName}" }
        File(activity.noBackupFilesDir, "conversation-repair-alpha13.json").writeText(buildJsonObject {
            put("result", result); put("conversationId", conversationId); put("turns", turns)
        }.toString())
    }
}
