package dev.ondevice.gemma.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudConfig(val baseUrl: String = "", val model: String = "", val apiKey: String = "")

/** API keys are encrypted with a non-exportable Android Keystore key. Never included in backups. */
class AgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
    private val alias = "mobile-agent-api-key-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): CloudConfig {
        val encrypted = prefs.getString("key", "").orEmpty()
        val plain = if (encrypted.isEmpty()) "" else runCatching {
            val pieces = encrypted.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        return CloudConfig(prefs.getString("url", "").orEmpty(), prefs.getString("model", "").orEmpty(), plain)
    }

    fun save(config: CloudConfig) {
        val value = if (config.apiKey.isBlank()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        check(prefs.edit().putString("url", config.baseUrl.trim()).putString("model", config.model.trim())
            .putString("key", value).commit()) { "保存设置失败" }
    }

    fun allowedPackages(): Set<String> = prefs.getStringSet("allowed", emptySet()).orEmpty().toSet()
    fun saveAllowed(packages: Set<String>) { prefs.edit().putStringSet("allowed", packages).apply() }
    fun restrictApps(): Boolean = prefs.getBoolean("restrict_apps", allowedPackages().isNotEmpty())
    fun saveRestrictApps(value: Boolean) { prefs.edit().putBoolean("restrict_apps", value).apply() }
    fun interactionMode(): String = if (prefs.getString("interaction_mode", "chat") == "operate") "operate" else "chat"
    fun saveInteractionMode(value: String) { require(value in setOf("chat", "operate")); prefs.edit().putString("interaction_mode", value).apply() }
    fun learningEnabled(): Boolean = prefs.getBoolean("task_learning", true)
    fun saveLearningEnabled(value: Boolean) { prefs.edit().putBoolean("task_learning", value).apply() }
}
