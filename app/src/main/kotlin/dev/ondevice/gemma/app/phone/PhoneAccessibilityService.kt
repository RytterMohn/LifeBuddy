package dev.ondevice.gemma.app.phone

import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.app.i18n.systemText
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.*
import dev.ondevice.gemma.app.PlaygroundActivity
import dev.ondevice.gemma.app.MainActivity
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class PhoneAccessibilityService : AccessibilityService(), PhoneDriver {
    companion object { const val PRACTICE_MARKER = "MobileAgentPractice" }
    private val paths = mutableMapOf<String, List<Int>>()
    private var lastChange = 0L
    private var lastScreen: ScreenSnapshot? = null
    private var overlay: LinearLayout? = null
    private var overlayProgress: TextView? = null
    private val sensitive = Regex("password|passwd|otp|verification.?code|密码|验证码", RegexOption.IGNORE_CASE)
    private val systemBridge by lazy { SystemBridge(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhoneController.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !PhoneController.busy) return
        val fromOverlay = windows.any { it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        if (!fromOverlay && event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            )) lastChange = SystemClock.elapsedRealtime()
    }

    override fun onInterrupt() { PhoneController.pause("无障碍服务被中断") }
    override fun onUnbind(intent: Intent?): Boolean {
        PhoneController.detach(this)
        hideOverlay()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        PhoneController.detach(this)
        hideOverlay()
        super.onDestroy()
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        val candidates = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return (candidates.firstOrNull { it.isActive } ?: candidates.firstOrNull { it.isFocused })?.root
            ?: rootInActiveWindow
    }

    override suspend fun observe(allowed: Set<String>): ScreenSnapshot = withContext(Dispatchers.Main.immediate) {
        check(!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "手机已锁定，请解锁后重试" }
        val root = appRoot() ?: error("当前页面不可读取，请切换到目标应用")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            paths.clear()
            if (pkg !in allowed) return@withContext ScreenSnapshot(
                UUID.randomUUID().toString(), pkg, System.currentTimeMillis(), "blocked", emptyList(),
                "当前应用未获授权，未读取界面内容",
            ).also { lastScreen = it }
            val nodes = mutableListOf<ScreenNode>()
            val signatures = mutableListOf<String>()
            var count = 0
            var practice = false
            fun visit(node: AccessibilityNodeInfo, path: List<Int>, depth: Int, hidden: Boolean, clickableParent: String = "") {
                if (depth > 30 || count++ >= 500) return
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                if (description == PRACTICE_MARKER) practice = true
                val rid = node.viewIdResourceName.orEmpty()
                val hint = node.hintText?.toString().orEmpty()
                var nextClickable = clickableParent
                val privateNode = hidden || node.isPassword || sensitive.containsMatchIn("$rid $description $text $hint")
                if (!privateNode && node.isVisibleToUser && node.isEnabled && nodes.size < 100) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (text.isNotBlank() || description.isNotBlank() || hint.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable || node.isLongClickable || node.isCheckable || node.rangeInfo!=null) {
                        val id = "n" + nodes.size
                        val canSearch = Build.VERSION.SDK_INT >= 30 && node.isEditable &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id } &&
                            Regex("search|搜索|查找", RegexOption.IGNORE_CASE).containsMatchIn("$hint $description $rid")
                        val range=node.rangeInfo
                        val canProgress=range!=null && node.actionList.any { it.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }
                        val state=(if(Build.VERSION.SDK_INT>=30) node.stateDescription?.toString().orEmpty() else "").ifBlank {
                            when { node.isCheckable -> if(node.isChecked) "已开启/已选中" else "已关闭/未选中"
                                range!=null -> "当前值 ${range.current}，范围 ${range.min} 至 ${range.max}"; else -> "" }
                        }
                        nodes += ScreenNode(id, text.take(400), description.take(250), rid,
                            node.isClickable, node.isEditable, node.isScrollable,
                            hint = hint.take(120), clickTargetId = if (node.isClickable) id else clickableParent,
                            longClickable = node.isLongClickable, canSubmitSearch = canSearch,
                            contextHeader = !node.isEditable && bounds.centerY() in 0..(resources.displayMetrics.heightPixels * .28f).toInt(),
                            checkable=node.isCheckable,checked=node.isChecked,selected=node.isSelected,stateDescription=state.take(160),
                            rangeMin=range?.min,rangeMax=range?.max,rangeValue=range?.current,canSetProgress=canProgress,
                            rangeIsInteger=range?.type==AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT)
                        if (node.isClickable) nextClickable = id
                        paths[id] = path
                        signatures += "$path|$text|$description|$hint|$rid|$bounds|${node.isClickable}|${node.isEditable}|${node.isScrollable}|${node.isLongClickable}|$canSearch|${node.isCheckable}|${node.isChecked}|${node.isSelected}|$state|${range?.min}|${range?.max}|${range?.current}|$canProgress"
                    }
                }
                if (!privateNode) for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { visit(child, path + i, depth + 1, false, nextClickable) } finally { child.recycle() }
                }
            }
            visit(root, emptyList(), 0, false)
            // Never expose our API-key/settings/chat screens to the planner.
            val isControl = pkg == packageName && !practice
            if (isControl) { nodes.clear(); paths.clear(); signatures.clear() }
            val bytes = "$pkg|${root.windowId}|${resources.configuration.orientation}|${signatures.joinToString()}".toByteArray()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            ScreenSnapshot(UUID.randomUUID().toString(), pkg, System.currentTimeMillis(), fingerprint, nodes,
                if (isControl) "当前是 Agent 控制页，内容不提供给模型。请 OPEN_APP 到目标应用；自身包名会打开练习页面。"
                else "仅提供可访问的可见节点；最多 100 个节点，每段文本最多 400 字符。",
                appVersion = runCatching {
                    val info = packageManager.getPackageInfo(pkg, 0)
                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
                }.getOrDefault(""),systemTargets=systemBridge.targets(allowed),localTime=java.time.ZonedDateTime.now().toString()
            ).also { lastScreen = it }
        } finally { root.recycle() }
    }

    override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean = withContext(Dispatchers.Main.immediate) {
        check(lastScreen?.id == screen.id && action.snapshotId == screen.id) { "快照失效" }
        val run = PhoneController.run.value
        val live = observe(run.allowedPackages)
        check(live.fingerprint == screen.fingerprint) { "执行前页面已变化，未执行此步骤" }
        check(PhoneController.busy) { "任务已停止" }
        // The last step is the persisted intent for THIS call, not a prior send to replay.
        val pending = run.steps.lastOrNull()
        check(pending != null && !pending.dispatched && pending.action.copy(snapshotId = action.snapshotId) == action) { "执行意图已变化" }
        PhonePolicy.validate(action.copy(snapshotId = live.id), live, run.allowedPackages, run.messageRequest, run.steps.dropLast(1))?.let { error(it) }
        if (action.type == PhoneActionType.WAIT) return@withContext true
        if(action.type==PhoneActionType.OPEN_SETTINGS || action.type in SystemPhoneActions.creates) {
            val intent=systemBridge.build(action,run.allowedPackages,screen.systemTargets) ?: return@withContext false
            startActivity(intent)
            return@withContext true
        }
        if (action.type == PhoneActionType.COMPOSE_SMS) {
            val request = run.messageRequest ?: return@withContext false
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", request.recipient, null))
                .setPackage(request.packageName).putExtra("sms_body", request.body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) == null) return@withContext false
            startActivity(intent)
            return@withContext true
        }
        if (action.type == PhoneActionType.OPEN_APP) {
            val intent = if (action.packageName == packageName) Intent(this@PhoneAccessibilityService, PlaygroundActivity::class.java)
            else packageManager.getLaunchIntentForPackage(action.packageName) ?: return@withContext false
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext true
        }
        if (action.type == PhoneActionType.BACK) return@withContext performGlobalAction(GLOBAL_ACTION_BACK)
        val descriptor=screen.nodes.find { it.id==action.nodeId }
        if(action.type==PhoneActionType.SET_CHECKED && descriptor?.checked==action.checked) return@withContext true
        val targetId=if(action.type==PhoneActionType.SET_CHECKED && descriptor?.clickable==false) descriptor.clickTargetId else action.nodeId
        val path = paths[targetId] ?: return@withContext false
        var node = appRoot() ?: return@withContext false
        try {
            for (index in path) {
                val child = node.getChild(index) ?: return@withContext false
                node.recycle()
                node = child
            }
            if (node.isPassword || !node.isVisibleToUser || !node.isEnabled) return@withContext false
            when (action.type) {
                PhoneActionType.TAP, PhoneActionType.SEND_MESSAGE -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                PhoneActionType.SET_CHECKED -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                PhoneActionType.SET_PROGRESS -> {
                    val range=node.rangeInfo ?: return@withContext false
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {
                        putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,range.min+(range.max-range.min)*action.value.toFloat()/100f)
                    })
                }
                PhoneActionType.LONG_PRESS -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                PhoneActionType.SUBMIT_SEARCH -> if (Build.VERSION.SDK_INT >= 30) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
                PhoneActionType.TYPE -> { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS); node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                }) }
                PhoneActionType.SCROLL -> node.performAction(if (action.direction == "backward")
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                else -> false
            }
        } finally { node.recycle() }
    }

    override suspend fun awaitChange() {
        val started = SystemClock.elapsedRealtime()
        delay(250)
        while (SystemClock.elapsedRealtime() - started < 2500 && SystemClock.elapsedRealtime() - lastChange < 350) delay(100)
    }

    fun showOverlay(run: PhoneRun, action: PhoneAction?, screen: ScreenSnapshot?) {
        // Ordinary status updates should not remove/recreate an accessibility window each time.
        if (action == null && overlay != null && overlayProgress != null) {
            val message = systemText(run.message).take(220)
            if (overlayProgress?.text?.toString() != message) overlayProgress?.text = message
            return
        }
        hideOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun background(color: Int, radius: Int = 18) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), 0xFFE5E5E5.toInt())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = background(0xFFFAFAFA.toInt())
            elevation = dp(10).toFloat()
        }
        fun label(value: String) = TextView(this).apply {
            text = value; setTextColor(0xFF202123.toInt()); textSize = 12f
        }
        val status = if (action?.type == PhoneActionType.SEND_MESSAGE) tr("确认发送这条消息", "Confirm this message") else if (action != null) tr("请确认下一步", "Confirm next step") else tr("正在执行")
        panel.addView(label("LifeBuddy · $status").apply {
            textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (action != null) {
            val node = screen?.nodes?.find { it.id == action.nodeId }
            val request = run.messageRequest
            val detail = if (action.type in setOf(PhoneActionType.SEND_MESSAGE, PhoneActionType.COMPOSE_SMS) && request != null)
                "应用：${request.packageName}\n收件人：${request.recipient}\n正文：${request.body}\n" +
                    if (action.type == PhoneActionType.SEND_MESSAGE) "确认后将尝试发送一次。请核对下方应用页面。" else "仅打开草稿，不会直接发送。"
            else "${action.reason}\n应用：${screen?.packageName}\n" +
                "目标：${action.packageName.ifBlank { node?.text?.ifBlank { node.description } ?: action.nodeId }}\n" +
                (if (action.type == PhoneActionType.TYPE) "将替换输入为：${action.text}" else "")
            panel.addView(ScrollView(this).apply { addView(label(detail).apply { setPadding(0, dp(8), 0, dp(8)) }) },
                LinearLayout.LayoutParams(-1, dp(if (action.type in setOf(PhoneActionType.TYPE, PhoneActionType.SEND_MESSAGE, PhoneActionType.COMPOSE_SMS)) 135 else 76)))
        } else panel.addView(label(systemText(run.message).take(220)).apply { setPadding(0, dp(8), 0, dp(8)); overlayProgress=this })
        val buttons = LinearLayout(this)
        fun button(title: String, primary: Boolean = false, click: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = title; textSize = 13f; isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF202123.toInt())
                background = background(if (primary) 0xFF202123.toInt() else 0xFFF0F0F0.toInt(), 22)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(6), dp(3), 0) })
        }
        if (action != null) button(if (action.type == PhoneActionType.SEND_MESSAGE) tr("确认发送", "Confirm send") else tr("允许本步", "Allow step"), true) { PhoneController.approve() }
        button(tr("暂停")) { PhoneController.pause("用户暂停；请检查当前页面后重新开始") }
        button(tr("停止")) { PhoneController.stop() }
        panel.addView(buttons)
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(12) }
        wm.addView(panel, params)
        overlay = panel
    }

    fun hideOverlay() {
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
        overlayProgress = null
    }

    fun returnToAgent(runId: String) {
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TASK_ID, runId))
    }
}
