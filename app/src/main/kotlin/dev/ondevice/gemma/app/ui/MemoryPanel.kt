package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.data.AgentMemoryStore
import dev.ondevice.gemma.memory.MemoryBook
import dev.ondevice.gemma.memory.MemoryEntry

@Composable
internal fun MemoryPanel(enabled: Boolean) {
    val context = LocalContext.current
    val store = remember { AgentMemoryStore.get(context) }
    var notice by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(runCatching { store.read() }.getOrElse { notice = tr("记忆读取失败，原文件仍保留"); emptyList() }) }
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("profile") }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr("长期记忆"), fontSize = 22.sp)
        Text(tr("你明确说“记住”时，助手会保存原文中的偏好或事实。记忆用于后续云端对话和手机任务；不会把思考过程或临时步骤存成事实。"), fontSize = 13.sp, lineHeight = 21.sp, color = Muted)
        Text(tr("个人偏好", "Profile") + " ${entries.filter { it.category == "profile" }.sumOf { it.text.length }}/${MemoryBook.PROFILE_LIMIT} · " + tr("常用信息", "Notes") + " ${entries.filter { it.category == "notes" }.sumOf { it.text.length }}/${MemoryBook.NOTES_LIMIT}", fontSize = 11.sp, color = Muted)
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.text, fontSize = 14.sp, lineHeight = 22.sp)
                Text(if (entry.category == "profile") tr("个人偏好") else tr("常用信息"), fontSize = 11.sp, color = Muted)
                Row {
                    TextButton(enabled = enabled, onClick = { editing = entry; draft = entry.text; category = entry.category }) { Text(tr("编辑")) }
                    TextButton(enabled = enabled, onClick = {
                        runCatching { store.update("remove", entry.category, entry.id, "", "settings") }
                            .onSuccess { entries = it; if (editing?.id == entry.id) { editing = null; draft = "" }; notice = tr("已删除，后续请求不再使用这条记忆") }
                            .onFailure { notice = it.message ?: tr("删除失败") }
                    }) { Text(tr("删除")) }
                }
                HorizontalDivider(color = Line)
            }
        }
        if (entries.isEmpty()) Text(tr("还没有长期记忆。你可以在下面添加，或在对话中说“记住我的偏好……”"), fontSize = 13.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(category == "profile", { category = "profile" }, label = { Text(tr("个人偏好")) }, enabled = enabled)
            FilterChip(category == "notes", { category = "notes" }, label = { Text(tr("常用信息")) }, enabled = enabled)
        }
        OutlinedTextField(draft, { draft = it.take(600) }, modifier = Modifier.fillMaxWidth(), label = { Text(if (editing == null) tr("添加一条记忆") else tr("修改这条记忆")) }, enabled = enabled, minLines = 2)
        if (notice.isNotBlank()) Text(notice, fontSize = 12.sp, color = Muted)
        Row {
            Button(enabled = enabled && draft.isNotBlank(), onClick = {
                runCatching { store.update(if (editing == null) "add" else "replace", category, editing?.id.orEmpty(), draft, "settings") }
                    .onSuccess { entries = it; editing = null; draft = ""; notice = tr("已保存") }
                    .onFailure { notice = it.message ?: tr("保存失败") }
            }) { Text(tr("保存记忆")) }
            if (editing != null) TextButton(onClick = { editing = null; draft = "" }) { Text(tr("取消编辑")) }
        }
        Text(tr("删除对话不会删除单独保存的长期记忆，请在这里管理。记忆只保存在此手机，并随请求发送至你配置的模型服务。"), fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
    }
}
