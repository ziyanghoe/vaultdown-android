package dev.vaultdown.app

import android.os.SystemClock
import dev.vaultdown.core.DevicePoll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Never a data class: generated toString must not expose credentials.
class GitHubSession(val access: String, val refresh: String? = null, val expiresAt: Long = 0) {
    fun json() = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt)
    companion object {
        fun fromJson(j: JSONObject) = GitHubSession(j.getString("access"),
            j.optString("refresh").takeIf { it.isNotBlank() && it != "null" }, j.optLong("expiresAt"))
    }
}
data class GitHubRepo(val owner: String, val name: String, val branch: String, val privateRepo: Boolean) {
    val fullName get() = "$owner/$name"
}
data class GitHubPage<T>(val items: List<T>, val more: Boolean)
data class GitHubDeviceAuthorization(val deviceCode: String, val userCode: String, val expiresAt: Long, val interval: Long) {
    fun json() = JSONObject().put("deviceCode", deviceCode).put("userCode", userCode)
        .put("expiresAt", expiresAt).put("interval", interval)
    companion object {
        fun fromJson(j: JSONObject) = GitHubDeviceAuthorization(j.getString("deviceCode"), j.getString("userCode"),
            j.getLong("expiresAt"), j.getLong("interval"))
    }
}

object GitHubLogin {
    const val VERIFY_URL = "https://github.com/login/device"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    /** OAuth device endpoints use JSON error bodies for expected pending/slow-down states. */
    private fun exchange(path: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return client.newCall(Request.Builder().url("https://github.com/$path")
            .header("Accept", "application/json").post(body).build()).execute().use { response ->
            val source = response.body?.source() ?: throw GitHubFailure("GitHub returned an empty sign-in response.")
            if (source.request(64 * 1024L + 1)) throw GitHubFailure("GitHub sign-in response is too large.")
            val text = source.readUtf8()
            try { JSONObject(text) } catch (_: Exception) {
                throw GitHubFailure("GitHub sign-in returned HTTP ${response.code}. Please try again.")
            }
        }
    }
    private fun execute(request: Request): Pair<String, Boolean> = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw GitHubFailure(when(response.code) {
            401 -> "Your GitHub session expired. Sign in again."
            403 -> "GitHub denied access. Check organization approval or try again after the rate limit resets."
            404 -> "Repository is no longer available to this account."
            429 -> "GitHub is limiting requests. Please try again later."
            else -> "GitHub returned HTTP ${response.code}. Please try again."
        })
        val source = response.body?.source() ?: throw GitHubFailure("GitHub returned an empty response.")
        if (source.request(8L * 1024 * 1024 + 1)) throw GitHubFailure("GitHub response is too large.")
        source.readUtf8() to response.headers("Link").any { it.contains("rel=\"next\"") }
    }
    private fun api(token: String, path: List<String>, page: Int? = null): Pair<String, Boolean> {
        val url = "https://api.github.com".toHttpUrl().newBuilder()
        path.forEach { url.addPathSegment(it) }
        if (page != null) { url.addQueryParameter("per_page", "100"); url.addQueryParameter("page", page.toString()) }
        if (path == listOf("user", "repos")) { url.addQueryParameter("sort", "full_name"); url.addQueryParameter("direction", "asc") }
        return execute(Request.Builder().url(url.build()).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Vaultdown-Android").build())
    }
    private fun session(j: JSONObject): GitHubSession {
        val access = j.optString("access_token")
        require(access.isNotEmpty() && access.all { it.code in 33..126 }) { "GitHub did not return a valid session. Sign in again." }
        val seconds = j.optLong("expires_in", 0)
        return GitHubSession(access, j.optString("refresh_token").takeIf { it.isNotBlank() },
            if (seconds > 0) System.currentTimeMillis() + seconds * 1000 else 0)
    }
    fun refresh(old: GitHubSession): GitHubSession {
        val refresh = old.refresh ?: throw GitHubFailure("Your GitHub session expired. Sign in again.")
        val j = exchange("login/oauth/access_token", mapOf("client_id" to BuildConfig.GITHUB_CLIENT_ID,
            "grant_type" to "refresh_token", "refresh_token" to refresh))
        if (j.has("error")) throw GitHubFailure("Your GitHub session expired. Sign in again.")
        return session(j)
    }
    fun beginDeviceAuthorization(): GitHubDeviceAuthorization {
        require(BuildConfig.GITHUB_CLIENT_ID.isNotBlank()) { "GitHub sign-in is not configured in this build." }
        val j = exchange("login/device/code", mapOf("client_id" to BuildConfig.GITHUB_CLIENT_ID, "scope" to "repo"))
        if (j.has("error")) throw GitHubFailure(if (j.optString("error") == "device_flow_disabled")
            "Enable Device flow in the Vaultdown OAuth app, then try again." else "GitHub sign-in could not start. Check the OAuth app configuration.")
        check(j.getString("verification_uri") == VERIFY_URL) { "GitHub returned an unexpected sign-in address." }
        val seconds = j.getLong("expires_in")
        require(seconds in 1..900) { "GitHub returned an invalid sign-in lifetime." }
        return GitHubDeviceAuthorization(j.getString("device_code"), j.getString("user_code"),
            System.currentTimeMillis() + seconds * 1000, j.optLong("interval", 5).coerceIn(5, 60))
    }
    suspend fun finishDeviceAuthorization(auth: GitHubDeviceAuthorization): GitHubSession {
        val remaining = (auth.expiresAt - System.currentTimeMillis()) / 1000
        val poll = DevicePoll(SystemClock.elapsedRealtime(), remaining, auth.interval)
        while (true) {
            delay(poll.delayMillis())
            poll.checkActive(SystemClock.elapsedRealtime())
            val result = withContext(Dispatchers.IO) { exchange("login/oauth/access_token", mapOf(
                "client_id" to BuildConfig.GITHUB_CLIENT_ID, "device_code" to auth.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")) }
            kotlin.coroutines.coroutineContext.ensureActive()
            poll.checkActive(SystemClock.elapsedRealtime())
            if (!result.has("error")) return session(result)
            poll.accept(result.getString("error"), result.optLong("interval", 0))
        }
    }
    fun identity(token: String) = JSONObject(api(token, listOf("user")).first).getString("login")
    fun repositories(token: String, page: Int): GitHubPage<GitHubRepo> {
        val (body, more) = api(token, listOf("user", "repos"), page)
        val rows = JSONArray(body)
        val repos = (0 until rows.length()).mapNotNull { i ->
            val r = rows.getJSONObject(i)
            if (r.optBoolean("archived") || r.optBoolean("disabled") || r.optJSONObject("permissions")?.optBoolean("push") != true) null
            else GitHubRepo(r.getJSONObject("owner").getString("login"), r.getString("name"), r.getString("default_branch"), r.getBoolean("private"))
        }
        return GitHubPage(repos, more)
    }
    fun branches(token: String, repo: GitHubRepo, page: Int): GitHubPage<String> {
        val (body, more) = api(token, listOf("repos", repo.owner, repo.name, "branches"), page)
        val rows = JSONArray(body)
        return GitHubPage((0 until rows.length()).map { rows.getJSONObject(it).getString("name") }, more)
    }
}

data class LoginUi(
    val account: String? = null, val code: String? = null, val busy: Boolean = false,
    val repos: List<GitHubRepo> = emptyList(), val moreRepos: Boolean = false,
    val selected: GitHubRepo? = null, val branches: List<String> = emptyList(),
    val branch: String? = null, val moreBranches: Boolean = false, val error: String? = null
)
