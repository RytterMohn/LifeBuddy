package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.systemText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.llm.Role
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.serialization.json.*

@Composable
fun ChatScreen(viewModel: ChatViewModel, useCloud: Boolean = true, onMode: () -> Unit = {}, onSettings: () -> Unit = {}) {
    LaunchedEffect(useCloud) { viewModel.selectCloud(useCloud) }
    val cloudReady by viewModel.cloudReady.collectAsState()
    val destination by viewModel.cloudDestination.collectAsState()
    val messages by viewModel.messagesFlow.collectAsState()
    val streaming by viewModel.streamingFlow.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val conversationId by viewModel.conversationId.collectAsState()
    val notice by viewModel.historyNotice.collectAsState()
    val phase by viewModel.harnessPhase.collectAsState()
    var input by rememberSaveable(useCloud, conversationId) { mutableStateOf("") }
    val listState = key(conversationId) { rememberLazyListState() }
    var followBottom by remember(conversationId) { mutableStateOf(true) }
    var replyHeight by remember { mutableIntStateOf(0) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging,listState.canScrollForward) {
        if(dragging) followBottom = !listState.canScrollForward
    }
    LaunchedEffect(conversationId, messages.size, streaming,replyHeight) {
        val total = messages.size + if(streaming != null) 1 else 0
        if(total > 0 && followBottom) listState.scrollToItem(total - 1, Int.MAX_VALUE)
    }
    Column(Modifier.fillMaxSize()) {
        Text(systemText(modelStatus),Modifier.padding(horizontal=24.dp,vertical=8.dp),fontSize=12.sp,color=Muted)
        if(notice.isNotBlank()) Text(systemText(notice),Modifier.padding(horizontal=24.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        if(messages.isEmpty() && !busy) {
            Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                AgentMark(52)
                Spacer(Modifier.height(20.dp))
                Text(tr("从一句话开始"),fontSize=26.sp)
                Text(if(useCloud) tr("聊一聊，也能帮你计算和查询时间") else tr("本地对话 · 不执行手机操作"),Modifier.padding(12.dp),fontSize=13.sp,color=Muted)
                if(useCloud && !cloudReady) Button(onClick=onSettings) { Text(tr("配置模型")) }
                if(useCloud && cloudReady) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(onClick={input=tr("请调用计算器，计算 128*37。")},label={Text(tr("帮我算一算"))})
                    SuggestionChip(onClick={input=tr("请调用时间工具，告诉我现在几点。")},label={Text(tr("现在几点？"))})
                }
            }
        } else LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            itemsIndexed(messages, key={ _, message -> message.id }) { index,msg ->
                if(msg.role == Role.USER) UserMessage(msg.content)
                else if(msg.isTool) ToolMessage(msg)
                else Column(if(index==messages.lastIndex) Modifier.onSizeChanged { replyHeight=it.height } else Modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    MarkdownMessage(msg.content)
                    if (useCloud && msg.detail.isNotBlank()) {
                        val metrics = remember(msg.detail) { runCatching { Json.parseToJsonElement(msg.detail).jsonObject["harness"]?.jsonObject }.getOrNull() }
                        metrics?.let {
                            var showMetrics by remember(msg.id) { mutableStateOf(false) }
                            TextButton(onClick = { showMetrics = !showMetrics }, contentPadding = PaddingValues(0.dp)) { Text(tr("本次执行信息"), fontSize = 11.sp, color = Muted) }
                            if (showMetrics) Text(tr("${it["requests"] ?: 0} 次模型请求 · ${it["tools"] ?: 0} 次工具调用 · ${it["compactions"] ?: 0} 次历史整理\n耗时 ${it["elapsedMs"]?.jsonPrimitive?.long?.div(1000.0)} 秒 · 最大输入约 ${it["peakEstimatedInputTokens"]} Token（估算）", "${it["requests"] ?: 0} requests · ${it["tools"] ?: 0} tool calls · ${it["compactions"] ?: 0} summaries\n${it["elapsedMs"]?.jsonPrimitive?.long?.div(1000.0)} s · Peak input ~${it["peakEstimatedInputTokens"]} tokens"), fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
                        }
                    }
                    if(msg.state in setOf("stopped","interrupted")) Text(if(msg.state=="stopped") tr("已停止生成") else tr("生成中断 · 已保留内容"),fontSize=11.sp,color=Muted)
                }
            }
            streaming?.let { item {
                Column(Modifier.onSizeChanged { replyHeight=it.height },verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(it.isEmpty()) Text(systemText(phase),fontSize=16.sp,color=Muted) else MarkdownMessage(it)
                    if(it.isNotEmpty()) Text(tr("正在生成…"),fontSize=11.sp,color=Muted)
                }
            } }
        }
        Composer(input,{input=it.take(4000)},tr("发送消息…"),tr("聊天"),onMode,{
            followBottom=true; viewModel.send(input); input=""
        },input.isNotBlank() && (!useCloud || cloudReady),busy,viewModel::stop)
        Text(if(useCloud && cloudReady) tr("消息与工具结果将发送至 $destination", "Messages and tool results go to $destination") else tr("当前模型状态见顶部提示"),Modifier.align(Alignment.CenterHorizontally).padding(bottom=8.dp),fontSize=10.sp,color=Muted)
    }
}
