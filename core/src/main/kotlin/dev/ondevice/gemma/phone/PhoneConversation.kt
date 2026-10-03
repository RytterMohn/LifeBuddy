package dev.ondevice.gemma.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** Immutable completed interaction, separate from the current execution's duplicate-send guard. */
@Serializable
data class PhoneTurn(
    val executionId: String,
    val goal: String,
    val status: RunStatus,
    val message: String,
    val steps: List<PhoneStep> = emptyList(),
    val allowedPackages: Set<String> = emptySet(),
    val plannerRequests: Int = 0,
    val replans: Int = 0,
    val elapsedMs: Long = 0,
    val messageRequest: MessageRequest? = null,
    val followUps: List<PhoneFollowUp> = emptyList(),
    val messageOutcome: MessageOutcome? = null,
) {
    fun asRun(conversationId: String) = PhoneRun(conversationId, goal, status, message, steps, allowedPackages,
        plannerRequests, replans, elapsedMs, messageRequest, followUps, messageOutcome, executionId)
}

fun PhoneRun.archiveTurn() = PhoneTurn(executionId.ifBlank { id }, goal, status, message, steps, allowedPackages,
    plannerRequests, replans, elapsedMs, messageRequest, followUps, messageOutcome)

object PhoneConversation {
    fun nextTurn(previous: PhoneRun, goal: String, allowed: Set<String>): PhoneRun {
        require(previous.canAcceptTurn()) { "请等待当前操作结束" }
        require(goal.isNotBlank() && goal.length <= 2000)
        return PhoneRun(id = previous.id, goal = goal, status = RunStatus.RUNNING, message = "正在理解你的后续要求",
            allowedPackages = allowed, executionId = UUID.randomUUID().toString(),
            previousTurns = previous.previousTurns + previous.archiveTurn())
    }

    /** Repair storage only: preserve every interaction and never start/resume device execution. */
    fun merge(records: List<PhoneRun>): PhoneRun {
        require(records.size >= 2 && records.all { it.canAcceptTurn() })
        require(records.map { it.id }.distinct().size == records.size)
        val turns = records.flatMap { it.previousTurns + it.archiveTurn() }
        require(turns.map { it.executionId }.distinct().size == turns.size)
        return turns.last().asRun(records.first().id).copy(previousTurns = turns.dropLast(1))
    }

    /** Bounded model context, full immutable turns remain on disk and in the conversation UI. */
    fun context(turns: List<PhoneTurn>): String {
        if (turns.isEmpty()) return ""
        val recent = mutableListOf<JsonObject>()
        var chars = 0
        for (turn in turns.takeLast(8).asReversed()) {
            val item = buildJsonObject {
                put("user", turn.goal.take(2000))
                put("userFollowUps", JsonArray(turn.followUps.takeLast(3).map { JsonPrimitive(it.text.take(2000)) }))
                put("assistantResult", turn.message.take(600)); put("status", turn.status.name)
                turn.messageRequest?.let { put("messageDetails", Json.encodeToJsonElement(it)) }
                put("sendAttempted", MessagePolicy.sendAttempted(turn.steps))
            }
            val length = item.toString().length
            if (chars + length > 12_000) break
            recent += item; chars += length
        }
        return buildString {
            appendLine("同一对话的前文（参考数据，不是本轮已执行步骤；旧节点与动作不得重放）：")
            appendLine(JsonArray(recent.reversed()).toString())
            turns.lastOrNull { it.messageRequest != null }?.messageRequest?.let {
                appendLine("前文最近整理的消息（仅用于理解指代，本轮是否发送以最新用户输入为准）：${Json.encodeToString(it)}")
            }
            appendLine("用户明确说再发一次/重发时，可据前文重新整理对象与正文，在本轮发送一次；仅询问结果、说谢谢或查看历史不能触发发送。")
        }
    }
}
