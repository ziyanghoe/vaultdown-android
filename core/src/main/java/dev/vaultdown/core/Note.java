package dev.vaultdown.core;

import java.util.Objects;

/** Immutable row. Null incoming SHA with conflict=true means deleted on GitHub. */
public final class Note {
    public final String path, text, baseSha, baseText, incomingSha, incomingText;
    public final boolean conflict;
    public final long revision;

    public Note(String path, String text, String baseSha, String baseText,
                boolean conflict, String incomingSha, String incomingText, long revision) {
        Paths.validate(path);
        this.path = path;
        this.text = Objects.requireNonNull(text);
        this.baseSha = baseSha;
        this.baseText = baseText;
        this.conflict = conflict;
        this.incomingSha = incomingSha;
        this.incomingText = incomingText;
        this.revision = revision;
    }
    public static Note fresh(String path, String text) {
        return new Note(path, text, null, null, false, null, null, 0);
    }
    public static Note remote(String path, String text, String sha) {
        return new Note(path, text, sha, text, false, null, null, 0);
    }
    public boolean dirty() { return baseSha == null || !Objects.equals(text, baseText); }
    public Note edit(String value) {
        return new Note(path, value, baseSha, baseText, conflict, incomingSha, incomingText, revision + 1);
    }
    /** Apply a queued keystroke against the version actually displayed when it was typed. */
    public static Note editFrom(Note displayed, Note latest, String value) {
        if (latest == null) return displayed.edit(value).withConflict(null, null);
        if (!latest.conflict && !Objects.equals(displayed.baseSha, latest.baseSha)
                && !Objects.equals(displayed.text, latest.baseText)) {
            return new Note(displayed.path, value, displayed.baseSha, displayed.baseText, true,
                latest.baseSha, latest.baseText, latest.revision + 1);
        }
        return latest.edit(value);
    }
    public Note withConflict(String sha, String value) {
        return new Note(path, text, baseSha, baseText, true, sha, value, revision + 1);
    }
    public Note acknowledge(String sha, String uploadedText) {
        return new Note(path, text, sha, uploadedText, false, null, null, revision + 1);
    }
    public Note keepLocal() {
        return new Note(path, text, incomingSha, incomingText, false, null, null, revision + 1);
    }
}
