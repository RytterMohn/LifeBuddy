package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.systemText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.phone.*

@Composable
internal fun ToolsPanel() {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SheetHeading(tr("手机工具"), tr("${PhoneTools.all.size} 项能力，按任务组合使用。", "${PhoneTools.all.size} capabilities, combined for each task."))
        Text(tr("助手根据你的一句话选择应用和工具，核对对象和正文后直接发送消息，无需再次确认。"), fontSize = 13.sp, lineHeight = 21.sp, color = Muted)
        Text(tr("应用技能"), fontSize = 17.sp)
        Text(tr("根据当前任务加载相关流程，切换 App 无需切换模式。内置技能是操作参考，每一步仍核对实际页面。"), fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
        AppSkillLibrary.bundled.descriptors.forEach { skill ->
            var expanded by remember(skill.id) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(skill.title) }
            if (expanded) MarkdownMessage(AppSkillLibrary.bundled.body(skill.id))
        }
        HorizontalDivider(color = Line)
        PhoneTools.all.forEach { tool ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(systemText(tool.title), fontSize = 15.sp)
                Text(toolSummary(tool.action), fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
            }
            HorizontalDivider(color = Line)
        }
    }
}

private fun toolSummary(action: PhoneActionType): String = when (action) {
    PhoneActionType.SEARCH_EXTENSIONS -> tr("按关键词搜索导入的技能和已启用的 MCP 工具。", "Search imported skills and enabled MCP tools by keyword.")
    PhoneActionType.READ_EXTENSION -> tr("按需读取技能参考文件或工具参数定义。", "Read skill references or tool parameter definitions on demand.")
    PhoneActionType.CALL_EXTENSION -> tr("调用已经读取过的外部工具；中断后不自动重试。", "Call a previously read external tool, without automatic retries after interruption.")
    PhoneActionType.LIST_APPS -> tr("查看这次任务允许使用的应用。")
    PhoneActionType.SEARCH_APPS -> tr("按名称、别名或包名检索应用，较长列表分批查看。")
    PhoneActionType.SEARCH_SKILLS -> tr("查找与当前任务有关的应用用法。")
    PhoneActionType.LOAD_SKILL -> tr("需要时读取一份应用操作流程。")
    PhoneActionType.READ_SCREEN -> tr("读取当前页面可访问的文字和控件。")
    PhoneActionType.FIND_NODES -> tr("按关键词查找当前页面的文字和按钮。")
    PhoneActionType.OPEN_APP -> tr("打开这次任务允许使用的应用。")
    PhoneActionType.OPEN_SETTINGS -> tr("直达本机可用的网络、显示、声音、电池、通知等设置页或闹钟列表。")
    PhoneActionType.SET_ALARM -> tr("按时间和重复日设置系统闹钟，再查看结果。")
    PhoneActionType.SET_TIMER -> tr("开始你指定时长的倒计时，再查看结果。")
    PhoneActionType.SET_CHECKED -> tr("读取开关当前状态，按你的要求开启或关闭。")
    PhoneActionType.SET_PROGRESS -> tr("调整界面实际支持的亮度、音量等滑块，并读回数值。")
    PhoneActionType.TAP -> tr("点击页面上的按钮或列表项。")
    PhoneActionType.LONG_PRESS -> tr("长按页面中支持此操作的内容。")
    PhoneActionType.TYPE -> tr("填写或替换输入框中的文字，支持中文。")
    PhoneActionType.SUBMIT_SEARCH -> tr("提交页面搜索框中的关键词。")
    PhoneActionType.SCROLL -> tr("滚动页面，继续查找需要的内容。")
    PhoneActionType.BACK -> tr("返回上一页，再检查当前页面。")
    PhoneActionType.WAIT -> tr("等待页面加载或更新。")
    PhoneActionType.PREPARE_MESSAGE -> tr("从你的描述中整理对象和正文，信息不清楚时向你询问。")
    PhoneActionType.RESPOND -> tr("不需要操作手机时，直接回答你的问题。")
    PhoneActionType.NOTE_PREFERENCE -> tr("从你明确表达的稳定偏好提出候选，确认后再用于后续任务。")
    PhoneActionType.COMPOSE_SMS -> tr("用你填写的号码和正文打开系统短信草稿。")
    PhoneActionType.SEND_MESSAGE -> tr("核对你指定的对象和原文后直接发送一次，并检查结果。")
    PhoneActionType.VERIFY_MESSAGE -> tr("核对草稿或聊天页新增的消息；不代表对方已收到。")
    PhoneActionType.FINISH -> tr("核对页面结果后结束任务，并说明完成依据。")
    PhoneActionType.ASK_USER -> tr("遇到登录、重名或无法核对的页面时暂停，说明需要你的帮助。")
}
