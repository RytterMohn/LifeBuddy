package dev.ondevice.gemma.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatStreamDecoderTest {
    private fun chunk(delta: String="{}", finish: String?=null) = """{"choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""
    private val toolStart = """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\""}}]}"""
    private val toolEnd = """{"tool_calls":[{"index":0,"function":{"name":"","arguments":"128*37\"}"}}]}"""

    @Test fun `SSE comments BOM multiline and event boundaries`() {
        val parser=SseEventDecoder()
        assertNull(parser.line("\uFEFF: keep alive"))
        assertNull(parser.line("event: message"))
        assertNull(parser.line("data: first"));assertNull(parser.line("data: second"))
        assertEquals("first\nsecond",parser.line(""))
        assertNull(parser.line(""))
        parser.line("data:[DONE]");assertEquals("[DONE]",parser.line(""))
    }
    @Test fun `text is available before finish and usage is accepted`() {
        val decoder=ChatStreamDecoder()
        assertEquals("你",decoder.event(chunk("""{"role":"assistant","content":"你"}""")))
        assertFailsWith<IllegalStateException> {decoder.response()}
        assertEquals("好",decoder.event(chunk("""{"content":"好"}""","stop")))
        decoder.event("""{"choices":[],"usage":{"completion_tokens":2}}""")
        decoder.event("[DONE]")
        assertEquals("你好",Json.parseToJsonElement(decoder.response()).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }
    @Test fun `gateway empty finish reason means generation is ongoing`() {
        val decoder=ChatStreamDecoder()
        assertEquals("开始",decoder.event(chunk("""{"content":"开始"}""", "")))
        assertFailsWith<IllegalStateException> {decoder.response()}
        decoder.event(chunk(finish="stop"));decoder.event("[DONE]")
        assertContains(decoder.response(),"开始")
    }
    @Test fun `empty gateway names do not erase split tool calls`() = runBlocking<Unit> {
        var requestCount=0
        val calls=mutableListOf<ChatToolResult>()
        val text=StringBuilder()
        val agent=CloudChatAgent("deepseek-test",ChatTransport { request,onText ->
            assertTrue(request["stream"]!!.jsonPrimitive.boolean)
            val decoder=ChatStreamDecoder()
            if(requestCount++==0) {
                decoder.event(chunk(toolStart));assertTrue(calls.isEmpty())
                decoder.event(chunk(toolEnd,"tool_calls"));assertTrue(calls.isEmpty())
            } else {
                assertContains(request.toString(),"4736")
                onText(decoder.event(chunk("""{"content":"47"}""")))
                onText(decoder.event(chunk("""{"content":"36"}""","stop")))
            }
            decoder.event("[DONE]");decoder.response()
        })
        assertEquals("4736",agent.chat("calculate",onText={text.append(it)},onTool=calls::add))
        assertEquals("4736",text.toString());assertEquals("calculator",calls.single().name)
    }
    @Test fun `interleaved tool indexes and reasoning are preserved`() {
        val decoder=ChatStreamDecoder()
        decoder.event(chunk("""{"reasoning_content":"context","tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"get_time","arguments":"{"}},{"index":0,"id":"a","type":"function","function":{"name":"system_info","arguments":"{"}}]}"""))
        decoder.event(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"}"}},{"index":1,"function":{"arguments":"}"}}]}""","tool_calls"))
        decoder.event("[DONE]")
        val msg=Json.parseToJsonElement(decoder.response()).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("context",msg["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(listOf("a","b"),msg["tool_calls"]!!.jsonArray.map {it.jsonObject["id"]!!.jsonPrimitive.content})
    }
    @Test fun `truncated tool stream never executes or commits history`() = runBlocking<Unit> {
        var turn=0
        var tools=0
        val agent=CloudChatAgent("test",ChatTransport { request,_ ->
            val decoder=ChatStreamDecoder()
            if(turn++ == 0) {decoder.event(chunk(toolStart));decoder.response()}
            else {
                assertEquals(2,request["messages"]!!.jsonArray.size)
                decoder.event(chunk("""{"content":"ok"}""","stop"));decoder.event("[DONE]");decoder.response()
            }
        })
        assertFailsWith<IllegalStateException> {agent.chat("first") {tools++}}
        assertEquals(0,tools);assertEquals("ok",agent.chat("second"))
    }
    @Test fun `length finish missing DONE and stream error are rejected`() {
        assertFailsWith<IllegalArgumentException> { ChatStreamDecoder().event(chunk(finish="length")) }
        assertFailsWith<IllegalStateException> { ChatStreamDecoder().event("[DONE]") }
        assertFailsWith<IllegalArgumentException> { ChatStreamDecoder().event("""{"error":{"message":"private provider error"}}""") }
        val decoder=ChatStreamDecoder();decoder.event(chunk("""{"content":"part"}""","stop"))
        assertFailsWith<IllegalStateException> { decoder.response() }
    }
    @Test fun `cancel a partial stream prevents tool execution`() = runBlocking<Unit> {
        val started=CompletableDeferred<Unit>()
        var called=false
        var partial=""
        val agent=CloudChatAgent("test",ChatTransport { _,onText ->onText("partial");started.complete(Unit);awaitCancellation()})
        val job=launch {agent.chat("hello",onText={partial+=it}) {called=true}}
        started.await();job.cancelAndJoin();assertEquals("partial",partial);assertFalse(called)
    }
    @Test fun `oversized event and missing tool name are rejected`() {
        assertFailsWith<IllegalArgumentException> {SseEventDecoder().line("data:"+"x".repeat(262145))}
        val decoder=ChatStreamDecoder()
        decoder.event(chunk("""{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"arguments":"{}"}}]}""","tool_calls"));decoder.event("[DONE]")
        assertFailsWith<IllegalArgumentException> {decoder.response()}
    }
}
