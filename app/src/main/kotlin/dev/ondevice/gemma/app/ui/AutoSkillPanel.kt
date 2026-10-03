package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.data.AutoSkillStore
import dev.ondevice.gemma.learning.AutoSkill
import dev.ondevice.gemma.learning.AutoSkillBook

@Composable
internal fun AutoSkillPanel(enabled: Boolean, onSource: (String) -> Unit) {
    val context=LocalContext.current
    val store=remember { AutoSkillStore.get(context) }
    val state by store.state.collectAsState()
    val storeNotice by store.notice.collectAsState()
    var editing by remember { mutableStateOf<AutoSkill?>(null) }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    fun change(skill: AutoSkill, operation: String, text: String="") {
        runCatching { store.change(skill.id,operation,text) }.onSuccess { error="" }.onFailure { error=it.message ?: tr("保存失败，原技能保留") }
    }
    Text(tr("自动学会的技能 · ${state.skills.size}", "Learned skills · ${state.skills.size}"),fontSize=17.sp)
    Text(tr("完成任务后自动生成试用技能；换不同参数再次成功后，标记为已复用验证。只作当前页面的操作参考。"),fontSize=12.sp,lineHeight=20.sp,color=Muted)
    if(storeNotice.isNotBlank()) Text(storeNotice,fontSize=12.sp,color=Muted)
    if(error.isNotBlank()) Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    if(state.jobs.isNotEmpty()) TextButton(enabled=enabled,onClick={store.kick()}) { Text(tr("继续整理 ${state.jobs.size} 项任务", "Continue processing ${state.jobs.size} tasks")) }
    if(state.skills.isEmpty()) Text(tr("还没有自动技能。让助手在未用过的 App 中完成一次搜索或其他可核对的操作，相关技能会出现在这里。"),fontSize=13.sp,lineHeight=21.sp,color=Muted)
    state.skills.sortedByDescending { it.updatedAt }.forEach { skill ->
        var expanded by remember(skill.id) { mutableStateOf(false) }
        var history by remember(skill.id) { mutableStateOf(false) }
        val version=remember(skill.id,skill.current.version) { runCatching {
            val info=context.packageManager.getPackageInfo(skill.packageName,0)
            if(Build.VERSION.SDK_INT>=28) info.longVersionCode.toString() else info.versionCode.toString()
        }.getOrNull() }
        val mismatch=version!=skill.current.version
        Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(skill.title,fontSize=15.sp,lineHeight=23.sp)
            Text(when {
                skill.status=="disabled" -> tr("已停用")
                mismatch -> tr("应用版本已变化 · 等待重新探索")
                skill.status=="review" -> tr("流程连续失效 · 暂停参考")
                skill.status=="verified" -> tr("已复用验证")
                else -> tr("试用技能")
            }+tr(" · 修订 ${skill.current.number}", " · Revision ${skill.current.number}"),fontSize=12.sp,color=Muted)
            Text(tr("本修订成功 ${skill.current.successes} 次 · 流程失配 ${skill.current.failures} 次 · ${skill.current.variants.size} 组参数", "${skill.current.successes} successes · ${skill.current.failures} mismatches · ${skill.current.variants.size} parameter sets"),fontSize=11.sp,color=Muted)
            Row {
                TextButton(onClick={expanded=!expanded}) { Text(if(expanded) tr("收起流程") else tr("查看流程")) }
                TextButton(enabled=enabled,onClick={editing=skill;notes=skill.current.notes;error=""}) { Text(tr("编辑说明")) }
                if(skill.status=="disabled" || skill.status=="review") TextButton(enabled=enabled && !mismatch,onClick={change(skill,"activate")}) { Text(tr("启用")) }
                else TextButton(enabled=enabled,onClick={change(skill,"disable")}) { Text(tr("停用")) }
                TextButton(enabled=enabled,onClick={change(skill,"delete")}) { Text(tr("删除")) }
            }
            if(expanded) {
                MarkdownMessage(AutoSkillBook.render(skill))
                skill.current.sourceRuns.lastOrNull()?.let { id -> TextButton(enabled=enabled,onClick={onSource(id)}) { Text(tr("查看来源会话")) } }
                if(skill.revisions.size>1) TextButton(onClick={history=!history}) { Text(if(history) tr("收起历史版本") else tr("历史版本（${skill.revisions.size-1}）", "Earlier revisions (${skill.revisions.size-1})")) }
                if(history) skill.revisions.dropLast(1).asReversed().forEach { revision ->
                    Text(tr("修订 ${revision.number} · App 版本 ${revision.version} · 成功 ${revision.successes} 次", "Revision ${revision.number} · App ${revision.version} · ${revision.successes} successes"),fontSize=12.sp,color=Muted)
                    MarkdownMessage(AutoSkillBook.render(skill.copy(revisions=listOf(revision),status="trial")))
                }
            }
            HorizontalDivider(color=Line)
        }
    }
    editing?.let { skill -> AlertDialog(onDismissRequest={editing=null},title={Text(tr("编辑技能说明"))},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(tr("可以补充正确入口、条件或核对方式。保存会保留旧版本；自动修订时保留你的补充。"),fontSize=13.sp)
            OutlinedTextField(notes,{notes=it.take(1200)},minLines=4,maxLines=10,label={Text(tr("补充或纠正流程"))})
            if(error.isNotBlank()) Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(enabled=enabled,onClick={change(skill,"edit",notes);if(error.isBlank()) editing=null}) { Text(tr("保存修订")) }},
        dismissButton={TextButton(onClick={editing=null}) { Text(tr("取消")) }}) }
}
