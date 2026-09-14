package dev.vaultdown.app

import android.widget.TextView
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vaultdown.core.FileTree
import dev.vaultdown.core.Note
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun VaultdownScreen(vm: VaultViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var newNote by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exportText by rememberSaveable { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("Could not open the selected destination.")
                    stream.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                }
            } catch (_: Exception) { vm.fail("Could not export this note. Choose another destination.") }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val wide = maxWidth >= 840.dp
            val sidebar: @Composable () -> Unit = {
                Sidebar(ui, onSelect = { vm.select(it); scope.launch { drawer.close() } },
                    onNew = { newNote = true }, onSettings = { settings = true })
            }
            val editor: @Composable () -> Unit = {
                Editor(ui, wide, onMenu = { scope.launch { drawer.open() } }, onEdit = vm::edit,
                    onNew = { newNote = true }, onSettings = { settings = true }, onSync = vm::sync,
                    onConflict = { conflict = true }, onExport = {
                        exportText = ui.text
                        export.launch(ui.selected?.substringAfterLast('/') ?: "Note.md")
                    })
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(292.dp).fillMaxHeight()) { sidebar() }
                VerticalDivider()
                Box(Modifier.weight(1f)) { editor() }
            } else ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                ModalDrawerSheet(Modifier.width(310.dp)) { sidebar() }
            }) { editor() }
        }
    }
    if (settings) ConnectionDialog(ui, vm, onDismiss = { settings = false })
    if (newNote) NewNoteDialog(onDismiss = { newNote = false }, onCreate = { vm.create(it); newNote = false })
    val selected = ui.notes.find { it.path == ui.selected }
    if (conflict && selected?.conflict == true) ConflictDialog(selected, ui.saving,
        onDismiss = { conflict = false }, onChoose = { vm.resolve(it); conflict = false })
    if (ui.error != null) AlertDialog(onDismissRequest = vm::clearError,
        title = { Text("Something needs attention") }, text = { Text(ui.error.orEmpty()) },
        confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } })
}

@Composable private fun Sidebar(ui: VaultUi, onSelect: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    val rows = remember(ui.notes, collapsed, query) { FileTree.rows(ui.notes.map { it.path }, collapsed, query) }
    val notes = remember(ui.notes) { ui.notes.associateBy { it.path } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = Violet.copy(alpha = .14f)) {
                Text("V", Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = Violet, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Vaultdown", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text("A little space to think.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(25.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Find a note", fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) })
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WORKSPACE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onNew, enabled = ui.initialized) { Icon(Icons.Outlined.Add, "New note", Modifier.size(20.dp)) }
        }
        Text(ui.config?.repo ?: "Offline notes", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(ui.config?.let { "${it.owner} · ${it.branch}" } ?: "Only on this device",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(rows, key = { "${it.folder}:${it.path}" }) { row ->
                val active = row.path == ui.selected && !row.folder
                Surface(color = if (active) Violet.copy(alpha = .12f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
                        if (row.folder) collapsed = if (row.path in collapsed) collapsed - row.path else collapsed + row.path
                        else onSelect(row.path)
                    }) {
                    Row(Modifier.padding(start = (8 + row.depth * 14).dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { row.folder && row.expanded -> Icons.Outlined.ExpandMore
                            row.folder -> Icons.Outlined.ChevronRight
                            else -> Icons.Outlined.Description }, null,
                            Modifier.size(17.dp), tint = if (active) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(9.dp))
                        Text(if (row.folder) row.label else row.label.substringBeforeLast('.'),
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f), color = if (active) Violet else MaterialTheme.colorScheme.onSurface)
                        notes[row.path]?.let { note ->
                            if (note.conflict) Icon(Icons.Outlined.WarningAmber, "Conflict", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            else if (note.dirty()) Text("•", color = Mint)
                        }
                    }
                }
            }
            if (rows.isEmpty()) item { Text("No notes found", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${ui.notes.size} notes", fontSize = 12.sp)
                Text(if (ui.config == null) "Connect GitHub to sync" else "GitHub connected", fontSize = 10.sp, color = Mint)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Connection settings", Modifier.size(20.dp)) }
        }
    }
}

@Composable private fun Editor(ui: VaultUi, wide: Boolean, onMenu: () -> Unit, onEdit: (String) -> Unit,
    onNew: () -> Unit, onSettings: () -> Unit, onSync: () -> Unit, onConflict: () -> Unit, onExport: () -> Unit) {
    var preview by rememberSaveable { mutableStateOf(false) }
    var value by remember(ui.config?.vaultId, ui.selected) { mutableStateOf(TextFieldValue(ui.text)) }
    LaunchedEffect(ui.text) {
        if (value.text != ui.text) value = TextFieldValue(ui.text, TextRange(value.selection.start.coerceAtMost(ui.text.length)))
    }
    val selected = ui.notes.find { it.path == ui.selected }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.selected) { scroll.scrollTo(0) }
    fun insert(before: String, after: String = "") {
        val start = value.selection.min
        val end = value.selection.max
        val text = value.text.replaceRange(start, end, before + value.text.substring(start, end) + after)
        value = TextFieldValue(text, TextRange(start + before.length, end + before.length))
        onEdit(text)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open file tree") }
            Column(Modifier.weight(1f).padding(start = if (wide) 20.dp else 4.dp)) {
                Text(ui.selected?.substringBeforeLast('/', "Notes") ?: "Your workspace", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.selected?.substringAfterLast('/') ?: "Vaultdown", fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onNew, enabled = ui.initialized) { Icon(Icons.Outlined.Add, "New note", Modifier.size(21.dp)) }
            IconButton(onClick = onSync, enabled = ui.config != null && !ui.status.running) {
                if (ui.status.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Sync, "Sync now", Modifier.size(21.dp))
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Connection settings", Modifier.size(21.dp)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { preview = false }) { Text("Write", color = if (!preview) Violet else MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { preview = true }) { Text("Read", color = if (preview) Violet else MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            Text(if (ui.saving) "Saving…" else "Saved locally", color = if (ui.saving) MaterialTheme.colorScheme.onSurfaceVariant else Mint, fontSize = 10.sp)
            IconButton(onClick = onExport, enabled = ui.selected != null) { Icon(Icons.Outlined.FileDownload, "Export Markdown", Modifier.size(19.dp)) }
        }
        if (selected?.conflict == true) Surface(color = MaterialTheme.colorScheme.error.copy(alpha = .12f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("This note changed on GitHub too.", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onConflict) { Text("Review") }
            }
        }
        if (ui.selected == null) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("Room for your\nnext idea.", fontSize = 36.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(if (ui.config == null) "Write a note or connect a GitHub repository to bring your Markdown files here."
                else "Your Markdown files will appear after sync. You can also create the first note.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNew, enabled = ui.initialized) { Text("New note") }
        } else Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = if (wide) 48.dp else 24.dp, vertical = 12.dp)) {
            if (preview) MarkdownPreview(ui.text)
            else BasicTextField(value = value, onValueChange = { value = it; onEdit(it.text) },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp, lineHeight = 25.sp), cursorBrush = SolidColor(Violet))
        }
        if (!preview && ui.selected != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf("H1" to "# ", "H2" to "## ", "List" to "- ", "Task" to "- [ ] ", "Quote" to "> ").forEach { (label, prefix) ->
                TextButton(onClick = { insert(prefix) }) { Text(label, fontSize = 12.sp) }
            }
            TextButton(onClick = { insert("**", "**") }) { Text("B", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { insert("_", "_") }) { Text("Italic", fontSize = 12.sp) }
            TextButton(onClick = { insert("`", "`") }) { Text("Code", fontSize = 12.sp) }
            TextButton(onClick = { insert("[", "](https://)") }) { Text("Link", fontSize = 12.sp) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.status.message, modifier = Modifier.weight(1f), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ui.selected != null) Text("${ui.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }} words",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable private fun MarkdownPreview(markdown: String) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember { Markwon.builder(context).usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context)).usePlugin(TaskListPlugin.create(context)).build() }
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { TextView(it).apply {
        textSize = 17f; setLineSpacing(8f, 1f); setTextIsSelectable(true); setTextColor(color)
    } }, update = {
        if (it.tag != markdown) {
            markwon.setMarkdown(it, markdown)
            // The preview does not dispatch repository-controlled URLs or fetch remote resources.
            it.movementMethod = null
            it.tag = markdown
        }
    })
}

@Composable private fun NewNoteDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Start a new note") }, text = {
        Column {
            Text("Add folders in the path to organize your workspace.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(path, { path = it }, singleLine = true, label = { Text("Note path") }, placeholder = { Text("Projects/Idea.md") })
        }
    }, confirmButton = { TextButton(onClick = { onCreate(path) }, enabled = path.isNotBlank()) { Text("Create note") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun ConnectionDialog(ui: VaultUi, vm: VaultViewModel, onDismiss: () -> Unit) {
    val login by vm.login.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val activity = context as? MainActivity
    val dismiss = { vm.closeLogin(); onDismiss() }
    LaunchedEffect(Unit) { vm.openLogin() }
    DisposableEffect(Unit) {
        activity?.protectCredentials(true)
        onDispose { activity?.protectCredentials(false) }
    }
    AlertDialog(onDismissRequest = { if (!ui.connecting) dismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (login.selected != null) "Choose a branch" else "Connect GitHub") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (login.account == null) {
                    Text("Sign in to GitHub, then choose a repository for your Markdown notes.")
                    Text("GitHub will request repository access, including private repositories. Vaultdown syncs only the repository you select.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (BuildConfig.GITHUB_CLIENT_ID.isBlank()) {
                        Text("GitHub sign-in is not available in this build yet. Please install a configured build.", color = MaterialTheme.colorScheme.error)
                    } else if (login.code != null) {
                        Text("Enter this code on GitHub:")
                        SelectionContainer { Text(login.code.orEmpty(), fontSize = 25.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                        Button(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubLogin.VERIFY_URL))) }
                            catch (_: Exception) { vm.fail("Open github.com/login/device in your browser and enter the displayed code.") }
                        }) { Text("Open GitHub") }
                        Text("After authorizing Vaultdown, return here. Waiting for approval…", fontSize = 12.sp)
                    } else {
                        Button(onClick = vm::signIn, enabled = !login.busy) { Text("Sign in with GitHub") }
                    }
                } else {
                    Text("Signed in as ${login.account}", color = Mint, fontSize = 13.sp)
                    if (login.selected == null) {
                        OutlinedTextField(query, { query = it }, label = { Text("Find a repository") }, singleLine = true)
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(login.repos.filter { it.fullName.contains(query, true) }, key = { it.fullName }) { repo ->
                                TextButton(onClick = { vm.selectRepo(repo) }, enabled = !login.busy && !ui.connecting) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(repo.fullName)
                                        Text(if (repo.privateRepo) "Private" else "Public", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        if (login.repos.isEmpty() && !login.busy) Text("No writable repositories found. Check GitHub permissions and organization approval.", fontSize = 12.sp)
                        if (login.moreRepos || login.error != null) TextButton(onClick = vm::moreRepositories, enabled = !login.busy) { Text("Load more repositories") }
                        Text("Only repositories you can write to are shown. Archived repositories are excluded.", fontSize = 11.sp)
                        TextButton(onClick = vm::signIn, enabled = !login.busy && !ui.connecting && BuildConfig.GITHUB_CLIENT_ID.isNotBlank()) { Text("Sign in again / switch account") }
                    } else {
                        Text(login.selected!!.fullName, fontWeight = FontWeight.SemiBold)
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(login.branches, key = { it }) { branch ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = !ui.connecting) { vm.chooseBranch(branch) }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = login.branch == branch, onClick = { vm.chooseBranch(branch) }, enabled = !ui.connecting)
                                    Text(branch, Modifier.weight(1f))
                                }
                            }
                        }
                        if (login.branches.isEmpty() && !login.busy) Text("No branches available. Initialize this repository with a README on GitHub, then try again.", fontSize = 12.sp)
                        if (login.moreBranches || login.error != null) TextButton(onClick = vm::moreBranches, enabled = !login.busy) { Text("Load branches") }
                        TextButton(onClick = vm::backToRepos, enabled = !login.busy && !ui.connecting) { Text("Change repository") }
                    }
                }
                if (login.error != null) Text(login.error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (login.busy || ui.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ui.config != null) TextButton(onClick = { vm.disconnect(dismiss) }, enabled = !ui.connecting && !ui.saving && !login.busy) { Text("Disconnect GitHub") }
            }
        }, confirmButton = {
            if (login.selected != null) TextButton(onClick = { vm.connectSelected(dismiss) },
                enabled = !ui.connecting && !ui.saving && !login.busy && login.branch != null) { Text("Connect & sync") }
        }, dismissButton = { TextButton(onClick = dismiss, enabled = !ui.connecting) { Text("Close") } })
}

@Composable private fun ConflictDialog(note: Note, saving: Boolean, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose what to keep") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(note.path, color = Violet, fontSize = 12.sp)
            Text("Both versions are saved here. Keeping both creates a separate note with your local text.", fontSize = 13.sp)
            Text("ON THIS DEVICE", fontSize = 10.sp, color = Mint)
            Text(note.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            HorizontalDivider()
            Text("ON GITHUB", fontSize = 10.sp, color = Mint)
            Text(note.incomingText ?: "This note was deleted on GitHub.", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { onChoose("local") }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Keep my version") }
            OutlinedButton(onClick = { onChoose("remote") }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Use GitHub version") }
        }
    }, confirmButton = { TextButton(onClick = { onChoose("both") }, enabled = !saving) { Text("Keep both") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } })
}
