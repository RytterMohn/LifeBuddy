package dev.ondevice.gemma.llm

import dev.ondevice.gemma.prompts.Gemma3Format

/**
 * Mock 推理引擎：不加载任何模型，用于桌面/CI 冒烟测试 harness 完整链路
 * （工具调用 → 执行 → 回填 → 最终回答）。接入真实模型后仅用于测试。
 *
 * 行为：用户消息含"时间"→ 触发 get_time 工具调用；
 *       含"计算"→ 触发 calculator 工具调用；其余→ 原样回显。
 */
class MockEngine : InferenceEngine {

    override val modelInfo = ModelInfo(
        id = "mock",
        backend = "mock",
        contextTokens = 4096,
    )

    override suspend fun streamCompletion(
        prompt: String,
        options: GenOptions,
        onToken: (String) -> Unit,
    ): String {
        // 从 prompt 里取最后一个 user 回合（真实模型不这么干，这只是测试桩）
        val lastUser = Gemma3Format.lastUserTurn(prompt)
        val output = when {
            "时间" in lastUser || "几点" in lastUser || "日期" in lastUser -> """
                <tool_call>
                {"function": "get_time", "arguments": {}}
                </tool_call>
            """.trimIndent()

            "计算" in lastUser || "算" in lastUser -> """
                <tool_call>
                {"function": "calculator", "arguments": {"expression": "1+1"}}
                </tool_call>
            """.trimIndent()

            else -> "（Mock 引擎）你说了：$lastUser"
        }
        // 逐字吐出模拟流式
        output.forEach { onToken(it.toString()) }
        return output
    }

    override suspend fun warmUp() = Unit

    override fun close() = Unit
}
