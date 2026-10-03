package dev.ondevice.gemma.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Debug-only, signature-protected fixed tasks; calls the configured model with synthetic App data. */
class ToolsFixtureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preserveForReturnTest = false
    private val fixture = "dev.ondevice.gemma.fixture"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.offline_fixture_running); textSize = 22f; setPadding(32, 400, 32, 32) })
        if (PhoneController.busy) { finish(); return }
        if(intent.getBooleanExtra("systemRegression",false)) {
            preserveForReturnTest=true
            scope.launch { SystemToolsFixture.regression(this@ToolsFixtureActivity);scope.cancel() }
            return
        }
        if(intent.getBooleanExtra("autoSkillSeed",false) || intent.getBooleanExtra("autoSkillReuse",false)) {
            preserveForReturnTest=true
            scope.launch { AutoSkillFixture.regression(this@ToolsFixtureActivity,intent.getBooleanExtra("autoSkillReuse",false)); scope.cancel() }
            return
        }
        if (intent.getBooleanExtra("appSkillsRegression", false)) {
            preserveForReturnTest = true
            scope.launch { AppSkillsFixture.regression(this@ToolsFixtureActivity); scope.cancel() }
            return
        }
        if (intent.getBooleanExtra("conversationRegression", false)) {
            preserveForReturnTest = true
            scope.launch { ConversationFixture.regression(this@ToolsFixtureActivity); scope.cancel() }
            return
        }
        if (intent.getBooleanExtra("repairHistory", false)) {
            preserveForReturnTest = true
            scope.launch { ConversationFixture.repair(this@ToolsFixtureActivity, intent.getStringExtra("repairIds").orEmpty()); scope.cancel() }
            return
        }
        if (intent.getBooleanExtra("messageRegression", false)) { preserveForReturnTest = true; messageRegression(); return }
        if (intent.getBooleanExtra("latency", false)) { runLatencyCheck(); return }
        scope.launch {
            val history = HistoryStore.get(this@ToolsFixtureActivity)
            val previous = history.selectedId
            val created = mutableSetOf<String>()
            val runs = mutableListOf<PhoneRun>()
            var stage = "service"
            var result = "未完成"
            fun saveProgress() {
                File(noBackupFilesDir, "tools-alpha08-progress.json").writeText(buildJsonObject {
                    put("stage", stage); put("steps", PhoneController.run.value.steps.size); put("status", PhoneController.run.value.status.name)
                }.toString())
            }
            try {
                check(packageManager.checkSignatures(packageName, fixture) == android.content.pm.PackageManager.SIGNATURE_MATCH)
                withTimeout(360_000) {
                    withTimeout(15_000) { while (!PhoneController.connected.value) delay(200) }
                    val apps = mapOf(fixture to "离线工具测试信箱（模拟 QQ 联系人和短信草稿）")
                    suspend fun waitForTask(): PhoneRun {
                        var reported = -1
                        while (PhoneController.busy) {
                            val run = PhoneController.run.value
                            if (run.id.isNotEmpty() && run.allowedPackages == setOf(fixture, packageName)) created += run.id
                            if (run.steps.size != reported) { reported = run.steps.size; saveProgress() }
                            check(run.status != RunStatus.WAITING_APPROVAL) { "Unexpected approval in autonomous mode" }
                            delay(100)
                        }
                        return PhoneController.run.value
                    }
                    for ((request, goal) in listOf(
                        MessageRequest("app", fixture, "测试联系人", "HARNESS_QQ_LOCAL_08", false) to
                            "在离线工具测试信箱（模拟 QQ）给测试联系人发一条消息，正文原文是 HARNESS_QQ_LOCAL_08。这是本地模拟任务，不要打开真实 QQ。",
                        MessageRequest("sms", fixture, "15500000000", "HARNESS_SMS_DRAFT_08", true) to
                            "在离线工具测试信箱准备一条给 15500000000 的短信草稿，内容为 HARNESS_SMS_DRAFT_08，不要发送。",
                    )) {
                        stage = request.channel
                        PhoneController.start(this@ToolsFixtureActivity, goal, apps, true, returnToAgent = false)
                        val run = waitForTask()
                        runs += run
                        check(run.status == RunStatus.COMPLETED && run.messageRequest == request)
                        check(run.steps.any { it.action.type == PhoneActionType.PREPARE_MESSAGE })
                        check(run.steps.count { it.action.type == PhoneActionType.SEND_MESSAGE && it.dispatched } == if (request.draftOnly) 0 else 1)
                    }
                    stage = "clarification"
                    PhoneController.start(this@ToolsFixtureActivity, "在离线工具测试信箱（模拟 QQ）帮我准备一条消息，但收件人和正文我还没告诉你。", apps, true, returnToAgent = false)
                    val question = waitForTask()
                    runs += question
                    check(question.status == RunStatus.PAUSED && question.steps.last().action.type == PhoneActionType.ASK_USER)
                    PhoneController.start(this@ToolsFixtureActivity, "给测试联系人准备正文为 HARNESS_FOLLOWUP_08 的消息，只要草稿，不发送。", apps, true, resume = question, returnToAgent = false)
                    val continued = waitForTask()
                    runs += continued
                    check(continued.id == question.id && continued.followUps.size == 1 && continued.status == RunStatus.COMPLETED)
                    check(continued.messageRequest?.body == "HARNESS_FOLLOWUP_08" && continued.messageRequest?.draftOnly == true)
                    stage = "respond"
                    PhoneController.start(this@ToolsFixtureActivity, "这次不需要操作任何应用，请直接回答：你好。", apps, true, returnToAgent = false)
                    val answer = waitForTask()
                    runs += answer
                    check(answer.status == RunStatus.COMPLETED && answer.steps.last().action.type == PhoneActionType.RESPOND)
                    check(answer.steps.none { it.dispatched && it.action.type in PhonePolicy.deviceActions })
                }
                result = "PASS: natural language without message forms; QQ simulation and SMS draft; clarification continued in same history; direct answer; zero send confirmations"
            } catch (error: Exception) {
                result = "FAIL at $stage: ${error.javaClass.simpleName}"
            } finally {
                if (PhoneController.busy) PhoneController.stop()
                withContext(NonCancellable) { withTimeoutOrNull(5000) { while (PhoneController.busy) delay(50) } }
                val last = PhoneController.run.value
                if (last.id in created && runs.none { it == last }) runs += last
                File(noBackupFilesDir, "tools-alpha08-result.json").writeText(buildJsonObject {
                    put("result", result); put("stage", stage); put("runs", Json.encodeToJsonElement(runs))
                }.toString())
                created.forEach { id -> history.delete(id); if (!PhoneController.busy) PhoneController.forgetDeletedRun(id) }
                history.selectedId = previous
                stage = "finished"; saveProgress()
            }
        }
    }
    override fun onDestroy() { if (!preserveForReturnTest) scope.cancel(); super.onDestroy() }

    /** Real model + accessibility, only the isolated mailbox; returning home must not kill this observer. */
    private fun messageRegression() = scope.launch {
        val history = HistoryStore.get(this@ToolsFixtureActivity)
        val previous = history.selectedId
        val request = MessageRequest("app", fixture, "测试联系人", "HARNESS_MESSAGE_12", false)
        val goal = "用模拟 QQ（离线工具测试信箱）给测试联系人发送 HARNESS_MESSAGE_12。这是本地模拟，不打开真实 QQ。"
        var created = ""
        var captured = PhoneRun()
        var result = "FAIL"
        var returned = false
        var approvals = 0
        try {
            check(packageManager.checkSignatures(packageName, fixture) == android.content.pm.PackageManager.SIGNATURE_MATCH)
            withTimeout(180_000) {
                withTimeout(15_000) { while (!PhoneController.connected.value) delay(100) }
                val prior = PhoneController.run.value.id
                PhoneController.start(this@ToolsFixtureActivity, goal, mapOf(fixture to "离线工具测试信箱（模拟 QQ）"), true)
                while (PhoneController.busy) {
                    val run = PhoneController.run.value
                    if (run.id != prior && run.goal == goal && run.allowedPackages == setOf(fixture, packageName)) {
                        created = run.id; captured = run
                        if (run.status == RunStatus.WAITING_APPROVAL) {
                            approvals++
                            error("Unexpected approval in autonomous mode")
                        }
                    }
                    delay(100)
                }
                captured = PhoneController.run.value
                check(captured.id == created && captured.messageRequest == request)
                check(captured.status == RunStatus.COMPLETED && captured.messageOutcome == MessageOutcome.VERIFIED)
                check(approvals == 0 && captured.steps.count { it.action.type == PhoneActionType.SEND_MESSAGE && it.dispatched } == 1)
                check(captured.steps.any { it.action.type == PhoneActionType.PREPARE_MESSAGE && it.dispatched })
                check(captured.steps.takeLast(2).map { it.action.type } == listOf(PhoneActionType.VERIFY_MESSAGE, PhoneActionType.FINISH))
                delay(700)
                val page = PhoneController.diagnosticService().observe(setOf(packageName))
                returned = page.packageName == packageName && page.notice.contains("Agent 控制页") && history.selectedId == created
                check(returned)
                result = "PASS: natural language, zero approvals, one simulated send, local verification, automatic return to Agent"
            }
        } catch (error: Exception) { result = "FAIL: ${error.javaClass.simpleName}" }
        finally {
            if (created.isNotBlank() && PhoneController.busy && PhoneController.run.value.id == created) PhoneController.stop()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { while (PhoneController.busy) delay(100) } }
            File(noBackupFilesDir, "message-regression-alpha12.json").writeText(buildJsonObject {
                put("result", result); put("returnedToAgent", returned); put("approvals", approvals); put("run", Json.encodeToJsonElement(captured))
            }.toString())
            if (created.isNotBlank() && !PhoneController.busy) {
                history.delete(created); PhoneController.forgetDeletedRun(created); history.selectedId = previous
                PhoneController.diagnosticService().returnToAgent(previous)
            }
            scope.cancel()
        }
    }

    /** Fixed own-App task only; exercises real accessibility and API without touching other App contents. */
    private fun runLatencyCheck() = scope.launch {
        val history = HistoryStore.get(this@ToolsFixtureActivity)
        val goal = "打开手机 Agent 的内置练习页面，输入 SPEED_ALPHA10_OK，点击保存练习，然后核对已保存文字。只操作内置练习页。"
        // Recover only this fixed fixture's earlier interrupted test records.
        history.search("SPEED_ALPHA10_OK").forEach { item ->
            val old = history.load(item.id)?.run
            if(old?.goal==goal && old.allowedPackages==setOf(packageName)) { history.delete(item.id); PhoneController.forgetDeletedRun(item.id) }
        }
        val previous = history.selectedId
        val practice = getSharedPreferences("practice", MODE_PRIVATE)
        val previousSaved = practice.getString("saved", null)
        var created = ""
        var result = "FAIL: not started"
        var captured = PhoneRun()
        try {
            check(packageManager.checkSignatures(packageName, fixture) == android.content.pm.PackageManager.SIGNATURE_MATCH)
            check(practice.edit().remove("saved").commit())
            withTimeout(120_000) {
                withTimeout(15_000) { while(!PhoneController.connected.value) delay(100) }
                val priorId = PhoneController.run.value.id
                PhoneController.start(this@ToolsFixtureActivity,goal,mapOf(packageName to "手机 Agent 内置练习页面"), true, returnToAgent = false)
                while(PhoneController.busy) {
                    val run = PhoneController.run.value
                    if(created.isBlank()) {
                        if(run.id==priorId || run.goal!=goal || run.allowedPackages!=setOf(packageName)) { delay(100); continue }
                        created=run.id
                    }
                    check(run.id==created)
                    captured=run
                    check(run.status != RunStatus.WAITING_APPROVAL) { "Unexpected approval" }
                    delay(100)
                }
                captured=PhoneController.run.value.also {
                    check(it.id!=priorId && it.goal==goal && it.allowedPackages==setOf(packageName))
                    if(created.isBlank()) created=it.id
                    check(it.id==created)
                }
                check(captured.status==RunStatus.COMPLETED)
                check(captured.steps.last().action.evidence.contains("SPEED_ALPHA10_OK"))
                check(captured.steps.any { it.dispatched && it.action.type==PhoneActionType.TYPE && it.action.text=="SPEED_ALPHA10_OK" })
                check(captured.steps.any { it.dispatched && it.action.type==PhoneActionType.TAP })
                check(practice.getString("saved", null)=="已保存：SPEED_ALPHA10_OK")
                check(captured.steps.all { it.timing != null })
                result="PASS: real API and accessibility, own practice page only"
            }
        } catch(error: Exception) { result="FAIL: ${error.javaClass.simpleName}" }
        finally {
            if(PhoneController.busy && PhoneController.run.value.id==created) PhoneController.stop()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { while(PhoneController.busy && PhoneController.run.value.id==created) delay(50) } }
            if(created.isNotBlank() && PhoneController.run.value.id==created) captured=PhoneController.run.value
            File(noBackupFilesDir,"latency-alpha10-device.json").writeText(buildJsonObject {
                val config = getSharedPreferences("agent_settings", MODE_PRIVATE)
                put("providerHost", android.net.Uri.parse(config.getString("url", "")).host.orEmpty())
                put("model", config.getString("model", "").orEmpty())
                put("result",result);put("run",Json.encodeToJsonElement(captured))
            }.toString())
            practice.edit().apply { if(previousSaved==null) remove("saved") else putString("saved",previousSaved) }.commit()
            if(created.isNotBlank()) { history.delete(created); if(!PhoneController.busy) PhoneController.forgetDeletedRun(created) }
            if(!PhoneController.busy) history.selectedId=previous
        }
    }
}
