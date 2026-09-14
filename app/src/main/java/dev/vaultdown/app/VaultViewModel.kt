package dev.vaultdown.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.vaultdown.core.Note
import dev.vaultdown.core.Paths
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
    fun connect(config: RepoConfig, enteredToken: String, done: () -> Unit) {
        if (state.value.saving || state.value.connecting) return
        viewModelScope.launch {
            state.value = state.value.copy(connecting = true, error = null)
            try {
                withContext(Dispatchers.IO) {
                    app.syncMutex.withLock {
                        config.validate()
                        val token = enteredToken.trim().ifEmpty { app.settings.token().orEmpty() }
                        require(token.isNotEmpty()) { "Enter a GitHub token." }
                        // Validate read access and branch before changing the active workspace.
                        GitHubRemote(config, token).list()
                        app.settings.save(config, token)
                    }
                }
                state.value = state.value.copy(config = config, selected = null, text = "")
                refresh()
                app.schedulePeriodic()
                app.enqueueSync()
                done()
            } catch (e: Exception) {
                fail(when (e) {
                    is GitHubFailure, is IllegalArgumentException, is IllegalStateException -> e.message ?: "Connection failed."
                    else -> "Cannot reach GitHub. Check your connection and try again."
                })
            } finally { state.value = state.value.copy(connecting = false) }
        }
    }
    fun disconnect(done: () -> Unit) {
        if (state.value.saving || state.value.connecting) return
        viewModelScope.launch {
            state.value = state.value.copy(connecting = true)
            try {
                withContext(Dispatchers.IO) { app.syncMutex.withLock { app.settings.disconnect() } }
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
