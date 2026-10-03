package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.systemText
import dev.ondevice.gemma.app.i18n.AppLanguage
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.data.AgentSettings
import dev.ondevice.gemma.app.data.AppCatalog
import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.data.ConversationKind
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHome(chatViewModel: ChatViewModel, returnTaskId: String = "", onTaskShown: () -> Unit = {}) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings = remember { AgentSettings(context) }
    val connected by PhoneController.connected.collectAsState()
    val run by PhoneController.run.collectAsState()
    val localBusy by chatViewModel.busy.collectAsState()
    val running = run.status in setOf(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL)
    val history = chatViewModel.history
    val conversations by history.conversations.collectAsState()
    val chatId by chatViewModel.conversationId.collectAsState()
    val initial = remember { history.conversations.value.find { it.id == history.selectedId } }
    var mode by rememberSaveable { mutableStateOf(if (initial != null) { if (ConversationKind.isChat(initial.kind)) "chat" else "operate" } else settings.interactionMode()) }
    var chatUseCloud by rememberSaveable { mutableStateOf(initial?.kind != ConversationKind.LOCAL) }
    var viewedRunId by rememberSaveable { mutableStateOf(initial?.takeUnless { ConversationKind.isChat(it.kind) }?.id.orEmpty()) }
    var goal by rememberSaveable { mutableStateOf("") }
    var showRun by rememberSaveable { mutableStateOf(viewedRunId.isNotBlank()) }
    var error by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(settings.allowedPackages()) }
    var restrictApps by remember { mutableStateOf(settings.restrictApps()) }
    var config by remember { mutableStateOf(settings.read()) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(AppCatalog.list(context)) }
    val selectedHistoryId = if (mode == "chat") chatId else if (showRun) viewedRunId else ""
    val selectedTitle = conversations.find { it.id == selectedHistoryId }?.title
    val viewedRun = if (viewedRunId == run.id || viewedRunId.isBlank()) run else remember(viewedRunId, conversations) { history.load(viewedRunId)?.run ?: PhoneRun() }
    val awaitingReply = showRun && viewedRun.canContinueWithReply()
    LaunchedEffect(returnTaskId) {
        if (returnTaskId.isNotBlank()) {
            history.load(returnTaskId)?.run?.let {
                showRun = true; viewedRunId = it.id; history.selectedId = it.id; mode = "operate"; sheet = null
            }
            onTaskShown()
        }
    }
    LaunchedEffect(run.id, running) {
        if (running) { showRun = true; viewedRunId = run.id; history.selectedId = run.id; mode = "operate" }
    }
    fun startOperation() {
        error = ""
        if (!connected) { disclosure = true; return }
        if (runCatching { CloudPhonePlanner.validate(config) }.isFailure) { error = tr("先在设置中配置模型"); sheet = "settings"; return }
        keyboard?.hide()
        runCatching {
            apps=AppCatalog.list(context)
            PhoneController.start(context, goal.trim(), apps.filter { !restrictApps || it.first in selected }.toMap(), true,
                conversation = viewedRun.takeIf { showRun && it.canAcceptTurn() })
        }.onSuccess { showRun = true; viewedRunId = PhoneController.run.value.id; goal = ""; sheet = null }
            .onFailure { error = it.message ?: tr("无法开始任务") }
    }
    fun newChat() {
        if (running || localBusy) return
        showRun = false; goal = ""; error = ""; viewedRunId = ""
        chatUseCloud = true; chatViewModel.selectCloud(true); chatViewModel.clear()
        history.selectedId = ""
        scope.launch { drawer.close() }
    }
    fun selectMode(next: String) {
        if (running || localBusy) return
        mode = next; settings.saveInteractionMode(next); sheet = null; error = ""
        if (next == "chat") { chatUseCloud = true; chatViewModel.selectCloud(true) }
    }
    ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=!running,drawerContent={
        HistoryDrawer(history, selectedHistoryId, running || localBusy,
            onOpen={ entry ->
                if (!running && !localBusy) {
                    mode = if (ConversationKind.isChat(entry.kind)) "chat" else "operate"
                    error = ""; goal = ""
                    if (ConversationKind.isChat(entry.kind)) { chatUseCloud = entry.kind != ConversationKind.LOCAL; chatViewModel.openConversation(entry.id); showRun = false }
                    else { viewedRunId = entry.id; showRun = true; history.selectedId = entry.id }
                    keyboard?.hide(); scope.launch { drawer.close() }
                }
            }, onNew={newChat()}, onClose={scope.launch { drawer.close() }},
            onSettings={scope.launch { drawer.close() }; sheet="settings"},
            onDelete={ id ->
                history.delete(id); PhoneController.forgetDeletedRun(id)
                if (chatId == id) chatViewModel.clear()
                if (viewedRunId == id) { viewedRunId=""; showRun=false; history.selectedId="" }
                if (selectedHistoryId != id) history.selectedId=selectedHistoryId
            })
    }) {
        Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                GlyphButton("menu",tr("打开侧边栏"),{scope.launch { drawer.open() }})
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled=!running && !localBusy) { sheet="mode" }.padding(8.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                    Text(selectedTitle ?: "LifeBuddy",modifier=Modifier.weight(1f,fill=false),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.width(5.dp)); Glyph("chevron",Modifier.size(16.dp),Muted)
                }
                GlyphButton("new",tr("新对话"),{newChat()},!running && !localBusy)
            }
            if (mode == "chat") Box(Modifier.weight(1f)) { ChatScreen(chatViewModel,chatUseCloud,{sheet="mode"},{sheet="settings"}) }
            else {
                Row(Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).clickable { if(!connected) disclosure=true else sheet="settings" }
                    .padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).background(if(connected) Green else Color(0xFFC58B36),CircleShape))
                    Text(if(connected) tr("操作模式 · 自主选择应用与工具") else tr("连接手机，开始操作"),fontSize=11.sp,color=Muted)
                }
                if(showRun && viewedRun.id.isNotBlank()) key(viewedRun.id) { TaskConversation(viewedRun,Modifier.weight(1f)) }
                else OperationWelcome(Modifier.weight(1f)) { goal = it; error = "" }
                if(error.isNotBlank()) Text(systemText(error),Modifier.padding(horizontal=24.dp,vertical=6.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Composer(goal,{goal=it.take(2000)},if (awaitingReply) tr("回复助手的问题，继续任务…") else if (showRun) tr("继续这段对话…") else tr("一句话，告诉我你想做什么…"),
                    tr("操作"),{sheet="mode"},{startOperation()},enabled=!running && goal.isNotBlank(),busy=running,onStop=PhoneController::stop)
                Text(tr("发送后由助手操作${if (restrictApps) "所选" else "可用"}应用；可见文字会发送给模型。消息按指令直接发送，可随时暂停。", "The assistant operates ${if (restrictApps) "selected" else "available"} apps. Visible text goes to your model. Requested messages send directly; pause anytime."),fontSize=10.sp,lineHeight=15.sp,color=Muted,
                    modifier=Modifier.padding(horizontal=24.dp).align(Alignment.CenterHorizontally).padding(bottom=9.dp))
            }
        }
    }
    if(sheet != null) ModalBottomSheet(onDismissRequest={sheet=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.White,dragHandle={BottomSheetDefaults.DragHandle(color=Line)}) {
        when(sheet) {
            "mode" -> Column(Modifier.padding(horizontal=20.dp).padding(bottom=24.dp)) {
                SheetHeading(tr("选择模式"),tr("同一个助手，两种使用方式。"))
                OptionRow("chat",tr("聊天"),tr("讨论、写作和问答，不控制手机。"),mode=="chat") { selectMode("chat") }
                OptionRow("phone",tr("操作"),tr("说出目标，让助手自主选择应用和工具。"),mode=="operate") { selectMode("operate") }
            }
            "settings" -> SettingsPanel(config,!running && !localBusy,connected,selected.size,restrictApps,
                onSave={new -> settings.save(new);config=new;chatViewModel.refreshCloudConfig()},onApps={apps=AppCatalog.list(context);sheet="apps"},onConnect={sheet=null;disclosure=true},
                onHistory={sheet=null;scope.launch { drawer.open() }},onMemory={sheet="memory"},onTools={sheet="tools"},
                onRestrict={restrictApps=it;settings.saveRestrictApps(it)},onLearning={sheet="learning"},onExtensions={sheet="extensions"})
            "extensions" -> ExtensionsPanel(!running && !localBusy)
            "memory" -> MemoryPanel(!running && !localBusy)
            "learning" -> LearningPanel(!running && !localBusy) { id ->
                val source = history.load(id)
                if (source?.run != null) { mode="operate";viewedRunId=id;showRun=true;history.selectedId=id;sheet=null;error="" }
                else android.widget.Toast.makeText(context,tr("来源任务已删除，学习记录仍可单独管理"),android.widget.Toast.LENGTH_SHORT).show()
            }
            "tools" -> ToolsPanel()
            "apps" -> AppPicker(apps,selected,!running,onChange={selected=it;settings.saveAllowed(it)},onDone={sheet="settings"})
        }
    }
    if(disclosure) AlertDialog(onDismissRequest={disclosure=false},title={Text(tr("连接你的手机"))},
        text={Text(tr("操作模式根据你的目标读取可用 App 的可见界面，并自动执行打开、点击、输入和滚动。页面文字和任务步骤会发送给你配置的模型服务。消息核对对象和正文后直接发送，无需再次确认；可随时暂停或停止。应用范围可在设置中限制。"))},
        confirmButton={TextButton(onClick={disclosure=false;context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}) {Text(tr("前往系统设置"))}},
        dismissButton={TextButton(onClick={disclosure=false}) {Text(tr("稍后"))}})
}

@Composable
private fun OperationWelcome(modifier: Modifier,onExample: (String) -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f).heightIn(min=32.dp))
        AgentMark(56)
        Spacer(Modifier.height(22.dp))
        Text(tr("你说目标，我来操作"),fontSize=25.sp,fontWeight=FontWeight.SemiBold)
        Text(tr("自动选择应用、拆解步骤并检查结果。"),Modifier.padding(top=10.dp),fontSize=13.sp,color=Muted)
        Spacer(Modifier.height(30.dp))
        Suggestion("chat",tr("帮我准备一条消息"),tr("例如：用 QQ 告诉小王，我会晚到十分钟"),{onExample(tr("用 QQ 给小王准备一条“我会晚到十分钟”的消息，先不要发送。"))})
        Spacer(Modifier.height(10.dp))
        Suggestion("new",tr("帮我记下来"),tr("创建一条备忘录并检查结果"),{onExample(tr("打开备忘录，新建一条内容为“LifeBuddy 测试”的笔记，保存并检查结果。"))})
        Spacer(Modifier.weight(1f).heightIn(min=28.dp))
    }
}

@Composable
private fun Suggestion(icon: String,title: String,detail: String,onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp,Line,RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).clickable(onClick=onClick)
        .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Glyph(icon,color=Muted)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text(detail,fontSize=11.sp,color=Muted)
        }
        Glyph("right",Modifier.size(16.dp),Muted)
    }
}

@Composable
private fun SettingsPanel(config: CloudConfig,enabled: Boolean,connected: Boolean,appCount: Int,restrictApps: Boolean,
    onSave: (CloudConfig) -> Unit,onApps: () -> Unit,onConnect: () -> Unit,onHistory: () -> Unit,onMemory: () -> Unit,onTools: () -> Unit,onRestrict: (Boolean) -> Unit,onLearning: () -> Unit,onExtensions: () -> Unit) {
    val context = LocalContext.current
    var draft by remember(config) { mutableStateOf(config) }
    var notice by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SheetHeading(tr("设置"),tr("把模型和手机连接起来。"))
        Text(tr("界面语言", "App language"), fontSize=14.sp, fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("system" to tr("跟随系统", "System"), "zh" to "中文", "en" to "English").forEach { (id, label) ->
                FilterChip(AppLanguage.selection == id, { AppLanguage.select(context, id) }, label = { Text(label) })
            }
        }
        OptionRow("plus",tr("扩展中心", "Extensions"),tr("导入技能包，连接 MCP 工具服务。", "Import skills and connect MCP tool services."),onClick=onExtensions)
        HorizontalDivider(color=Line)
        Text(tr("云端模型"),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
        OutlinedTextField(draft.baseUrl,{draft=draft.copy(baseUrl=it)},label={Text(tr("HTTPS API 基础地址"))},placeholder={Text("https://…/v1")},
            modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),singleLine=true,enabled=enabled)
        OutlinedTextField(draft.model,{draft=draft.copy(model=it)},label={Text(tr("模型名称"))},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),singleLine=true,enabled=enabled)
        OutlinedTextField(draft.apiKey,{draft=draft.copy(apiKey=it)},label={Text("API Key")},visualTransformation=PasswordVisualTransformation(),
            modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),singleLine=true,enabled=enabled)
        Text(tr("兼容 Chat Completions 与 function tools。地址填写到 API 根路径；Key 加密保存在本机。保存不会发起模型请求。"),fontSize=12.sp,lineHeight=19.sp,color=Muted)
        if(notice.isNotEmpty()) Text(systemText(notice),fontSize=12.sp,color=Green)
        Button(onClick={runCatching {CloudPhonePlanner.validate(draft);onSave(draft)}.onSuccess {notice=tr("已保存，尚未请求模型")}.onFailure {notice=it.message ?: tr("保存失败")}},
            enabled=enabled,modifier=Modifier.fillMaxWidth().height(48.dp)) {Text(tr("保存模型设置"))}
        TextButton(onClick={runCatching {onSave(CloudConfig());draft=CloudConfig()}.onSuccess {notice=tr("模型凭据已清除")}.onFailure {notice=tr("清除失败")}},enabled=enabled) {Text(tr("清除模型凭据"))}
        HorizontalDivider(color=Line)
        OptionRow("phone",tr("手机操作服务"),if(connected) tr("已连接 · 普通步骤自动执行") else tr("尚未连接，前往开启"),onClick=onConnect)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("限制应用范围"),fontSize=14.sp)
                Text(if (restrictApps) tr("仅使用你选中的应用") else tr("由助手从已安装应用中选择"),fontSize=12.sp,color=Muted)
            }
            Switch(restrictApps,onCheckedChange=onRestrict,enabled=enabled)
        }
        if (restrictApps) OptionRow("settings",tr("可用应用"),tr("已选择 $appCount 个外部应用", "$appCount apps selected"),onClick=onApps)
        OptionRow("spark",tr("手机工具"),tr("查看助手可以调用的能力。"),onClick=onTools)
        Text(tr("本版只读取界面文字，不采集截图。不支持验证码、支付、视觉页面和后台无人值守。"),fontSize=12.sp,lineHeight=19.sp,color=Muted)
        HorizontalDivider(color=Line)
        OptionRow("spark",tr("长期记忆"),tr("查看、修改和删除助手记住的偏好与常用信息。"),onClick=onMemory)
        OptionRow("spark",tr("学习与经验"),tr("查看习惯候选、应用操作经验，纠正或停用旧方法。"),onClick=onLearning)
        OptionRow("chat",tr("对话与任务历史"),tr("自动保存在本机，可从侧边栏搜索、重命名和删除。"),onClick=onHistory)
        Text(tr("卸载应用或清除应用数据会删除历史记录。"),fontSize=11.sp,color=Muted)
        Text("LifeBuddy · 0.2.0-alpha18",fontSize=11.sp,color=Muted)
    }
}

@Composable
private fun AppPicker(apps: List<Pair<String,String>>,selected: Set<String>,enabled: Boolean,onChange: (Set<String>) -> Unit,onDone: () -> Unit) {
    var search by remember {mutableStateOf("")}
    Column(Modifier.fillMaxHeight(.85f).padding(horizontal=24.dp).padding(bottom=20.dp)) {
        SheetHeading(tr("允许操作的应用"),tr("只读取与操作你选中的应用。内置练习页始终可用。"))
        OutlinedTextField(search,{search=it},placeholder={Text(tr("搜索应用"))},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp))
        LazyColumn(Modifier.weight(1f).padding(top=12.dp)) {
            items(apps.filter {search.isBlank() || it.second.contains(search,true) || it.first.contains(search,true)},key={it.first}) { (pkg,label) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled) {onChange(if(pkg in selected) selected-pkg else selected+pkg)}.padding(vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(label,fontSize=14.sp);Text(pkg,fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    Checkbox(pkg in selected,onCheckedChange={onChange(if(it) selected+pkg else selected-pkg)},enabled=enabled)
                }
            }
        }
        Button(onClick=onDone,modifier=Modifier.fillMaxWidth().height(48.dp)) {Text(tr("完成 · 已选择 ${selected.size} 个", "Done · ${selected.size} selected"))}
    }
}
