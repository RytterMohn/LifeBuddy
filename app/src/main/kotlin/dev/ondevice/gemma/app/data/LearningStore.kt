package dev.ondevice.gemma.app.data

import android.content.Context
import android.util.AtomicFile
import dev.ondevice.gemma.learning.*
import dev.ondevice.gemma.phone.PhoneLearning
import dev.ondevice.gemma.phone.PhoneRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

class LearningStore(context: Context, filename: String = "task-learning.json") : PhoneLearning {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(LearningState())
    val state = _state.asStateFlow()
    private val _notice = MutableStateFlow("")
    val notice = _notice.asStateFlow()
    init { runCatching { _state.value = read() }.onFailure { _notice.value = "学习笔记读取失败，原文件保留；手机任务仍可运行。" } }

    @Synchronized private fun read(): LearningState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return LearningState()
        return json.decodeFromString<LearningState>(String(file.readFully(), Charsets.UTF_8)).also {
            require(it.entries.size <= LearningBook.LIMIT && it.dismissed.size <= 128)
        }
    }
    private fun write(value: LearningState) {
        val output = file.startWrite()
        try { output.write(Json.encodeToString(value).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        _state.value = value
        _notice.value = ""
    }
    @Synchronized override fun proposePreference(statements: List<String>, quote: String, packageName: String, runId: String): String = try {
        val next = LearningBook.proposeHabit(read(), statements, quote, packageName, runId)
        write(next)
        val entry = next.entries.find { it.key == LearningBook.habitKey(quote, packageName) }
        if (entry == null) "未新增候选：该条已被删除，或学习笔记容量已满"
        else if (entry.status == "active") "此偏好已由用户确认，无需重复记录"
        else if (entry.status == "disabled") "此偏好已停用，保持停用状态"
        else "已记录习惯候选，待用户在“学习与经验”中确认；当前任务和后续任务不能把候选当作事实"
    } catch (error: IllegalArgumentException) { "未记录候选：${error.message}" }
      catch (_: Exception) { _notice.value = "学习笔记保存失败，未影响本次任务"; "学习笔记保存失败" }

    @Synchronized fun record(run: PhoneRun) {
        runCatching { write(LearningBook.recordOutcome(read(), run)) }
            .onFailure { _notice.value = "经验保存失败，原文件保留；本次任务结果不受影响。" }
    }
    @Synchronized fun change(id: String, operation: String, text: String = "") { write(LearningBook.change(read(), id, operation, text)) }
    @Synchronized fun confirmedHabits(): String = Json.encodeToString(read().entries.filter { it.kind == "habit" && it.status == "active" }
        .sortedByDescending { it.updatedAt }.take(8).map { mapOf("preference" to it.text, "app" to it.packageName) })
    @Synchronized fun habitContext(goal: String, currentPackage: String, allowed: Set<String>): LearningContext =
        runCatching { LearningBook.context(read().let { it.copy(entries=it.entries.filter { entry -> entry.kind=="habit" }) }, goal, currentPackage, allowed) { null } }
            .getOrElse { LearningContext("", emptyList()) }
    @Synchronized fun context(goal: String, currentPackage: String, allowed: Set<String>, version: (String) -> String?): LearningContext =
        runCatching { LearningBook.context(read(), goal, currentPackage, allowed, version) }
            .getOrElse { _notice.value = "暂时无法读取学习笔记，本次按当前页面执行"; LearningContext("", emptyList()) }

    companion object {
        @Volatile private var instance: LearningStore? = null
        fun get(context: Context): LearningStore = instance ?: synchronized(this) {
            instance ?: LearningStore(context.applicationContext).also { instance = it }
        }
    }
}
