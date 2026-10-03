package dev.ondevice.gemma.app.data

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.ondevice.gemma.app.phone.SystemBridge

object AppCatalog {
    fun list(context: Context): List<Pair<String,String>> {
        val sms=Telephony.Sms.getDefaultSmsPackage(context)
        val launched=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .associate { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
        return (launched+SystemBridge(context).discoveredApps()).filterKeys { it!=context.packageName }
            .map { (pkg,name) -> pkg to (name+if(pkg==sms) "（系统默认短信应用）" else "") }.sortedBy { it.second }
    }
}
