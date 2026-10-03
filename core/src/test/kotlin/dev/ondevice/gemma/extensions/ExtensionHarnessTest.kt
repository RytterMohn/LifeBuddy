package dev.ondevice.gemma.extensions

import dev.ondevice.gemma.cloud.*
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ExtensionHarnessTest {
    private val page = ScreenSnapshot("s", "agent", 0, "same", emptyList())
    private val driver = object : PhoneDriver {
        override suspend fun observe(allowed: Set<String>) = page
        override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean = error("Extension calls must not reach the phone executor")
        override suspend fun awaitChange() = Unit
    }
    private open class Port : ExtensionPort {
        override val available = true
        var calls = 0
        override fun search(query: String, cursor: String) = "skill:daily-plan; mcp:server:weather"
        override fun read(id: String, resource: String, cursor: String) = "Use today's weather to plan a short walk."
        override suspend fun call(id: String, arguments: JsonObject): ExtensionResult { calls++; return ExtensionResult("Sunny, 20 C") }
    }
    private fun action(type: PhoneActionType) = PhoneAction("s", type, "Check weather", extensionId = "mcp:server:weather", argumentsJson = "{\"city\":\"Beijing\"}")

    @Test fun `discover read and call external tool completes in same serialized task`() = runBlocking {
        val port = Port(); var final = PhoneRun(); var requests = 0
        val plan = PhonePlanner { when (requests++) {
            0 -> action(PhoneActionType.SEARCH_EXTENSIONS)
            1 -> action(PhoneActionType.READ_EXTENSION)
            2 -> action(PhoneActionType.CALL_EXTENSION)
            else -> PhoneAction("s", PhoneActionType.RESPOND, "Answer from tool", text = "The service reports sunny weather, 20 C.")
        } }
        PhoneRunner(plan, driver, { _, _ -> error("unexpected approval") }, { final = it }, confirmEveryAction = false, extensions = port).run("Check Beijing weather", mapOf("agent" to "LifeBuddy"))
        assertEquals(RunStatus.COMPLETED, final.status); assertEquals(1, port.calls)
        val restored = Json.decodeFromString<PhoneRun>(Json.encodeToString(final))
        assertTrue(restored.steps[2].dispatchAttempted && restored.steps[2].extensionSucceeded)
        assertContains(restored.steps[2].observation, "20 C"); assertFalse(restored.hasUnconfirmedExtension())
    }
    @Test fun `uncertain external outcome pauses and never retries`() = runBlocking {
        val port = object : Port() { override suspend fun call(id: String, arguments: JsonObject): ExtensionResult { calls++; throw java.io.IOException("lost") } }
        var final = PhoneRun()
        PhoneRunner({ action(PhoneActionType.CALL_EXTENSION) }, driver, { _, _ -> true }, { final = it }, extensions = port)
            .run("Do task", mapOf("agent" to "LifeBuddy"))
        assertEquals(1, port.calls); assertEquals(RunStatus.PAUSED, final.status); assertTrue(final.hasUnconfirmedExtension())
        assertContains(final.message, "outcome unknown")
    }
    @Test fun `duplicate external calls are blocked across reordered arguments`() = runBlocking {
        val port = Port(); var final = PhoneRun(); var plans = 0
        PhoneRunner({ action(PhoneActionType.CALL_EXTENSION).copy(argumentsJson = if (plans++ == 0) "{\"a\":1,\"b\":2}" else "{\"b\":2,\"a\":1}") }, driver,
            { _, _ -> true }, { final = it }, extensions = port).run("Do task", mapOf("agent" to "LifeBuddy"))
        assertEquals(1, port.calls); assertEquals(RunStatus.PAUSED, final.status); assertContains(final.message, "duplicate blocked")
    }
    @Test fun `cancellation preserves dispatch checkpoint without a success claim`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var final = PhoneRun()
        val port = object : Port() { override suspend fun call(id: String, arguments: JsonObject): ExtensionResult { calls++; entered.complete(Unit); awaitCancellation() } }
        val job = launch { PhoneRunner({ action(PhoneActionType.CALL_EXTENSION) }, driver, { _, _ -> true }, { final = it }, extensions = port).run("Do task", mapOf("agent" to "LifeBuddy")) }
        entered.await(); job.cancelAndJoin()
        assertEquals(1, port.calls); assertTrue(final.hasUnconfirmedExtension()); assertFalse(final.steps.last().extensionSucceeded)
    }
    @Test fun `failed checkpoint prevents external invocation`() = runBlocking {
        val port = Port()
        assertFailsWith<IllegalStateException> {
            PhoneRunner({ action(PhoneActionType.CALL_EXTENSION) }, driver, { _, _ -> true }, { if (it.steps.any { s -> s.dispatchAttempted }) error("disk full") }, extensions = port)
                .run("Do task", mapOf("agent" to "LifeBuddy"))
        }
        assertEquals(0, port.calls)
    }
    @Test fun `disabled extensions cannot execute and unavailable schemas are not guessed`() = runBlocking {
        var final = PhoneRun()
        PhoneRunner({ action(PhoneActionType.CALL_EXTENSION) }, driver, { _, _ -> true }, { final = it })
            .run("Do task", mapOf("agent" to "LifeBuddy"))
        assertEquals(RunStatus.PAUSED, final.status); assertFalse(final.steps.last().dispatchAttempted)
    }
    @Test fun `chat can read a skill and answer in English without an external call gateway`() = runBlocking {
        val port = Port(); var calls = 0
        val transport = ChatTransport { request, onText ->
            val names = request.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue("extension_search" in names && "extension_read" in names && "extension_call" !in names)
            assertContains(request.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content, "use English")
            val index = calls++
            val message = buildJsonObject {
                put("role", "assistant")
                if (index < 2) putJsonArray("tool_calls") { addJsonObject {
                    put("id", "call$index"); put("type", "function"); putJsonObject("function") {
                        put("name", if (index == 0) "extension_search" else "extension_read")
                        put("arguments", if (index == 0) "{\"query\":\"daily\",\"cursor\":\"\"}" else "{\"extensionId\":\"skill:daily-plan\",\"resource\":\"\",\"cursor\":\"\"}")
                    }
                } } else { onText("Plan a short walk."); put("content", "Plan a short walk.") }
            }
            buildJsonObject { putJsonArray("choices") { addJsonObject { put("finish_reason", if (index < 2) "tool_calls" else "stop"); put("message", message) } } }.toString()
        }
        val agent = CloudChatAgent("test", transport, extensions = port, responseLanguage = { "English" })
        assertEquals("Plan a short walk.", agent.chat("Use daily plan skill")); assertEquals(0, port.calls)
        assertEquals(3, calls); assertContains(agent.snapshotState().toString(), "extension_read")
    }
}
