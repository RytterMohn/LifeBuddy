package dev.ondevice.gemma.llm

import dev.ondevice.gemma.config.AgentConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * llama.cpp Android 实现。
 *
 * 依赖：native/llama_jni.cpp 编译出的 libllama.so（内嵌 llama.cpp 静态库）。
 * 集成步骤（详见 native/llama_jni.cpp 顶部注释）：
 *   1. 拉取 llama.cpp 源码，编译 arm64-v8a 静态库
 *   2. 用 NDK 编译 native/llama_jni.cpp 并与 libllama.a 链接，产出 libllama.so
 *   3. app/build.gradle.kts 配置 ndk abiFilters 与 jniLibs 路径
 *
 * JNI 调用在 IO 调度器的串行队列上执行，
 * 原生层通过 token 回调把增量文本送回 Kotlin（见 NativeTokenCallback）。
 */
class LlamaCppEngine(
    private val modelPath: String,
    private val config: AgentConfig,
) : InferenceEngine {

    private var handle: Long = 0L
    private val serial = Mutex()
    private val lifetime = Any()
    @Volatile private var closed = false

    override val modelInfo = ModelInfo(
        id = config.modelId,
        path = modelPath,
        backend = "llama.cpp",
        contextTokens = config.contextWindow,
    )

    private fun load() {
        check(!closed) { "推理引擎已关闭" }
        if (handle != 0L) return
        synchronized(lifetime) { handle = NativeLlama.llamaInit() }
        try {
            check(NativeLlama.llamaLoadModel(handle, modelPath, config.contextWindow)) { "llama.cpp 加载模型失败" }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        serial.withLock {
            try { load() } finally { if (closed) release() }
        }
    }

    override suspend fun streamCompletion(
        prompt: String,
        options: GenOptions,
        onToken: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        serial.withLock {
            try {
                load()
                val context = currentCoroutineContext()
                context.ensureActive()
                check(!closed) { "推理引擎已关闭" }
                val collector = StringBuilder()
                NativeLlama.llamaGenerate(handle, prompt, options.temperature, options.topP, options.maxTokens) { piece ->
                    context.ensureActive()
                    collector.append(piece)
                    onToken(piece)
                }
                context.ensureActive()
                collector.toString()
            } finally {
                if (closed) release()
            }
        }
    }

    override fun cancel() = synchronized(lifetime) {
        if (handle != 0L) NativeLlama.llamaCancel(handle)
    }

    private fun release() = synchronized(lifetime) {
        if (handle != 0L) {
            NativeLlama.llamaRelease(handle)
            handle = 0L
        }
    }

    override fun close() {
        closed = true
        cancel()
        // Never release a native context still in use, and never block the UI waiting for it.
        if (serial.tryLock()) {
            try { release() } finally { serial.unlock() }
        }
    }

}

/**
 * 原生 token 回调：由 JNI 层在原生线程调用，线程安全要求由 NativeLlama 内部保证。
 */
fun interface NativeTokenCallback {
    fun onToken(piece: String)
}

/**
 * JNI 绑定面，对应 native/llama_jni.cpp 中的导出符号。
 * loadLibrary 失败时说明 libllama.so 未打包，请先完成原生编译。
 */
object NativeLlama {
    init {
        System.loadLibrary("llama")
    }

    external fun llamaInit(): Long

    external fun llamaLoadModel(handle: Long, modelPath: String, nCtx: Int): Boolean

    external fun llamaGenerate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        callback: NativeTokenCallback,
    ): String

    external fun llamaCancel(handle: Long)

    external fun llamaRelease(handle: Long)
}
