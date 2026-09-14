package dev.vaultdown.core;

import java.io.IOException;
import java.util.*;
import static dev.vaultdown.core.SyncEngine.Action.*;

/** Dependency-free executable integration tests for the production sync engine. */
public final class CoreTests {
    private static int passed;
    private interface Checked { void run() throws Exception; }
    private static void test(String name, Checked body) throws Exception {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { throw new AssertionError(name, e); }
    }
    private static void eq(Object want, Object actual) {
        if (!Objects.equals(want, actual)) throw new AssertionError("Expected " + want + ", got " + actual);
    }
    private static String sha(String text) { return Paths.blobSha(text); }
    private static Note clean(String text) { return Note.remote("Notes/A.md", text, sha(text)); }
    private static final class MemoryStore implements SyncEngine.Store {
        final Map<String, Note> notes = new TreeMap<>();
        public List<Note> all() { return new ArrayList<>(notes.values()); }
        public boolean apply(String path, Note expected, Note replacement) {
            Note actual = notes.get(path);
            if ((actual == null) != (expected == null) || (actual != null && actual.revision != expected.revision)) return false;
            if (replacement == null) notes.remove(path);
            else notes.put(path, new Note(path, replacement.text, replacement.baseSha, replacement.baseText,
                replacement.conflict, replacement.incomingSha, replacement.incomingText,
                actual == null ? 0 : actual.revision + 1));
            return true;
        }
        public void acknowledge(Note sent, String newSha) {
            Note latest = notes.get(sent.path);
            if (latest != null && Objects.equals(latest.baseSha, sent.baseSha) && !latest.conflict)
                notes.put(sent.path, latest.acknowledge(newSha, sent.text));
        }
        void put(Note note) { notes.put(note.path, note); }
        Note a() { return notes.get("Notes/A.md"); }
    }
    private static final class MemoryRemote implements SyncEngine.Remote {
        final Map<String, String> files = new TreeMap<>();
        Runnable beforeWrite = () -> {}, beforeRead = () -> {};
        int writes;
        boolean failList, failWrite;
        public Map<String, String> list() throws IOException {
            if (failList) throw new IOException("incomplete tree");
            Map<String, String> map = new TreeMap<>();
            files.forEach((path, text) -> map.put(path, sha(text)));
            return map;
        }
        public String read(String path, String expectedSha) throws IOException {
            String text = files.get(path); // Capture immutable snapshot blob.
            beforeRead.run();
            if (text == null || !sha(text).equals(expectedSha)) throw new IOException("missing blob");
            return text;
        }
        public String write(String path, String text, String expectedSha) throws IOException {
            beforeWrite.run();
            if (failWrite) throw new IOException("offline");
            String current = files.containsKey(path) ? sha(files.get(path)) : null;
            if (!Objects.equals(current, expectedSha)) throw new IOException("409 SHA conflict");
            writes++; files.put(path, text); return sha(text);
        }
        void a(String text) { files.put("Notes/A.md", text); }
    }
    private static void sync(MemoryStore local, MemoryRemote remote) throws IOException {
        new SyncEngine().sync(local, remote, () -> false);
    }
    private static void fails(Checked checked) throws Exception {
        boolean failed = false;
        try { checked.run(); } catch (IOException | IllegalArgumentException e) { failed = true; }
        if (!failed) throw new AssertionError("Expected failure");
    }
    public static void main(String[] args) throws Exception {
        test("Git blob SHA uses UTF-8 byte length", () -> {
            eq("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", sha(""));
            eq("ce013625030ba8dba906f756967f9e9ca394464a", sha("hello\n"));
            eq("e6076a05b53658ebd812398523da7f38fc552aa1", sha("你好\n"));
        });
        test("safe Unicode, spaces, nested paths", () -> {
            Paths.validate("工作/Cloud notes.MD"); Paths.validate("A#B/Design.markdown");
        });
        test("unsafe and hidden paths rejected", () -> {
            for (String path : List.of("../a.md", "/a.md", "a//b.md", "a/./b.md", ".git/a.md",
                    ".github/workflows/a.md", "a\\b.md", "a\n.md", "a.txt", "")) fails(() -> Paths.validate(path));
        });
        test("new and untouched decisions", () -> {
            eq(UPLOAD, SyncEngine.decide(Note.fresh("A.md", ""), null));
            eq(NONE, SyncEngine.decide(clean("old"), sha("old")));
            eq(DOWNLOAD, SyncEngine.decide(null, sha("new")));
        });
        test("initial pull preserves directories and text", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("# Welcome\n你好");
            sync(l,r); eq("# Welcome\n你好", l.a().text); eq(false, l.a().dirty()); eq(0,r.writes);
        });
        test("local edit uploads and becomes clean", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("old"); l.put(clean("old").edit("new"));
            sync(l,r); eq("new",r.files.get("Notes/A.md")); eq(false,l.a().dirty());
        });
        test("remote-only edit downloads", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("remote"); l.put(clean("old"));
            sync(l,r); eq("remote",l.a().text); eq(0,r.writes);
        });
        test("concurrent edits retain both versions", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("remote"); l.put(clean("old").edit("local"));
            sync(l,r); eq(true,l.a().conflict); eq("local",l.a().text); eq("remote",l.a().incomingText); eq(0,r.writes);
        });
        test("clean remote deletion removes cache", () -> {
            MemoryStore l = new MemoryStore(); l.put(clean("old")); sync(l,new MemoryRemote()); eq(null,l.a());
        });
        test("remote deletion with local edits becomes conflict", () -> {
            MemoryStore l = new MemoryStore(); l.put(clean("old").edit("local")); sync(l,new MemoryRemote());
            eq(true,l.a().conflict); eq(null,l.a().incomingSha); eq("local",l.a().text);
        });
        test("simultaneous new files conflict", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("theirs");
            l.put(Note.fresh("Notes/A.md","mine")); sync(l,r); eq(true,l.a().conflict); eq(0,r.writes);
        });
        test("same content after interrupted upload is acknowledged without a new commit", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("new"); l.put(clean("old").edit("new"));
            sync(l,r); eq(false,l.a().dirty()); eq(0,r.writes);
        });
        test("incomplete tree cannot delete local notes", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); l.put(clean("old")); r.failList = true;
            fails(() -> sync(l,r)); eq("old",l.a().text);
        });
        test("failed upload keeps dirty local text", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("old"); r.failWrite=true; l.put(clean("old").edit("new"));
            fails(() -> sync(l,r)); eq(true,l.a().dirty()); eq("new",l.a().text);
        });
        test("typing during upload survives acknowledgment", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("old"); l.put(clean("old").edit("sent"));
            r.beforeWrite = () -> l.put(l.a().edit("newer draft"));
            sync(l,r); eq("newer draft",l.a().text); eq("sent",l.a().baseText); eq(true,l.a().dirty());
            r.beforeWrite=()->{}; sync(l,r); eq("newer draft",r.files.get("Notes/A.md")); eq(false,l.a().dirty());
        });
        test("typing during download cannot be overwritten", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("remote"); l.put(clean("old"));
            r.beforeRead=()->l.put(l.a().edit("typed")); sync(l,r); eq("typed",l.a().text); eq(sha("old"),l.a().baseSha);
            r.beforeRead=()->{}; sync(l,r); eq(true,l.a().conflict);
        });
        test("remote update between tree and PUT triggers SHA protection", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("old"); l.put(clean("old").edit("local"));
            r.beforeWrite=()->r.a("racing remote"); fails(() -> sync(l,r)); eq("racing remote",r.files.get("Notes/A.md"));
            eq(true,l.a().dirty()); r.beforeWrite=()->{}; sync(l,r); eq(true,l.a().conflict);
        });
        test("keeping local explicitly rebases then uploads", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("remote");
            l.put(clean("old").edit("local").withConflict(sha("remote"),"remote").keepLocal()); sync(l,r);
            eq("local",r.files.get("Notes/A.md")); eq(false,l.a().dirty());
        });
        test("keeping local after remote deletion recreates the file", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote();
            l.put(clean("old").edit("local").withConflict(null,null).keepLocal()); sync(l,r); eq("local",r.files.get("Notes/A.md"));
        });
        test("unresolved conflict stays blocked and refreshes newer remote version", () -> {
            MemoryStore l = new MemoryStore(); MemoryRemote r = new MemoryRemote(); r.a("remote");
            l.put(clean("old").edit("local").withConflict(sha("remote"),"remote")); sync(l,r); eq(0,r.writes);
            r.a("remote again"); sync(l,r); eq("local",l.a().text); eq("remote again",l.a().incomingText);
        });
        test("queued keystroke against an old screen cannot silently rebase over a download", () -> {
            Note edited = Note.editFrom(clean("old"),clean("remote"),"typed");
            eq("typed",edited.text); eq(true,edited.conflict); eq("remote",edited.incomingText);
        });
        test("queued keystroke after remote deletion retains the draft", () -> {
            Note edited = Note.editFrom(clean("old"),null,"typed");
            eq("typed",edited.text); eq(true,edited.conflict); eq(null,edited.incomingSha);
        });
        test("queued typing after acknowledgment of the displayed text stays editable", () -> {
            Note displayed = clean("old").edit("sent");
            Note edited = Note.editFrom(displayed,displayed.acknowledge(sha("sent"),"sent"),"newer");
            eq(false,edited.conflict); eq("sent",edited.baseText); eq("newer",edited.text);
        });
        test("cancellation leaves queued edits intact", () -> {
            MemoryStore l = new MemoryStore(); l.put(clean("old").edit("draft"));
            fails(() -> new SyncEngine().sync(l,new MemoryRemote(),()->true)); eq("draft",l.a().text);
        });
        test("tree orders folders first and preserves nesting", () -> {
            List<FileTree.Row> rows = FileTree.rows(List.of("z.md","Work/B.md","Work/A.md","a.md"),Set.of(),"");
            eq(List.of("Work","Work/A.md","Work/B.md","a.md","z.md"),rows.stream().map(r->r.path).toList());
            eq(1,rows.get(1).depth);
        });
        test("collapsed folders hide children; search reveals them", () -> {
            eq(1,FileTree.rows(List.of("Work/A.md"),Set.of("Work"),"").size());
            eq(2,FileTree.rows(List.of("Work/A.md"),Set.of("Work"),"a.md").size());
            eq(0,FileTree.rows(List.of("Work/A.md"),Set.of(),"missing").size());
        });
        System.out.println("\n" + passed + " tests passed.");
    }
}
