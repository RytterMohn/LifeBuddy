package dev.ondevice.gemma.agent

import dev.ondevice.gemma.config.AgentConfig
import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.InferenceEngine
import dev.ondevice.gemma.llm.MockEngine
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.memory.ChatMemory
import dev.ondevice.gemma.tools.builtin.BuiltinTools
import dev.ondevice.gemma.tools.ToolRegistry

/**
 * 一键装配：引擎 + 内置工具 + 记忆 + 配置 → Agent。
 * 供 CLI / App 复用，保证两端的 harness 行为一致。
 */
object AgentLoop {

    /** 标准装配（Mock 引擎，冒烟测试用） */
    fun mock(config: AgentConfig = AgentConfig()): Agent = assemble(MockEngine(), config)

    /**
     * 标准装配（真实引擎）。
     * @param engine 已指向模型文件（调用方负责 warmUp）
     */
    fun assemble(
        engine: InferenceEngine,
        config: AgentConfig,
        extraTools: ToolRegistry = ToolRegistry(),
    ): Agent {
        val registry = ToolRegistry()
            .registerAll(*BuiltinTools.all().toTypedArray())
        // 额外工具合并（同名覆盖）
        extraTools.all().forEach { registry.register(it) }

        val memory = ChatMemory(maxTokens = config.contextWindow)
        return Agent(engine, registry, memory, config)
    }
}
