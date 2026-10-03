package dev.ondevice.gemma.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CloudChatAgentTest {
    @Test fun `chat mode never advertises or executes phone tools`() = runBlocking<Unit> {
        var executed = false
        val agent = CloudChatAgent("test", ChatTransport { request, _ ->
            assertTrue(request["tools"]!!.jsonArray.none { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content.startsWith("phone_") })
            call("phone_open_app", "{\"packageName\":\"qq\"}")
        })
        assertFailsWith<IllegalArgumentException> { agent.chat("打开 QQ") { executed = true } }
        assertFalse(executed)
    }
    private fun answer(text: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject {put("finish_reason","stop");putJsonObject("message") {put("role","assistant");put("content",text)}} }
    }.toString()
    private fun call(name: String="calculator", args: String="{\"expression\":\"128*37\"}", id: String="call_1", finish: String="tool_calls"): String = buildJsonObject {
        putJsonArray("choices") { addJsonObject {
            put("finish_reason",finish)
            putJsonObject("message") {put("role","assistant");put("content",JsonNull);put("reasoning_content","provider continuation")
                putJsonArray("tool_calls") {addJsonObject {put("id",id);put("type","function");putJsonObject("function") {put("name",name);put("arguments",args)}}}
            }
        } }
    }.toString()

    @Test fun `ordinary conversation includes previous turn and no tool evidence`() = runBlocking<Unit> {
        val requests=mutableListOf<JsonObject>()
        val agent=CloudChatAgent("deepseek-test",ChatTransport { request, _ ->requests+=request;answer("好的")})
        val events=mutableListOf<ChatToolResult>()
        agent.chat("我叫小林",onTool=events::add);agent.chat("我叫什么？",onTool=events::add)
        assertEquals(4,requests.last()["messages"]!!.jsonArray.size)
        assertTrue(requests.last().toString().contains("小林"));assertTrue(events.isEmpty())
    }
    @Test fun `tool round sends real result and matching id back to model`() = runBlocking<Unit> {
        val requests=mutableListOf<JsonObject>()
        val agent=CloudChatAgent("deepseek-test",ChatTransport { request, _ ->requests+=request;if(requests.size==1) call() else answer("4736")})
        val events=mutableListOf<ChatToolResult>()
        assertEquals("4736",agent.chat("计算",onTool=events::add))
        assertEquals(4736.0,Json.parseToJsonElement(events.single().result).jsonObject["result"]!!.jsonPrimitive.double)
        val history=requests.last()["messages"]!!.jsonArray
        assertEquals("call_1",history.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("provider continuation",history[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        val schema=requests.first()["tools"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("object",schema["type"]!!.jsonPrimitive.content)
    }
    @Test fun `reject unregistered tool without execution`() = runBlocking<Unit> {
        var executed=false
        val agent=CloudChatAgent("test",ChatTransport { request, _ ->call("shell","{}")})
        assertFailsWith<IllegalArgumentException> { agent.chat("test") {executed=true} }
        assertFalse(executed)
    }
    @Test fun `reject malformed arguments`() = runBlocking<Unit> {
        for(args in listOf("[]","{\"expression\":true}","{\"expression\":\"1+1\",\"extra\":1}")) {
            var executed=false
            assertFailsWith<Exception> {CloudChatAgent("test",ChatTransport { request, _ ->call(args=args)}).chat("test") {executed=true}}
            assertFalse(executed)
        }
    }
    @Test fun `truncated tool output must not execute`() = runBlocking<Unit> {
        var executed=false
        assertFailsWith<IllegalArgumentException> {CloudChatAgent("test",ChatTransport { request, _ ->call(finish="length")}).chat("test") {executed=true}}
        assertFalse(executed)
    }
    @Test fun `cancel after request prevents execution and history commit`() = runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>()
        val agent=CloudChatAgent("test",ChatTransport { request, _ ->gate.complete(Unit);awaitCancellation()})
        var executed=false
        val job=launch {agent.chat("test") {executed=true}}
        gate.await();job.cancelAndJoin();assertFalse(executed)
    }
    @Test fun `repeated tool id stops loop`() = runBlocking<Unit> {
        var count=0
        assertFailsWith<IllegalArgumentException> {CloudChatAgent("test",ChatTransport { request, _ ->call()}).chat("test") {count++}}
        assertEquals(1,count)
    }
    @Test fun `clear removes complete conversation history`() = runBlocking<Unit> {
        val requests=mutableListOf<JsonObject>()
        val agent=CloudChatAgent("test",ChatTransport { request, _ ->requests+=request;answer("ok")})
        agent.chat("old");agent.clear();agent.chat("new")
        assertEquals(2,requests.last()["messages"]!!.jsonArray.size)
    }
    @Test fun `tool limit bounds repeated valid requests`() = runBlocking<Unit> {
        var count=0
        val agent=CloudChatAgent("test",ChatTransport { request, _ ->call(id="call_${count}", args="{\"expression\":\"$count+1\"}")})
        assertFailsWith<IllegalStateException> {agent.chat("test") {count++}}
        assertEquals(8,count)
    }
    @Test fun `nonfinite calculator result is returned as error JSON`() = runBlocking<Unit> {
        var n=0
        val events=mutableListOf<ChatToolResult>()
        CloudChatAgent("test",ChatTransport { request, _ ->if(n++==0) call(args="{\"expression\":\"1/0\"}") else answer("除数不能为零")}).chat("test",onTool=events::add)
        assertNotNull(Json.parseToJsonElement(events.single().result).jsonObject["error"])
    }
    @Test fun `serialized history restores matching tools without replay`() = runBlocking<Unit> {
        var requests = 0
        val original = CloudChatAgent("test", ChatTransport { _, _ -> if (requests++ == 0) call() else answer("4736") })
        original.chat("计算 128*37")
        val persisted = Json.parseToJsonElement(original.snapshotHistory().toString()).jsonArray
        var restoredRequest: JsonObject? = null
        var replayed = false
        val restored = CloudChatAgent("test", ChatTransport { request, _ -> restoredRequest = request; answer("上次结果是 4736") })
        restored.restoreHistory(persisted)
        restored.chat("上次结果？", onTool = { replayed = true })
        val messages = restoredRequest!!.getValue("messages").jsonArray
        assertEquals("call_1", messages[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("provider continuation", messages[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse(replayed)
    }

    @Test fun `switching saved conversations replaces context rather than appending`() = runBlocking<Unit> {
        val requests = mutableListOf<JsonObject>()
        val agent = CloudChatAgent("test", ChatTransport { request, _ -> requests += request; answer("ok") })
        agent.chat("ALPHA secret")
        val first = agent.snapshotHistory()
        agent.clear(); agent.chat("BETA secret")
        val second = agent.snapshotHistory()
        agent.restoreHistory(first); agent.chat("remember?")
        assertTrue("ALPHA" in requests.last().toString())
        assertFalse("BETA" in requests.last().toString())
        agent.restoreHistory(second); agent.chat("remember again?")
        assertTrue("BETA" in requests.last().toString())
        assertFalse("ALPHA" in requests.last().toString())
    }

    @Test fun `interrupted turns never enter restored model context`() = runBlocking<Unit> {
        var count = 0
        val agent = CloudChatAgent("test", ChatTransport { _, onText ->
            if (count++ == 0) answer("saved") else { onText("partial"); throw CancellationException("stopped") }
        })
        agent.chat("completed")
        val before = agent.snapshotHistory()
        assertFailsWith<CancellationException> { agent.chat("incomplete") }
        assertEquals(before, agent.snapshotHistory())
        assertFalse("incomplete" in agent.snapshotHistory().toString())
    }

    @Test fun `reject orphan tool result when restoring context`() {
        val agent = CloudChatAgent("test", ChatTransport { _, _ -> answer("ok") })
        val invalid = Json.parseToJsonElement("""[[{"role":"user","content":"hello"},{"role":"tool","tool_call_id":"orphan","content":"1"},{"role":"assistant","content":"ok"}]]""").jsonArray
        assertFailsWith<IllegalArgumentException> { agent.restoreHistory(invalid) }
        assertTrue(agent.snapshotHistory().isEmpty())
    }

    @Test fun `short conversations retain early constraints beyond six turns`() = runBlocking<Unit> {
        val agent = CloudChatAgent("test", ChatTransport { _, _ -> answer("ok") })
        repeat(10) { agent.chat("turn-$it") }
        assertEquals(10, agent.snapshotHistory().size)
        assertTrue("turn-0" in agent.snapshotHistory().toString())
        assertTrue("turn-9" in agent.snapshotHistory().toString())
    }
}
