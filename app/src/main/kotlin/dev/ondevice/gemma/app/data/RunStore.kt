package dev.ondevice.gemma.app.data

import android.content.Context
import dev.ondevice.gemma.phone.PhoneRun

/** All runs are retained in history. Restoring a run is read-only. */
class RunStore(context: Context) {
    private val history = HistoryStore.get(context)
    var kind: String = ConversationKind.PRACTICE
    fun load(): PhoneRun = history.latestRun()
    fun save(run: PhoneRun) = history.saveRun(run, kind)
    fun clear() { history.delete(load().id) }
}
