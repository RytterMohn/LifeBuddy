package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.AppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDrawer(store: HistoryStore, selectedId: String, busy: Boolean,
    onOpen: (ConversationSummary) -> Unit, onNew: () -> Unit, onClose: () -> Unit,
    onSettings: () -> Unit, onDelete: (String) -> Unit) {
    val all by store.conversations.collectAsState()
    var search by remember { mutableStateOf("") }
    val results by produceState(all, search, all) {
        if (search.isBlank()) value = all else {
            delay(150)
            value = withContext(Dispatchers.IO) { store.search(search) }
        }
    }
    var rename by remember { mutableStateOf<ConversationSummary?>(null) }
    var delete by remember { mutableStateOf<ConversationSummary?>(null) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val groups = remember(results, search, AppLanguage.key) {
        results.groupBy { if (search.isNotBlank()) tr("搜索结果") else historyDateGroup(it.updatedAt) }
    }
    ModalDrawerSheet(drawerContainerColor = Color(0xFFFAFAFA), modifier = Modifier.width(320.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentMark(30)
            Text("LifeBuddy", Modifier.weight(1f).padding(start = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            GlyphButton("close", tr("关闭侧边栏"), onClose)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0F0F0)).clickable(enabled = !busy, onClick = onNew)
            .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("new", Modifier.size(19.dp))
            Text(tr("新对话"), Modifier.padding(start = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color.White).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("search", Modifier.size(18.dp), Muted)
            BasicTextField(search, { search = it }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Ink),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp),
                decorationBox = { field -> Box { if (search.isEmpty()) Text(tr("搜索对话和任务"), fontSize = 14.sp, color = Muted); field() } })
            if (search.isNotEmpty()) GlyphButton("close", tr("清除搜索"), { search = "" })
        }
        if (busy) Text(tr("正在执行，停止后可切换会话"), Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 11.sp, color = Muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)) {
            if (results.isEmpty()) item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (search.isBlank()) tr("你的对话会保存在这里") else tr("没有找到相关记录"), fontSize = 14.sp)
                    Text(if (search.isBlank()) tr("聊天、工具结果和手机任务都会自动保存。") else tr("试试标题、消息内容或任务关键词。"), fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
                }
            }
            groups.forEach { (group, entries) ->
                item(key = "group:$group") { Text(group, Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp), fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Medium) }
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(entry, entry.id == selectedId, !busy, onOpen = { onOpen(entry) },
                        onRename = { title = entry.title; rename = entry; error = "" }, onDelete = { delete = entry; error = "" })
                }
            }
        }
        if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 24.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 24.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(horizontal = 26.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("settings", Modifier.size(20.dp))
            Text(tr("设置"), Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp)
            Glyph("right", Modifier.size(14.dp), Muted)
        }
        Text(tr("${all.size} 条记录 · 仅保存在这台手机", "${all.size} records · Stored on this phone"), Modifier.padding(start = 26.dp, bottom = 18.dp), fontSize = 10.sp, color = Muted)
    }
    rename?.let { entry ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(tr("重命名对话")) }, text = {
            Column {
                OutlinedTextField(title, { title = it.take(80) }, singleLine = true, label = { Text(tr("对话名称")) }, shape = RoundedCornerShape(12.dp))
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
            runCatching { store.rename(entry.id, title) }.onSuccess { rename = null; error = "" }.onFailure { error = tr("保存失败，请重试") }
        }) { Text(tr("保存")) } }, dismissButton = { TextButton(onClick = { rename = null }) { Text(tr("取消")) } })
    }
    delete?.let { entry ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text(tr("删除这条记录？")) },
            text = { Text(tr("将删除“${entry.title}”及其中的消息、工具结果和任务步骤。此操作无法撤销。", "Delete ${entry.title} and its messages, tool results and task steps? This cannot be undone.")) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                runCatching { onDelete(entry.id) }.onSuccess { delete = null; error = "" }.onFailure { error = tr("删除失败，请重试"); delete = null }
            }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(tr("取消")) } })
    }
}

@Composable
private fun HistoryRow(entry: ConversationSummary, selected: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) Color(0xFFECECEC) else Color.Transparent).clickable(enabled = enabled, onClick = onOpen)
        .padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(if (ConversationKind.isChat(entry.kind)) "chat" else "phone", Modifier.size(17.dp), Muted)
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            Text(historySubtitle(entry), fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            GlyphButton("more", tr("管理对话：${entry.title}", "Manage conversation: ${entry.title}"), { menu = true }, enabled)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(tr("重命名")) }, leadingIcon = { Glyph("new", Modifier.size(18.dp)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(tr("删除"), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Glyph("trash", Modifier.size(18.dp), MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

internal fun historyDateGroup(timestamp: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return when {
        !date.isBefore(today) -> tr("今天")
        date == today.minusDays(1) -> tr("昨天")
        !date.isBefore(today.minusDays(7)) -> tr("过去 7 天")
        !date.isBefore(today.minusDays(30)) -> tr("过去 30 天")
        else -> tr("${date.year} 年 ${date.monthValue} 月", "${date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${date.year}")
    }
}

private fun historySubtitle(entry: ConversationSummary): String = when (entry.kind) {
    ConversationKind.CLOUD -> tr("聊天")
    ConversationKind.LOCAL -> tr("聊天 · 本地历史")
    else -> (if (entry.kind == ConversationKind.PRACTICE) tr("操作 · 练习历史") else tr("操作")) + " · " + when (entry.status) {
        "COMPLETED" -> tr("已完成"); "RUNNING" -> tr("执行中"); "WAITING_APPROVAL" -> tr("等待确认")
        "PAUSED" -> tr("已暂停"); "CANCELLED" -> tr("已停止"); "FAILED" -> tr("未完成"); else -> tr("准备开始")
    }
}
