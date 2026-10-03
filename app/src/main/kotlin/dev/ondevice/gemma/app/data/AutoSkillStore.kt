package dev.ondevice.gemma.app.data

import android.content.Context
import android.util.AtomicFile
import dev.ondevice.gemma.learning.*
import dev.ondevice.gemma.phone.PhoneRun
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Queue and revisions commit together; restart can resume without replaying a phone action or API call. */
class AutoSkillStore(context: Context, filename: String = "auto-skills.json", private val enabled: () -> Boolean = { AgentSettings(context).learningEnabled() }) {
    private val file=AtomicFile(File(context.noBackupFilesDir, filename))
    private val json=Json { ignoreUnknownKeys=true }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var worker: Job? = null
    private val _state=MutableStateFlow(AutoSkillState())
    val state=_state.asStateFlow()
    private val _notice=MutableStateFlow("")
    val notice=_notice.asStateFlow()
    init { runCatching { _state.value=read() }.onFailure { _notice.value="自动技能读取失败，原文件保留" } }
    @Synchronized private fun read(): AutoSkillState {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return AutoSkillState()
        return json.decodeFromString<AutoSkillState>(String(file.readFully(), Charsets.UTF_8)).also(AutoSkillBook::validate)
    }
    private fun write(state: AutoSkillState) {
        AutoSkillBook.validate(state)
        val out=file.startWrite()
        try { out.write(Json.encodeToString(state).toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch(error: Exception) { file.failWrite(out); throw error }
        _state.value=state
    }
    @Synchronized fun record(run: PhoneRun) {
        if(!enabled()) return
        runCatching {
            val old=read(); val next=AutoSkillBook.record(old, run)
            if(next!=old) write(next)
            if(next.jobs.isNotEmpty()) _notice.value="正在整理已完成任务的技能"
        }.onFailure { _notice.value="技能队列保存失败，任务结果不受影响" }
    }
    /** Called after return-to-Agent and on process startup; never performs a phone action. */
    @Synchronized fun kick() {
        if(worker?.isActive==true || !enabled()) return
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var failed=false
            try { while(enabled()) {
                val processed=synchronized(this@AutoSkillStore) {
                    val old=read()
                    if(old.jobs.isEmpty()) false else {
                        val next=AutoSkillBook.processNext(old)
                        write(next)
                        val changed=next.skills.firstOrNull { skill -> old.skills.none { it==skill } }
                        _notice.value=if(changed==null) "技能整理已完成" else "已学会：${changed.title}"
                        true
                    }
                }
                if(!processed) break
                yield()
            } } catch(_: Exception) { failed=true; _notice.value="技能整理未完成，队列已保留，可稍后重试" }
            finally { synchronized(this@AutoSkillStore) {
                worker=null
                if(!failed && enabled() && _state.value.jobs.isNotEmpty()) kick()
            } }
        }
        worker=pending
        pending.start()
    }
    @Synchronized fun change(id: String, operation: String, notes: String = "") { write(AutoSkillBook.change(read(), id, operation, notes)) }
    @Synchronized fun snapshot() = read()
    suspend fun awaitIdle() { while(true) { val current=synchronized(this) { worker } ?: break; current.join() } }
    companion object {
        @Volatile private var instance: AutoSkillStore? = null
        fun get(context: Context): AutoSkillStore = instance ?: synchronized(this) {
            instance ?: AutoSkillStore(context.applicationContext).also { instance=it }
        }
    }
}
