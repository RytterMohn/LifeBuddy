package dev.ondevice.gemma.app.model

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.cloud.ChatTransport
import dev.ondevice.gemma.cloud.ChatStreamDecoder
import dev.ondevice.gemma.cloud.SseEventDecoder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared cancellable HTTPS transport. Never logs credentials, request bodies or provider errors. */
class CloudApiClient(private val config: CloudConfig) : ChatTransport {
    private fun call(request: JsonObject): Call {
        validate(config)
        val http=Request.Builder().url(config.baseUrl.trim().trimEnd('/')+"/chat/completions")
            .header("Authorization","Bearer ${config.apiKey.trim()}")
            .post(request.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(http)
    }
    suspend fun complete(request: JsonObject, onTiming: (networkMs: Long, responseHeadersMs: Long) -> Unit = { _, _ -> }): String = awaitBody(call(request), onTiming)

    override suspend fun stream(request: JsonObject, onText: suspend (String) -> Unit): String {
        val decoder = ChatStreamDecoder()
        val call = call(request)
        callbackFlow {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(IOException("模型连接失败或中断，请检查网络后重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            check(response.isSuccessful) { "模型服务返回 HTTP ${response.code}，请检查地址、Key 和模型权限" }
                            check(response.body?.contentType()?.subtype == "event-stream") { "模型服务未返回流式响应，请检查接口兼容性" }
                            val source = response.body!!.source()
                            val framing = SseEventDecoder()
                            var wireBytes = 0L
                            while (!source.exhausted()) {
                                val line = source.readUtf8LineStrict(262144)
                                wireBytes += line.length + 1
                                require(wireBytes <= 2 * 1024 * 1024) { "流式响应过大" }
                                val event = framing.line(line) ?: continue
                                if (trySendBlocking(event).isFailure || event == "[DONE]") break
                            }
                            close()
                        } catch (e: Exception) {
                            // I/O errors can contain URLs; never expose raw exception details.
                            close(if (e is IOException) IOException("流式连接中断，请重试") else e)
                        }
                    }
                }
            })
            awaitClose { call.cancel() }
        }.collect { event ->
            val text = decoder.event(event)
            if (text.isNotEmpty()) onText(text)
        }
        return decoder.response()
    }
    private suspend fun awaitBody(call: Call, onTiming: (Long, Long) -> Unit): String = suspendCancellableCoroutine { continuation ->
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object : Callback {
            override fun onFailure(call: Call,e: IOException) {
                if(continuation.isActive) continuation.resumeWithException(IOException("模型连接失败或超时，请检查网络和设置"))
            }
            override fun onResponse(call: Call,response: Response) {
                val headersMs = elapsed()
                response.use {
                    val result=runCatching {
                        check(response.isSuccessful) {"模型服务返回 HTTP ${response.code}，请检查地址、Key 和模型权限"}
                        val source=response.body?.source() ?: error("模型响应为空")
                        source.request(262145)
                        require(source.buffer.size <= 262144) {"模型响应过大"}
                        source.buffer.readUtf8()
                    }
                    if(continuation.isActive) {
                        onTiming(elapsed(), headersMs)
                        result.fold(continuation::resume,continuation::resumeWithException)
                    }
                }
            }
        })
    }
    companion object {
        // Pool connections across tasks; credentials remain per-request headers.
        private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(105,TimeUnit.SECONDS).build()
        fun validate(config: CloudConfig) {
            val url=config.baseUrl.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {"填写 HTTPS API 基础地址，例如供应商提供的 /v1 地址"}
            require(config.model.isNotBlank()) {"请填写模型名称"}
            require(config.apiKey.isNotBlank()) {"请填写 API Key"}
        }
    }
}
