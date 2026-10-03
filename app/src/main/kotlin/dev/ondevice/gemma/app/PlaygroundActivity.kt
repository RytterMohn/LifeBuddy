package dev.ondevice.gemma.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.ondevice.gemma.app.phone.PhoneAccessibilityService

/** Accessible controls with real persisted output for on-device smoke testing. */
class PlaygroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("practice", MODE_PRIVATE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = PhoneAccessibilityService.PRACTICE_MARKER
            setPadding(32, 380, 32, 32)
        }
        column.addView(TextView(this).apply { text = "LifeBuddy 练习页面"; textSize = 24f })
        column.addView(TextView(this).apply { text = "用于验证观察、输入、点击和读回。练习内容只保存在本机。" })
        val field = EditText(this).apply { id = View.generateViewId(); hint = "练习内容"; minLines = 2 }
        column.addView(field)
        val result = TextView(this).apply { text = prefs.getString("saved", "尚未保存") }
        column.addView(Button(this).apply {
            text = "保存练习"
            setOnClickListener {
                result.text = "已保存：${field.text}"
                prefs.edit().putString("saved", result.text.toString()).apply()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(field.windowToken, 0)
                field.clearFocus()
            }
        })
        column.addView(result)
        column.addView(Button(this).apply {
            text = "重置练习"
            setOnClickListener { field.setText(""); result.text = "尚未保存"; prefs.edit().remove("saved").apply() }
        })
        column.addView(Button(this).apply { text = "回到 Agent"; setOnClickListener { finish() } })
        setContentView(column)
    }
}
