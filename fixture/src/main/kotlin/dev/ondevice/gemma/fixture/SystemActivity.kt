package dev.ondevice.gemma.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Receives the real Android intent contracts without creating alarms or modifying system settings. */
class SystemActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState);show(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);show(intent) }
    private fun show(intent: Intent) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(30,90,30,30) }
        fun label(t:String)=TextView(this).apply { text=t;textSize=20f;setPadding(0,18,0,18) }
        column.addView(label("LifeBuddy 系统工具测试 · 不影响真实设置"))
        val values=JSONObject().put("action",intent.action)
        when(intent.action) {
            AlarmClock.ACTION_SET_ALARM,AlarmClock.ACTION_SET_TIMER -> {
                val name=intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
                if(!name.startsWith("HARNESS_SYSTEM_")) { finish();return }
                values.put("label",name).put("skipUi",intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true))
                val prefs=getSharedPreferences("system-test",MODE_PRIVATE)
                val key=if(intent.action==AlarmClock.ACTION_SET_ALARM) "alarmCalls" else "timerCalls"
                val calls=prefs.getInt(key,0)+1;prefs.edit().putInt(key,calls).commit();values.put("calls",calls)
                if(intent.action==AlarmClock.ACTION_SET_ALARM) {
                    val hour=intent.getIntExtra(AlarmClock.EXTRA_HOUR,-1);val minute=intent.getIntExtra(AlarmClock.EXTRA_MINUTES,-1)
                    val days=intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS).orEmpty()
                    values.put("hour",hour).put("minute",minute).put("calendarDays",JSONArray(days))
                    column.addView(label("闹钟：$name %02d:%02d 已开启".format(hour,minute)))
                    column.addView(label("重复："+days.joinToString { listOf("","周日","周一","周二","周三","周四","周五","周六").getOrElse(it) { "错误" } }))
                } else {
                    val seconds=intent.getIntExtra(AlarmClock.EXTRA_LENGTH,-1);values.put("seconds",seconds)
                    column.addView(label("$name 倒计时 %02d:%02d 运行中".format(seconds/60,seconds%60)))
                }
                column.addView(label("本场景系统请求次数：$calls"))
                File(noBackupFilesDir,if(key=="alarmCalls") "system-alarm.json" else "system-timer.json").writeText(values.toString())
            }
            Settings.ACTION_DISPLAY_SETTINGS -> {
                column.addView(label("模拟显示设置"))
                val toggle=Switch(this).apply { text="自动亮度";isChecked=true }
                val progress=SeekBar(this).apply { contentDescription="屏幕亮度";max=100;this.progress=25 }
                val summary=label("当前亮度：25%")
                fun save() { File(noBackupFilesDir,"system-display.json").writeText(JSONObject().put("automatic",toggle.isChecked).put("brightness",progress.progress).toString()) }
                toggle.setOnCheckedChangeListener { _,_->save() }
                progress.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) { summary.text="当前亮度：$value%";save() }
                    override fun onStartTrackingTouch(bar:SeekBar?)=Unit
                    override fun onStopTrackingTouch(bar:SeekBar?)=Unit
                })
                column.addView(toggle);column.addView(progress);column.addView(summary);save()
            }
            else -> { finish();return }
        }
        setContentView(column)
    }
}
