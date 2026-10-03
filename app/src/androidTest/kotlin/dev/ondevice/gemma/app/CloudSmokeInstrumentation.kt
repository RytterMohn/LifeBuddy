package dev.ondevice.gemma.app

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import dev.ondevice.gemma.app.data.AgentSettings
import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.cloud.CloudChatAgent
import dev.ondevice.gemma.cloud.ChatToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.File

/** Installed only in the separate androidTest APK. No credential or test entry point ships in app. */
class CloudSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        Thread {
            val output=Bundle()
            var stage="settings"
            try {
                val input=File(targetContext.noBackupFilesDir,"cloud-smoke-config.json")
                if(input.exists()) {
                    try {
                        val json=Json.parseToJsonElement(input.readText()).jsonObject
                        val config=CloudConfig(json.getValue("baseUrl").jsonPrimitive.content,json.getValue("model").jsonPrimitive.content,json.getValue("apiKey").jsonPrimitive.content)
                        CloudApiClient.validate(config);AgentSettings(targetContext).save(config)
                    } finally { input.delete() }
                }
                val config=AgentSettings(targetContext).read()
                val evidence=runBlocking { withTimeout(180000) {
                    val agent=CloudChatAgent(config.model,CloudApiClient(config))
                    val events=mutableListOf<ChatToolResult>()
                    stage="streaming_greeting"
                    val parts=mutableListOf<String>()
                    val started=SystemClock.elapsedRealtime()
                    var firstTokenMs=-1L
                    val greeting=agent.chat("你好！我叫小林，请用中文介绍手机助手能做什么，约 200 字，使用 Markdown 标题、加粗和列表。",onText={
                        if(parts.isEmpty()) firstTokenMs=SystemClock.elapsedRealtime()-started
                        parts+=it
                    },onTool=events::add)
                    val totalMs=SystemClock.elapsedRealtime()-started
                    check(greeting.isNotBlank())
                    check(parts.size>1 && parts.joinToString("")==greeting) {"stream_deltas_failed"}
                    check(firstTokenMs in 0 until totalMs) {"not_incremental"}
                    stage="memory"
                    val memory=agent.chat("我刚才说我叫什么名字？用一句话回答。",onTool=events::add)
                    check("小林" in memory) {"multi_turn_memory_failed"}
                    stage="streaming_tools"
                    val answer=agent.chat("请实际调用 calculator 计算 128*37，再调用 get_time 查询手机当前时间，最后汇总两个工具的结果。",onTool=events::add)
                    check(events.any {it.name=="calculator" && Json.parseToJsonElement(it.result).jsonObject["result"]?.jsonPrimitive?.doubleOrNull == 4736.0}) {"calculator_not_called"}
                    check(events.any {it.name=="get_time"}) {"time_not_called"}
                    stage="cancellation"
                    var cancelledAfterToken=false
                    try {
                        CloudChatAgent(config.model,CloudApiClient(config)).chat("请写一篇 1000 字的中文手机助手介绍。",onText={
                            cancelledAfterToken=true
                            throw CancellationException("test_cancel_after_first_token")
                        })
                        error("cancel_failed")
                    } catch (_: CancellationException) { check(cancelledAfterToken) }
                    buildJsonObject {
                        put("model",config.model);put("greeting",greeting);put("memory",memory);put("answer",answer)
                        putJsonArray("tools") {events.forEach { event -> addJsonObject {put("name",event.name);put("result",event.result)} }}
                        put("success",true)
                        put("streamChunks",parts.size);put("firstTokenMs",firstTokenMs);put("totalMs",totalMs)
                        put("cancelledAfterFirstToken",cancelledAfterToken)
                    }
                } }
                File(targetContext.noBackupFilesDir,"stream-smoke-result.json").writeText(evidence.toString())
                output.putString("result","PASS: real SSE deltas, multi-turn memory, streamed tools and cancellation")
                finish(0,output)
            } catch(e: Exception) {
                // Provider response and secrets deliberately excluded from instrumentation output.
                output.putString("result","FAIL at $stage: "+e.javaClass.simpleName)
                finish(1,output)
            }
        }.start()
    }
}
