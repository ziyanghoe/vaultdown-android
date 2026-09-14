package dev.vaultdown.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class RepoConfig(val owner: String, val repo: String, val branch: String) {
    val vaultId: String get() = "${owner.lowercase(java.util.Locale.ROOT)}/${repo.lowercase(java.util.Locale.ROOT)}@$branch"
    fun validate() {
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { "Enter a GitHub username or organization." }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo !in listOf(".", "..")) { "Enter the repository name without a URL." }
        require(branch.isNotBlank() && branch.length <= 255 && !branch.any { it.code < 33 || it.code == 127 } &&
            !branch.contains("..") && !branch.contains("@{") && !branch.any { it in "~^:?*[\\" } &&
            !branch.startsWith('/') && !branch.endsWith('/') && !branch.endsWith('.') &&
            branch.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") }) { "Enter a valid existing branch name." }
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val alias = "vaultdown.github-token.v1"
    @Synchronized fun config(): RepoConfig? = prefs.getString("config", null)?.let {
        val json = JSONObject(it)
        RepoConfig(json.getString("owner"), json.getString("repo"), json.getString("branch"))
    }
    @Synchronized fun token(): String? {
        val payload = prefs.getString("token", null) ?: return null
        try {
            val parts = payload.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("Unlock your device or enter your GitHub token again in Settings.") }
    }
    @Synchronized fun save(config: RepoConfig, token: String) {
        config.validate()
        require(token.isNotBlank() && token.none { it.isWhitespace() }) { "Enter a valid GitHub token." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val configJson = JSONObject().put("owner", config.owner).put("repo", config.repo).put("branch", config.branch)
        check(prefs.edit().putString("config", configJson.toString()).putString("token", encrypted).commit()) {
            "Could not save connection settings."
        }
    }
    @Synchronized fun disconnect() { check(prefs.edit().remove("token").remove("config").commit()) }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
