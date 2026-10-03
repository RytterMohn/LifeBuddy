package dev.ondevice.gemma.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray

/** Offline cross-app fixture. No account, network, contacts provider or SMS sending code. */
class MailboxActivity : Activity() {
    private val contact = "测试联系人"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); showIntent(intent) }
    private fun showIntent(value: Intent) {
        val diagnostic = listOf("latency", "messageRegression", "conversationRegression", "repairHistory", "appSkillsRegression", "autoSkillSeed", "autoSkillReuse", "systemRegression").firstOrNull { value.getBooleanExtra(it, false) }
        if (diagnostic != null) {
            if(diagnostic=="systemRegression") getSharedPreferences("system-test",MODE_PRIVATE).edit().clear().commit()
            startActivity(Intent().setClassName("dev.ondevice.gemma.app", "dev.ondevice.gemma.app.ToolsFixtureActivity")
                .putExtra(diagnostic, true).putExtra("repairIds", value.getStringExtra("repairIds")))
            finish()
        }
        else if (value.action == Intent.ACTION_SENDTO) chat(value.data?.schemeSpecificPart.orEmpty(), value.getStringExtra("sms_body").orEmpty())
        else home()
    }
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(28, 70, 28, 24); setBackgroundColor(0xFFF8F8F8.toInt())
    }
    private fun label(value: String, size: Float = 17f) = TextView(this).apply { text = value; textSize = size; setPadding(12, 12, 12, 12) }
    private fun home(wechat: Boolean = false) {
        val root = column()
        root.addView(label(if (wechat) "模拟微信" else "离线工具测试信箱", 24f))
        root.addView(label("所有内容仅保存在测试页，不会发给任何人。"))
        if (!wechat) {
            root.addView(Button(this).apply { text="模拟微信"; setOnClickListener { home(true) } })
            root.addView(Button(this).apply { text="模拟网盘"; setOnClickListener { searchScene(false) } })
            root.addView(Button(this).apply { text="模拟美团"; setOnClickListener { searchScene(true) } })
            root.addView(Button(this).apply { text="陌生笔记"; setOnClickListener { notebook() } })
        }
        root.addView(Button(this).apply {
            text = "启动跨 App 自动验证"
            setOnClickListener { startActivity(Intent().setClassName("dev.ondevice.gemma.app", "dev.ondevice.gemma.app.ToolsFixtureActivity")) }
        })
        val search = EditText(this).apply { id = View.generateViewId(); hint = "搜索联系人"; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH }
        root.addView(search)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 28, 20, 28)
            addView(label(contact, 20f)); isClickable = true
            setOnClickListener { hideKeyboard(search); chat(contact, "") }
        }
        root.addView(row)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { row.visibility = if (contact.contains(s.toString())) View.VISIBLE else View.GONE }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        search.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(search); true } else false }
        setContentView(root)
    }
    private fun searchScene(shop: Boolean) {
        val root = column()
        root.addView(label(if (shop) "模拟美团外卖" else "模拟百度网盘 · 我的文件", 24f))
        val input = EditText(this).apply { hint=if(shop) "搜索商家" else "搜索我的文件"; isSingleLine=true; imeOptions=EditorInfo.IME_ACTION_SEARCH }
        root.addView(input)
        fun submit() {
            hideKeyboard(input)
            val result = column()
            val matched = input.text.toString() == if(shop) "测试粥铺" else "测试报价单.pdf"
            result.addView(label(if(matched) "搜索结果：1 项" else "没有找到结果", 24f))
            if(matched) {
                if(shop) result.addView(Button(this).apply { text="测试粥铺"; setOnClickListener { shopMenu() } })
                else result.addView(label("测试报价单.pdf"))
            }
            setContentView(result)
        }
        root.addView(Button(this).apply { text="搜索"; setOnClickListener { submit() } })
        input.setOnEditorActionListener { _, action, _ -> if(action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        setContentView(root)
    }
    private fun shopMenu() {
        val root = column()
        root.addView(label("测试粥铺", 24f))
        root.addView(Button(this).apply { text="南瓜粥"; setOnClickListener {
            val choices = column()
            choices.addView(label("南瓜粥 · 选择口味", 24f))
            listOf("少辣", "正常辣").forEach { flavor -> choices.addView(Button(this@MailboxActivity).apply {
                text=flavor; setOnClickListener {
                    val result = column(); result.addView(label("口味选择结果", 24f)); result.addView(label("南瓜粥 · 已选$flavor")); setContentView(result)
                }
            }) }
            setContentView(choices)
        } })
        setContentView(root)
    }
    private fun notebook() {
        val root=column()
        root.addView(label("陌生笔记",24f))
        val input=EditText(this).apply { hint="搜索笔记"; id=View.generateViewId(); isSingleLine=true; imeOptions=EditorInfo.IME_ACTION_SEARCH }
        root.addView(input)
        fun submit() {
            hideKeyboard(input)
            val query=input.text.toString()
            val result=column()
            result.addView(label("搜索结果",24f))
            result.addView(label(if(query.startsWith("HARNESS_NOTE_")) "找到笔记：$query" else "没有匹配笔记"))
            setContentView(result)
        }
        root.addView(Button(this).apply { text="搜索"; setOnClickListener { submit() } })
        input.setOnEditorActionListener { _, action, _ -> if(action==EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        setContentView(root)
    }
    private fun chat(recipient: String, draft: String) {
        val root = column()
        root.addView(label(recipient, 24f))
        root.addView(label("离线模拟聊天 · 不连接 QQ 或运营商", 14f))
        root.addView(Button(this).apply { text = "返回联系人"; setOnClickListener { home() } })
        root.addView(Space(this), LinearLayout.LayoutParams(1, 320))
        val input = EditText(this).apply { id = View.generateViewId(); hint = "消息正文"; minLines = 2; setText(draft) }
        root.addView(input)
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val preferences = getSharedPreferences("offline-messages", MODE_PRIVATE)
        val saved = JSONArray(preferences.getString(recipient, "[]"))
        fun bubble(body: String) = label(body).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        for (index in 0 until saved.length()) messages.addView(bubble(saved.getString(index)))
        val counter = label("模拟发送次数：${saved.length()}")
        root.addView(Button(this).apply {
            text = "发送"
            setOnClickListener {
                if (input.text.isNotBlank()) {
                    val body = input.text.toString()
                    saved.put(body)
                    check(preferences.edit().putString(recipient, saved.toString()).commit())
                    messages.addView(bubble(body))
                    input.setText(""); hideKeyboard(input); input.clearFocus()
                    counter.text = "模拟发送次数：${saved.length()}"
                }
            }
        })
        root.addView(counter); root.addView(messages)
        setContentView(root)
    }
    private fun hideKeyboard(view: View) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0) }
}
