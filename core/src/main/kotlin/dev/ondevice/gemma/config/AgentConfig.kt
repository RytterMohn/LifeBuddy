package dev.ondevice.gemma.config

/**
 * Agent 与推理的全局配置。
 * 所有字段有默认值，装配时按需覆盖。
 */
data class AgentConfig(
    /** 模型名，用于日志与 UI 展示 */
    val modelId: String = "gemma-3-4b-it-Q4_K_M",

    /** 上下文窗口（token 数），手机端建议 2048~8192 */
    val contextWindow: Int = 4096,

    /** 单轮对话中工具调用的最大迭代次数 */
    val maxToolIterations: Int = 6,

    /** 采样参数：手机端轻量模型建议低温（0.3~0.5），工具调用更稳定 */
    val temperature: Float = 0.5f,
    val topP: Float = 0.95f,
    val maxGenerateTokens: Int = 1024,

    /** 系统提示词；null 时使用 Prompts.defaultSystem */
    val systemPrompt: String? = null,
)
