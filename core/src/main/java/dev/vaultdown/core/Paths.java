package dev.vaultdown.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class Paths {
    private Paths() {}
    public static void validate(String path) {
        if (path == null || path.length() > 1024 || path.startsWith("/") || path.contains("\\")
                || path.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Use a relative Markdown path, for example Notes/Idea.md.");
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith("."))
                throw new IllegalArgumentException("Empty, hidden, and parent-directory paths are not supported.");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown"))
            throw new IllegalArgumentException("The filename must end in .md or .markdown.");
    }
    public static boolean supported(String path) {
        try { validate(path); return true; } catch (IllegalArgumentException e) { return false; }
    }
    public static String blobSha(String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
