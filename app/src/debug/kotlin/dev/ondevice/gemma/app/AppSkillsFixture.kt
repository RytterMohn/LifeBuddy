package dev.ondevice.gemma.app

import android.app.Activity
import android.content.pm.PackageManager
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Fixed native synthetic pages only; production skill bodies are rebound to the isolated fixture package. */
internal object AppSkillsFixture {
    private const val fixture = "dev.ondevice.gemma.fixture"
    suspend fun regression(activity: Activity) {
        val history = HistoryStore.get(activity)
        val selected = history.selectedId
        val created = mutableSetOf<String>()
        val runs = mutableListOf<PhoneRun>()
        var stage = "resources"
        var result = "FAIL"
        var returns = 0
        try {
            check(activity.packageManager.checkSignatures(activity.packageName, fixture) == PackageManager.SIGNATURE_MATCH)
            val library = AppSkillLibrary.bundled
            check(library.descriptors.size == 5)
            library.descriptors.forEach { check(library.body(it.id).isNotBlank()) }
            withTimeout(8 * 60_000L) {
                withTimeout(15_000) { while (!PhoneController.connected.value) delay(100) }
                val cases = listOf(
                    Triple("messaging", "messaging.", "打开离线工具测试信箱的模拟微信，给测试联系人发送 HARNESS_APPS_14。这是本地模拟，仅操作离线工具测试信箱，不打开真实微信。"),
                    Triple("netdisk", "netdisk.", "打开离线工具测试信箱的模拟网盘，在我的文件里搜索测试报价单.pdf，核对搜索结果后告诉我。仅操作测试信箱。"),
                    Triple("meituan", "meituan.", "打开离线工具测试信箱的模拟美团，搜索测试粥铺，打开南瓜粥并选择少辣，到口味结果页核对。仅操作测试信箱，不下单、不付款。"),
                )
                for ((name, prefix, goal) in cases) {
                    stage = name
                    val rebound = AppSkillLibrary(library.descriptors.filter { it.id.startsWith(prefix) }.map { it.copy(packages=setOf(fixture)) }, library::body)
                    PhoneController.start(activity, goal, mapOf(fixture to "离线工具测试信箱（模拟微信、网盘、美团）"), true, knowledge=PhoneKnowledge(rebound), learnFromTask=false)
                    while (PhoneController.busy) {
                        val run = PhoneController.run.value
                        if (run.goal == goal && run.allowedPackages == setOf(fixture, activity.packageName)) created += run.id
                        File(activity.noBackupFilesDir, "app-skills-alpha14-progress.json").writeText(buildJsonObject {
                            put("stage", stage); put("status", run.status.name); put("steps", run.steps.size)
                        }.toString())
                        check(run.status != RunStatus.WAITING_APPROVAL)
                        delay(150)
                    }
                    val run = PhoneController.run.value
                    runs += run
                    check(run.id in created && run.status == RunStatus.COMPLETED)
                    check(run.steps.any { it.timing?.request?.skillIds.orEmpty().any { id -> id.startsWith(prefix) } })
                    when(name) {
                        "messaging" -> {
                            check(run.messageRequest == MessageRequest("app", fixture, "测试联系人", "HARNESS_APPS_14", false))
                            check(run.messageOutcome == MessageOutcome.VERIFIED)
                            check(run.steps.count { it.dispatched && it.action.type == PhoneActionType.SEND_MESSAGE } == 1)
                        }
                        "netdisk" -> {
                            check(run.steps.any { it.dispatched && it.action.type == PhoneActionType.TYPE && it.action.text == "测试报价单.pdf" })
                            check(run.steps.last().action.evidence.let { "测试报价单.pdf" in it || "搜索结果" in it })
                        }
                        "meituan" -> check(run.steps.last().action.evidence.contains("少辣"))
                    }
                    delay(700)
                    val page = PhoneController.diagnosticService().observe(setOf(activity.packageName))
                    check(page.packageName == activity.packageName && page.notice.contains("Agent 控制页") && history.selectedId == run.id)
                    returns++
                }
                result = "PASS: bundled Android resources, three native synthetic App tasks, real API and accessibility, direct simulated send, return to Agent after every task"
            }
        } catch(error: Exception) { result = "FAIL at $stage: ${error.javaClass.simpleName}" }
        finally {
            if (PhoneController.busy && PhoneController.run.value.id in created) PhoneController.stop()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { while (PhoneController.busy) delay(100) } }
            val last = PhoneController.run.value
            if (last.id in created && runs.lastOrNull() != last) runs += last
            File(activity.noBackupFilesDir, "app-skills-alpha14-device.json").writeText(buildJsonObject {
                put("result", result); put("stage", stage); put("returnedToAgentCount", returns)
                put("actualAppCompatibilityTest", false); put("realMessagesSent", 0); put("realOrdersSubmitted", 0)
                put("runs", Json.encodeToJsonElement(runs))
            }.toString())
            if (!PhoneController.busy) {
                created.forEach { history.delete(it); PhoneController.forgetDeletedRun(it) }
                history.selectedId = selected
                PhoneController.diagnosticService().returnToAgent(selected)
            }
        }
    }
}
