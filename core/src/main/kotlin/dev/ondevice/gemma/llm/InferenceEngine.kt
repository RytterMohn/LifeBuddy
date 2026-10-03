package dev.ondevice.gemma.llm

/** 对话角色 */
enum class Role { SYSTEM, USER, MODEL, TOOL_RESULT }

data class ChatMessage(
    val role: Role,
    val content: String,
    /** 工具调用 ID / 名称，用于 TOOL_RESULT 对应 */
    val toolCallId: String? = null,
)

/** 生成参数 */
data class GenOptions(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 1024,
)

/**
 * 推理引擎抽象。手机端实现：LlamaCppEngine（llama.cpp JNI）、
 * MediaPipeEngine（MediaPipe LLM Inference）、MLCEngine（MLC-LLM）等。
 *
 * 所有实现都必须支持流式输出；token 回调可能来自任意线程，实现方负责切线程。
 */
interface InferenceEngine {
    /** 模型元信息（用于 UI/日志） */
    val modelInfo: ModelInfo

    /**
     * 流式生成。prompt 由调用方按 Gemma3Format 拼好（含系统提示、历史、工具 Schema）。
     * @return 完整生成文本（与 onToken 累加结果一致）
     */
    suspend fun streamCompletion(
        prompt: String,
        options: GenOptions,
        onToken: (String) -> Unit,
    ): String

    /** 预热（加载模型、分配 KV cache） */
    suspend fun warmUp()

    /** Signal an in-flight generation to stop. */
    fun cancel() = Unit

    /** 释放模型资源 */
    fun close()
}

data class ModelInfo(
    val id: String,
    val path: String? = null,
    val backend: String,
    val contextTokens: Int = 0,
)
