package dev.ondevice.gemma.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HarnessReliabilityTest {
    private fun answer(text: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { put("finish_reason", "stop"); putJsonObject("message") { put("role", "assistant"); put("content", text); put("reasoning_content", "continuation") } } }
    }.toString()
    private fun seed(count: Int) = JsonArray((0 until count).map { i -> JsonArray(listOf(
        buildJsonObject { put("role", "user"); put("content", if (i == 0) "项目代号银杏-42，回复必须用中文" else "普通消息 $i") },
        buildJsonObject { put("role", "assistant"); put("content", "已确认 $i") },
    )) })
    private val limits = HarnessLimits(maxTurns = 3, keepRecentTurns = 1)

    @Test fun `compaction retains constraints and recent complete turns across restart`() = runBlocking<Unit> {
        val requests = mutableListOf<JsonObject>()
        val agent = CloudChatAgent("test", ChatTransport { request, _ ->
            requests += request
            answer(if ("tools" !in request) "用户项目代号银杏-42；必须用中文；前两轮已确认。" else "银杏-42")
        }, limits = limits)
        agent.restoreState(seed(3))
        agent.chat("继续项目")
        assertEquals(2, requests.size)
        assertContains(requests.last().toString(), "银杏-42")
        assertContains(requests.last().toString(), "普通消息 2")
        assertEquals(1, agent.lastStats.compactions)
        assertEquals(2, agent.lastStats.requests)
        val state = agent.snapshotState()
        val restored = CloudChatAgent("test", ChatTransport { request, _ ->
            assertContains(request.toString(), "银杏-42"); answer("已继续")
        }, limits = limits)
        restored.restoreState(state)
        assertEquals(state, restored.snapshotState())
        restored.chat("接着做")
    }

    @Test fun `failed or cancelled compaction cannot replace original context`() = runBlocking<Unit> {
        for (cancel in listOf(false, true)) {
            val agent = CloudChatAgent("test", ChatTransport { _, _ ->
                if (cancel) throw CancellationException("cancel") else answer("")
            }, limits = limits)
            agent.restoreHistory(seed(3))
            val before = agent.snapshotState()
            assertFails { agent.chat("继续") }
            assertEquals(before, agent.snapshotState())
            assertEquals(if (cancel) "cancelled" else "failed", agent.lastStats.outcome)
        }
    }

    @Test fun `failed final answer does not commit staged summary`() = runBlocking<Unit> {
        val agent = CloudChatAgent("test", ChatTransport { request, _ ->
            if ("tools" !in request) answer("早期约束已整理") else throw IllegalStateException("network")
        }, limits = limits)
        agent.restoreHistory(seed(3))
        val before = agent.snapshotState()
        assertFails { agent.chat("继续") }
        assertEquals(before, agent.snapshotState())
    }

    @Test fun `budget considers CJK system tools and new input not only saved messages`() = runBlocking<Unit> {
        assertTrue(ContextBudget.estimate("汉".repeat(900)) > ContextBudget.estimate("a".repeat(900)))
        var requested = false
        val agent = CloudChatAgent("test", ChatTransport { _, _ -> requested = true; answer("unexpected") }, limits = HarnessLimits(inputTokens = 3000))
        assertFailsWith<IllegalArgumentException> { agent.chat("汉".repeat(4000)) }
        assertFalse(requested)
        assertTrue(agent.snapshotHistory().isEmpty())
    }

    @Test fun `plain assistant provider continuation survives context restore`() = runBlocking<Unit> {
        val agent = CloudChatAgent("test", ChatTransport { _, _ -> answer("ok") })
        agent.chat("hello")
        val restored = CloudChatAgent("test", ChatTransport { request, _ ->
            val messages = request.getValue("messages").jsonArray
            assertEquals("continuation", messages[2].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
            answer("ok")
        })
        restored.restoreState(agent.snapshotState()); restored.chat("again")
    }

    @Test fun `large Chinese history is compacted in bounded complete chunks`() = runBlocking<Unit> {
        val fixture = JsonArray((0 until 12).map { i -> JsonArray(listOf(
            buildJsonObject { put("role", "user"); put("content", "第${i}轮" + "内容".repeat(450)) },
            buildJsonObject { put("role", "assistant"); put("content", "已确认") },
        )) })
        var summaries = 0
        val agent = CloudChatAgent("test", ChatTransport { request, _ ->
            assertTrue(ContextBudget.estimate(request.toString()) <= 5000)
            if ("tools" !in request) summaries++
            answer("目标及约束保持不变")
        }, limits = HarnessLimits(inputTokens = 5000))
        agent.restoreHistory(fixture)
        agent.chat("继续")
        assertTrue(summaries in 1..3)
        assertEquals(summaries, agent.lastStats.compactions)
        assertTrue(agent.snapshotState()["summarizedTurns"]!!.jsonPrimitive.int > 0)
        assertTrue(agent.lastStats.peakEstimatedInputTokens <= 5000)
    }

    @Test fun `whole tool group goes into summary and never reexecutes`() = runBlocking<Unit> {
        val toolTurn = Json.parseToJsonElement("""[{"role":"user","content":"计算 1+1"},{"role":"assistant","tool_calls":[{"id":"c","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\"1+1\"}"}}]},{"role":"tool","tool_call_id":"c","content":"2"},{"role":"assistant","content":"2"}]""")
        val agent = CloudChatAgent("test", ChatTransport { request, _ ->
            if ("tools" !in request) {
                val content = request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
                assertContains(content, "tool_call_id"); assertContains(content, "calculator")
            }
            answer("计算结果为 2")
        }, limits = limits)
        agent.restoreHistory(JsonArray(listOf(toolTurn) + seed(2)))
        var executed = false
        agent.chat("之前算出了什么？", onTool = { executed = true })
        assertFalse(executed)
    }
}
