package com.example.studiomobile.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;

/**
 * Extracts a project ZIP into an abstract destination without any Android dependency.
 *
 * <p>Protections: zip-slip (absolute paths, drive letters and {@code ..} segments are rejected), entry-count and
 * total-size limits against zip bombs, and removal of archive noise such as {@code __MACOSX/} and {@code .DS_Store}.
 * When every entry lives under one top-level folder (for example {@code MyProject/app/...}), that folder is
 * flattened so the destination directly contains {@code settings.gradle} and {@code app/}.</p>
 *
 * <p>Password-protected ZIPs (ZipCrypto and AES) are detected before anything is written. Without a password the import fails with
 * {@link PasswordRequiredException}; with a wrong one, with {@link WrongPasswordException}. Both let the caller ask for a password and retry.</p>
 */
public final class ProjectZipImporter {
    public static final int MAX_ENTRIES = 20000;
    public static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;

    /** Opens a fresh stream over the same ZIP on every call, because the archive is read twice (scan, then extract). */
    public interface SourceOpener { InputStream open() throws IOException; }

    /** Destination of the extracted files. Paths are relative, use '/' and never contain '..'. */
    public interface Sink {
        void ensureDirectory(String relativePath) throws IOException;
        /** Opens a file for writing, creating parent directories as needed and replacing existing content. */
        OutputStream openFile(String relativePath) throws IOException;
    }

    public interface ProgressListener { void onProgress(int filesWritten, int filesTotal); }

    public static final class Result {
        public final int filesWritten;
        public final int directoriesCreated;
        public final long bytesWritten;
        /** Name of the flattened top-level folder, or null when the archive had no single root folder. */
        public final String flattenedRoot;
        Result(int files, int directories, long bytes, String flattenedRoot) {
            this.filesWritten = files; this.directoriesCreated = directories; this.bytesWritten = bytes; this.flattenedRoot = flattenedRoot;
        }
    }

    /** The ZIP has encrypted entries and no password was given. */
    public static final class PasswordRequiredException extends IOException {
        public PasswordRequiredException() { super("This ZIP is password protected."); }
    }

    /** The ZIP has encrypted entries and the password given does not open them. */
    public static final class WrongPasswordException extends IOException {
        public WrongPasswordException() { super("Wrong password."); }
    }

    private ProjectZipImporter() { }

    public static Result importZip(SourceOpener source, Sink sink, ProgressListener progress) throws IOException {
        return importZip(source, sink, progress, null);
    }

    /** @param password the ZIP password, or null/empty when the user has none. The array is not modified; the caller should clear it afterwards. */
    public static Result importZip(SourceOpener source, Sink sink, ProgressListener progress, char[] password) throws IOException {
        char[] secret = password == null || password.length == 0 ? null : password;
        try {
            return extract(source, sink, progress, secret);
        } catch (UnsafeEntryException unsafe) {
            throw new IOException(unsafe.getMessage(), unsafe);
        }
    }

    /** Maps a zip4j failure to a password exception when that is what it means; otherwise null. */
    private static IOException passwordProblem(ZipException exception, boolean havePassword, boolean sawEncrypted) {
        ZipException.Type type = exception.getType();
        if (type == ZipException.Type.WRONG_PASSWORD) return havePassword ? new WrongPasswordException() : new PasswordRequiredException();
        // ZipCrypto only has a 1-byte password check, so a wrong password can pass it and show up as a CRC error instead.
        if (type == ZipException.Type.CHECKSUM_MISMATCH && sawEncrypted) return havePassword ? new WrongPasswordException() : new PasswordRequiredException();
        return null;
    }

    private static Result extract(SourceOpener source, Sink sink, ProgressListener progress, char[] password) throws IOException {
        List<String> names = new ArrayList<>();
        boolean encrypted = false;
        // Scan: list the entries and, for encrypted ones, read them completely so a wrong password is found before any file is written.
        try (ZipInputStream scan = new ZipInputStream(source.open(), password, StandardCharsets.UTF_8)) {
            LocalFileHeader entry;
            byte[] drain = new byte[32768];
            while ((entry = scan.getNextEntry()) != null) {
                if (names.size() >= MAX_ENTRIES) throw new IOException("The ZIP has too many entries (limit " + MAX_ENTRIES + ").");
                names.add(entry.getFileName());
                if (entry.isEncrypted() && !entry.isDirectory()) {
                    encrypted = true;
                    if (password == null) throw new PasswordRequiredException();
                    while (scan.read(drain) != -1) { /* only verifies the password and the CRC */ }
                }
            }
        } catch (ZipException exception) {
            IOException problem = passwordProblem(exception, password != null, encrypted);
            if (problem != null) throw problem;
            throw new IOException("Could not read the ZIP: " + exception.getMessage(), exception);
        }
        if (names.isEmpty()) throw new IOException("The ZIP is empty or is not a valid ZIP file.");

        String root = commonRoot(names);
        int totalFiles = 0;
        for (String name : names) { String path = relativePath(name, root); if (path != null && !name.endsWith("/")) totalFiles++; }
        if (totalFiles == 0) throw new IOException("The ZIP does not contain any files.");

        int files = 0, directories = 0; long bytes = 0;
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source.open(), password, StandardCharsets.UTF_8)) {
            LocalFileHeader entry;
            byte[] buffer = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                String rawName = entry.getFileName();
                String path = relativePath(rawName, root);
                if (path == null) continue;
                if (rawName.endsWith("/") || entry.isDirectory()) { sink.ensureDirectory(path); directories++; continue; }
                if (!seen.add(path)) throw new IOException("The ZIP contains the same file twice: " + path);
                try (OutputStream out = sink.openFile(path)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        bytes += count;
                        if (bytes > MAX_TOTAL_BYTES) throw new IOException("The ZIP expands beyond " + (MAX_TOTAL_BYTES >> 20) + " MB; import stopped.");
                        out.write(buffer, 0, count);
                    }
                }
                files++;
                if (progress != null) progress.onProgress(files, totalFiles);
            }
        } catch (ZipException exception) {
            IOException problem = passwordProblem(exception, password != null, encrypted);
            if (problem != null) throw problem;
            throw new IOException("Could not extract the ZIP: " + exception.getMessage(), exception);
        }
        return new Result(files, directories, bytes, root);
    }

    /** Returns the single top-level folder shared by every meaningful entry, or null when there is none. */
    static String commonRoot(List<String> rawNames) {
        String candidate = null;
        for (String raw : rawNames) {
            List<String> parts = segments(raw);
            if (parts == null || parts.isEmpty() || isNoise(parts)) continue;
            boolean directoryEntry = raw.endsWith("/");
            if (parts.size() == 1 && !directoryEntry) return null;
            String first = parts.get(0);
            if (candidate == null) candidate = first; else if (!candidate.equals(first)) return null;
        }
        // A lone top-level folder such as "src" or "app" is part of the project layout, not a wrapper around it.
        if (candidate != null && STRUCTURAL_FOLDERS.contains(candidate.toLowerCase(java.util.Locale.ROOT))) return null;
        return candidate;
    }

    /** Folder names that belong to an Android project layout and must never be flattened away. */
    private static final Set<String> STRUCTURAL_FOLDERS = new HashSet<>(java.util.Arrays.asList(
        "src", "app", "gradle", "libs", "res", "assets", "main", "java", "kotlin"));

    /** Normalised relative path with the flattened root removed, or null when the entry must be skipped. */
    static String relativePath(String rawName, String flattenedRoot) {
        List<String> parts = segments(rawName);
        if (parts == null || parts.isEmpty() || isNoise(parts)) return null;
        if (flattenedRoot != null) {
            if (!parts.get(0).equals(flattenedRoot)) return null;
            parts = parts.subList(1, parts.size());
            if (parts.isEmpty()) return null;
        }
        StringBuilder path = new StringBuilder();
        for (String part : parts) { if (path.length() > 0) path.append('/'); path.append(part); }
        return path.toString();
    }

    /** Splits an entry name into safe segments; throws {@link UnsafeEntryException} for traversal attempts. */
    private static List<String> segments(String rawName) {
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || (name.length() > 1 && name.charAt(1) == ':')) throw new UnsafeEntryException(rawName);
        List<String> parts = new ArrayList<>();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new UnsafeEntryException(rawName);
            if (part.indexOf('\0') >= 0) throw new UnsafeEntryException(rawName);
            parts.add(part);
        }
        return parts;
    }

    private static boolean isNoise(List<String> parts) {
        String first = parts.get(0), last = parts.get(parts.size() - 1);
        return first.equals("__MACOSX") || last.equals(".DS_Store") || last.equals("Thumbs.db");
    }

    /** Thrown for path-traversal attempts; surfaced to callers as an {@link IOException}. */
    static final class UnsafeEntryException extends RuntimeException {
        UnsafeEntryException(String entryName) { super("Unsafe path in ZIP: " + entryName); }
    }
}
