package dev.ondevice.gemma.app.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import dev.ondevice.gemma.llm.Role
import dev.ondevice.gemma.phone.PhoneRun
import dev.ondevice.gemma.phone.RunStatus
import dev.ondevice.gemma.phone.PhoneConversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

object ConversationKind {
    const val CLOUD = "chat"
    const val LOCAL = "local"
    const val TASK = "cloud"
    const val PRACTICE = "practice"
    fun isChat(kind: String) = kind == CLOUD || kind == LOCAL
}

data class ConversationSummary(
    val id: String, val kind: String, val title: String,
    val createdAt: Long, val updatedAt: Long, val status: String,
)
data class HistoryMessage(
    val id: Long, val role: Role, val content: String,
    val isTool: Boolean = false, val state: String = "complete", val detail: String = "",
)
data class ConversationRecord(
    val summary: ConversationSummary, val messages: List<HistoryMessage>,
    val context: String, val run: PhoneRun?,
)

/** Private, local-only history. Each message is its own row; streaming never rewrites older turns. */
class HistoryStore(context: Context, databaseName: String = "conversation-history.db") : AutoCloseable {
    private val privateDirectory = context.noBackupFilesDir
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = context.getSharedPreferences("$databaseName-state", Context.MODE_PRIVATE)
    private val helper = object : SQLiteOpenHelper(context, File(context.noBackupFilesDir, databaseName).path, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE conversations (
                id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT '',
                context TEXT NOT NULL DEFAULT '[]', run TEXT)""")
            db.execSQL("""CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL, content TEXT NOT NULL, is_tool INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'complete', detail TEXT NOT NULL DEFAULT '')""")
            db.execSQL("CREATE INDEX messages_conversation ON messages(conversation_id, id)")
            db.execSQL("CREATE INDEX conversations_updated ON conversations(updated_at DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val db get() = helper.writableDatabase
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations = _conversations.asStateFlow()
    var selectedId: String
        get() = preferences.getString("selected", "").orEmpty()
        @SuppressLint("ApplySharedPref")
        set(value) {
            // Tiny navigation checkpoint must reach disk before an abrupt process exit.
            preferences.edit().putString("selected", value).commit()
        }

    init {
        // Recovery is passive: preserve partial replies and pause tasks, never resume a network/tool action.
        db.execSQL("UPDATE messages SET state='interrupted', content=CASE WHEN content='' THEN '回复在完成前中断。' ELSE content END WHERE state='streaming'")
        db.rawQuery("SELECT id, run FROM conversations WHERE run IS NOT NULL", null).use { cursor ->
            while (cursor.moveToNext()) {
                val run = json.decodeFromString<PhoneRun>(cursor.getString(1))
                if (run.status in setOf(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL)) {
                    val paused = interrupted(run)
                    db.update("conversations", ContentValues().apply {
                        put("run", json.encodeToString(paused)); put("status", paused.status.name)
                    }, "id=?", arrayOf(cursor.getString(0)))
                }
            }
        }
        refresh()
    }

    @Synchronized fun migrateLegacyRun(context: Context) {
        val legacy = AtomicFile(File(context.noBackupFilesDir, "last-phone-run.json"))
        if (!legacy.baseFile.exists() && !File(legacy.baseFile.path + ".bak").exists()) return
        val run = json.decodeFromString<PhoneRun>(String(legacy.readFully(), Charsets.UTF_8))
        if (run.id.isNotBlank() && load(run.id) == null) {
            val safeRun = if (run.status in setOf(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL)) interrupted(run) else run
            // Older versions did not save the mode; infer only the fixed built-in practice goal.
            saveRun(safeRun, if (run.goal == "在练习页面填写并保存第一版验证") ConversationKind.PRACTICE else ConversationKind.TASK,
                legacy.baseFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
        }
        legacy.delete() // only after a successful import, so relaunch cannot resurrect deleted records
    }

    @Synchronized fun createChat(kind: String, firstMessage: String): String {
        require(ConversationKind.isChat(kind))
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", id); put("kind", kind); put("title", titleFor(firstMessage))
            put("created_at", now); put("updated_at", now)
        })
        selectedId = id
        refresh()
        return id
    }

    @Synchronized fun append(id: String, role: Role, content: String, isTool: Boolean = false,
                             state: String = "complete", detail: String = ""): HistoryMessage {
        val messageId = transaction {
            val inserted = db.insertOrThrow("messages", null, ContentValues().apply {
                put("conversation_id", id); put("role", role.name); put("content", content)
                put("is_tool", if (isTool) 1 else 0); put("state", state); put("detail", detail)
            })
            db.update("conversations", ContentValues().apply { put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(id))
            inserted
        }
        if (state != "streaming") refresh()
        return HistoryMessage(messageId, role, content, isTool, state, detail)
    }

    @Synchronized fun updateMessage(messageId: Long, content: String, state: String, savedContext: String? = null, detail: String? = null) {
        transaction {
            check(db.update("messages", ContentValues().apply { put("content", content); put("state", state); if (detail != null) put("detail", detail) }, "id=?", arrayOf(messageId.toString())) == 1)
            if (savedContext != null) db.execSQL("UPDATE conversations SET context=? WHERE id=(SELECT conversation_id FROM messages WHERE id=?)", arrayOf(savedContext, messageId))
        }
    }

    @Synchronized fun removeEmptyDraft(messageId: Long) {
        db.delete("messages", "id=? AND state='streaming' AND content=''", arrayOf(messageId.toString()))
    }

    @Synchronized fun load(id: String): ConversationRecord? {
        val summary: ConversationSummary
        val savedContext: String
        val run: PhoneRun?
        db.rawQuery("SELECT * FROM conversations WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            summary = cursor.summary()
            savedContext = cursor.string("context")
            run = cursor.getString(cursor.getColumnIndexOrThrow("run"))?.let { json.decodeFromString<PhoneRun>(it) }
        }
        val messages = mutableListOf<HistoryMessage>()
        db.rawQuery("SELECT * FROM messages WHERE conversation_id=? ORDER BY id", arrayOf(id)).use { cursor ->
            while (cursor.moveToNext()) messages += HistoryMessage(cursor.long("id"), Role.valueOf(cursor.string("role")),
                cursor.string("content"), cursor.long("is_tool") == 1L, cursor.string("state"), cursor.string("detail"))
        }
        return ConversationRecord(summary, messages, savedContext, run)
    }

    @Synchronized fun saveRun(run: PhoneRun, kind: String, timestamp: Long = System.currentTimeMillis()) {
        if (run.id.isBlank()) return
        transaction {
            db.execSQL("INSERT OR IGNORE INTO conversations(id,kind,title,created_at,updated_at) VALUES(?,?,?,?,?)",
                arrayOf(run.id, kind, titleFor(run.goal), timestamp, timestamp))
            db.update("conversations", ContentValues().apply {
                put("updated_at", timestamp); put("status", run.status.name); put("run", json.encodeToString(run))
            }, "id=?", arrayOf(run.id))
        }
        refresh()
    }

    @Synchronized fun latestRun(): PhoneRun = _conversations.value.firstOrNull { !ConversationKind.isChat(it.kind) }
        ?.let { load(it.id)?.run } ?: PhoneRun()

    /** Explicit repair only. Snapshot affected rows privately before merging; never executes a task. */
    @Synchronized fun mergeTaskConversations(ids: List<String>): PhoneRun {
        require(ids.size in 2..20 && ids.distinct().size == ids.size)
        val records = ids.map { requireNotNull(load(it)) }
        require(records.all { it.summary.kind == ConversationKind.TASK && it.run != null })
        require(records.zipWithNext().all { (a, b) -> a.summary.createdAt <= b.summary.createdAt })
        val merged = PhoneConversation.merge(records.map { requireNotNull(it.run) })
        fun rows(query: String, id: String): List<JsonObject> = db.rawQuery(query, arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(buildJsonObject {
                cursor.columnNames.forEachIndexed { index, name -> put(name, cursor.getString(index)?.let(::JsonPrimitive) ?: JsonNull) }
            }) }
        }
        val backup = AtomicFile(File(privateDirectory, "conversation-merge-${UUID.randomUUID()}.json"))
        val snapshot = buildJsonObject {
            put("selectedId", selectedId)
            put("conversations", JsonArray(ids.flatMap { rows("SELECT * FROM conversations WHERE id=?", it) }))
            put("messages", JsonArray(ids.flatMap { rows("SELECT * FROM messages WHERE conversation_id=? ORDER BY id", it) }))
        }
        val output = backup.startWrite()
        try { output.write(snapshot.toString().toByteArray(Charsets.UTF_8)); backup.finishWrite(output) }
        catch (error: Exception) { backup.failWrite(output); throw error }
        transaction {
            check(db.update("conversations", ContentValues().apply {
                put("run", json.encodeToString(merged)); put("status", merged.status.name); put("updated_at", System.currentTimeMillis())
            }, "id=?", arrayOf(merged.id)) == 1)
            ids.drop(1).forEach { id ->
                db.execSQL("UPDATE messages SET conversation_id=? WHERE conversation_id=?", arrayOf(merged.id, id))
                check(db.delete("conversations", "id=?", arrayOf(id)) == 1)
            }
        }
        selectedId = merged.id
        refresh()
        return merged
    }

    @Synchronized fun rename(id: String, title: String) {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 80) { "标题须为 1–80 个字符" }
        check(db.update("conversations", ContentValues().apply { put("title", clean) }, "id=?", arrayOf(id)) == 1)
        refresh()
    }

    @Synchronized fun delete(id: String) {
        db.delete("conversations", "id=?", arrayOf(id)) // foreign key cascades messages and context belongs to the same row
        if (selectedId == id) selectedId = ""
        refresh()
    }

    /** Bounded retrieval from this conversation only. Keep complete history on disk, never replay tools. */
    @Synchronized fun searchMessages(id: String, query: String): JsonArray {
        require(query.isNotBlank() && query.length <= 80)
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val results = mutableListOf<JsonObject>()
        db.rawQuery("""SELECT id, role, content, state, is_tool, detail FROM messages WHERE conversation_id=? AND state!='streaming'
            AND (content LIKE ? ESCAPE '\' OR (is_tool=1 AND detail LIKE ? ESCAPE '\')) ORDER BY id DESC LIMIT 6""", arrayOf(id, "%$escaped%", "%$escaped%")).use { cursor ->
            while (cursor.moveToNext()) {
                val content = if (cursor.long("is_tool") == 1L) cursor.string("content") + "\n" + cursor.string("detail") else cursor.string("content")
                val start = (content.indexOf(query.trim(), ignoreCase = true) - 120).coerceAtLeast(0)
                results += buildJsonObject {
                    put("messageId", cursor.long("id")); put("role", cursor.string("role")); put("state", cursor.string("state"))
                    put("isTool", cursor.long("is_tool") == 1L)
                    put("excerpt", content.drop(start).take(650)); put("partial", start > 0 || content.length > 650)
                }
            }
        }
        return JsonArray(results.reversed())
    }

    /** Parameter binding and escaped LIKE: search input is always literal text. */
    @Synchronized fun search(query: String): List<ConversationSummary> {
        if (query.isBlank()) return _conversations.value
        val pattern = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val rows = mutableListOf<ConversationSummary>()
        db.rawQuery("""SELECT * FROM conversations c WHERE title LIKE ? ESCAPE '\'
            OR EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND (m.content LIKE ? ESCAPE '\' OR m.detail LIKE ? ESCAPE '\'))
            OR run LIKE ? ESCAPE '\' ORDER BY updated_at DESC, id""", arrayOf(pattern, pattern, pattern, pattern)).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.summary()
        }
        return rows
    }

    private fun refresh() {
        val rows = mutableListOf<ConversationSummary>()
        db.rawQuery("SELECT id,kind,title,created_at,updated_at,status FROM conversations ORDER BY updated_at DESC, id", null).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.summary()
        }
        _conversations.value = rows
    }
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    override fun close() = helper.close()
    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.summary() = ConversationSummary(string("id"), string("kind"), string("title"), long("created_at"), long("updated_at"), string("status"))

    companion object {
        @Volatile private var instance: HistoryStore? = null
        fun get(context: Context): HistoryStore = instance ?: synchronized(this) {
            instance ?: HistoryStore(context.applicationContext).also { it.migrateLegacyRun(context); instance = it }
        }
        private fun titleFor(text: String) = text.trim().replace(Regex("\\s+"), " ").take(60).ifBlank { "新对话" }
        private fun interrupted(run: PhoneRun) = run.copy(status = RunStatus.PAUSED,
            message = "上次任务被中断，外部操作结果可能待确认；请先检查手机，再重新开始。")
    }
}
