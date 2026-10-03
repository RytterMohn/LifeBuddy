package dev.ondevice.gemma.phone

import kotlinx.serialization.json.*

object PhoneResponseCodec {
    private val json = Json { ignoreUnknownKeys = false }
    fun decode(raw: String): PhoneAction {
        require(raw.length <= 262144)
        val choice = json.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray.single().jsonObject
        require(choice["finish_reason"]?.jsonPrimitive?.content in setOf("tool_calls", "stop")) { "Incomplete phone action" }
        val message = choice["message"]!!.jsonObject
        require(message["role"]?.jsonPrimitive?.content == "assistant")
        val calls = message["tool_calls"]!!.jsonArray
        require(calls.size == 1) { "Only a single action may be planned" }
        val call = calls.single().jsonObject
        require(call["type"]?.jsonPrimitive?.content == "function" && !call["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        val function = call["function"]!!.jsonObject
        val name = function["name"]!!.jsonPrimitive.content
        val arguments = function["arguments"]!!.jsonPrimitive.content
        // Legacy records/older tests remain readable, but new requests advertise the typed catalog only.
        if (name == "phone_step") return json.decodeFromString<PhoneAction>(arguments)
        return PhoneTools.decode(name, json.parseToJsonElement(arguments).jsonObject)
    }
}
