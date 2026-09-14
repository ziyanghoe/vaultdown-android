package dev.vaultdown.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.*
import dev.vaultdown.core.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SyncStatus(val running: Boolean = false, val message: String = "Offline workspace", val version: Long = 0)

class VaultdownApp : Application() {
    lateinit var database: VaultDatabase
    lateinit var settings: Settings
    val syncMutex = Mutex()
    val status = MutableStateFlow(SyncStatus())
    private val handler = Handler(Looper.getMainLooper())
    private val delayedSync = Runnable { enqueueSync() }
    override fun onCreate() {
        super.onCreate()
        database = VaultDatabase(this)
        settings = Settings(this)
        if (settings.config() != null) {
            status.value = SyncStatus(message = "Ready to sync")
            schedulePeriodic()
        }
    }
    fun changed(message: String = status.value.message, running: Boolean = status.value.running) {
        synchronized(status) { status.value = SyncStatus(running, message, status.value.version + 1) }
    }
    fun scheduleAfterEdit() {
        handler.removeCallbacks(delayedSync)
        handler.postDelayed(delayedSync, 5000)
    }
    fun enqueueSync() {
        if (settings.config() == null) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(this).enqueueUniqueWork("vaultdown-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        if (!status.value.running) changed("Sync queued · waiting for network")
    }
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("vaultdown-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun cancelSync() {
        handler.removeCallbacks(delayedSync)
        WorkManager.getInstance(this).cancelUniqueWork("vaultdown-now")
        WorkManager.getInstance(this).cancelUniqueWork("vaultdown-periodic")
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as VaultdownApp
        app.syncMutex.withLock {
            val config = app.settings.config() ?: return@withLock Result.success()
            try {
                val token = app.settings.token() ?: throw GitHubFailure("Sign in to GitHub in Settings.")
                app.changed("Syncing with GitHub…", true)
                val local = app.database.vault(config.vaultId)
                val report = SyncEngine().sync(local, GitHubRemote(config, token)) { isStopped }
                val conflicts = local.all().count { it.conflict }
                val pending = local.all().count { it.dirty() && !it.conflict }
                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                app.changed(when {
                    conflicts > 0 -> "$conflicts conflict(s) need your review"
                    pending > 0 || report.skipped > 0 -> "New edits saved · another sync is queued"
                    else -> "Synced $time · ↑${report.uploaded} ↓${report.downloaded}"
                }, false)
                if (pending > 0 || report.skipped > 0) app.enqueueSync()
                Result.success()
            } catch (e: GitHubFailure) {
                app.changed(e.message ?: "GitHub sync failed.", false)
                if (e.retryable && runAttemptCount < 5) Result.retry() else Result.failure()
            } catch (_: IOException) {
                app.changed("Network unavailable · local notes saved · retrying", false)
                Result.retry()
            } catch (e: IllegalStateException) {
                app.changed(e.message ?: "Open Settings to reconnect GitHub.", false)
                Result.failure()
            } catch (_: org.json.JSONException) {
                app.changed("Unexpected GitHub response. Your notes were left safe; retry later.", false)
                Result.failure()
            } catch (_: android.database.SQLException) {
                app.changed("Local storage is unavailable. Check free space before editing or syncing again.", false)
                Result.failure()
            } catch (_: Exception) {
                // A persisted connection or unexpected API response must never crash a later app launch.
                app.changed("GitHub connection needs attention. Local notes are still available.", false)
                Result.failure()
            }
        }
    }
}
