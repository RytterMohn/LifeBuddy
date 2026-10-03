package dev.ondevice.gemma.phone

import kotlinx.serialization.json.*
import kotlin.test.*

class PhoneResponseCodecTest {
    private fun response(arguments: String, name: String = "phone_step", count: Int = 1) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { put("finish_reason", "tool_calls"); putJsonObject("message") {
            put("role", "assistant")
            putJsonArray("tool_calls") { repeat(count) { addJsonObject {
                put("id", "call_1"); put("type", "function")
                putJsonObject("function") { put("name", name); put("arguments", arguments) }
            } } }
        } } }
    }.toString()
    private val action = """{"snapshotId":"s","type":"TAP","reason":"保存","nodeId":"n0"}"""
    @Test fun `decodes a single structured action`() {
        val result = PhoneResponseCodec.decode(response(action))
        assertEquals(PhoneActionType.TAP, result.type)
        assertEquals("n0", result.nodeId)
    }
    @Test fun `rejects truncated action even with parseable arguments`() { assertFails { PhoneResponseCodec.decode(response(action).replace("tool_calls\",\"message", "length\",\"message")) } }
    @Test fun `rejects multiple actions`() { assertFails { PhoneResponseCodec.decode(response(action, count = 2)) } }
    @Test fun `rejects arbitrary tool names`() { assertFails { PhoneResponseCodec.decode(response(action, name = "terminal")) } }
    @Test fun `rejects malformed action rather than filling empty arguments`() {
        assertFails { PhoneResponseCodec.decode(response("{}")) }
        assertFails { PhoneResponseCodec.decode(response("not json")) }
    }
    @Test fun `rejects unknown actions and unexpected parameters`() {
        assertFails { PhoneResponseCodec.decode(response(action.replace("TAP", "PAY"))) }
        assertFails { PhoneResponseCodec.decode(response(action.dropLast(1) + ",\"shell\":\"cmd\"}")) }
    }
}
