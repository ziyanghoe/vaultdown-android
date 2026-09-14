package dev.vaultdown.core;

import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Network operations never hold the database lock; every local replacement is optimistic. */
public final class SyncEngine {
    public interface Remote {
        /** Must be a COMPLETE, immutable snapshot. Throw on truncated/inaccessible trees. */
        Map<String, String> list() throws IOException;
        String read(String path, String sha) throws IOException;
        /** expectedSha=null means create only; implementation must never force-overwrite. */
        String write(String path, String text, String expectedSha) throws IOException;
    }
    public interface Store {
        List<Note> all();
        /** Atomically insert-if-absent or replace/delete-if-revision-matches. */
        boolean apply(String path, Note expected, Note replacement);
        /** Advance the base to exactly what was sent, retaining newer editor text. */
        void acknowledge(Note sent, String newSha);
    }
    public enum Action { NONE, DOWNLOAD, REMOVE, UPLOAD, ACKNOWLEDGE, CONFLICT }
    public static Action decide(Note local, String remoteSha) {
        if (local == null) return remoteSha == null ? Action.NONE : Action.DOWNLOAD;
        if (local.conflict) return Objects.equals(local.incomingSha, remoteSha) ? Action.NONE : Action.CONFLICT;
        if (local.dirty()) {
            if (Objects.equals(Paths.blobSha(local.text), remoteSha)) return Action.ACKNOWLEDGE;
            if (Objects.equals(local.baseSha, remoteSha)) return Action.UPLOAD;
            return Action.CONFLICT;
        }
        if (Objects.equals(local.baseSha, remoteSha)) return Action.NONE;
        return remoteSha == null ? Action.REMOVE : Action.DOWNLOAD;
    }
    public static final class Report {
        public int uploaded, downloaded, conflicts, skipped;
    }
    public Report sync(Store store, Remote remote, BooleanSupplier cancelled) throws IOException {
        Map<String, String> upstream = remote.list(); // Failure cannot be interpreted as an empty repo.
        Map<String, Note> local = new TreeMap<>();
        for (Note note : store.all()) local.put(note.path, note);
        Set<String> paths = new TreeSet<>(upstream.keySet());
        paths.addAll(local.keySet());
        Report report = new Report();
        for (String path : paths) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("Sync paused; local edits are saved.");
            Note note = local.get(path);
            String sha = upstream.get(path);
            switch (decide(note, sha)) {
                case DOWNLOAD: {
                    String content = remote.read(path, sha);
                    if (store.apply(path, note, Note.remote(path, content, sha))) report.downloaded++;
                    else report.skipped++;
                    break;
                }
                case REMOVE:
                    if (!store.apply(path, note, null)) report.skipped++;
                    break;
                case UPLOAD: {
                    String uploadedSha = remote.write(path, note.text, note.baseSha);
                    store.acknowledge(note, uploadedSha);
                    report.uploaded++;
                    break;
                }
                case ACKNOWLEDGE:
                    store.acknowledge(note, sha);
                    break;
                case CONFLICT: {
                    String content = sha == null ? null : remote.read(path, sha);
                    if (store.apply(path, note, note.withConflict(sha, content))) report.conflicts++;
                    else report.skipped++;
                    break;
                }
                default: break;
            }
        }
        return report;
    }
}
