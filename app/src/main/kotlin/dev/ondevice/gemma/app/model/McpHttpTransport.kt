package dev.ondevice.gemma.app.model

import dev.ondevice.gemma.cloud.SseEventDecoder
import dev.ondevice.gemma.extensions.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One POST per RPC; redirects and transport retries are disabled for external mutations. */
class McpHttpTransport : McpTransport {
    override suspend fun post(url: String, headers: Map<String, String>, body: JsonObject): McpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("MCP connection interrupted or timed out / MCP 连接中断或超时"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = runCatching {
                        if (!response.isSuccessful) return@runCatching McpResponse(response.code, emptyMap(), "")
                        val returnedHeaders = response.headers.toMultimap().mapValues { it.value.first() }
                        if (body["id"] == null) return@runCatching McpResponse(response.code, returnedHeaders, "")
                        val source = response.body?.source() ?: error("Empty MCP response / MCP 返回为空")
                        val subtype = response.body?.contentType()?.subtype
                        val text = if (subtype == "event-stream") {
                            val decoder = SseEventDecoder()
                            var size = 0L
                            var answer: String? = null
                            while (answer == null && !source.exhausted()) {
                                val line = source.readUtf8LineStrict(1_048_576)
                                size += line.toByteArray(Charsets.UTF_8).size + 1
                                require(size <= 1_048_576) { "MCP stream exceeds limit / MCP 流超过大小限制" }
                                val event = decoder.line(line) ?: continue
                                val message = Json.parseToJsonElement(event).jsonObject
                                if (message["id"] == body["id"] && ("result" in message || "error" in message)) answer = event
                                else require("method" !in message || "id" !in message) { "Unsupported MCP server request / 不支持此服务端请求" }
                            }
                            answer ?: error("MCP stream ended before result / MCP 流未返回结果")
                        } else {
                            require(subtype == "json") { "Expected MCP JSON or SSE / MCP 返回格式不支持" }
                            source.request(1_048_577)
                            require(source.buffer.size <= 1_048_576) { "MCP response exceeds limit / MCP 返回超过大小限制" }
                            source.buffer.readUtf8()
                        }
                        McpResponse(response.code, returnedHeaders, text)
                    }.recoverCatching { error ->
                        if (error is IOException) throw IOException("MCP connection interrupted / MCP 连接中断") else throw error
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            }
        })
    }
    companion object {
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }
}
