package dev.ondevice.gemma.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.data.ExtensionStore
import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.extensions.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

@Composable
internal fun ExtensionsPanel(enabled: Boolean) {
    val context = LocalContext.current
    val store = remember { ExtensionStore.get(context) }
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("skills") }
    var importKind by remember { mutableStateOf("skills") }
    var previewSkills by remember { mutableStateOf<List<ImportedSkill>?>(null) }
    var previewServers by remember { mutableStateOf<List<McpImport>?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    fun errorMessage(error: Exception): String {
        val status = Regex("MCP HTTP (\\d{3})").find(error.message.orEmpty())?.value
        return status ?: tr("未完成，请检查文件格式、服务地址和网络。支持 SKILL.md、技能 ZIP，以及 HTTPS MCP 配置。", "Could not complete. Check the file, endpoint and connection. Use SKILL.md, a skill ZIP, or an HTTPS MCP configuration.")
    }
    fun work(block: suspend () -> Unit) {
        if (!enabled || busy) return
        busy = true; notice = ""
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = errorMessage(error) }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val result = withContext(Dispatchers.IO) {
                val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty()
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; output.write(buffer, 0, n); require(output.size() <= SkillImporter.MAX_IMPORT_BYTES) }
                    output.toByteArray()
                } ?: error("File unavailable")
                if (importKind == "skills") SkillImporter.import(bytes, filename) to null
                else null to McpConfig.parse(String(bytes, Charsets.UTF_8))
            }
            previewSkills = result.first; previewServers = result.second
        }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SheetHeading(tr("扩展中心", "Extensions"), tr("导入做事的方法，连接更多工具。", "Add skills and connect more tools."))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(tab == "skills", { tab = "skills" }, label = { Text(tr("技能", "Skills") + " · ${state.skills.size}") })
            FilterChip(tab == "mcp", { tab = "mcp" }, label = { Text("MCP · ${state.servers.size}") })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotEmpty()) Text(notice, fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
        if (tab == "skills") {
            Text(tr("支持 SKILL.md 或包含技能目录的 ZIP。仅在相关任务中搜索和读取，不会把所有技能塞进对话。", "Import SKILL.md or a ZIP of skill folders. Skills are searched and loaded as needed, keeping conversations compact."), fontSize = 13.sp, lineHeight = 21.sp, color = Muted)
            Button({ importKind = "skills"; picker.launch(arrayOf("*/*")) }, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("导入技能文件", "Import skill file")) }
            Text(tr("可读取包内 Markdown、文本与 JSON 等参考文件；Python、Node、Shell 脚本不会在手机执行。同名导入会更新已有技能。", "Text references in the package can be read. Python, Node and shell scripts do not run on the phone. Importing the same name updates that skill."), fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
            if (state.skills.isEmpty()) Text(tr("还没有导入技能。自动学到的技能仍在“学习与经验”中管理。", "No imported skills yet. Automatically learned skills remain under Learning & experience."), fontSize = 13.sp, color = Muted)
            state.skills.forEach { skill ->
                var expanded by remember(skill.name) { mutableStateOf(false) }
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(skill.name, fontSize = 16.sp); Text(skill.description, fontSize = 12.sp, lineHeight = 19.sp, color = Muted) }
                    Switch(skill.enabled, { work { withContext(Dispatchers.IO) { store.changeSkill(skill.name) } } }, enabled = enabled && !busy)
                }
                if (skill.hasScripts) Text(tr("含脚本：当前仅使用说明和参考资料", "Contains scripts: instructions and text references only"), fontSize = 11.sp, color = Muted)
                Row {
                    TextButton({ expanded = !expanded }) { Text(if (expanded) tr("收起", "Collapse") else tr("查看说明", "View instructions")) }
                    TextButton({ work { withContext(Dispatchers.IO) { store.changeSkill(skill.name, remove = true) } } }, enabled = enabled && !busy) { Text(tr("删除", "Remove")) }
                }
                if (expanded) MarkdownMessage(skill.body.take(24_000))
            }
        } else {
            Text(tr("连接 HTTPS Streamable HTTP 服务。导入后可查看工具，模型在操作模式按需读取参数并调用；聊天模式不执行外部工具。", "Connect HTTPS Streamable HTTP services. Review the tools, then let operation mode discover and call them as needed. Chat mode does not invoke external tools."), fontSize = 13.sp, lineHeight = 21.sp, color = Muted)
            Button({ showConnect = true }, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("连接 MCP 服务", "Connect MCP service")) }
            OutlinedButton({ importKind = "mcp"; picker.launch(arrayOf("*/*")) }, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("导入 MCP JSON 配置", "Import MCP JSON")) }
            Text(tr("兼容 mcpServers 配置中的 url 和 headers；认证头加密保存在本机。暂不支持 stdio / npx、本机脚本、OAuth 登录和旧版 SSE 地址。", "Supports mcpServers URL and headers. Credentials are encrypted on this phone. stdio/npx, local scripts, OAuth login and legacy SSE endpoints are not supported yet."), fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
            state.servers.forEach { server ->
                var expanded by remember(server.id) { mutableStateOf(false) }
                var query by remember(server.id) { mutableStateOf("") }
                var visibleCount by remember(server.id, query, server.syncedAt) { mutableStateOf(40) }
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(server.name, fontSize = 16.sp); Text("${android.net.Uri.parse(server.url).host} · ${server.tools.size} " + tr("个工具", "tools"), fontSize = 12.sp, color = Muted) }
                    Switch(server.enabled, { work { withContext(Dispatchers.IO) { store.changeServer(server.id) } } }, enabled = enabled && !busy)
                }
                Row {
                    TextButton({ expanded = !expanded }) { Text(tr("查看工具", "View tools")) }
                    TextButton({ work { withContext(Dispatchers.IO) { store.refresh(server.id) }; notice = tr("工具目录已更新", "Tool catalog refreshed") } }, enabled = enabled && !busy) { Text(tr("刷新", "Refresh")) }
                    TextButton({ work { withContext(Dispatchers.IO) { store.changeServer(server.id, remove = true) } } }, enabled = enabled && !busy) { Text(tr("删除", "Remove")) }
                }
                if (expanded) {
                    OutlinedTextField(query, { query = it }, label = { Text(tr("搜索工具", "Search tools")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val matches = remember(server.tools, query) { server.tools.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) } }
                    matches.take(visibleCount).forEach { tool -> Text(tool.name, fontSize = 13.sp); Text(tool.description, fontSize = 12.sp, lineHeight = 19.sp, color = Muted) }
                    if (matches.size > visibleCount) TextButton({ visibleCount += 40 }) { Text(tr("显示更多", "Show more")) }
                }
            }
        }
    }
    if (showConnect) AlertDialog(onDismissRequest = { if (!busy) showConnect = false }, title = { Text(tr("连接 MCP", "Connect MCP")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("服务名称", "Service name")) }, singleLine = true, enabled = !busy)
            OutlinedTextField(url, { url = it.take(2048) }, label = { Text("HTTPS MCP URL") }, singleLine = true, enabled = !busy)
            OutlinedTextField(token, { token = it.take(8192) }, label = { Text(tr("Bearer Token（可选）", "Bearer token (optional)")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Text(tr("连接时只读取工具目录。启用后，操作模式可按你的任务调用服务，参数会发送到该服务。", "Connecting only reads the catalog. Once enabled, operation mode can invoke these tools for your tasks and send their arguments to this service."), fontSize = 12.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotEmpty()) Text(notice, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = enabled && !busy && name.isNotBlank() && url.isNotBlank(), onClick = {
        work { withContext(Dispatchers.IO) { store.connect(listOf(McpImport(name.trim(), url.trim(), if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")))) }
            token = ""; name = ""; url = ""; showConnect = false; notice = tr("连接成功，工具已就绪", "Connected. Tools are ready.") }
    }) { Text(tr("连接并启用", "Connect & enable")) } }, dismissButton = { TextButton({ showConnect = false }, enabled = !busy) { Text(tr("取消", "Cancel")) } })
    if (previewSkills != null || previewServers != null) AlertDialog(onDismissRequest = { if (!busy) { previewSkills = null; previewServers = null } }, title = { Text(tr("导入预览", "Import preview")) }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            previewSkills?.forEach { Text(it.name); Text(it.description, fontSize = 12.sp); if (it.hasScripts) Text(tr("包含脚本，手机不会执行", "Scripts included; they will not run on the phone"), fontSize = 12.sp) }
            previewServers?.forEach { Text(it.name); Text(it.url, fontSize = 12.sp) }
            Text(tr("同名项目将更新。技能会按需读取；MCP 会先连接并读取目录，成功后启用。", "Existing names will be updated. Skills load on demand. MCP services are enabled after their catalogs connect successfully."), fontSize = 12.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotEmpty()) Text(notice, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = enabled && !busy, onClick = { work {
        val skills = previewSkills; val servers = previewServers
        withContext(Dispatchers.IO) { if (skills != null) store.importSkills(skills); if (servers != null) store.connect(servers) }
        previewSkills = null; previewServers = null; notice = tr("导入完成", "Import complete")
    } }) { Text(tr("导入并启用", "Import & enable")) } }, dismissButton = { TextButton({ previewSkills = null; previewServers = null }, enabled = !busy) { Text(tr("取消", "Cancel")) } })
}
