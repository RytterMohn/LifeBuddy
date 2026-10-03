package dev.ondevice.gemma.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import dev.ondevice.gemma.app.data.ExtensionStore
import dev.ondevice.gemma.app.data.HistoryStore
import dev.ondevice.gemma.app.i18n.AppLanguage
import dev.ondevice.gemma.app.i18n.tr
import dev.ondevice.gemma.extensions.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Isolated extension files and credentials. Never invokes a real remote service. */
class ExtensionSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() { Thread {
        val checks = mutableListOf<String>(); var stage = "start"
        var status = 0; val output = Bundle()
        val namespace = "extension-smoke-${UUID.randomUUID()}"
        val root = File(targetContext.cacheDir, namespace).apply { mkdirs() }
        val isolated = object : ContextWrapper(targetContext) { override fun getNoBackupFilesDir() = root }
        val previousLanguage = AppLanguage.selection
        try {
            runBlocking { withTimeout(60_000) {
                val secret = "synthetic-smoke-\"credential\"-18"
                var calls = 0; var echoCatalog = false
                val transport = McpTransport { _, headers, request ->
                    check(headers["Authorization"] == "Bearer $secret")
                    val method = request.getValue("method").jsonPrimitive.content
                    val result = when (method) {
                        "initialize" -> buildJsonObject { put("protocolVersion", "2025-11-25"); putJsonObject("capabilities") { putJsonObject("tools") {} } }
                        "notifications/initialized" -> JsonObject(emptyMap())
                        "tools/list" -> buildJsonObject { putJsonArray("tools") { add(buildJsonObject {
                            put("name", "echo"); put("description", if (echoCatalog) secret else "Echo the synthetic fixture")
                            putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }; putJsonArray("required") { add("text") } }
                        }) } }
                        "tools/call" -> { calls++; buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "fixture-ok $secret") }) } } }
                        else -> error("Unexpected method")
                    }
                    McpResponse(200, mapOf("Mcp-Session-Id" to "fixture-session"), buildJsonObject {
                        put("jsonrpc", "2.0"); request["id"]?.let { put("id", it) }; put("result", result)
                    }.toString())
                }
                stage = "skill persistence"
                var store = ExtensionStore(isolated, credentialNamespace = namespace, transport = transport)
                store.importSkills(SkillImporter.import("---\nname: smoke-plan\ndescription: 中英文测试 / bilingual fixture\n---\nPlan a small task.".toByteArray(), "SKILL.md"))
                store = ExtensionStore(isolated, credentialNamespace = namespace, transport = transport)
                check(store.state.value.skills.single().name == "smoke-plan")
                check("Plan a small task" in store.session(false).read("skill:smoke-plan"))
                store.changeSkill("smoke-plan"); check(!store.session(false).available)
                store.changeSkill("smoke-plan"); check(store.session(false).available)
                checks += "Imported skill survives reopen; enable/disable applies immediately"
                stage = "credential encryption"
                store.connect(listOf(McpImport("Fixture", "https://fixture.example/mcp", mapOf("Authorization" to "Bearer $secret"))))
                val server = store.state.value.servers.single(); val id = "mcp:${server.id}:echo"
                val encrypted = targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).getString(server.id, null)!!
                check(secret !in encrypted && secret !in File(root, "extensions-v1.json").readText())
                store = ExtensionStore(isolated, credentialNamespace = namespace, transport = transport)
                val port = store.session(true); val args = buildJsonObject { put("text", "synthetic") }
                check(runCatching { port.validateCall(id, args) }.isFailure)
                port.read(id); val result = port.call(id, args)
                check(calls == 1 && secret !in result.text && "credential redacted" in result.text)
                check("mcp:" !in store.session(false).search(""))
                check(runCatching { store.session(false).read(id) }.isFailure)
                checks += "Android Keystore encrypts headers; credentials survive reopen, stay out of catalogs and are redacted from tool output; chat excludes MCP"
                stage = "catalog leak rejection"
                echoCatalog = true
                check(runCatching { store.refresh(server.id) }.isFailure)
                check(store.state.value.servers.single().tools.single().description != secret)
                echoCatalog = false
                store.changeServer(server.id); check(runCatching { port.read(id) }.isFailure)
                store.changeServer(server.id, remove = true); store.changeSkill("smoke-plan", remove = true)
                check(!store.session(true).available && targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).getString(server.id, null) == null)
                checks += "Credential-bearing catalog rejected; disabled and removed tools cannot be read or called"
            } }
            stage = "language UI and history"
            val history = HistoryStore.get(targetContext)
            val selected = history.selectedId
            val records = history.conversations.value.map { history.load(it.id) }
            runOnMainSync { AppLanguage.select(targetContext, "en") }
            sendStatus(1, Bundle().apply { putString("stage", "ui_ready") })
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            check(awaitNode("Open sidebar") != null)
            click("Open sidebar"); click("Settings")
            check(awaitNode("App language") != null)
            click("中文")
            check(awaitNode("界面语言") != null)
            check(!AppLanguage.english && tr("设置") == "设置")
            click("English")
            check(awaitNode("App language") != null && AppLanguage.english)
            check(!activity.isFinishing && history.selectedId == selected)
            check(history.conversations.value.map { history.load(it.id) } == records)
            checks += "Actual settings UI switches English → Chinese → English without Activity recreation or any selected-conversation/history change"
            stage = "extension UI"
            var extensions = awaitNode("Extensions", 500)
            repeat(5) {
                if (extensions == null) { scroll(); Thread.sleep(200); extensions = awaitNode("Extensions", 500) }
            }
            click("Extensions")
            check(awaitNode("Import skill file") != null)
            capture("extensions-en.png")
            runOnMainSync { AppLanguage.select(targetContext, "zh") }; waitForIdleSync()
            check(awaitNode("导入技能文件") != null); capture("extensions-zh.png")
            runOnMainSync { AppLanguage.select(targetContext, "en") }; waitForIdleSync()
            click("MCP · 0")
            check(awaitNode("Connect MCP service") != null && awaitNode("Import MCP JSON") != null)
            checks += "Extensions screen exposes skill import, MCP connect and JSON import"
            output.putString("result", "PASS"); output.putString("checks", checks.joinToString("\n"))
        } catch (error: Throwable) {
            status = 1; output.putString("result", "FAIL at $stage: ${error.javaClass.simpleName}"); output.putString("checks", checks.joinToString("\n"))
        } finally {
            runOnMainSync { AppLanguage.select(targetContext, previousLanguage) }
            targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
        finish(status, output)
    }.start() }

    private fun capture(name: String) {
        Thread.sleep(250)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun find(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
        for (i in 0 until root.childCount) find(root.getChild(i), label)?.let { return it }
        return null
    }
    private fun awaitNode(label: String, timeout: Long = 3500): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeout
        do { find(uiAutomation.rootInActiveWindow, label)?.let { return it }; Thread.sleep(80) } while (System.currentTimeMillis() < deadline)
        return null
    }
    private fun click(label: String) {
        var node = checkNotNull(awaitNode(label)) { "Missing UI label" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync(); Thread.sleep(200)
    }
    private fun scroll() {
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) if (visit(node.getChild(i))) return true
            return false
        }
        visit(uiAutomation.rootInActiveWindow)
    }
}
