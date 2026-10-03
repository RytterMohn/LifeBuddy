package dev.ondevice.gemma.app.extensions

import dev.ondevice.gemma.app.model.McpHttpTransport
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class McpHttpTransportTest {
    private val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 7); put("method", "tools/call") }
    private val answer = """{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"ok"}]}}"""
    private fun request(socket: Socket): Pair<Map<String, String>, String> {
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        check(input.readLine() == "POST /mcp HTTP/1.1")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: error("Missing headers")
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val chars = CharArray(headers.getValue("content-length").toInt()); var offset = 0
        while (offset < chars.size) { val n = input.read(chars, offset, chars.size - offset); check(n > 0); offset += n }
        return headers to String(chars)
    }
    @Test fun `JSON and SSE responses traverse real HTTP and SSE stops at matching response`() = runBlocking {
        for (sse in listOf(false, true)) {
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val release = CountDownLatch(1); var serverError: Throwable? = null
            val worker = thread {
                try { server.accept().use { socket ->
                    val (headers, received) = request(socket)
                    check("text/event-stream" in headers.getValue("accept")); check(Json.parseToJsonElement(received) == body)
                    val payload = if (sse) "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: $answer\n\n" else answer
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: ${if (sse) "text/event-stream" else "application/json"}\r\nMcp-Session-Id: test-session\r\n" +
                        (if (sse) "Connection: close\r\n" else "Content-Length: ${payload.toByteArray().size}\r\n") + "\r\n"
                    socket.getOutputStream().apply { write((head + payload).toByteArray()); flush() }
                    if (sse) release.await(5, TimeUnit.SECONDS)
                } } catch (error: Throwable) { serverError = error }
            }
            try {
                val result = withTimeout(3000) { McpHttpTransport().post("http://127.0.0.1:${server.localPort}/mcp", mapOf("Accept" to "application/json, text/event-stream"), body) }
                assertEquals(answer, result.body); assertEquals(200, result.status)
            } finally { release.countDown(); server.close(); worker.join(1000) }
            serverError?.let { throw it }
        }
    }
    @Test fun `HTTP disconnect after receipt does not retry a tool POST`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); val calls = AtomicInteger()
        val worker = thread {
            try { while (!server.isClosed) server.accept().use { request(it); calls.incrementAndGet() } }
            catch (_: java.io.IOException) { }
        }
        try {
            assertFailsWith<java.io.IOException> { McpHttpTransport().post("http://127.0.0.1:${server.localPort}/mcp", emptyMap(), body) }
            assertEquals(1, calls.get())
        } finally { server.close(); worker.join(1000) }
    }
}
