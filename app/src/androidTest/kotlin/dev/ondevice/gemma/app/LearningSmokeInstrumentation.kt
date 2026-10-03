package dev.ondevice.gemma.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import dev.ondevice.gemma.app.data.*
import dev.ondevice.gemma.learning.*
import dev.ondevice.gemma.phone.*
import kotlinx.serialization.json.*
import java.io.File

/** No model, accessibility, real App contents or production histories are needed or read. */
class LearningSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        Thread {
            val result = Bundle()
            val checks = mutableListOf<String>()
            var exitCode = 0
            var stage = "create isolated stores"
            val root = File(targetContext.cacheDir, "learning-alpha09-test").apply { deleteRecursively(); mkdirs() }
            val isolated = object : ContextWrapper(targetContext) { override fun getNoBackupFilesDir() = root }
            var history: HistoryStore? = null
            try {
                var store = LearningStore(isolated)
                history = HistoryStore(isolated, "learning-smoke-history.db")
                val memory = ConversationMemory(AgentMemoryStore(isolated), history, { "synthetic" }, store::confirmedHabits)
                val quote = "我通常喜欢清淡口味"
                stage = "pending habit and chat isolation"
                check("待用户" in store.proposePreference(listOf(quote), quote, "", "synthetic-habit"))
                check(store.state.value.entries.single().status == "pending")
                check(store.context("午餐", "test.shop", setOf("test.shop")) { "12" }.text.isEmpty())
                check(quote !in memory.snapshot())
                val habitId = store.state.value.entries.single().id
                store.change(habitId, "edit", "我偏好少油清淡")
                check("少油清淡" in memory.snapshot())
                check(quote !in memory.snapshot())
                check("已由用户确认" in store.proposePreference(listOf(quote), quote, "", "synthetic-habit-2"))
                checks += "pending habit excluded from phone and chat; correction activates preference; duplicate keeps correction"

                stage = "reopen and disable"
                store = LearningStore(isolated)
                check(store.state.value.entries.single().text == "我偏好少油清淡")
                check(store.state.value.entries.single().sourceRuns.size == 2)
                store.change(habitId, "disable")
                check(store.confirmedHabits() == "[]")
                check(store.context("午餐", "test.shop", setOf("test.shop")) { "12" }.text.isEmpty())
                store.change(habitId, "delete")
                store.proposePreference(listOf(quote), quote, "", "synthetic-habit-3")
                check(store.state.value.entries.isEmpty())
                checks += "AtomicFile survives reopen; disable stops retrieval; deleted candidate is not relearned"

                stage = "route and source history persistence"
                val navigation = NavigationHint("test.shop", "12", "合成商店", "搜索", "test.shop:id/search")
                val run = PhoneRun("synthetic-route", "搜索测试物品", RunStatus.COMPLETED, steps=listOf(
                    PhoneStep(1, PhoneAction("old-screen", PhoneActionType.TAP, "搜索", "old-node"), true, "变化", navigation=navigation),
                    PhoneStep(2, PhoneAction("old-screen", PhoneActionType.TYPE, "输入", "old-node", text="SYNTHETIC_PRIVATE_INPUT"), true, "读回", navigation=navigation.copy(label="")),
                    PhoneStep(3, PhoneAction("old-screen", PhoneActionType.FINISH, "找到", evidence="合成结果"))))
                history.saveRun(run, ConversationKind.TASK)
                store.record(run)
                val entry = store.state.value.entries.single()
                check(history.load(entry.sourceRuns.single())!!.run!!.steps.first().navigation == navigation)
                check("SYNTHETIC_PRIVATE_INPUT" !in File(root, "task-learning.json").readText())
                check("old-node" !in File(root, "task-learning.json").readText())
                check(store.context("搜索", "test.shop", setOf("test.shop")) { "12" }.ids == listOf(entry.id))
                check(store.context("搜索", "test.shop", setOf("test.shop")) { "13" }.ids.isEmpty())
                checks += "successful route and source history persisted without input values or old node IDs; version mismatch excluded"

                stage = "quarantine and recovery"
                val failed = run.copy(id="synthetic-failure-1", status=RunStatus.FAILED, steps=run.steps.map {
                    it.copy(action=it.action.copy(experienceIds=listOf(entry.id))) })
                store.record(failed); store.record(failed.copy(id="synthetic-failure-2"))
                check(store.state.value.entries.single().status == "review")
                check(store.context("搜索", "test.shop", setOf("test.shop")) { "12" }.ids.isEmpty())
                store.change(entry.id, "activate")
                check(store.context("搜索", "test.shop", setOf("test.shop")) { "12" }.ids.size == 1)
                history.delete(run.id)
                check(history.load(entry.sourceRuns.single()) == null && store.state.value.entries.size == 1)
                store.change(entry.id, "delete"); store.record(run.copy(id="synthetic-repeat"))
                check(LearningStore(isolated).state.value.entries.isEmpty())
                checks += "two referenced failures suspend route; explicit reenable works; source deletion independent; route deletion persists"

                stage = "corrupt storage fail closed"
                val broken = File(root, "broken.json").apply { writeText("{broken synthetic file") }
                val damaged = LearningStore(isolated, "broken.json")
                check(damaged.notice.value.isNotBlank())
                damaged.record(run)
                check(damaged.context("搜索", "test.shop", setOf("test.shop")) { "12" }.ids.isEmpty())
                check(broken.readText() == "{broken synthetic file")
                checks += "corrupt notebook preserves original bytes and falls back to fresh planning"

                File(targetContext.noBackupFilesDir, "learning-alpha09-result.json").writeText(buildJsonObject {
                    put("success", true); put("realApi", false); put("realAppActions", 0)
                    put("androidSdk", android.os.Build.VERSION.SDK_INT)
                    putJsonArray("checks") { checks.forEach { add(it) } }
                }.toString())
                result.putString("result", "PASS: " + checks.joinToString("; "))
            } catch (error: Exception) {
                result.putString("result", "FAIL at $stage: ${error.javaClass.simpleName}")
                exitCode = 1
            } finally {
                history?.close(); root.deleteRecursively()
                targetContext.deleteSharedPreferences("learning-smoke-history.db-state")
            }
            finish(exitCode, result)
        }.start()
    }
}
