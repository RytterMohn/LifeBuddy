package dev.ondevice.gemma.app

import android.app.Activity
import android.content.pm.PackageManager
import dev.ondevice.gemma.app.data.AppCatalog
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.phone.SystemBridge
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

internal object SystemToolsFixture {
    suspend fun regression(activity: Activity) {
        val fixture="dev.ondevice.gemma.fixture"
        val history=HistoryStore.get(activity);val selected=history.selectedId
        val created=mutableSetOf<String>();val runs=mutableListOf<PhoneRun>();var result="FAIL";var returns=0
        val capabilities=mutableListOf<SystemTarget>();val realOpened=mutableListOf<String>()
        var stage="connect"
        try {
            check(activity.packageManager.checkSignatures(activity.packageName,fixture)==PackageManager.SIGNATURE_MATCH)
            withTimeout(360_000) {
                withTimeout(15_000) { while(!PhoneController.connected.value) delay(100) }
                val bridge=SystemBridge(activity)
                val realApps=AppCatalog.list(activity).toMap()-fixture
                capabilities+=bridge.targets(realApps.keys)
                for((index,goal) in listOf(
                    "在离线系统测试应用中设置每周一到周五07:35的闹钟，标签HARNESS_SYSTEM_ALARM，核对结果。只操作测试应用。",
                    "在离线系统测试应用中开始5分钟倒计时，标签HARNESS_SYSTEM_TIMER，核对结果。只操作测试应用。",
                    "打开离线系统测试应用的显示设置，关闭自动亮度，再把屏幕亮度调到40%，检查结果。只操作测试应用。"
                ).withIndex()) {
                    stage="task-${index+1}"
                    PhoneController.start(activity,goal,mapOf(fixture to "离线系统测试（时钟、闹钟、设置）"),true,learnFromTask=false)
                    while(PhoneController.busy) {
                        val run=PhoneController.run.value
                        if(run.goal==goal) created+=run.id
                        File(activity.noBackupFilesDir,"system-alpha16-progress.json").writeText(buildJsonObject {put("stage",stage);put("steps",run.steps.size);put("status",run.status.name)}.toString())
                        check(run.status!=RunStatus.WAITING_APPROVAL);delay(150)
                    }
                    val run=PhoneController.run.value;runs+=run
                    check(run.goal==goal && run.status==RunStatus.COMPLETED) { "task incomplete: ${run.message.take(160)}" }
                    when(index) {
                        0 -> { val a=run.steps.single { it.action.type==PhoneActionType.SET_ALARM && it.dispatched }.action
                            check(a.hour.toInt()==7 && a.minute.toInt()==35 && SystemPhoneActions.days(a.days)?.sorted()==listOf(1,2,3,4,5) && a.text=="HARNESS_SYSTEM_ALARM") }
                        1 -> check(run.steps.single { it.action.type==PhoneActionType.SET_TIMER && it.dispatched }.action.let { it.seconds.toInt()==300 && it.text=="HARNESS_SYSTEM_TIMER" })
                        else -> { check(run.steps.any { it.dispatched && it.action.type==PhoneActionType.SET_CHECKED && !it.action.checked });check(run.steps.any { it.dispatched && it.action.type==PhoneActionType.SET_PROGRESS && it.action.value.toFloat()==40f }) }
                    }
                    delay(600)
                    check(PhoneController.diagnosticService().observe(setOf(activity.packageName)).packageName==activity.packageName)
                    returns++
                }
                // Open actual OS pages through the production resolver, observe package only, change nothing.
                for(id in listOf("display","alarms")) {
                    stage="native-$id"
                    val target=capabilities.firstOrNull { it.id==id } ?: continue
                    val action=PhoneAction("diagnostic",PhoneActionType.OPEN_SETTINGS,"验证只读系统入口",destination=id)
                    val intent=bridge.build(action,realApps.keys,capabilities) ?: error("Unresolvable system capability")
                    activity.startActivity(intent)
                    withTimeout(8_000) {
                        while(true) {
                            delay(250)
                            val page=runCatching { PhoneController.diagnosticService().observe(setOf(target.packageName)) }.getOrNull()
                            if(page?.packageName==target.packageName) break
                        }
                    }
                    realOpened+=id
                    PhoneController.diagnosticService().returnToAgent(selected);delay(500)
                }
                result="PASS: real model and native intent transport into isolated clock/settings fixture, switch/range readback; real OS pages opened read-only"
            }
        } catch(e:Exception) { result="FAIL at $stage: ${e.javaClass.simpleName}: ${e.message?.take(180)}" }
        finally {
            if(PhoneController.busy && PhoneController.run.value.id in created) PhoneController.stop()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { while(PhoneController.busy) delay(100) } }
            File(activity.noBackupFilesDir,"system-alpha16-result.json").writeText(Json.encodeToString(buildJsonObject {
                put("result",result);put("stage",stage);put("automaticReturns",returns);put("runs",Json.encodeToJsonElement(runs))
                put("nativeCapabilities",Json.encodeToJsonElement(capabilities));put("nativeReadOnlyPagesOpened",Json.encodeToJsonElement(realOpened))
                put("realAlarmsCreated",0);put("realSystemSettingsChanged",false)
            }))
            if(!PhoneController.busy) {
                created.filter { it.isNotBlank() }.forEach { history.delete(it);PhoneController.forgetDeletedRun(it) }
                history.selectedId=selected;runCatching { PhoneController.diagnosticService().returnToAgent(selected) }
            }
        }
    }
}
