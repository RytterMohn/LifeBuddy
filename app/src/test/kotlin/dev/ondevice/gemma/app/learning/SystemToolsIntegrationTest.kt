package dev.ondevice.gemma.app.learning

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class SystemToolsIntegrationTest {
    @Test fun systemIntentsAndStatefulControls()= runBlocking {
        check(System.getenv("ANDROIDMODEL_LEARNING_API_TEST")=="1")
        val config=CloudConfig(System.getenv("ANDROIDMODEL_TEST_API_URL").orEmpty(),System.getenv("ANDROIDMODEL_TEST_MODEL").orEmpty(),System.getenv("ANDROIDMODEL_TEST_API_KEY").orEmpty())
        val reports=mutableListOf<PhoneRun>();var success=false
        val goals=listOf("设置每周一到周五早上7点30分的闹钟，标签是晨练。检查结果。",
            "开始一个5分钟的倒计时，标签是泡茶，检查结果。", "关闭自动亮度，并把屏幕亮度设为40%，检查结果。",
            "先设置每周一到周五早上7点30分的晨练闹钟，确认后再关闭自动亮度，把亮度调成40%，完成两件事后告诉我。")
        try {
            goals.forEachIndexed { i,goal ->
                val driver=SystemDriver();var run=PhoneRun()
                try {
                    withTimeout(150_000) { PhoneRunner(CloudPhonePlanner(config),driver,{_,_->error("Unexpected approval")},{run=it},maxSteps=12,confirmEveryAction=false)
                        .run(goal,driver.apps) }
                    assertEquals(RunStatus.COMPLETED,run.status,run.message)
                    when(i) {
                        0 -> { val a=driver.created.single();assertEquals(PhoneActionType.SET_ALARM,a.type);assertEquals(7,a.hour.toInt());assertEquals(30,a.minute.toInt());assertEquals(listOf(1,2,3,4,5),SystemPhoneActions.days(a.days));assertEquals("晨练",a.text) }
                        1 -> { val a=driver.created.single();assertEquals(PhoneActionType.SET_TIMER,a.type);assertEquals(300,a.seconds.toInt());assertEquals("泡茶",a.text) }
                        else -> { assertFalse(driver.automatic);assertEquals(40f,driver.brightness);assertEquals(1,driver.switchChanges);assertEquals(1,driver.rangeChanges)
                            if(i==3) { assertEquals(PhoneActionType.SET_ALARM,driver.created.single().type);assertTrue(run.steps.any { it.action.type==PhoneActionType.SET_ALARM && it.systemVerified }) }
                        }
                    }
                    assertTrue(run.steps.all { (it.timing?.request?.appCandidates ?: 0)<=8 && (it.timing?.request?.knowledgeChars ?: 0)<=6000 })
                    println("PASS system scenario ${i+1}: exact parameters and observable result")
                } finally { reports+=run }
            }
            success=true
        } finally {
            File(System.getenv("ANDROIDMODEL_TEST_REPORT") ?: error("Missing report path")).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint=true }.encodeToString(buildJsonObject {
                put("success",success);put("model",config.model);put("environment","Real provider and production planner/runner, synthetic OS pages; no personal settings changed")
                put("allowedAppCount",1003);put("actualSystemSideEffects",false);put("runs",Json.encodeToJsonElement(reports))
            }))
        }
    }
    private class SystemDriver: PhoneDriver {
        val apps=mapOf("test.agent" to "LifeBuddy","test.clock" to "时钟（闹钟、计时器）","test.settings" to "系统设置")+(1..1000).associate { "other.app$it" to "无关应用$it" }
        private val targets=listOf(SystemTarget("alarm","设置闹钟","test.clock"),SystemTarget("timer","计时器","test.clock"),SystemTarget("display","显示与亮度","test.settings"))
        private var current="test.agent"
        var automatic=true;var brightness=25f;var switchChanges=0;var rangeChanges=0
        val created=mutableListOf<PhoneAction>()
        override suspend fun observe(allowed: Set<String>)=ScreenSnapshot("page",current,0,"$current-$automatic-$brightness-${created.size}",when(current) {
            "test.clock" -> created.mapIndexed { i,a -> ScreenNode("result$i",if(a.type==PhoneActionType.SET_ALARM) "${a.text} 07:30 周一至周五 已开启" else "${a.text} 倒计时 04:58 运行中") }
            "test.settings" -> listOf(ScreenNode("title","显示设置"),ScreenNode("auto","自动亮度",clickable=true,checkable=true,checked=automatic,stateDescription=if(automatic) "自动亮度已开启" else "自动亮度已关闭"),
                ScreenNode("level","屏幕亮度",stateDescription="屏幕亮度 ${brightness.toInt()}%",rangeMin=0f,rangeMax=100f,rangeValue=brightness,canSetProgress=true))
            else -> emptyList()
        },systemTargets=targets,localTime="2026-10-03T20:00:00+08:00[Asia/Shanghai]")
        override suspend fun execute(action: PhoneAction,screen: ScreenSnapshot): Boolean {
            when(action.type) {
                PhoneActionType.SET_ALARM,PhoneActionType.SET_TIMER -> { check(created.isEmpty());created+=action;current="test.clock" }
                PhoneActionType.OPEN_SETTINGS -> { if(action.destination!="display") return false;current="test.settings" }
                PhoneActionType.OPEN_APP -> current=action.packageName
                PhoneActionType.SET_CHECKED -> { if(action.nodeId!="auto") return false;if(automatic!=action.checked) switchChanges++;automatic=action.checked }
                PhoneActionType.SET_PROGRESS -> { if(action.nodeId!="level") return false;rangeChanges++;brightness=action.value.toFloat() }
                else -> return false
            };return true
        }
        override suspend fun awaitChange()=Unit
    }
}
