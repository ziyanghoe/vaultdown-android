package dev.vaultdown.app

import android.util.Base64
import dev.vaultdown.core.Paths
import dev.vaultdown.core.SyncEngine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

class GitHubFailure(message: String, val retryable: Boolean = false) : IOException(message)

class GitHubRemote(private val config: RepoConfig, private val token: String) : SyncEngine.Remote {
    init {
        config.validate()
        require(token.isNotEmpty() && token.all { it.code in 33..126 }) {
            "Enter a valid GitHub token without spaces or line breaks."
        }
    }
    companion object {
        const val MAX_NOTE_BYTES = 1_048_576
        private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
    override fun list(): Map<String, String> {
        // A 404/409 is an error, not an empty vault. Start with an initialized GitHub repository.
        val branch = request(listOf("branches", config.branch))
        val treeSha = branch.getJSONObject("commit").getJSONObject("commit").getJSONObject("tree").getString("sha")
        val tree = request(listOf("git", "trees", treeSha), recursive = true)
        if (tree.optBoolean("truncated", false)) throw GitHubFailure("Repository tree is too large. Use a smaller notes repository; no notes were removed.")
        val entries = tree.getJSONArray("tree")
        val files = linkedMapOf<String, String>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val path = entry.getString("path")
            if (entry.getString("type") == "blob" && entry.getString("mode") in listOf("100644", "100755") && Paths.supported(path)) {
                if (entry.optLong("size", 0) > MAX_NOTE_BYTES) throw GitHubFailure("$path is larger than 1 MiB. Move it outside this notes repository to sync.")
                files[path] = entry.getString("sha")
            }
        }
        if (files.size > 2000) throw GitHubFailure("This version supports up to 2,000 Markdown notes per repository.")
        return files
    }
    override fun read(path: String, sha: String): String {
        // Read immutable blobs, never a moving branch or arbitrary download_url.
        val json = request(listOf("git", "blobs", sha))
        if (json.optString("encoding") != "base64" || json.getLong("size") > MAX_NOTE_BYTES)
            throw GitHubFailure("$path must be a UTF-8 Markdown file under 1 MiB.")
        val bytes = Base64.decode(json.getString("content"), Base64.DEFAULT)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw GitHubFailure("$path is not valid UTF-8; it was left unchanged.") }
        if (Paths.blobSha(text) != sha) throw GitHubFailure("Content verification failed for $path. Retry sync.", true)
        return text
    }
    override fun write(path: String, text: String, expectedSha: String?): String {
        Paths.validate(path)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_NOTE_BYTES) throw GitHubFailure("$path exceeds 1 MiB. Your changes remain saved on this device.")
        val payload = JSONObject().put("message", "Vaultdown: update $path").put("branch", config.branch)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        expectedSha?.let { payload.put("sha", it) }
        return request(listOf("contents") + path.split('/'), payload).getJSONObject("content").getString("sha")
    }
    private fun request(segments: List<String>, payload: JSONObject? = null, recursive: Boolean = false): JSONObject {
        val url = "https://api.github.com".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegment(config.owner).addPathSegment(config.repo)
        segments.forEach { url.addPathSegment(it) }
        if (recursive) url.addQueryParameter("recursive", "1")
        val builder = Request.Builder().url(url.build())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Vaultdown-Android")
        payload?.let { builder.put(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val limited = response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null
                val message = when {
                    limited -> "GitHub rate limit reached. Sync will retry later."
                    response.code == 401 -> "GitHub session expired or invalid. Sign in again in Settings."
                    response.code == 403 -> "GitHub denied access. Check Contents read/write permission, organization approval, and branch rules."
                    response.code == 404 -> "Repository or branch not found. Sign in again and choose an initialized branch."
                    response.code == 409 || response.code == 422 -> "GitHub changed during sync or rejected the commit. Retrying will check for conflicts; also check branch rules."
                    response.code in 300..399 -> "Repository was moved. Choose it again in Settings."
                    else -> "GitHub returned HTTP ${response.code}. Your local notes are saved."
                }
                throw GitHubFailure(message, limited || response.code in listOf(409, 422, 429) || response.code >= 500)
            }
            val body = response.body ?: throw GitHubFailure("GitHub returned an empty response.", true)
            val source = body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw GitHubFailure("GitHub response is too large; sync was stopped safely.")
            return JSONObject(source.readUtf8())
        }
    }
}
