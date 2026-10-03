package dev.ondevice.gemma.app.learning

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.cloud.*
import dev.ondevice.gemma.extensions.*
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/** Real model, synthetic skill and remote tool. No user history or external mutations. */
class ExtensionsIntegrationTest {
    private class Port(val toolsAllowed: Boolean) : ExtensionPort {
        override val available = true
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}""").jsonObject
        val state = ExtensionState(
            listOf(ImportedSkill("daily-brief", "Daily brief / 每日简报", "Read references/focus.md and use its anchor word in your reply.", mapOf("references/focus.md" to "Anchor word: ORCHID. Suggest a short walk and a focused work block."))),
            listOf(McpServer("trip-demo", "Trip demo / 行程演示", "https://example.test/mcp", tools = listOf(McpTool("weather_lookup", "Look up weather / 查询城市天气", schema)) +
                (1..1000).map { McpTool("unused_$it", "Unrelated utility $it", buildJsonObject { put("type", "object") }) })))
        val readIds = mutableListOf<String>()
        val calls = mutableListOf<JsonObject>()
        override fun search(query: String, cursor: String) = ExtensionCatalog.search(state, query, cursor, toolsAllowed)
        override fun read(id: String, resource: String, cursor: String): String {
            readIds += "$id/$resource"
            if (id == "skill:daily-brief") return ExtensionCatalog.readSkill(state.skills.single(), resource, cursor)
            check(toolsAllowed && id == "mcp:trip-demo:weather_lookup")
            return buildJsonObject { put("id", id); put("inputSchema", schema); put("description", "Return actual weather for the city.") }.toString()
        }
        override fun validateCall(id: String, arguments: JsonObject) {
            check(toolsAllowed && id == "mcp:trip-demo:weather_lookup" && readIds.any { it.startsWith(id) })
            McpClient.validateArguments(schema, arguments)
        }
        override suspend fun call(id: String, arguments: JsonObject): ExtensionResult {
            validateCall(id, arguments); calls += arguments
            return ExtensionResult(buildJsonObject { put("city", arguments.getValue("city")); put("celsius", 21); put("condition", "Sunny / 晴"); put("receipt", "DEMO_W_18") }.toString())
        }
    }
    @Test fun bilingualSkillsAndToolDiscovery() = runBlocking {
        check(System.getenv("ANDROIDMODEL_LEARNING_API_TEST") == "1")
        val config = CloudConfig(System.getenv("ANDROIDMODEL_TEST_API_URL").orEmpty(), System.getenv("ANDROIDMODEL_TEST_MODEL").orEmpty(), System.getenv("ANDROIDMODEL_TEST_API_KEY").orEmpty())
        val reports = mutableListOf<JsonObject>(); var success = false
        try {
            val chatPort = Port(false); val events = mutableListOf<ChatToolResult>()
            val answer = withTimeout(150_000) { CloudChatAgent(config.model, CloudApiClient(config), extensions = chatPort, responseLanguage = { "English" })
                .chat("Use the imported daily-brief skill and its reference file. Include the anchor word from that reference in a short plan. Answer in English.", onTool = events::add) }
            assertContains(answer, "ORCHID"); assertTrue(chatPort.readIds.contains("skill:daily-brief/references/focus.md")); assertTrue(chatPort.calls.isEmpty())
            assertTrue(events.any { it.name == "extension_search" }); assertFalse(Regex("[\\p{IsHan}]").containsMatchIn(answer))
            reports += buildJsonObject { put("scenario", "English chat uses imported skill and reference"); put("passed", true); put("answer", answer); putJsonArray("tools") { events.forEach { add(it.name) } } }
            println("PASS English imported skill, reference and answer")
            for (english in listOf(true, false)) {
                val port = Port(true); var run = PhoneRun()
                val driver = object : PhoneDriver {
                    override suspend fun observe(allowed: Set<String>) = ScreenSnapshot("s", "test.agent", 0, "same", emptyList())
                    override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean = error("No phone actions authorized in fixture")
                    override suspend fun awaitChange() = Unit
                }
                val goal = if (english) "Use the connected Trip demo service to look up weather in Hangzhou. Do not operate phone apps. Summarize the actual service result in English."
                    else "使用已连接的行程演示工具服务，查询杭州天气。不要操作手机 App，用中文汇总服务实际返回的结果。"
                withTimeout(180_000) { PhoneRunner(CloudPhonePlanner(config, extensions = port, responseLanguage = { if (english) "English" else "Chinese" }), driver,
                    { _, _ -> error("Unexpected confirmation") }, { run = it }, maxSteps = 12, confirmEveryAction = false, extensions = port).run(goal, mapOf("test.agent" to "LifeBuddy")) }
                reports += buildJsonObject { put("scenario", if (english) "English MCP discovery" else "Chinese MCP discovery"); put("run", Json.encodeToJsonElement(run)); put("calls", JsonArray(port.calls)) }
                assertEquals(RunStatus.COMPLETED, run.status, run.message); assertEquals(1, port.calls.size)
                assertTrue(port.calls.single().getValue("city").jsonPrimitive.content.lowercase() in setOf("hangzhou", "杭州"))
                // Preserve quoted service values while checking that the narrative is in the requested language.
                assertContains(run.message, "21"); if (english) assertFalse(Regex("[\\p{IsHan}]").containsMatchIn(run.message.replace("晴", "").replace("杭州", ""))) else assertTrue(Regex("[\\p{IsHan}]").containsMatchIn(run.message))
                println("PASS ${if (english) "English" else "Chinese"} tool lookup in 1001 tools, one call and returned result")
            }
            success = true
        } finally {
            File(System.getenv("ANDROIDMODEL_TEST_REPORT") ?: error("Missing report path")).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(buildJsonObject {
                put("success", success); put("model", config.model); put("realProvider", true); put("syntheticExtensions", true); put("externalMutations", 0); put("reports", JsonArray(reports))
            }))
        }
    }
}
