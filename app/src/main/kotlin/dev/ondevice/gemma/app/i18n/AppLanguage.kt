package dev.ondevice.gemma.app.i18n

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Changing the UI language recomposes labels without restarting a task or recreating history. */
object AppLanguage {
    var selection by mutableStateOf("system")
        private set
    private var systemLanguage by mutableStateOf("zh")
    val english: Boolean get() = when (selection) { "zh" -> false; "en" -> true; else -> systemLanguage != "zh" }
    val modelLanguage: String get() = if (english) "English" else "Chinese"
    val key: String get() = "$selection:$systemLanguage"
    fun initialize(context: Context) {
        selection = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).getString("ui_language", "system")
            ?.takeIf { it in setOf("system", "zh", "en") } ?: "system"
        systemChanged()
    }
    fun systemChanged() { systemLanguage = Resources.getSystem().configuration.locales[0].language }
    fun select(context: Context, value: String) {
        require(value in setOf("system", "zh", "en"))
        check(context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).edit().putString("ui_language", value).commit())
        selection = value
    }
}

fun tr(chinese: String, english: String = EnglishTexts.values[chinese] ?: chinese): String = if (AppLanguage.english) english else chinese

/** Only call for app-generated labels/status, never for user messages or external tool content. */
fun systemText(value: String): String {
    val chinese = EnglishTexts.reverse[value] ?: value
    if (!AppLanguage.english) return chinese
    EnglishTexts.values[chinese]?.let { return it }
    Regex("正在规划第 (\\d+) 步").matchEntire(chinese)?.let { return "Planning step ${it.groupValues[1]}" }
    if (chinese.endsWith(" · 聊天模式")) return chinese.removeSuffix(" · 聊天模式") + " · Chat"
    return chinese
}
