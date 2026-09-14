package dev.vaultdown.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.vaultdown.core.Note
import dev.vaultdown.core.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class VaultUi(
    val notes: List<Note> = emptyList(), val selected: String? = null,
    val text: String = "", val config: RepoConfig? = null, val status: SyncStatus = SyncStatus(),
    val saving: Boolean = false, val connecting: Boolean = false, val error: String? = null,
    val initialized: Boolean = false
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VaultdownApp
    private val state = MutableStateFlow(VaultUi(config = app.settings.config()))
    val ui = state.asStateFlow()
    private data class Save(val vault: String, val note: Note, val text: String, val sequence: Long)
    private val saves = Channel<Save>(Channel.UNLIMITED)
    private val drafts = mutableMapOf<String, Pair<Long, String>>()
    private var sequence = 0L
    private var refreshSequence = 0L
    private val vaultId get() = state.value.config?.vaultId ?: "offline"
    private fun key(vault: String, path: String) = "$vault\u0000$path"

    init {
        viewModelScope.launch {
            for (save in saves) {
                val draftKey = key(save.vault, save.note.path)
                try {
                    withContext(Dispatchers.IO) {
                        val local = app.database.vault(save.vault)
                        local.edit(save.note, save.text)
                    }
                    if (drafts[draftKey]?.first == save.sequence) drafts.remove(draftKey)
                    refresh()
                    if (save.vault == vaultId && state.value.config != null) app.scheduleAfterEdit()
                } catch (e: Exception) {
                    state.value = state.value.copy(error = "Could not save locally. Keep this screen open and export your text. ${e.message.orEmpty()}")
                }
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val offline = app.database.vault("offline")
                if (offline.all().isEmpty()) offline.create("Welcome.md", WELCOME)
            }
            app.status.collect {
                state.value = state.value.copy(status = it)
                refresh()
            }
        }
    }
    private suspend fun refresh(prefer: String? = null) {
        val request = ++refreshSequence
        val id = vaultId
        val notes = withContext(Dispatchers.IO) { app.database.vault(id).all() }
        if (id != vaultId || request != refreshSequence) return
        val selected = (prefer ?: state.value.selected)?.takeIf { p -> notes.any { it.path == p } || drafts.containsKey(key(id, p)) }
            ?: notes.firstOrNull()?.path
        val text = selected?.let { drafts[key(id, it)]?.second ?: notes.find { n -> n.path == it }?.text }.orEmpty()
        state.value = state.value.copy(notes = notes, selected = selected, text = text,
            saving = drafts.isNotEmpty(), initialized = true)
    }
    fun select(path: String) {
        val note = state.value.notes.find { it.path == path } ?: return
        state.value = state.value.copy(selected = path, text = drafts[key(vaultId, path)]?.second ?: note.text)
    }
    fun edit(text: String) {
        val note = state.value.notes.find { it.path == state.value.selected } ?: return
        val seq = ++sequence
        drafts[key(vaultId, note.path)] = seq to text
        state.value = state.value.copy(text = text, saving = true)
        saves.trySend(Save(vaultId, note, text, seq))
    }
    fun create(path: String, text: String? = null) {
        if (!state.value.initialized) return
        viewModelScope.launch {
            try {
                val normalized = path.trim().let { if (it.endsWith(".md", true) || it.endsWith(".markdown", true)) it else "$it.md" }
                Paths.validate(normalized)
                val id = vaultId
                withContext(Dispatchers.IO) {
                    app.database.vault(id).create(normalized, text ?: "# ${normalized.substringAfterLast('/').substringBeforeLast('.')}\n\n")
                }
                refresh(normalized)
                app.scheduleAfterEdit()
            } catch (e: Exception) { fail(e.message ?: "Could not create note.") }
        }
    }
    fun sync() { app.enqueueSync() }
    fun fail(message: String) { state.value = state.value.copy(error = message) }
    fun clearError() { state.value = state.value.copy(error = null) }
    fun connectSelected(done: () -> Unit) {
        val repo = loginState.value.selected ?: return
        val branch = loginState.value.branch ?: return
        val session = pendingSession ?: return
        connect(RepoConfig(repo.owner, repo.name, branch), session, done)
    }
    private fun connect(config: RepoConfig, session: GitHubSession, done: () -> Unit) {
        if (state.value.saving || state.value.connecting) return
        viewModelScope.launch {
            state.value = state.value.copy(connecting = true, error = null)
            try {
                withContext(Dispatchers.IO) {
                    app.syncMutex.withLock {
                        config.validate()
                        val token = session.access
                        // Validate read access and branch before changing the active workspace.
                        GitHubRemote(config, token).list()
                        app.settings.save(config, session)
                    }
                }
                state.value = state.value.copy(config = config, selected = null, text = "")
                refresh()
                app.schedulePeriodic()
                app.enqueueSync()
                closeLogin()
                done()
            } catch (e: Exception) {
                fail(when (e) {
                    is GitHubFailure, is IllegalArgumentException, is IllegalStateException -> e.message ?: "Connection failed."
                    else -> "Cannot reach GitHub. Check your connection and try again."
                })
            } finally { state.value = state.value.copy(connecting = false) }
        }
    }
    private val loginState = MutableStateFlow(LoginUi())
    val login = loginState.asStateFlow()
    private var loginJob: Job? = null
    private var loginGeneration = 0L
    private var pendingSession: GitHubSession? = null
    private var pendingDeviceAuthorization: GitHubDeviceAuthorization? = null
    private var repoPage = 0
    private var branchPage = 0
    private fun loginTask(block: suspend () -> Unit) {
        val generation = ++loginGeneration
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            loginState.value = loginState.value.copy(busy = true, error = null)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                loginState.value = loginState.value.copy(code = null, error =
                    if (e is GitHubFailure || e is IllegalStateException || e is IllegalArgumentException)
                        e.message ?: "GitHub sign-in failed." else "Cannot reach GitHub. Check your connection and retry.")
            } finally { if (generation == loginGeneration) loginState.value = loginState.value.copy(busy = false) }
        }
    }
    fun openLogin() {
        if (loginJob?.isActive == true || loginState.value.account != null) return
        loginTask {
            val saved = withContext(Dispatchers.IO) { app.settings.session() }
            if (saved != null) loadAccount(saved)
            else withContext(Dispatchers.IO) { app.settings.pendingDeviceAuthorization() }?.let { resumeDeviceAuthorization(it) }
        }
    }
    fun signIn() {
        if (state.value.connecting) return
        pendingSession = null
        pendingDeviceAuthorization = null
        loginState.value = LoginUi()
        loginTask {
            withContext(Dispatchers.IO) { app.settings.clearDeviceAuthorization() }
            val auth = withContext(Dispatchers.IO) { GitHubLogin.beginDeviceAuthorization().also(app.settings::saveDeviceAuthorization) }
            resumeDeviceAuthorization(auth)
        }
    }
    private suspend fun resumeDeviceAuthorization(auth: GitHubDeviceAuthorization) {
        pendingDeviceAuthorization = auth
        loginState.value = LoginUi(code = auth.userCode, busy = true)
        try {
            val session = GitHubLogin.finishDeviceAuthorization(auth)
            withContext(Dispatchers.IO) { app.settings.clearDeviceAuthorization() }
            pendingDeviceAuthorization = null
            loadAccount(session)
        } catch (e: Exception) {
            if (e is IllegalStateException && (e.message?.contains("expired") == true || e.message?.contains("cancelled") == true)) {
                withContext(Dispatchers.IO) { app.settings.clearDeviceAuthorization() }
                pendingDeviceAuthorization = null
            }
            throw e
        }
    }
    private suspend fun loadAccount(session: GitHubSession) {
        val account = withContext(Dispatchers.IO) { GitHubLogin.identity(session.access) }
        pendingSession = session
        repoPage = 0
        loginState.value = LoginUi(account = account, busy = true)
        fetchRepos()
    }
    private suspend fun fetchRepos() {
        val session = pendingSession ?: return
        val next = repoPage + 1
        val page = withContext(Dispatchers.IO) { GitHubLogin.repositories(session.access, next) }
        repoPage = next
        loginState.value = loginState.value.copy(repos = (loginState.value.repos + page.items).distinctBy { it.fullName }, moreRepos = page.more)
    }
    fun moreRepositories() { if (!loginState.value.busy) loginTask { fetchRepos() } }
    fun selectRepo(repo: GitHubRepo) {
        if (loginState.value.busy || state.value.connecting) return
        branchPage = 0
        loginState.value = loginState.value.copy(selected = repo, branches = emptyList(), branch = null, moreBranches = false)
        loginTask { fetchBranches(repo) }
    }
    private suspend fun fetchBranches(repo: GitHubRepo) {
        val session = pendingSession ?: return
        val next = branchPage + 1
        val page = withContext(Dispatchers.IO) { GitHubLogin.branches(session.access, repo, next) }
        branchPage = next
        val branches = (loginState.value.branches + page.items).distinct()
        loginState.value = loginState.value.copy(branches = branches, moreBranches = page.more,
            branch = loginState.value.branch ?: branches.find { it == repo.branch } ?: branches.firstOrNull())
    }
    fun moreBranches() {
        val repo = loginState.value.selected ?: return
        if (!loginState.value.busy) loginTask { fetchBranches(repo) }
    }
    fun chooseBranch(branch: String) {
        if (!state.value.connecting && branch in loginState.value.branches) loginState.value = loginState.value.copy(branch = branch)
    }
    fun backToRepos() {
        if (loginState.value.busy || state.value.connecting) return
        loginState.value = loginState.value.copy(selected = null, branch = null, branches = emptyList(), error = null)
    }
    fun closeLogin() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        pendingSession = null
        pendingDeviceAuthorization = null
        loginState.value = LoginUi()
    }
    fun disconnect(done: () -> Unit) {
        if (state.value.saving || state.value.connecting) return
        viewModelScope.launch {
            state.value = state.value.copy(connecting = true)
            try {
                withContext(Dispatchers.IO) { app.syncMutex.withLock { app.settings.disconnect() } }
                closeLogin()
                app.cancelSync()
                state.value = state.value.copy(config = null, selected = null, text = "")
                app.changed("Offline workspace", false)
                refresh()
                done()
            } catch (_: Exception) { fail("Could not disconnect. Please try again.") }
            finally { state.value = state.value.copy(connecting = false) }
        }
    }
    fun resolve(choice: String) {
        val path = state.value.selected ?: return
        val note = state.value.notes.find { it.path == path } ?: return
        if (state.value.saving) return
        val id = vaultId
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { app.syncMutex.withLock { app.database.vault(id).resolve(path, note.revision, choice) } }
                refresh()
                app.enqueueSync()
            } catch (e: Exception) { fail(e.message ?: "Could not resolve this conflict. Try again."); refresh() }
        }
    }
    companion object {
        private val WELCOME = """
            # A little space to think.

            Welcome to **Vaultdown** — your Markdown notes, close at hand.

            ## Make yourself at home
            - Open the file tree to browse folders.
            - Use **New note** and a path like `Projects/Idea.md`.
            - Switch between Write and Read to preview Markdown.
            - Connect your notes repository in Settings.

            ## Your words, everywhere
            Notes save locally as you type. When connected, changes sync to GitHub after a short pause and whenever you reopen the app.

            > If a note changes in both places, you decide which version to keep.

            This welcome note belongs to the offline workspace. Connecting a repository opens its own separate workspace.
        """.trimIndent()
    }
}
