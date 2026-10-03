package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.systemText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.ondevice.gemma.app.data.AutoSkillStore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.serialization.json.*

@Composable
internal fun TaskConversation(run: PhoneRun, modifier: Modifier) {
    val listState = rememberLazyListState()
    val turns = run.previousTurns.map { it.asRun(run.id) } + run
    LaunchedEffect(run.executionId, run.goal, run.followUps.size) {
        // Position the newest exchange while keeping earlier interactions available above it.
        val previousItems = run.previousTurns.sumOf { 2 + it.followUps.size }
        listState.scrollToItem(previousItems + run.followUps.size)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        turns.forEach { turn -> taskTurn(turn) }
    }
}

private fun LazyListScope.taskTurn(run: PhoneRun) {
    val key = run.executionId.ifBlank { run.id }
    item(key = "$key-user") { UserMessage(run.goal) }
    run.followUps.forEachIndexed { index, reply ->
        item(key = "$key-reply-$index") {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                run.steps.getOrNull(reply.afterStep - 1)?.action?.reason?.let { MarkdownMessage(it) }
                UserMessage(reply.text)
            }
        }
    }
    item(key = "$key-result") { TaskResult(run) }
}

@Composable
private fun TaskResult(run: PhoneRun) {
    val context=LocalContext.current
    val skillStore=remember { AutoSkillStore.get(context) }
    val skillState by skillStore.state.collectAsState()
    val execution=run.executionId.ifBlank { run.id }
    val learned=skillState.skills.filter { skill -> skill.revisions.any { execution in it.executions } }
    var expanded by remember(run.executionId, run.goal) { mutableStateOf(false) }
    val active = run.status in setOf(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL)
    val completed = run.status == RunStatus.COMPLETED
    val finish = run.steps.lastOrNull()?.action?.takeIf { it.type == PhoneActionType.FINISH }
    val uncertainSend = !active && run.hasUnconfirmedSend()
    val uncertainExtension = !active && run.hasUnconfirmedExtension()
    val status = if (uncertainExtension) tr("外部调用结果待确认", "External outcome unknown") else if (uncertainSend) tr("发送结果待确认") else when (run.status) {
        RunStatus.COMPLETED -> tr("已完成"); RunStatus.RUNNING -> tr("正在执行"); RunStatus.WAITING_APPROVAL -> tr("等待你的确认")
        RunStatus.PAUSED -> tr("已暂停"); RunStatus.CANCELLED -> tr("已停止"); RunStatus.FAILED -> tr("未完成"); else -> tr("准备开始")
    }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentMark(30)
                    Text(tr("任务记录"), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Row(Modifier.clip(CircleShape).background(if (completed) Green.copy(alpha = .08f) else Soft).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (completed) Glyph("check", Modifier.size(13.dp), Green)
                        Text(status, fontSize = 11.sp, color = if (completed) Green else Muted)
                    }
                }
                // The full model explanation remains in the expandable final step.
                MarkdownMessage(if (uncertainExtension) tr("外部工具已开始执行，但结果尚未返回。请先到对应服务核对；停止或连接中断不代表执行失败，助手不会自动重试。", "The external tool started, but its result is unknown. Check the service first. Stopping or losing the connection does not mean it failed; the assistant will not retry automatically.")
                    else if (uncertainSend) tr("已尝试发送，结果待确认。请先到聊天页核对；后续读取或滚动失败不代表消息未发送，Agent 不会自动重发。")
                    else if (completed && finish != null) run.message.substringBefore("\n页面证据：") else if (active || !completed) systemText(run.message) else run.message)
                if (uncertainSend) Text(tr("可以在这段对话里继续说明；明确要求再发一次时，会发起新一轮发送。"), fontSize = 12.sp, color = Muted)
                if(completed && learned.isNotEmpty()) Text(tr("已积累技能：${learned.joinToString { it.title }}。可在设置 → 学习与经验中查看。", "Learned: ${learned.joinToString { it.title }}. View under Settings → Learning & experience."),fontSize=12.sp,lineHeight=19.sp,color=Muted)
                else if(completed && skillState.jobs.any { it.executionId==execution }) Text(tr("正在整理本次任务的技能…"),fontSize=12.sp,color=Muted)
                if (completed && finish != null && finish.evidence.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Soft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(tr("页面确认结果"), fontSize = 11.sp, color = Muted)
                        SelectionContainer { Text(finish.evidence, fontSize = 14.sp, lineHeight = 22.sp) }
                    }
                }
                Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("phone", Modifier.size(18.dp), Muted)
                        Text(tr("执行过程"), Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(tr("${run.steps.size} 步", "${run.steps.size} steps"), fontSize = 12.sp, color = Muted, modifier = Modifier.padding(end = 8.dp))
                        Glyph(if (expanded) "chevron" else "right", Modifier.size(14.dp), Muted)
                    }
                    if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (run.plannerRequests > 0) Text(tr("${run.plannerRequests} 次规划 · ${run.replans} 次恢复规划 · ${run.elapsedMs / 1000} 秒", "${run.plannerRequests} plans · ${run.replans} replans · ${run.elapsedMs / 1000} s"), fontSize = 11.sp, color = Muted)
                        if (run.steps.isEmpty()) Text(tr("还没有执行步骤"), fontSize = 12.sp, color = Muted)
                        if (uncertainSend) Text(tr("任务停止时的记录：${run.message}", "Record when stopped: ${run.message}"), fontSize = 11.sp, color = Muted)
                        run.steps.forEach { step -> StepRow(step, active, completed) }
                    }
                }
                if (active) TextButton(onClick = { PhoneController.pause() }) { Glyph("pause", Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(tr("暂停任务")) }
                else Text(tr("已保存在历史记录中"), fontSize = 11.sp, color = Muted)
            }
}

@Composable
private fun StepRow(step: PhoneStep, active: Boolean, completed: Boolean) {
    val isFinish = step.action.type == PhoneActionType.FINISH
    val label = systemText(PhoneTools.title(step.action.type))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).background(Soft, CircleShape), contentAlignment = Alignment.Center) { Text(step.number.toString(), fontSize = 11.sp, color = Muted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(PhoneTools.name(step.action.type), fontSize = 10.sp, color = Muted)
            Text(step.action.reason, fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
            step.timing?.let { timing ->
                fun seconds(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)
                Text(tr("模型 ${seconds(timing.planningMs)} 秒 · 读取 ${seconds(timing.observeMs)} 秒 · 执行 ${seconds(timing.executeMs)} 秒 · 页面等待 ${seconds(timing.settleMs)} 秒", "Model ${seconds(timing.planningMs)} s · Read ${seconds(timing.observeMs)} s · Execute ${seconds(timing.executeMs)} s · Wait ${seconds(timing.settleMs)} s"),
                    fontSize=10.sp, lineHeight=16.sp, color=Muted)
            }
            if (step.action.text.isNotEmpty()) SelectionContainer {
                Text(step.action.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Soft).padding(10.dp), fontSize = 13.sp, lineHeight = 20.sp)
            }
            Text(when {
                step.action.type == PhoneActionType.RESPOND && completed -> tr("已回答，无需手机操作")
                isFinish && completed -> tr("已核对页面证据")
                step.action.type == PhoneActionType.SEND_MESSAGE && step.dispatchAttempted && !step.dispatched -> tr("已尝试调用发送；结果待确认，不会自动重试")
                step.action.type == PhoneActionType.CALL_EXTENSION && step.dispatchAttempted && !step.dispatched -> tr("外部调用已开始，结果待确认", "External call started; outcome unknown")
                !step.dispatched -> if (active && step.observation == "等待执行") tr("等待执行") else tr("未执行 · ${step.observation}", "Not executed · ${systemText(step.observation)}")
                else -> if (step.action.type in PhoneTools.extensionActions) step.observation else systemText(step.observation)
            }, fontSize = 11.sp, lineHeight = 18.sp, color = if (isFinish && completed) Green else Muted)
        }
    }
}

@Composable
internal fun ToolMessage(message: UiMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val detail = remember(message.detail) { runCatching { Json.parseToJsonElement(message.detail).jsonObject }.getOrNull() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Soft)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("check", Modifier.size(16.dp), Green)
            Text(systemText(message.content), Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(tr("已调用"), fontSize = 10.sp, color = Muted, modifier = Modifier.padding(end = 8.dp))
            Glyph(if (expanded) "chevron" else "right", Modifier.size(13.dp), Muted)
        }
        if (expanded) SelectionContainer {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail?.get("arguments")?.jsonObject?.takeIf { it.isNotEmpty() }?.let { Text(tr("参数：$it", "Arguments: $it"), fontSize = 12.sp, lineHeight = 19.sp, color = Muted) }
                Text(detail?.get("result")?.jsonPrimitive?.contentOrNull ?: message.detail, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
    }
}
