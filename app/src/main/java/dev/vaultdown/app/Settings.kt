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
    @Synchronized fun session(): GitHubSession? {
        val payload = prefs.getString("oauth_session", null) ?: prefs.getString("token", null) ?: return null
        val raw = try {
            val parts = payload.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("Unlock your device or sign in to GitHub again.") }
        val session = if (prefs.contains("oauth_session")) GitHubSession.fromJson(JSONObject(raw)) else GitHubSession(raw)
        if (session.expiresAt != 0L && session.expiresAt <= System.currentTimeMillis() + 60_000) {
            val renewed = GitHubLogin.refresh(session)
            check(prefs.edit().putString("oauth_session", encrypt(renewed.json().toString())).remove("token").commit()) {
                "Could not save the renewed GitHub session. Sign in again."
            }
            return renewed
        }
        return session
    }
    @Synchronized fun token(): String? = session()?.access
    private fun encrypt(raw: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(raw.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    @Synchronized fun save(config: RepoConfig, session: GitHubSession) {
        config.validate()
        require(session.access.isNotBlank() && session.access.all { it.code in 33..126 }) { "Sign in to GitHub again." }
        val configJson = JSONObject().put("owner", config.owner).put("repo", config.repo).put("branch", config.branch)
        check(prefs.edit().putString("config", configJson.toString())
            .putString("oauth_session", encrypt(session.json().toString())).remove("token").commit()) {
            "Could not save connection settings."
        }
    }
    @Synchronized fun pendingDeviceAuthorization(): GitHubDeviceAuthorization? {
        val payload = prefs.getString("oauth_device", null) ?: return null
        val auth = try { GitHubDeviceAuthorization.fromJson(JSONObject(decrypt(payload))) }
        catch (_: Exception) { clearDeviceAuthorization(); return null }
        return auth.takeIf { it.expiresAt > System.currentTimeMillis() } ?: run { clearDeviceAuthorization(); null }
    }
    @Synchronized fun saveDeviceAuthorization(auth: GitHubDeviceAuthorization) {
        check(prefs.edit().putString("oauth_device", encrypt(auth.json().toString())).commit())
    }
    @Synchronized fun clearDeviceAuthorization() { prefs.edit().remove("oauth_device").commit() }
    private fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun disconnect() {
        check(prefs.edit().remove("token").remove("oauth_session").remove("oauth_device").remove("config").commit())
    }
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
