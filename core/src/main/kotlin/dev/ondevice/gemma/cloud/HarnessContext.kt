package dev.ondevice.gemma.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A conservative, CJK-aware estimate; not a provider billing/tokenizer count. */
object ContextBudget {
    fun estimate(text: String): Int = (text.sumOf { if (it.code < 128) 1L else 3L }.div(3) + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

data class HarnessLimits(
    val inputTokens: Int = 12_000,
    val keepRecentTurns: Int = 2,
    val maxTurns: Int = 128,
    val maxModelCalls: Int = 8,
    val maxToolCalls: Int = 12,
) {
    init { require(inputTokens >= 3000 && keepRecentTurns >= 1 && maxTurns > keepRecentTurns && maxModelCalls in 1..16 && maxToolCalls in 1..24) }
}

@Serializable
data class HarnessStats(
    val requests: Int = 0,
    val tools: Int = 0,
    val compactions: Int = 0,
    val peakEstimatedInputTokens: Int = 0,
    val firstTextMs: Long? = null,
    val elapsedMs: Long = 0,
    val outcome: String = "running",
)

/** Persistence and UI belong to Android; the harness never opens files or knows credentials. */
interface HarnessMemory {
    fun snapshot(): String
    fun update(arguments: JsonObject, userMessage: String): String
    fun search(query: String): String
}
