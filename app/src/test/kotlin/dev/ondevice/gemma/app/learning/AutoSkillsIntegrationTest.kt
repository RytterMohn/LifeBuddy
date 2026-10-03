package dev.ondevice.gemma.app.learning

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.learning.*
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class AutoSkillsIntegrationTest {
    private val pkg="test.unseen.notebook"
    private val agent="test.agent"
    private val apps=mapOf(pkg to "陌生笔记",agent to "Agent")
    private val reportJson=Json { prettyPrint=true }
    @Test fun learnReloadReuseAndRevise() = runBlocking {
        check(System.getenv("ANDROIDMODEL_LEARNING_API_TEST")=="1")
        val config=CloudConfig(System.getenv("ANDROIDMODEL_TEST_API_URL").orEmpty(),System.getenv("ANDROIDMODEL_TEST_MODEL").orEmpty(),System.getenv("ANDROIDMODEL_TEST_API_KEY").orEmpty())
        var state=AutoSkillState()
        val runs=mutableListOf<PhoneRun>()
        var success=false
        var stage="first-run"
        suspend fun execute(query: String, version: String, layout: String): PhoneRun {
            val driver=NotebookDriver(version,layout)
            val knowledge=PhoneKnowledge(AutoSkillBook.library(state) { version })
            var run=PhoneRun()
            try {
                withTimeout(120_000) { PhoneRunner(CloudPhonePlanner(config,knowledge=knowledge),driver,{_,_->error("Unexpected approval")},{run=it},
                    maxSteps=14,confirmEveryAction=false,knowledge=knowledge,captureSkills=true).run("在陌生笔记中搜索$query，核对实际结果后告诉我是否找到。",apps) }
                assertEquals(RunStatus.COMPLETED,run.status,run.message)
                assertEquals(query,driver.query); assertTrue(driver.submitted)
                assertNotNull(run.steps.last().skillCompletion)
                return run
            } finally { runs+=run }
        }
        try { withTimeout(7*60_000L) {
            assertTrue(AppSkillLibrary.bundled.search("搜索",setOf(pkg)).isEmpty())
            val first=execute("测试条目甲","1","old")
            assertTrue(first.steps.none { it.timing?.request?.skillIds.orEmpty().any { id -> id.startsWith("learned.") } })
            state=AutoSkillBook.record(state,first)
            assertEquals(1,state.jobs.size)
            // Restart with pending work, then restart with a generated skill. Both use the storage JSON schema.
            state=AutoSkillBook.processNext(Json.decodeFromString(Json.encodeToString(state)))
            assertEquals("trial",state.skills.single().status)
            val id=state.skills.single().id
            val reference=state.skills.single().reference
            assertFalse("测试条目甲" in Json.encodeToString(state))
            state=Json.decodeFromString(Json.encodeToString(state))
            println("PASS unfamiliar App learned and persisted trial skill")
            stage="reload-reuse"
            val second=execute("测试条目乙","1","fresh")
            assertTrue(second.steps.any { reference in it.timing?.request?.skillIds.orEmpty() })
            assertTrue(second.steps.filter { it.action.nodeId.isNotBlank() }.all { it.action.nodeId.startsWith("fresh") })
            state=AutoSkillBook.processNext(AutoSkillBook.record(state,second))
            assertEquals(id,state.skills.single().id)
            assertEquals(1,state.skills.single().current.number)
            assertEquals("verified",state.skills.single().status)
            assertEquals(2,state.skills.single().current.successes)
            println("PASS new parameter uses generated guide and fresh nodes")
            stage="version-revision"
            val third=execute("测试条目丙","2","updated")
            assertTrue(third.steps.none { reference in it.timing?.request?.skillIds.orEmpty() })
            state=AutoSkillBook.processNext(AutoSkillBook.record(state,third))
            assertEquals(id,state.skills.single().id)
            assertEquals(2,state.skills.single().current.number)
            assertEquals("2",state.skills.single().current.version)
            assertEquals("trial",state.skills.single().status)
            assertFalse(listOf("测试条目甲","测试条目乙","测试条目丙").any { it in Json.encodeToString(state) })
            println("PASS version change excludes old guide and generates revision")
            success=true
        } } finally {
            File(System.getenv("ANDROIDMODEL_TEST_REPORT") ?: error("Missing report path")).apply { parentFile?.mkdirs() }.writeText(reportJson.encodeToString(buildJsonObject {
                put("success",success); put("stage",stage); put("model",config.model)
                put("environment","real API + production planner/runner + synthetic previously unknown App; local skill compiler")
                put("skillGenerationApiCalls",0); put("actualAppCompatibilityTest",false)
                put("runs",Json.encodeToJsonElement(runs)); put("state",Json.encodeToJsonElement(state))
            }))
        }
    }
    private inner class NotebookDriver(private val version: String,private val prefix: String): PhoneDriver {
        var current=agent
        var query=""
        var submitted=false
        override suspend fun observe(allowed: Set<String>)=ScreenSnapshot("$prefix-screen",current,System.currentTimeMillis(),"$current-$query-$submitted",
            when {
                current==agent -> listOf(ScreenNode("$prefix-agent","Agent 控制页"))
                submitted -> listOf(ScreenNode("$prefix-result","找到笔记：$query"),ScreenNode("$prefix-heading","搜索结果：1 项"))
                else -> listOf(ScreenNode("$prefix-input",query,resourceId="$pkg:id/query",editable=true,canSubmitSearch=true,hint="搜索笔记"),ScreenNode("$prefix-search","搜索",clickable=true))
            },appVersion=version)
        override suspend fun execute(action: PhoneAction,screen: ScreenSnapshot): Boolean {
            if(action.type==PhoneActionType.OPEN_APP && action.packageName==pkg) { current=pkg; return true }
            if(action.nodeId !in screen.nodes.map { it.id }) return false
            when(action.type) {
                PhoneActionType.TYPE -> query=action.text
                PhoneActionType.SUBMIT_SEARCH -> submitted=true
                PhoneActionType.TAP -> if(action.nodeId=="$prefix-search") submitted=true else return false
                else -> return false
            }
            return true
        }
        override suspend fun awaitChange()=Unit
    }
}
