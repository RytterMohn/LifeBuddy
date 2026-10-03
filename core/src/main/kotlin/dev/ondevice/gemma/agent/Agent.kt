package dev.ondevice.gemma.agent

import dev.ondevice.gemma.config.AgentConfig
import dev.ondevice.gemma.llm.ChatMessage
import dev.ondevice.gemma.llm.GenOptions
import dev.ondevice.gemma.llm.InferenceEngine
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.memory.ChatMemory
import dev.ondevice.gemma.prompts.Gemma3Format
import dev.ondevice.gemma.skills.SkillLibrary
import dev.ondevice.gemma.tools.ToolCall
import dev.ondevice.gemma.tools.ToolRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 一轮 chat 的结果 */
data class AgentResult(
    val answer: String,
    val toolCalls: List<ToolCall>,
    val iterations: Int,
)

/**
 * 轻量 Agent 主循环（对齐 pi 的 agent loop）：
 *
 *   user 输入 → 拼 prompt（system + skills 摘要 + 历史窗口 + 工具 Schema）
 *   → 流式生成 → 解析 <tool_call>
 *   → 有调用且未超迭代上限：执行工具，结果以 <tool_response> 回填，循环
 *   → 无调用 / 超限：输出最终回答并写入记忆
 */
class Agent(
    private val engine: InferenceEngine,
    private val tools: ToolRegistry,
    private val memory: ChatMemory,
    private val config: AgentConfig,
    private val skills: SkillLibrary? = null,
) {
    /** 清空当前会话记忆（UI/CLI 的 /clear） */
    fun clearMemory() = memory.clear()

    fun snapshotMemory(): List<ChatMessage> = memory.window()

    fun restoreMemory(messages: List<ChatMessage>) {
        memory.clear()
        messages.forEach(memory::add)
    }

    suspend fun chat(
        userInput: String,
        onToken: (String) -> Unit = {},
        onPrompt: (String) -> Unit = {},
        onTool: (ToolCall, String) -> Unit = { _, _ -> },
    ): AgentResult {
        memory.addUser(userInput)

        var answer = ""
        var iteration = 0
        val allCalls = mutableListOf<ToolCall>()

        while (true) {
            currentCoroutineContext().ensureActive()
            val system = buildSystemPrompt()
            val prompt = Gemma3Format.buildPrompt(
                system = system,
                history = memory.window(),
                tools = tools.schemas(),
            )
            onPrompt(prompt)

            // 流式生成；最终答案 = 最后一次生成的完整文本
            answer = engine.streamCompletion(
                prompt = prompt,
                options = GenOptions(
                    temperature = config.temperature,
                    topP = config.topP,
                    maxTokens = config.maxGenerateTokens,
                ),
                onToken = onToken,
            )

            val calls = tools.parseToolCalls(answer)
            if (calls.isEmpty() || iteration >= config.maxToolIterations) {
                if (iteration >= config.maxToolIterations && calls.isNotEmpty()) {
                    // 超限：把模型最后一次原始输出记入记忆，避免死循环
                    answer = "已达到工具调用上限，任务尚未完成。"
                    memory.addModel(answer)
                } else {
                    memory.addModel(answer)
                }
                return AgentResult(answer, allCalls, iteration)
            }

            allCalls += calls
            // 模型请求工具的回合也入记忆（原始文本含 <tool_call>）
            memory.add(ChatMessage(Role.MODEL, answer))
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                val result = tools.execute(call.name, call.arguments)
                memory.add(ChatMessage(Role.TOOL_RESULT, result, toolCallId = call.name))
                onTool(call, result)
            }
            iteration++
        }
    }

    private fun buildSystemPrompt(): String {
        val base = config.systemPrompt ?: Gemma3Format.defaultSystemPrompt
        val skillSummary = skills?.summaries()?.takeIf { it.isNotEmpty() }
            ?: return base
        return "$base\n\n# 可用技能（需要时先说明再用）\n$skillSummary"
    }
}
