package dev.ondevice.gemma.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Process
import dev.ondevice.gemma.app.data.AutoSkillStore
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.learning.AutoSkillBook
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Two fixed phases separated by an external process restart. The personal skill library is untouched. */
internal object AutoSkillFixture {
    private const val fixture="dev.ondevice.gemma.fixture"
    private const val filename="fixture-auto-skills-alpha15.json"
    suspend fun regression(activity: Activity, reuse: Boolean) {
        val history=HistoryStore.get(activity)
        val selected=history.selectedId
        val phase=if(reuse) "reuse" else "seed"
        val query=if(reuse) "HARNESS_NOTE_BETA" else "HARNESS_NOTE_ALPHA"
        val output=File(activity.noBackupFilesDir,"auto-skills-alpha15-$phase.json")
        var created=""; var run=PhoneRun(); var result="FAIL"; var returned=false
        var loadedSkill=""; var skillCount=0; var skillStatus=""; var durableQueueRecovered=false
        try {
            check(activity.packageManager.checkSignatures(activity.packageName,fixture)==PackageManager.SIGNATURE_MATCH)
            check(AppSkillLibrary.bundled.descriptors.none { fixture in it.packages })
            if(!reuse) { File(activity.noBackupFilesDir,filename).delete(); File(activity.noBackupFilesDir,"$filename.bak").delete() }
            val skills=AutoSkillStore(activity,filename) { true }
            if(reuse) {
                val seed=Json.parseToJsonElement(File(activity.noBackupFilesDir,"auto-skills-alpha15-seed.json").readText()).jsonObject
                check(seed.getValue("result").jsonPrimitive.content.startsWith("PASS"))
                check(seed.getValue("pid").jsonPrimitive.int!=Process.myPid())
                check(skills.snapshot().skills.single().status=="trial")
                loadedSkill=skills.snapshot().skills.single().reference
            } else check(skills.snapshot().skills.isEmpty())
            withTimeout(210_000) {
                withTimeout(15_000) { while(!PhoneController.connected.value) delay(100) }
                val goal="打开离线工具测试信箱中的陌生笔记，搜索$query，核对实际结果后告诉我。仅操作测试信箱。"
                PhoneController.start(activity,goal,mapOf(fixture to "陌生笔记"),true,autoSkillStore=skills)
                while(PhoneController.busy) {
                    val value=PhoneController.run.value
                    if(value.goal==goal && value.allowedPackages==setOf(fixture,activity.packageName)) { created=value.id; run=value }
                    File(activity.noBackupFilesDir,"auto-skills-alpha15-progress.json").writeText(buildJsonObject {
                        put("phase",phase); put("steps",value.steps.size); put("status",value.status.name)
                    }.toString())
                    check(value.status!=RunStatus.WAITING_APPROVAL)
                    delay(150)
                }
                run=PhoneController.run.value
                check(run.id==created && run.status==RunStatus.COMPLETED)
                check(run.steps.last().skillCompletion!=null)
                check(run.steps.any { it.dispatched && it.action.type==PhoneActionType.TYPE && it.action.text==query })
                skills.awaitIdle()
                val reopened=AutoSkillStore(activity,filename) { true }
                val state=reopened.snapshot()
                val skill=state.skills.single()
                skillCount=state.skills.size; skillStatus=skill.status
                check(!Json.encodeToString(state).contains(query))
                if(reuse) {
                    check(run.steps.any { loadedSkill in it.timing?.request?.skillIds.orEmpty() })
                    check(skill.current.number==1 && skill.current.successes==2 && skill.status=="verified") {
                        "Expected verified r1 with two successes, got r${skill.current.number}, ${skill.current.successes}, ${skill.status}"
                    }
                    // Durable pending job round-trip in a separate file; compile without network or phone execution.
                    val queueName="fixture-auto-queue-alpha15.json"
                    File(activity.noBackupFilesDir,queueName).delete()
                    val pending=AutoSkillStore(activity,queueName) { true }
                    pending.record(run)
                    check(pending.snapshot().jobs.size==1)
                    val recovered=AutoSkillStore(activity,queueName) { true }
                    recovered.kick(); recovered.awaitIdle()
                    check(recovered.snapshot().jobs.isEmpty() && recovered.snapshot().skills.size==1)
                    durableQueueRecovered=true
                    File(activity.noBackupFilesDir,queueName).delete()
                    reopened.change(skill.id,"edit","优先使用当前页面上可见的搜索入口。")
                    check(AutoSkillStore(activity,filename) { true }.snapshot().skills.single().current.number==2)
                    reopened.change(skill.id,"disable")
                    reopened.record(run.copy(executionId="disabled-probe")); reopened.kick(); reopened.awaitIdle()
                    check(reopened.snapshot().skills.single().status=="disabled")
                    reopened.change(skill.id,"delete")
                    reopened.record(run.copy(executionId="deleted-probe")); reopened.kick(); reopened.awaitIdle()
                    check(AutoSkillStore(activity,filename) { true }.snapshot().let { it.skills.isEmpty() && it.jobs.isEmpty() && it.dismissed.isNotEmpty() })
                } else {
                    check(skill.status=="trial" && skill.current.successes==1)
                    check(run.steps.none { it.timing?.request?.skillIds.orEmpty().any { id -> id.startsWith("learned.") } })
                }
                delay(700)
                val page=PhoneController.diagnosticService().observe(setOf(activity.packageName))
                returned=page.packageName==activity.packageName && history.selectedId==created
                check(returned)
                result=if(reuse) "PASS: process restart, parameterized skill reuse, distinct query verification, AtomicFile queue recovery, edit/disable/delete preserved" else "PASS: no preinstalled guide, first native task automatically generated trial skill on disk"
            }
        } catch(error: Exception) { result="FAIL: ${error.javaClass.simpleName}: ${error.message?.take(160)}" }
        finally {
            if(created.isNotBlank() && PhoneController.busy && PhoneController.run.value.id==created) PhoneController.stop()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { while(PhoneController.busy) delay(100) } }
            output.writeText(buildJsonObject {
                put("result",result); put("phase",phase); put("pid",Process.myPid()); put("returnedToAgent",returned)
                put("skillCount",skillCount); put("skillStatus",skillStatus); put("loadedSkill",loadedSkill)
                put("durableQueueRecovered",durableQueueRecovered); put("skillGenerationApiCalls",0)
                put("run",Json.encodeToJsonElement(run))
            }.toString())
            if(created.isNotBlank() && !PhoneController.busy) { history.delete(created); PhoneController.forgetDeletedRun(created); history.selectedId=selected }
            if(reuse) { File(activity.noBackupFilesDir,filename).delete(); File(activity.noBackupFilesDir,"$filename.bak").delete() }
            if(!PhoneController.busy) runCatching { PhoneController.diagnosticService().returnToAgent(selected) }
        }
    }
}
