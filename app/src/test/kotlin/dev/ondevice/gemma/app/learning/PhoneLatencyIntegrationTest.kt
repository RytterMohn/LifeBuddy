package dev.ondevice.gemma.app.learning

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class PhoneLatencyIntegrationTest {
    private val pkg = "test.latency.shop"
    private val apps = mapOf(pkg to "测试商店")
    private val json = Json { prettyPrint=true }

    @Test fun alternatingRequestStrategies() = runBlocking {
        check(System.getenv("ANDROIDMODEL_LEARNING_API_TEST") == "1")
        val config = CloudConfig(System.getenv("ANDROIDMODEL_TEST_API_URL").orEmpty(), System.getenv("ANDROIDMODEL_TEST_MODEL").orEmpty(), System.getenv("ANDROIDMODEL_TEST_API_KEY").orEmpty())
        CloudPhonePlanner.validate(config)
        val reports = mutableListOf<JsonObject>()
        var success = false
        try { withTimeout(6 * 60_000L) {
            // Both strategies share the same warmed HTTP pool; compare request content, not DNS/TLS startup.
            val warm = ShopDriver("warm")
            CloudPhonePlanner(config).next(PlannerInput("不要操作，只回复测试就绪", warm.observe(apps.keys), apps, emptyList()))
            repeat(3) { round ->
                val order = if(round % 2 == 0) listOf(false,true) else listOf(true,false)
                for (compact in order) {
                    val driver = ShopDriver("r$round-${if(compact) "compact" else "full"}")
                    var run = PhoneRun()
                    var passed = false
                    try {
                        PhoneRunner(CloudPhonePlanner(config,learningEnabled=true,compactRequests=compact),driver,
                            { _, _ -> error("No external sends in benchmark") },{run=it},maxSteps=6,confirmEveryAction=false)
                            .run("在测试商店选择少辣，到结果页核对，不下单、不付款。",apps)
                        assertEquals(RunStatus.COMPLETED,run.status)
                        assertEquals("少辣",driver.selected)
                        assertTrue(run.steps.all { it.timing?.request != null })
                        passed = true
                        println("PASS ${if(compact) "compact" else "full"} round=$round requests=${run.plannerRequests} elapsedMs=${run.elapsedMs}")
                    } finally {
                        reports += buildJsonObject {
                            put("round",round);put("strategy",if(compact) "compact" else "full");put("success",passed)
                            put("elapsedMs",run.elapsedMs);put("requests",run.plannerRequests)
                            putJsonArray("steps") { run.steps.forEach { step -> addJsonObject {
                                put("action",step.action.type.name)
                                step.timing?.request?.let { timing ->
                                    put("networkMs",timing.networkMs);put("responseHeadersMs",timing.responseHeadersMs)
                                    put("estimatedInputTokens",timing.estimatedInputTokens);put("toolCount",timing.toolCount)
                                    timing.outputTokens?.let { put("outputTokens",it) }
                                }
                            } } }
                        }
                    }
                }
            }
            success=true
        } } finally {
            val output=File(requireNotNull(System.getenv("ANDROIDMODEL_TEST_REPORT")))
            output.parentFile?.mkdirs()
            output.writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("success",success);put("model",config.model);put("warmupRequests",1)
                put("environment","real API; synthetic 40-node shop; shared warmed connection; alternating full/compact requests")
                put("deviceTest",false);put("runs",JsonArray(reports))
            }))
        }
    }

    private inner class ShopDriver(private val tag: String): PhoneDriver {
        private var page=0
        private var reads=0
        var selected=""
        override suspend fun observe(allowed: Set<String>): ScreenSnapshot {
            fun node(suffix: String,text: String,clickable: Boolean=false)=ScreenNode("$tag-$page-$suffix",text,resourceId="$pkg:id/$suffix",clickable=clickable)
            val controls=when(page) {
                0 -> listOf(node("title","测试商店"),node("taste","选择口味",true))
                1 -> listOf(node("title","选择午餐口味"),node("none","不辣",true),node("mild","少辣",true),node("normal","正常辣",true))
                else -> listOf(node("result","已选择：$selected；尚未下单"))
            }
            val padding=(1..(40-controls.size)).map { node("info$it","商品介绍 $it：合成页面的信息条目") }
            return ScreenSnapshot("$tag-${++reads}",pkg,System.currentTimeMillis(),"$tag-$page-$selected",controls+padding,appVersion="1")
        }
        override suspend fun execute(action: PhoneAction,screen: ScreenSnapshot): Boolean {
            if(action.type==PhoneActionType.OPEN_APP && action.packageName==pkg) {page=0;return true}
            if(action.type==PhoneActionType.WAIT) return true
            if(action.type!=PhoneActionType.TAP) return false
            val label=screen.nodes.find {it.id==action.nodeId && it.clickable}?.text ?: return false
            if(page==0 && label=="选择口味") page=1
            else if(page==1 && label in setOf("不辣","少辣","正常辣")) {selected=label;page=2}
            else return false
            return true
        }
        override suspend fun awaitChange()=Unit
    }
}
