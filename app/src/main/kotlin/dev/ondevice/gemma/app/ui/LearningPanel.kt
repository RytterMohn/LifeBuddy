package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.data.AgentSettings
import dev.ondevice.gemma.app.data.LearningStore
import dev.ondevice.gemma.app.data.AutoSkillStore
import dev.ondevice.gemma.learning.LearningEntry
import dev.ondevice.gemma.phone.PhoneActionType

@Composable
internal fun LearningPanel(enabled: Boolean, onSource: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { AgentSettings(context) }
    val store = remember { LearningStore.get(context) }
    val state by store.state.collectAsState()
    val storeNotice by store.notice.collectAsState()
    var learning by remember { mutableStateOf(settings.learningEnabled()) }
    var notice by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LearningEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    fun change(entry: LearningEntry, operation: String, value: String = "") {
        runCatching { store.change(entry.id, operation, value) }
            .onSuccess { notice = when (operation) { "delete" -> tr("已删除，同一条经验不会再次自动收录"); "disable" -> tr("已停用"); else -> tr("已保存，后续任务将使用最新内容") } }
            .onFailure { notice = tr("未能保存，请重试；原记录保留") }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SheetHeading(tr("学习与经验"), tr("从交给助手的任务中，积累下次能用上的经验。"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(tr("使用与学习经验"), fontSize=15.sp)
                Text(tr("仅在你主动运行任务时学习"), fontSize=12.sp, color=Muted)
            }
            Switch(learning, onCheckedChange={learning=it;settings.saveLearningEnabled(it);if(it) AutoSkillStore.get(context).kick()}, enabled=enabled)
        }
        Text(tr("仅从交给助手的任务中学习。成功流程会自动生成技能；习惯先保存为候选，确认后才用于后续任务。关闭后不记录或使用这些经验。"),fontSize=12.sp,lineHeight=20.sp,color=Muted)
        AutoSkillPanel(enabled,onSource)
        Text(tr("${state.entries.count { it.kind == "habit" }} 条习惯 · ${state.entries.count { it.kind == "app" }} 条旧版路径", "${state.entries.count { it.kind == "habit" }} habits · ${state.entries.count { it.kind == "app" }} legacy paths"),fontSize=12.sp,color=Muted)
        if (storeNotice.isNotBlank()) Text(storeNotice,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        if (notice.isNotBlank()) Text(notice,fontSize=12.sp,color=Muted)
        if (state.entries.isEmpty()) Text(tr("还没有习惯记录。明确告诉助手你的偏好后，可以在这里确认或纠正。"),fontSize=14.sp,lineHeight=23.sp)
        state.entries.sortedWith(compareBy<LearningEntry> { it.status != "pending" }.thenByDescending { it.updatedAt }).forEach { entry ->
            val currentVersion = if (entry.kind == "app") remember(entry.id, entry.version) {
                runCatching { val info=context.packageManager.getPackageInfo(entry.packageName,0);if(Build.VERSION.SDK_INT>=28) info.longVersionCode.toString() else info.versionCode.toString() }.getOrNull()
            } else null
            val mismatch = entry.kind == "app" && currentVersion != entry.version
            Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(entry.text,fontSize=15.sp,lineHeight=23.sp)
                val status = when { entry.kind=="app" -> tr("旧版路径记录 · 保留供查看"); mismatch -> tr("应用版本已变化，暂停参考"); entry.status=="pending" -> tr("习惯候选 · 待你确认"); entry.status=="review" -> tr("连续任务未完成 · 待复核"); entry.status=="disabled" -> tr("已停用"); else -> tr("习惯 · 已确认") }
                Text(status,fontSize=12.sp,color=Muted)
                if (entry.packageName.isNotBlank()) Text(entry.appName.ifBlank { entry.packageName },fontSize=11.sp,color=Muted)
                if (entry.kind == "app") {
                    Text(tr("来自 ${entry.sourceRuns.size} 次已完成任务 · 参考后完成 ${entry.successes} 次 / 未完成 ${entry.failures} 次", "From ${entry.sourceRuns.size} completed tasks · ${entry.successes} successful / ${entry.failures} incomplete uses"),fontSize=11.sp,lineHeight=18.sp,color=Muted)
                    entry.steps.forEachIndexed { index, step ->
                        val hint = when(step.action) {
                            PhoneActionType.TYPE -> tr("按本次任务填写内容")
                            PhoneActionType.TAP -> tr("查找并点击“${step.label.ifBlank { "对应控件" }}”", "Find and tap ${step.label.ifBlank { "the matching control" }}")
                            PhoneActionType.SUBMIT_SEARCH -> tr("提交搜索")
                            PhoneActionType.SCROLL -> tr("滚动页面")
                            PhoneActionType.BACK -> tr("返回上一页")
                            else -> tr("重新核对当前页面")
                        }
                        Text("${index+1}. $hint",fontSize=12.sp,lineHeight=19.sp,color=Muted)
                    }
                    Text(tr("旧版路径保留供查看，后续任务使用上方的自动技能。"),fontSize=11.sp,lineHeight=18.sp,color=Muted)
                } else Text(tr("来自 ${entry.sourceRuns.size} 次用户表述；确认前不会进入模型的偏好上下文。", "From ${entry.sourceRuns.size} statements. Not used as a preference until confirmed."),fontSize=11.sp,lineHeight=18.sp,color=Muted)
                Row {
                    if (entry.kind=="habit" && entry.status != "active") TextButton(enabled=enabled && !mismatch,onClick={change(entry,"activate")}) { Text(tr("确认习惯")) }
                    else if(entry.kind=="habit") TextButton(enabled=enabled,onClick={change(entry,"disable")}) { Text(tr("停用")) }
                    if (entry.kind=="habit") TextButton(enabled=enabled,onClick={editing=entry;draft=entry.text}) { Text(tr("纠正")) }
                    TextButton(enabled=enabled,onClick={change(entry,"delete")}) { Text(tr("删除")) }
                    entry.sourceRuns.lastOrNull()?.let { id -> TextButton(enabled=enabled,onClick={onSource(id)}) { Text(tr("来源")) } }
                }
                HorizontalDivider(color=Line)
            }
        }
        Text(tr("笔记保存在这台手机。启用时，匹配的操作经验和已确认习惯会随任务发给你配置的模型。这里的学习是经验检索，不是训练模型权重，也不保证一次就适配所有 App。"),fontSize=11.sp,lineHeight=18.sp,color=Muted)
    }
    editing?.let { entry -> AlertDialog(onDismissRequest={editing=null},title={Text(tr("纠正习惯"))},text={
        OutlinedTextField(draft,{draft=it.take(160)},minLines=2,label={Text(tr("今后使用的偏好"))})
    },confirmButton={TextButton(enabled=enabled && draft.isNotBlank(),onClick={change(entry,"edit",draft);editing=null}) { Text(tr("保存并确认")) }},dismissButton={TextButton(onClick={editing=null}) { Text(tr("取消")) }}) }
}
