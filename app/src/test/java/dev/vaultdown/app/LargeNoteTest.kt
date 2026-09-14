package dev.vaultdown.app

import android.content.ContentValues
import dev.vaultdown.core.Note
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LargeNoteTest {
    @Test fun legacyLargeRowSurvivesReopenEditAndAcknowledgement() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("vaults.db")
        val original = "界🙂\u0000line\n".repeat(240000)
        val incoming = original + "remote"
        var helper = VaultDatabase(context)
        // Seed the old schema directly: three large strings in one row.
        helper.writableDatabase.insertOrThrow("notes", null, ContentValues().apply {
            put("vault", "test"); put("path", "Large.md"); put("text", original)
            put("baseSha", "base"); put("baseText", original)
            put("conflict", 1); put("incomingSha", "remote"); put("incomingText", incoming)
            put("revision", 7)
        })
        helper.close()
        helper = VaultDatabase(context)
        try {
            val vault = helper.vault("test")
            val loaded = vault.all().single()
            assertEquals(original, loaded.text)
            assertEquals(original, loaded.baseText)
            assertEquals(incoming, loaded.incomingText)
            assertEquals(7L, loaded.revision)
            vault.resolve("Large.md", 7, "local")
            val resolved = vault.get("Large.md")!!
            assertFalse(resolved.conflict)
            val edited = vault.edit(resolved, original + "edit")
            vault.acknowledge(edited, "uploaded")
            val saved = vault.get("Large.md")!!
            assertEquals(original + "edit", saved.text)
            assertEquals(saved.text, saved.baseText)
            assertFalse(saved.dirty())
            vault.create("Empty.md", "")
            assertEquals("", vault.get("Empty.md")!!.text)
            assertNull(vault.get("Empty.md")!!.baseText)
        } finally {
            helper.close()
            context.deleteDatabase("vaults.db")
        }
    }
}
