package dev.vaultdown.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.vaultdown.core.Note
import dev.vaultdown.core.SyncEngine

class VaultDatabase(context: Context) : SQLiteOpenHelper(context, "vaults.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes (
            vault TEXT NOT NULL, path TEXT NOT NULL, text TEXT NOT NULL,
            baseSha TEXT, baseText TEXT, conflict INTEGER NOT NULL,
            incomingSha TEXT, incomingText TEXT, revision INTEGER NOT NULL,
            PRIMARY KEY(vault, path))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun vault(id: String): LocalVault = LocalVault(this, id)
}

class LocalVault(private val helper: VaultDatabase, private val id: String) : SyncEngine.Store {
    override fun all(): List<Note> = synchronized(helper) {
        helper.readableDatabase.query("notes", null, "vault=?", arrayOf(id), null, null, "path COLLATE NOCASE")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.note()) } }
    }
    fun get(path: String): Note? = synchronized(helper) { getInside(path) }
    private fun getInside(path: String): Note? = helper.readableDatabase
        .query("notes", null, "vault=? AND path=?", arrayOf(id, path), null, null, null)
        .use { if (it.moveToFirst()) it.note() else null }

    override fun apply(path: String, expected: Note?, replacement: Note?): Boolean = synchronized(helper) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val actual = getInside(path)
            if ((actual == null) != (expected == null) || actual?.revision != expected?.revision) return false
            if (replacement == null) db.delete("notes", "vault=? AND path=?", arrayOf(id, path))
            else write(replacement, (actual?.revision ?: -1) + 1)
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction() }
    }
    override fun acknowledge(sent: Note, newSha: String) = synchronized(helper) {
        val latest = getInside(sent.path) ?: return@synchronized
        // An edit during upload keeps its newer text; a user resolution cannot be overwritten.
        if (latest.baseSha == sent.baseSha && !latest.conflict) {
            write(latest.acknowledge(newSha, sent.text), latest.revision + 1)
        }
    }
    fun edit(displayed: Note, text: String): Note = synchronized(helper) {
        val edited = Note.editFrom(displayed, getInside(displayed.path), text)
        write(edited, edited.revision)
        edited
    }
    fun create(path: String, text: String) = synchronized(helper) {
        require(all().none { it.path.startsWith("$path/") || path.startsWith("${it.path}/") }) {
            "A note and a folder cannot share the same path. Choose a different name."
        }
        require(apply(path, null, Note.fresh(path, text))) { "A note with this path already exists." }
    }
    fun resolve(path: String, expectedRevision: Long, choice: String) = synchronized(helper) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val note = getInside(path) ?: return@synchronized
            if (!note.conflict) return@synchronized
            require(note.revision == expectedRevision) { "This conflict changed while you were reviewing it. Open Review again." }
            if (choice == "both") {
                val suffix = path.lastIndexOf('.')
                var copyPath: String
                do {
                    copyPath = path.substring(0, suffix) + " (local " + java.util.UUID.randomUUID().toString().take(8) + ")" + path.substring(suffix)
                } while (getInside(copyPath) != null)
                write(Note.fresh(copyPath, note.text), 0)
            }
            if (choice == "local") write(note.keepLocal(), note.revision + 1)
            else if (note.incomingSha == null) db.delete("notes", "vault=? AND path=?", arrayOf(id, path))
            else write(Note.remote(path, note.incomingText.orEmpty(), note.incomingSha), note.revision + 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun write(note: Note, revision: Long) {
        val values = ContentValues().apply {
            put("vault", id); put("path", note.path); put("text", note.text)
            put("baseSha", note.baseSha); put("baseText", note.baseText)
            put("conflict", if (note.conflict) 1 else 0)
            put("incomingSha", note.incomingSha); put("incomingText", note.incomingText)
            put("revision", revision)
        }
        helper.writableDatabase.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) { "Could not save note. Check device storage." } }
    }
    private fun Cursor.note(): Note {
        fun str(key: String): String? = getColumnIndexOrThrow(key).let { if (isNull(it)) null else getString(it) }
        return Note(str("path")!!, str("text")!!, str("baseSha"), str("baseText"),
            getInt(getColumnIndexOrThrow("conflict")) != 0, str("incomingSha"), str("incomingText"),
            getLong(getColumnIndexOrThrow("revision")))
    }
}
