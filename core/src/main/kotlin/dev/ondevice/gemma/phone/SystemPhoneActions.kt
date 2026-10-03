package dev.ondevice.gemma.phone

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data class SystemTarget(val id: String, val title: String, val packageName: String)

/** Bounded OS capabilities discovered on this device, not arbitrary intents supplied by the model. */
object SystemPhoneActions {
    val destinations=linkedMapOf("settings" to "系统设置", "wifi" to "Wi-Fi", "bluetooth" to "蓝牙",
        "display" to "显示与亮度", "sound" to "声音与音量", "notifications" to "通知",
        "location" to "定位", "battery" to "电池与省电", "storage" to "存储", "date" to "日期与时间",
        "apps" to "应用管理", "app_details" to "指定应用详情", "app_notifications" to "指定应用通知",
        "accessibility" to "无障碍设置", "locale" to "语言", "input" to "输入法", "wireless" to "无线网络",
        "alarms" to "闹钟列表")
    val creates=setOf(PhoneActionType.SET_ALARM,PhoneActionType.SET_TIMER)
    val controls=setOf(PhoneActionType.SET_CHECKED,PhoneActionType.SET_PROGRESS)
    fun target(action: PhoneAction)=when(action.type) {
        PhoneActionType.SET_ALARM -> "alarm"; PhoneActionType.SET_TIMER -> "timer"; else -> action.destination
    }
    fun days(value: String): List<Int>? {
        if(value.isEmpty()) return emptyList()
        val days=value.split(',').map { it.trim().toIntOrNull() ?: return null }
        return days.takeIf { it.size in 1..7 && it.all { d -> d in 1..7 } && it.distinct().size==it.size }
    }
    fun creationKey(a: PhoneAction)=listOf(a.type.name,a.packageName,if(a.type==PhoneActionType.SET_ALARM)
        "${a.hour.toIntOrNull()}:${a.minute.toIntOrNull()}:${days(a.days)?.sorted()}" else a.seconds.toIntOrNull().toString())
    fun attempted(steps: List<PhoneStep>, action: PhoneAction) = steps.any {
        it.action.type in creates && creationKey(it.action)==creationKey(action) && (it.dispatchAttempted || it.dispatched)
    }
    fun validate(a: PhoneAction, s: ScreenSnapshot, allowed: Set<String>, steps: List<PhoneStep>): String? {
        if(a.type in controls) {
            val node=s.nodes.find { it.id==a.nodeId } ?: return "目标控件不存在"
            if(MessagePolicy.isSendControl(node,s) || MessagePolicy.isRestrictedControl(node,s)) return "该控件不适合用设置工具操作"
            val parent=s.nodes.find { it.id==node.clickTargetId }
            if(parent!=null && (MessagePolicy.isSendControl(parent,s) || MessagePolicy.isRestrictedControl(parent,s))) return "该控件所属按钮不适合用设置工具操作"
            return if(a.type==PhoneActionType.SET_CHECKED) {
                if(!node.checkable || (!node.clickable && s.nodes.none { it.id==node.clickTargetId && it.clickable })) "该控件未暴露可操作的开关状态" else null
            } else {
                val v=a.value.toFloatOrNull()
                if(v==null || !v.isFinite() || v !in 0f..100f) "滑块比例须为 0–100 的数字"
                else if(!node.canSetProgress || node.rangeMin==null || node.rangeMax==null || node.rangeValue==null ||
                    !node.rangeMin.isFinite() || !node.rangeMax.isFinite() || node.rangeMax<=node.rangeMin) "该控件未暴露可设置的范围" else null
            }
        }
        val id=target(a)
        if(a.type==PhoneActionType.OPEN_SETTINGS && id !in destinations) return "未知系统入口"
        if(s.systemTargets.none { it.id==id && it.packageName in allowed && (a.type==PhoneActionType.OPEN_SETTINGS || it.packageName==a.packageName) }) return "该系统能力在本机或允许范围内不可用"
        if(a.type==PhoneActionType.OPEN_SETTINGS && id in setOf("app_details","app_notifications") && a.packageName !in allowed) return "目标应用未获授权"
        if(a.type in creates && attempted(steps,a)) return "本轮已请求创建相同项目，先核对时钟页面；不自动重复创建"
        return when(a.type) {
            PhoneActionType.SET_ALARM -> when {
                a.hour.toIntOrNull() !in 0..23 || a.minute.toIntOrNull() !in 0..59 -> "闹钟须使用有效的 24 小时时间"
                days(a.days)==null -> "重复日须用周一=1至周日=7的逗号分隔数字，单次留空"
                a.text.length>80 -> "闹钟标签过长"
                else -> null
            }
            PhoneActionType.SET_TIMER -> if(a.seconds.toIntOrNull() !in 1..86400 || a.text.length>80) "计时须为 1–86400 秒，标签不超过80字" else null
            else -> null
        }
    }
    fun controlMatches(a: PhoneAction,before: ScreenSnapshot,after: ScreenSnapshot): Boolean {
        if(before.packageName!=after.packageName) return false
        val old=before.nodes.find { it.id==a.nodeId } ?: return false
        val candidates=after.nodes.filter { n -> when {
            old.resourceId.isNotBlank() -> n.resourceId==old.resourceId
            old.text.isNotBlank() -> n.text==old.text
            old.description.isNotBlank() -> n.description==old.description
            else -> n.id==old.id
        } }
        if(candidates.size!=1) return false
        val n=candidates.single()
        return if(a.type==PhoneActionType.SET_CHECKED) n.checkable && n.checked==a.checked
        else {
            val min=n.rangeMin ?: return false; val max=n.rangeMax ?: return false; val value=n.rangeValue ?: return false
            val percent=a.value.toFloatOrNull() ?: return false
            abs(value-(min+(max-min)*percent/100f))<=maxOf((max-min)*.015f,if(n.rangeIsInteger) .5f else .001f)
        }
    }
    fun completionError(screen: ScreenSnapshot, steps: List<PhoneStep>): String? {
        return steps.filter { !it.systemVerified && it.action.type in creates && (it.dispatched || it.dispatchAttempted) }
            .firstNotNullOfOrNull { creationError(screen,it) }
    }
    fun verifiedSteps(screen: ScreenSnapshot,steps: List<PhoneStep>)=steps.map { step ->
        if(!step.systemVerified && step.dispatched && step.action.type in creates && creationError(screen,step)==null)
            step.copy(systemVerified=true,observation="已在时钟页面核对本次创建的可见结果，可继续任务其余步骤；不要重复创建") else step
    }
    private fun creationError(screen: ScreenSnapshot, created: PhoneStep): String? {
        if(!created.dispatched) return "系统创建结果待核对，不可声称完成或自动重新创建"
        val a=created.action
        if(screen.packageName!=a.packageName) return "请回到目标时钟应用核对创建结果"
        val visible=screen.nodes.filterNot { it.editable }.joinToString("\n") { "${it.text} ${it.description} ${it.stateDescription}" }
        if(a.text.isNotBlank() && a.text !in visible) return "当前时钟页面尚未显示本次标签，请继续核对"
        if(a.type==PhoneActionType.SET_ALARM) {
            val hour=a.hour.toIntOrNull() ?: return "时间无效"; val minute=a.minute.toIntOrNull() ?: return "时间无效"
            val normalized=visible.replace('：',':')
            if(!Regex("(?<![0-9])0?$hour\\s*[:时]\\s*0?$minute(?![0-9])").containsMatchIn(normalized)) return "当前页面尚未核对目标闹钟时间"
        }
        return null
    }
}
