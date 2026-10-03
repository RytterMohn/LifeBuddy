package dev.ondevice.gemma.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import dev.ondevice.gemma.app.data.AgentSettings
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Actual AccessibilityService + actual model + a separate offline fixture app. Never sends real messages. */
class PhoneToolsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        Thread {
            val output = Bundle()
            var stage = "connect_service"
            var resultCode = 1
            val fixture = "dev.ondevice.gemma.fixture"
            val savedRuns = mutableListOf<PhoneRun>()
            val history = HistoryStore.get(targetContext)
            val previous = history.selectedId
            val created = mutableListOf<String>()
            try {
                CloudApiClient.validate(AgentSettings(targetContext).read())
                check(targetContext.packageManager.getLaunchIntentForPackage(fixture) != null)
                runBlocking { withTimeout(360_000) {
                    withTimeout(20_000) { while (!PhoneController.connected.value) delay(200) }
                    for (request in listOf(
                        MessageRequest("qq", fixture, "测试联系人", "HARNESS_QQ_LOCAL_07", false),
                        MessageRequest("sms", fixture, "15500000000", "HARNESS_SMS_DRAFT_07", true),
                    )) {
                        stage = "fixture_${request.channel}"
                        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        delay(500)
                        runOnMainSync {
                            PhoneController.start(targetContext, "这是离线测试信箱，不是真实 QQ 或短信。" + request.goal(), mapOf(fixture to "离线工具测试信箱"), true, request)
                        }
                        var lastReported = -1
                        while (PhoneController.busy) {
                            val run = PhoneController.run.value
                            if (run.id.isNotEmpty() && run.messageRequest == request && run.id !in created) created += run.id
                            if (run.steps.size != lastReported) {
                                lastReported = run.steps.size
                                sendStatus(0, Bundle().apply { putString("progress", "$stage: ${run.steps.size} steps, ${run.steps.lastOrNull()?.action?.type}") })
                            }
                            check(run.status != RunStatus.WAITING_APPROVAL) { "Unexpected approval in autonomous mode" }
                            delay(100)
                        }
                        val run = PhoneController.run.value
                        savedRuns += run
                        check(run.status == RunStatus.COMPLETED) { "fixture_run_incomplete" }
                        check(run.steps.count { it.action.type == PhoneActionType.SEND_MESSAGE && it.dispatched } == if (request.draftOnly) 0 else 1)
                        check(run.steps.any { it.action.type == if (request.channel == "sms") PhoneActionType.COMPOSE_SMS else PhoneActionType.TYPE })
                    }
                } }
                output.putString("result", "PASS: actual model and accessibility operated separate offline mailbox; message prepared, recipient/body checked, one simulated send verified; SMS Intent draft prepared without send")
                resultCode = 0
            } catch (error: Exception) {
                output.putString("result", "FAIL at $stage: ${error.javaClass.simpleName}")
                resultCode = 1
            } finally {
                runOnMainSync { if (PhoneController.busy) PhoneController.stop() }
                val last = PhoneController.run.value
                if (last.messageRequest?.packageName == fixture && savedRuns.none { it.id == last.id }) savedRuns += last
                File(targetContext.noBackupFilesDir, "tools-alpha07-result.json").writeText(buildJsonObject {
                    put("stage", stage); put("result", output.getString("result")); put("runs", Json.encodeToJsonElement(savedRuns))
                }.toString())
                runBlocking { withTimeoutOrNull(5000) { while (PhoneController.busy) delay(50) } }
                created.forEach { id -> history.delete(id); runOnMainSync { if (!PhoneController.busy) PhoneController.forgetDeletedRun(id) } }
                history.selectedId = previous
            }
            finish(resultCode, output)
        }.start()
    }
}
