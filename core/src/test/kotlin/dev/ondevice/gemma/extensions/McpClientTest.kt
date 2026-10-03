package dev.ondevice.gemma.extensions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class McpClientTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}""").jsonObject
    private val tool = McpTool("weather", "Weather", schema)
    private fun result(request: JsonObject, value: JsonObject, session: String? = null) = McpResponse(200,
        session?.let { mapOf("mcp-session-id" to it) }.orEmpty(), buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", value) }.toString())
    private fun initialized(version: String = "2025-11-25") = buildJsonObject { put("protocolVersion", version); putJsonObject("capabilities") { putJsonObject("tools") {} } }

    @Test fun `negotiates session headers initialized notification and paginated catalogs`() = runBlocking {
        val methods = mutableListOf<String>()
        val client = McpClient("https://example.test/mcp", mapOf("Authorization" to "Bearer test-only")) { _, headers, request ->
            val method = request.getValue("method").jsonPrimitive.content; methods += method
            assertEquals("Bearer test-only", headers["Authorization"])
            if (method == "initialize") result(request, initialized("2025-06-18"), "SESSION_A")
            else {
                assertEquals("SESSION_A", headers["Mcp-Session-Id"]); assertEquals("2025-06-18", headers["MCP-Protocol-Version"])
                when (method) {
                    "notifications/initialized" -> { assertNull(request["id"]); McpResponse(202, emptyMap(), "") }
                    "tools/list" -> result(request, buildJsonObject {
                        val second = request.getValue("params").jsonObject["cursor"] != null
                        putJsonArray("tools") { addJsonObject { put("name", if (second) "weather2" else "weather"); put("inputSchema", schema) } }
                        if (!second) put("nextCursor", "next")
                    })
                    else -> result(request, buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "Sunny") } } })
                }
            }
        }
        assertEquals(listOf("weather", "weather2"), client.listTools().map { it.name })
        assertContains(client.call(tool, buildJsonObject { put("city", "Beijing") }).text, "Sunny")
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call"), methods)
    }
    @Test fun `uncertain tool call is never automatically retried`() = runBlocking {
        var calls = 0
        val client = McpClient("https://example.test/mcp", emptyMap()) { _, _, request ->
            when (request.getValue("method").jsonPrimitive.content) {
                "initialize" -> result(request, initialized())
                "notifications/initialized" -> McpResponse(202, emptyMap(), "")
                else -> { calls++; throw java.io.IOException("connection lost after dispatch") }
            }
        }
        assertFailsWith<java.io.IOException> { client.call(tool, buildJsonObject { put("city", "Shanghai") }) }
        assertEquals(1, calls)
    }
    @Test fun `rejects unsupported versions wrong IDs and repeated cursors`() = runBlocking {
        val badVersion = McpClient("https://example.test/mcp", emptyMap()) { _, _, request -> result(request, initialized("1900-01-01")) }
        assertFails { badVersion.listTools() }
        val badId = McpClient("https://example.test/mcp", emptyMap()) { _, _, _ -> McpResponse(200, emptyMap(), """{"jsonrpc":"2.0","id":999,"result":{}}""") }
        assertFails { badId.listTools() }
        var pages = 0
        val repeated = McpClient("https://example.test/mcp", emptyMap()) { _, _, request -> when(request.getValue("method").jsonPrimitive.content) {
            "initialize" -> result(request, initialized())
            "notifications/initialized" -> McpResponse(202, emptyMap(), "")
            else -> { pages++; result(request, buildJsonObject { putJsonArray("tools") {}; put("nextCursor", "same") }) }
        } }
        assertFails { repeated.listTools() }; assertEquals(2, pages)
    }
    @Test fun `argument errors are rejected before any HTTP request`() = runBlocking {
        var requests = 0
        val client = McpClient("https://example.test/mcp", emptyMap()) { _, _, _ -> requests++; error("must not call") }
        for (args in listOf(JsonObject(emptyMap()), buildJsonObject { put("city", 1) }, buildJsonObject { put("city", "x"); put("extra", true) }))
            assertFails { client.call(tool, args) }
        assertEquals(0, requests)
    }
    @Test fun `structured and error results remain bounded and explicit`() = runBlocking {
        val client = McpClient("https://example.test/mcp", emptyMap()) { _, _, request -> when(request.getValue("method").jsonPrimitive.content) {
            "initialize" -> result(request, initialized())
            "notifications/initialized" -> McpResponse(202, emptyMap(), "")
            else -> result(request, buildJsonObject { put("isError", true); putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "x".repeat(15_000)) }; addJsonObject { put("type", "image"); put("data", "not-loaded") } }; putJsonObject("structuredContent") { put("error", "invalid city") } })
        } }
        val returned = client.call(tool, buildJsonObject { put("city", "x") })
        assertTrue(returned.isError); assertTrue(returned.text.length < 8000)
        assertContains(returned.text, "\"truncated\":true"); assertFalse(returned.text.contains("not-loaded"))
    }
    @Test fun `imports HTTPS desktop configurations but rejects shell and credential URL variants`() {
        val parsed = McpConfig.parse("""{"mcpServers":{"Weather":{"url":"https://example.test/mcp","headers":{"Authorization":"Bearer synthetic"}}}}""").single()
        assertEquals("Weather", parsed.name); assertEquals("Bearer synthetic", parsed.headers["Authorization"])
        for (url in listOf("http://example.test/mcp", "https://user:pass@example.test/mcp", "https://example.test/mcp?token=secret", "https://example.test/mcp#x")) assertFails { McpConfig.validate(url, emptyMap()) }
        assertFails { McpConfig.validate(parsed.url, mapOf("Authorization" to "bad\r\nX: x")) }
        assertFails { McpConfig.validate(parsed.url, mapOf("Host" to "other.test")) }
        assertFails { McpConfig.parse("""{"mcpServers":{"shell":{"command":"npx","args":["server"]}}}""") }
    }
}
