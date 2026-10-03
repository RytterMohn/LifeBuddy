package dev.ondevice.gemma.app.learning

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.cloud.*
import dev.ondevice.gemma.learning.*
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/** Real HTTPS calls and production harness; every screen, action and preference is synthetic. */
class LaowangLearningIntegrationTest {
    private val pkg = "test.learning.shop"
    private val apps = mapOf(pkg to "测试商店")
    private var notebook = LearningState()
    private val cases = mutableListOf<JsonObject>()
    private var stage = "configuration"
    private val reportJson = Json { prettyPrint=true }

    @Test fun realProviderLearningFlow() = runBlocking {
        check(System.getenv("ANDROIDMODEL_LEARNING_API_TEST") == "1") { "Use the explicit learning API test launcher" }
        val config = CloudConfig(System.getenv("ANDROIDMODEL_TEST_API_URL").orEmpty(),
            System.getenv("ANDROIDMODEL_TEST_MODEL").orEmpty(), System.getenv("ANDROIDMODEL_TEST_API_KEY").orEmpty())
        CloudApiClient.validate(config)
        val output = File(System.getenv("ANDROIDMODEL_TEST_REPORT") ?: error("Missing report path"))
        var success = false
        try { withTimeout(6 * 60_000L) {
            stage = "streaming_chat_and_calculator"
            val agent = CloudChatAgent(config.model, CloudApiClient(config))
            val parts = mutableListOf<String>()
            val tools = mutableListOf<ChatToolResult>()
            val answer = agent.chat("请实际调用计算工具算 128*37，最后用一句中文回答结果。", onText={ parts += it }, onTool=tools::add)
            assertTrue(tools.any { it.name == "calculator" && "4736" in it.result })
            assertContains(answer, "4736")
            assertTrue(parts.size > 1)
            assertEquals(answer, parts.joinToString(""))
            cases += buildJsonObject {
                put("name", stage); put("success", true); put("answer", answer); put("streamChunks", parts.size)
                put("stats", Json.encodeToJsonElement(agent.lastStats))
            }
            println("PASS $stage")

            runCase(config, "propose_pending_habit", "我通常喜欢少辣。这是我的通用口味偏好，请将前面这句原话作为待确认的长期偏好，确认前不要把候选用于以后任务。只做记录和回复，不操作页面。", "pending") { run, driver, _ ->
                assertEquals(0, driver.executions)
                assertTrue(run.steps.any { it.action.type == PhoneActionType.NOTE_PREFERENCE })
                assertEquals("我通常喜欢少辣", notebook.entries.single().text.trimEnd('。'))
                assertEquals("pending", notebook.entries.single().status)
                assertEquals("", learningContext("继续", driver).text)
                assertContains(run.message, "学习与经验")
            }
            runCase(config, "pending_not_used", "我平时喜欢什么口味？只根据已确认的长期偏好回答，没有记录就直说不知道。不要操作页面，也不要新增记忆。", "before-confirm") { run, driver, _ ->
                assertEquals(0, driver.executions)
                assertFalse("少辣" in run.message)
                assertEquals("pending", notebook.entries.single().status)
            }

            val habitId = notebook.entries.single().id
            notebook = LearningBook.change(notebook, habitId, "activate")
            val choose = "在测试商店按照我平时的口味选择午餐口味，并到结果页核对完成；不下单、不付款。"
            runCase(config, "confirmed_habit_used", choose, "first") { _, driver, contexts ->
                assertEquals("少辣", driver.selected)
                assertTrue(contexts.any { "少辣" in it.text })
                assertEquals(1, notebook.entries.count { it.kind == "app" })
            }
            val firstRoute = notebook.entries.single { it.kind == "app" }
            assertTrue(firstRoute.steps.size >= 2)
            assertFalse("first-" in Json.encodeToString(firstRoute))

            // Same serialized schema as Android storage; the test does not replace AtomicFile testing.
            notebook = Json.decodeFromString(Json.encodeToString(notebook))
            notebook = LearningBook.change(notebook, habitId, "edit", "我通常喜欢不辣")
            runCase(config, "correction_overrides_old_route", choose, "fresh") { run, driver, contexts ->
                assertEquals("不辣", driver.selected)
                assertTrue(contexts.any { firstRoute.id in it.ids })
                assertTrue(run.steps.any { firstRoute.id in it.action.experienceIds })
                assertTrue(run.steps.filter { it.action.type == PhoneActionType.TAP }.all { it.action.nodeId.startsWith("fresh-") })
                assertEquals(1, notebook.entries.single { it.id == firstRoute.id }.successes)
            }
            runCase(config, "current_request_overrides_habit", "在测试商店选午餐口味。这次要正常辣，不按平时口味。到结果页核对，不下单、不付款。", "override") { _, driver, _ ->
                assertEquals("正常辣", driver.selected)
                assertEquals(1, notebook.entries.count { it.kind == "habit" })
            }
            runCase(config, "new_app_version_excludes_old_routes", choose, "updated", version="13") { _, driver, contexts ->
                assertEquals("不辣", driver.selected)
                assertTrue(contexts.all { it.ids.isEmpty() })
                assertTrue(notebook.entries.any { it.kind == "app" && it.version == "13" })
            }

            notebook = LearningBook.change(notebook, habitId, "delete")
            runCase(config, "deleted_habit_not_used", "我平时喜欢什么口味？只根据已确认的长期偏好回答，没有记录就直说不知道。不要从操作路径推测偏好，不操作页面，不新增记忆。", "deleted", learningVersion="unknown") { run, driver, _ ->
                assertEquals(0, driver.executions)
                assertFalse("不辣" in run.message)
                assertTrue(notebook.entries.none { it.kind == "habit" })
            }
            success = true
        } } finally {
            output.parentFile?.mkdirs()
            output.writeText(reportJson.encodeToString(buildJsonObject {
                put("success", success); put("lastStage", stage); put("model", config.model)
                put("transport", "real HTTPS and SSE via production CloudApiClient")
                put("environment", "JVM, synthetic screen driver, production PhoneRunner/CloudPhonePlanner/LearningBook")
                put("deviceTest", false); put("realMessagesSent", 0); put("realOrdersSubmitted", 0)
                put("cases", JsonArray(cases))
            }))
        }
    }

    private fun learningContext(goal: String, driver: ShopDriver, version: String = driver.version) =
        LearningBook.context(notebook, goal, pkg, apps.keys) { version }

    private suspend fun runCase(config: CloudConfig, name: String, goal: String, tag: String, version: String = "12", learningVersion: String = version,
        verify: (PhoneRun, ShopDriver, List<LearningContext>) -> Unit) {
        stage = name
        val driver = ShopDriver(tag, version)
        val contexts = mutableListOf<LearningContext>()
        var run = PhoneRun()
        val planner = CloudPhonePlanner(config, learningEnabled=true) { input ->
            learningContext(input.goal, driver, learningVersion).also(contexts::add)
        }
        val learning = PhoneLearning { statements, quote, scope, runId ->
            try {
                notebook = LearningBook.proposeHabit(notebook, statements, quote, scope, runId)
                "已记录习惯候选，待用户在“学习与经验”中确认；当前任务和后续任务不能把候选当作事实"
            } catch (_: IllegalArgumentException) { "未记录：不是用户明确的稳定偏好原话" }
        }
        var passed = false
        try {
            PhoneRunner(planner, driver, { _, _ -> error("Synthetic navigation must not request send approval") }, { run=it },
                maxSteps=8, confirmEveryAction=false, learning=learning).run(goal, apps)
            assertEquals(RunStatus.COMPLETED, run.status, "$name: ${run.message}")
            notebook = LearningBook.recordOutcome(notebook, run)
            verify(run, driver, contexts)
            passed = true
            println("PASS $name (${run.plannerRequests} requests, ${run.elapsedMs} ms)")
        } finally {
            cases += buildJsonObject {
                put("name", name); put("success", passed); put("status", run.status.name); put("answer", run.message)
                put("plannerRequests", run.plannerRequests); put("elapsedMs", run.elapsedMs); put("selected", driver.selected)
                put("version", version); put("deviceActions", driver.executions)
                putJsonArray("actions") { run.steps.forEach { add(it.action.type.name) } }
                putJsonArray("contextReferences") { contexts.forEach { add(it.ids.size) } }
                put("stepTimings", Json.encodeToJsonElement(run.steps.mapNotNull { it.timing }))
            }
        }
    }

    private inner class ShopDriver(val tag: String, val version: String) : PhoneDriver {
        var page = 0
        var selected = ""
        var executions = 0
        private var reads = 0
        private fun node(suffix: String, label: String, clickable: Boolean = true) =
            ScreenNode("$tag-$page-$suffix", label, resourceId="$pkg:id/$suffix", clickable=clickable)
        override suspend fun observe(allowed: Set<String>): ScreenSnapshot {
            val nodes = when(page) {
                0 -> listOf(node("title", "测试商店", false), node("taste", "选择口味"))
                1 -> listOf(node("title", "选择本次午餐口味", false), node("normal", "正常辣"), node("none", "不辣"), node("mild", "少辣"))
                else -> listOf(node("result", "已选择：$selected；尚未下单", false))
            }
            return ScreenSnapshot("$tag-screen-${++reads}", pkg, System.currentTimeMillis(), "$tag-$version-$page-$selected", nodes, appVersion=version)
        }
        override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
            executions++
            if (action.type == PhoneActionType.OPEN_APP && action.packageName == pkg) { page=0; return true }
            if (action.type == PhoneActionType.WAIT) return true
            if (action.type != PhoneActionType.TAP) return false
            val node = screen.nodes.singleOrNull { it.id == action.nodeId && it.clickable } ?: return false
            when {
                page == 0 && node.text == "选择口味" -> page=1
                page == 1 && node.text in setOf("少辣", "不辣", "正常辣") -> { selected=node.text; page=2 }
                else -> return false
            }
            return true
        }
        override suspend fun awaitChange() = Unit
    }
}
