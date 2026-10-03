package dev.ondevice.gemma.app.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import dev.ondevice.gemma.phone.*

/** Public Android contracts only; bind a resolved component inside the task's allowed packages. */
class SystemBridge(private val context: Context) {
    private val pm=context.packageManager
    private var cache: List<SystemTarget> = emptyList()
    private var cachedAllowed: Set<String> = emptySet()
    private var cachedAt=0L
    private val actions=linkedMapOf(
        "alarm" to AlarmClock.ACTION_SET_ALARM, "timer" to AlarmClock.ACTION_SET_TIMER, "alarms" to AlarmClock.ACTION_SHOW_ALARMS,
        "settings" to Settings.ACTION_SETTINGS, "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "date" to Settings.ACTION_DATE_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS, "app_details" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "app_notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS, "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "locale" to Settings.ACTION_LOCALE_SETTINGS, "input" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "wireless" to Settings.ACTION_WIRELESS_SETTINGS,
    ).apply { if(Build.VERSION.SDK_INT>=33) put("notifications",Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS) }
    fun intent(id: String, targetPackage: String=""): Intent = Intent(requireNotNull(actions[id])).apply {
        if(id=="app_details") data=Uri.fromParts("package",targetPackage.ifBlank { context.packageName },null)
        if(id=="app_notifications") putExtra(Settings.EXTRA_APP_PACKAGE,targetPackage.ifBlank { context.packageName })
    }
    private fun handlers(intent: Intent)=pm.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY).filter {
        it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled &&
            (it.activityInfo.permission==null || context.checkSelfPermission(it.activityInfo.permission)==PackageManager.PERMISSION_GRANTED)
    }
    fun discoveredApps(): Map<String,String> = buildMap {
        actions.keys.forEach { id -> handlers(intent(id)).forEach { r ->
            val info=r.activityInfo.applicationInfo
            val name=pm.getApplicationLabel(info).toString()
            put(info.packageName,name+if(id in setOf("alarm","timer","alarms")) "（时钟、闹钟、计时器）" else "（系统设置）")
        } }
    }
    fun targets(allowed: Set<String>): List<SystemTarget> {
        val now=SystemClock.elapsedRealtime()
        if(allowed==cachedAllowed && now-cachedAt<10_000) return cache
        cache=actions.keys.mapNotNull { id ->
            resolve(intent(id),allowed)?.let { SystemTarget(id,SystemPhoneActions.destinations[id] ?: if(id=="alarm") "设置闹钟" else "开始计时",it.packageName) }
        }
        cachedAllowed=allowed.toSet();cachedAt=now
        return cache
    }
    private fun resolve(intent: Intent, allowed: Set<String>): ComponentName? {
        val candidates=handlers(intent).filter { it.activityInfo.packageName in allowed }
        val preferred=pm.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val found=candidates.firstOrNull { it.activityInfo.packageName==preferred?.packageName && it.activityInfo.name==preferred.name }
            ?: candidates.sortedWith(compareByDescending<android.content.pm.ResolveInfo> { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                .thenBy { it.activityInfo.packageName }.thenBy { it.activityInfo.name }).firstOrNull() ?: return null
        return ComponentName(found.activityInfo.packageName,found.activityInfo.name)
    }
    fun build(action: PhoneAction, allowed: Set<String>, observed: List<SystemTarget>): Intent? {
        val id=SystemPhoneActions.target(action)
        val target=observed.find { it.id==id && it.packageName in allowed } ?: return null
        val request=intent(id,action.packageName)
        val component=resolve(request,setOf(target.packageName)) ?: return null
        if(action.type in SystemPhoneActions.creates && component.packageName!=action.packageName) return null
        if(action.type==PhoneActionType.SET_ALARM) {
            request.putExtra(AlarmClock.EXTRA_HOUR,action.hour.toInt()).putExtra(AlarmClock.EXTRA_MINUTES,action.minute.toInt())
            val weekdays=SystemPhoneActions.days(action.days) ?: return null
            if(weekdays.isNotEmpty()) request.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS,ArrayList(weekdays.map { if(it==7) 1 else it+1 }))
        }
        if(action.type==PhoneActionType.SET_TIMER) request.putExtra(AlarmClock.EXTRA_LENGTH,action.seconds.toInt())
        if(action.type in SystemPhoneActions.creates) {
            if(action.text.isNotBlank()) request.putExtra(AlarmClock.EXTRA_MESSAGE,action.text)
            request.putExtra(AlarmClock.EXTRA_SKIP_UI,false)
        }
        return request.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
