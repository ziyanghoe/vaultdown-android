package dev.vaultdown.core;

import java.util.*;

/** Collapsible folder-first tree; search automatically reveals matching descendants. */
public final class FileTree {
    public static final class Row {
        public final String path, label;
        public final int depth;
        public final boolean folder, expanded;
        Row(String path, String label, int depth, boolean folder, boolean expanded) {
            this.path=path; this.label=label; this.depth=depth; this.folder=folder; this.expanded=expanded;
        }
    }
    private static final class Node {
        final String name, path;
        final boolean folder;
        final Map<String, Node> children = new HashMap<>();
        Node(String name, String path, boolean folder) { this.name=name; this.path=path; this.folder=folder; }
    }
    public static List<Row> rows(Collection<String> paths, Set<String> collapsed, String query) {
        Node root = new Node("", "", true);
        String search = query.trim().toLowerCase(Locale.ROOT);
        for (String path : paths) {
            if (!path.toLowerCase(Locale.ROOT).contains(search)) continue;
            Node current = root;
            String prefix = "";
            String[] segments = path.split("/");
            for (int i=0; i<segments.length; i++) {
                prefix = prefix.isEmpty() ? segments[i] : prefix + "/" + segments[i];
                Node existing = current.children.get(segments[i]);
                if (existing == null) {
                    existing = new Node(segments[i], prefix, i < segments.length - 1);
                    current.children.put(segments[i], existing);
                }
                current = existing;
            }
        }
        List<Row> out = new ArrayList<>();
        flatten(root, 0, search.isEmpty() ? collapsed : Collections.emptySet(), out);
        return out;
    }
    private static void flatten(Node node, int depth, Set<String> collapsed, List<Row> out) {
        List<Node> children = new ArrayList<>(node.children.values());
        children.sort(Comparator.comparing((Node n) -> !n.folder)
                .thenComparing(n -> n.name, String.CASE_INSENSITIVE_ORDER).thenComparing(n -> n.name));
        for (Node child : children) {
            boolean expanded = child.folder && !collapsed.contains(child.path);
            out.add(new Row(child.path, child.name, depth, child.folder, expanded));
            if (expanded) flatten(child, depth + 1, collapsed, out);
        }
    }
}
