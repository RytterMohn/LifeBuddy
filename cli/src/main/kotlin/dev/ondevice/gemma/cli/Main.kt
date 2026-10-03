package dev.ondevice.gemma.cli

import dev.ondevice.gemma.agent.Agent
import dev.ondevice.gemma.agent.AgentLoop
import dev.ondevice.gemma.config.AgentConfig
import dev.ondevice.gemma.llm.LlamaCppEngine
import dev.ondevice.gemma.memory.JsonlChatStore
import dev.ondevice.gemma.tools.ToolRegistry
import dev.ondevice.gemma.tools.builtin.BuiltinTools
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 桌面调试 REPL：
 *   ./gradlew :cli:run --args="--mock"                    # Mock 引擎冒烟
 *   ./gradlew :cli:run --args="--llama models/x.gguf"     # llama.cpp（需本地 libllama）
 */
fun main(args: Array<String>) = runBlocking {
    val modelPath = parseModelPath(args)
    val agent: Agent
    val engineHolder: LlamaCppEngine?

    if (modelPath == null) {
        agent = AgentLoop.mock()
        engineHolder = null
        println("⚙ 引擎: Mock（冒烟模式）。传 --llama <gguf> 加载真实模型")
    } else {
        val config = AgentConfig()
        val engine = LlamaCppEngine(modelPath, config)
        engine.warmUp()
        println("⚙ 引擎: llama.cpp · 模型: ${engine.modelInfo.id} · ctx=${config.contextWindow}")
        agent = AgentLoop.assemble(engine, config)
        engineHolder = engine
    }

    println("输入 /clear 清空对话，/quit 退出。")
    while (true) {
        print("\n> ")
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isEmpty() -> continue
            line == "/quit" -> break
            line == "/clear" -> { agent.clearMemory(); println("（已清空）"); continue }
        }
        val result = agent.chat(
            userInput = line,
            onToken = { piece -> print(piece) },
            onPrompt = { p ->
                if (System.getenv("GEMMA_DEBUG") != null) {
                    println("\n=== prompt ===")
                    println(p.takeLast(1500))
                    println("=== end prompt ===")
                }
            },
        )
        println("\n\n— 工具调用 ${result.toolCalls.size} 次，迭代 ${result.iterations + 1} 轮 —")
        if (System.getenv("GEMMA_DEBUG") != null) {
            println("[debug] raw answer: " + result.answer.replace("\n", "\\n"))
        }
    }
    engineHolder?.close()
    println("\nbye")
}

private fun parseModelPath(args: Array<String>): String? {
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--llama" -> {
                val p = args.getOrNull(i + 1) ?: error("--llama 需要模型路径")
                require(File(p).exists()) { "模型文件不存在: $p" }
                return p
            }
            "--mock" -> return null
        }
        i++
    }
    return null
}
