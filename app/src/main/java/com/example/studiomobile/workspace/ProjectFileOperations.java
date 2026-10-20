package com.example.studiomobile.workspace;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * File operations on user projects for external callers such as the MCP server.
 *
 * <p>Every path is relative to one project folder under {@code Studio Mobile/My Project}. Absolute paths,
 * {@code ..} segments, backslashes and symbolic links that leave the project are rejected with a
 * {@link PathEscapeException}, so callers can count repeated escape attempts. A path that points at the
 * project root itself ({@code ""}, {@code "."}, {@code "./"}, {@code "a/.."} is already refused) is only
 * accepted by read-only listing; every operation that changes something refuses it.
 */
public final class ProjectFileOperations {

    /** Thrown when a path tries to leave the project folder. Subclass so the MCP layer can react to it. */
    public static final class PathEscapeException extends IOException {
        public PathEscapeException(String message) { super(message); }
    }

    /** One replacement of {@link #multiEdit}. */
    public static final class Edit {
        public final String oldText;
        public final String newText;
        public final boolean replaceAll;
        public Edit(String oldText, String newText, boolean replaceAll) {
            this.oldText = oldText; this.newText = newText; this.replaceAll = replaceAll;
        }
    }

    public static final int MAX_READ_CHARS = 200_000;
    public static final int MAX_WRITE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_LIST_ENTRIES = 500;
    /** Files above this size are never loaded whole; use line based reading or search_text. */
    public static final long MAX_WHOLE_FILE_BYTES = 4L * 1024 * 1024;
    public static final long MAX_SEARCH_FILE_BYTES = 1024L * 1024;
    public static final int MAX_SEARCH_RESULTS = 200;
    public static final int MAX_FIND_RESULTS = 500;
    public static final int MAX_COPY_ENTRIES = 5000;
    private static final int MAX_LINE_CHARS = 300;

    private final File projectsRoot;

    public ProjectFileOperations(File projectsRoot) {
        this.projectsRoot = projectsRoot;
    }

    public File getProjectsRoot() { return projectsRoot; }

    // ------------------------------------------------------------------ projects and paths

    public List<String> listProjects() {
        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        List<String> names = new ArrayList<>();
        if (dirs != null) for (File dir : dirs) names.add(dir.getName());
        Collections.sort(names);
        return names;
    }

    public File projectDirectory(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) throw new IOException("Missing 'project'. Call list_projects to see the names.");
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0)
            throw new PathEscapeException("Invalid project name: " + name);
        File dir = new File(projectsRoot, name);
        if (!dir.isDirectory()) throw new IOException("Project not found: " + name + ". Call list_projects to see the names.");
        return dir;
    }

    /**
     * Cleans a relative path: trims, drops empty and "." segments. The result is "" for the project root.
     * This is what closes the old hole where "." slipped past the "empty path" checks.
     */
    static String normalize(String relativePath) throws IOException {
        String path = relativePath == null ? "" : relativePath.trim();
        if (path.indexOf('\0') >= 0 || path.contains("\\")) throw new PathEscapeException("Invalid path: use '/' as separator and no special characters.");
        if (path.startsWith("/")) throw new PathEscapeException("Path must be relative to the project folder, not absolute: " + path);
        StringBuilder clean = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new PathEscapeException("Path must not contain '..': " + path);
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        return clean.toString();
    }

    /** Normalizes and refuses the project root. Used by everything that changes files. */
    private static String requireInner(String relativePath, String label) throws IOException {
        String path = normalize(relativePath);
        if (path.isEmpty()) throw new IOException("'" + label + "' must name a file or folder inside the project, not the project root ('" + (relativePath == null ? "" : relativePath.trim()) + "').");
        return path;
    }

    /** Resolves a path inside the project. An empty path means the project folder itself (read-only callers only). */
    public File resolve(File project, String relativePath) throws IOException {
        String path = normalize(relativePath);
        File target = path.isEmpty() ? project : new File(project, path);
        String root = project.getCanonicalPath();
        String resolved = target.getCanonicalPath();
        if (!resolved.equals(root) && !resolved.startsWith(root + File.separator))
            throw new PathEscapeException("Path leaves the project folder: " + path);
        return target;
    }

    /** The {@code .git} folder is never modified: a half written repository is hard to repair on a phone. */
    private static void requireNotProtected(String normalizedPath) throws IOException {
        if (normalizedPath.equals(".git") || normalizedPath.startsWith(".git/")) throw new IOException("The .git folder is protected and cannot be changed.");
    }

    private static boolean isSymlink(File file) { return Files.isSymbolicLink(file.toPath()); }

    // ------------------------------------------------------------------ list / find / search

    /** Lists files below a folder, depth limited, generated folders hidden. Directories end with '/'. */
    public String list(File project, String relativePath, int depth) throws IOException {
        File base = resolve(project, relativePath);
        if (!base.isDirectory()) throw new IOException("Not a folder: " + relativePath);
        int maxDepth = Math.max(1, Math.min(depth, 8));
        List<String> lines = new ArrayList<>();
        boolean[] truncated = { false };
        walkList(base, base, 1, maxDepth, lines, truncated);
        StringBuilder out = new StringBuilder();
        if (lines.isEmpty()) out.append("(empty folder)");
        for (String line : lines) out.append(line).append('\n');
        if (truncated[0]) out.append("... list cut at ").append(MAX_LIST_ENTRIES).append(" entries. Use a deeper 'path' or a smaller 'depth'.");
        return out.toString().trim();
    }

    private void walkList(File base, File dir, int level, int maxDepth, List<String> lines, boolean[] truncated) {
        File[] children = dir.listFiles();
        if (children == null) return;
        sort(children);
        for (File child : children) {
            if (isGenerated(child.getName())) continue;
            if (lines.size() >= MAX_LIST_ENTRIES) { truncated[0] = true; return; }
            String relative = base.toURI().relativize(child.toURI()).getPath();
            if (isSymlink(child)) { lines.add(relative.replaceAll("/$", "") + "  (symbolic link, not followed)"); continue; }
            if (child.isDirectory()) {
                if (level < maxDepth) {
                    lines.add(relative);
                    walkList(base, child, level + 1, maxDepth, lines, truncated);
                } else {
                    int hidden = countVisible(child);
                    lines.add(relative + (hidden > 0 ? "  (" + hidden + " item" + (hidden == 1 ? "" : "s") + " hidden by depth limit)" : ""));
                }
            } else {
                lines.add(relative + "  (" + child.length() + " bytes)");
            }
        }
    }

    private static int countVisible(File dir) {
        File[] children = dir.listFiles();
        int count = 0;
        if (children != null) for (File child : children) if (!isGenerated(child.getName())) count++;
        return count;
    }

    private static void sort(File[] children) {
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
    }

    private static boolean isGenerated(String name) {
        return name.equals(".git") || name.equals(".gradle") || name.equals("build") || name.equals("outputs") || name.equals(".idea");
    }

    /** find: files whose name (or relative path, when the pattern contains '/') matches a glob such as {@code *.xml}. */
    public String find(File project, String relativePath, String glob) throws IOException {
        if (glob == null || glob.trim().isEmpty()) throw new IOException("Missing 'pattern'. Example: *.xml or res/layout/*.xml");
        File base = resolve(project, relativePath);
        if (!base.isDirectory()) throw new IOException("Not a folder: " + relativePath);
        Pattern pattern = globToRegex(glob.trim());
        boolean byPath = glob.contains("/");
        List<String> hits = new ArrayList<>();
        walkFind(base, base, pattern, byPath, hits);
        if (hits.isEmpty()) return "No files match " + glob;
        StringBuilder out = new StringBuilder();
        for (String hit : hits) out.append(hit).append('\n');
        if (hits.size() >= MAX_FIND_RESULTS) out.append("... cut at ").append(MAX_FIND_RESULTS).append(" results. Use a narrower pattern or 'path'.");
        return out.toString().trim();
    }

    private void walkFind(File base, File dir, Pattern pattern, boolean byPath, List<String> hits) {
        File[] children = dir.listFiles();
        if (children == null) return;
        sort(children);
        for (File child : children) {
            if (hits.size() >= MAX_FIND_RESULTS) return;
            if (isGenerated(child.getName()) || isSymlink(child)) continue;
            String relative = relativeTo(base, child);
            if (child.isDirectory()) { walkFind(base, child, pattern, byPath, hits); continue; }
            if (pattern.matcher(byPath ? relative : child.getName()).matches()) hits.add(relative + "  (" + child.length() + " bytes)");
        }
    }

    /** grep: lines matching a word or regex, printed as {@code file:line: text}. */
    public String search(File project, String relativePath, String query, boolean regex, boolean ignoreCase, String fileGlob) throws IOException {
        if (query == null || query.isEmpty()) throw new IOException("Missing 'query'.");
        File base = resolve(project, relativePath);
        if (!base.exists()) throw new IOException("Not found: " + relativePath);
        Pattern pattern;
        try {
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            pattern = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException error) {
            throw new IOException("Invalid regular expression: " + error.getDescription());
        }
        Pattern nameFilter = fileGlob == null || fileGlob.trim().isEmpty() ? null : globToRegex(fileGlob.trim());
        List<String> hits = new ArrayList<>();
        int[] filesSeen = { 0 };
        walkSearch(base.isDirectory() ? base : base.getParentFile(), base, pattern, nameFilter, hits, filesSeen);
        if (hits.isEmpty()) return "No matches in " + filesSeen[0] + " text file(s).";
        StringBuilder out = new StringBuilder();
        for (String hit : hits) out.append(hit).append('\n');
        if (hits.size() >= MAX_SEARCH_RESULTS) out.append("... cut at ").append(MAX_SEARCH_RESULTS).append(" matches. Narrow the query, 'path' or 'file_pattern'.");
        return out.toString().trim();
    }

    private void walkSearch(File root, File current, Pattern pattern, Pattern nameFilter, List<String> hits, int[] filesSeen) {
        if (hits.size() >= MAX_SEARCH_RESULTS) return;
        if (current.isDirectory()) {
            File[] children = current.listFiles();
            if (children == null) return;
            sort(children);
            for (File child : children) {
                if (isGenerated(child.getName()) || isSymlink(child)) continue;
                walkSearch(root, child, pattern, nameFilter, hits, filesSeen);
                if (hits.size() >= MAX_SEARCH_RESULTS) return;
            }
            return;
        }
        if (current.length() > MAX_SEARCH_FILE_BYTES) return;
        if (nameFilter != null && !nameFilter.matcher(current.getName()).matches()) return;
        try {
            if (looksBinary(current)) return;
            filesSeen[0]++;
            String relative = relativeTo(root, current);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(current.toPath()), StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (pattern.matcher(line).find()) {
                        hits.add(relative + ":" + number + ": " + clip(line.trim()));
                        if (hits.size() >= MAX_SEARCH_RESULTS) return;
                    }
                }
            }
        } catch (IOException ignored) { /* unreadable file: skip */ }
    }

    private static String relativeTo(File base, File child) {
        String path = base.toURI().relativize(child.toURI()).getPath();
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String clip(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "...";
    }

    static Pattern globToRegex(String glob) throws IOException {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { regex.append(".*"); i++; }
                else regex.append("[^/]*");
            } else if (c == '?') regex.append("[^/]");
            else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        try { return Pattern.compile(regex.toString()); }
        catch (PatternSyntaxException error) { throw new IOException("Invalid pattern: " + glob); }
    }

    // ------------------------------------------------------------------ read

    /**
     * Reads a text file.
     * <ul>
     *   <li>{@code tailLines > 0}: the last N lines (tail).</li>
     *   <li>{@code startLine > 0}: lines startLine..endLine (sed -n); endLine 0 means up to a safe limit.</li>
     *   <li>otherwise: characters from {@code offset} (limited), like before.</li>
     * </ul>
     * The size is checked before anything is loaded; line modes stream the file.
     */
    public String read(File project, String relativePath, int offset, int maxChars, int startLine, int endLine, int tailLines, boolean lineNumbers) throws IOException {
        File file = resolve(project, relativePath);
        if (!file.isFile()) throw new IOException("File not found: " + relativePath);
        if (looksBinary(file)) throw new IOException("This looks like a binary file (" + file.length() + " bytes). Only text files can be read.");

        if (tailLines > 0) return readTail(file, Math.min(tailLines, 2000), lineNumbers);
        if (startLine > 0 || endLine > 0) return readRange(file, Math.max(1, startLine), endLine, lineNumbers);

        if (file.length() > MAX_WHOLE_FILE_BYTES)
            throw new IOException("File is large (" + file.length() + " bytes). Use start_line/end_line, tail_lines or search_text instead of reading it whole.");
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int start = Math.max(0, Math.min(offset, text.length()));
        int limit = maxChars <= 0 ? MAX_READ_CHARS : Math.min(maxChars, MAX_READ_CHARS);
        int end = Math.min(text.length(), start + limit);
        String slice = text.substring(start, end);
        if (lineNumbers) slice = numberWholeSlice(text, start, slice);
        if (end < text.length())
            slice += "\n[... cut. File has " + text.length() + " characters; call read_file again with offset=" + end + " for the rest.]";
        return slice;
    }

    private static String numberWholeSlice(String text, int start, String slice) {
        int line = 1;
        for (int i = 0; i < start; i++) if (text.charAt(i) == '\n') line++;
        StringBuilder out = new StringBuilder();
        String[] parts = slice.split("\n", -1);
        int shown = parts.length > 0 && parts[parts.length - 1].isEmpty() ? parts.length - 1 : parts.length;
        for (int i = 0; i < shown; i++) out.append(line++).append(": ").append(parts[i]).append('\n');
        return out.toString();
    }

    private String readRange(File file, int startLine, int endLine, boolean lineNumbers) throws IOException {
        int last = endLine <= 0 ? Integer.MAX_VALUE : endLine;
        if (last < startLine) throw new IOException("end_line must not be smaller than start_line.");
        StringBuilder out = new StringBuilder();
        int number = 0;
        boolean more = false;
        try (BufferedReader reader = reader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                number++;
                if (number < startLine) continue;
                if (number > last) break;
                if (out.length() > MAX_READ_CHARS) { more = true; break; }
                if (lineNumbers) out.append(number).append(": ");
                out.append(line).append('\n');
            }
        }
        if (number < startLine) return "(file has only " + number + " line(s))";
        if (more) out.append("[... more lines follow. Call again with start_line=").append(number).append(".]");
        return out.toString();
    }

    private String readTail(File file, int count, boolean lineNumbers) throws IOException {
        ArrayDeque<String> window = new ArrayDeque<>();
        int number = 0;
        try (BufferedReader reader = reader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                number++;
                window.addLast(line);
                if (window.size() > count) window.removeFirst();
            }
        }
        StringBuilder out = new StringBuilder();
        int n = number - window.size();
        for (String line : window) {
            n++;
            if (lineNumbers) out.append(n).append(": ");
            out.append(line).append('\n');
        }
        if (out.length() > MAX_READ_CHARS) return out.substring(out.length() - MAX_READ_CHARS);
        return out.toString();
    }

    private static BufferedReader reader(File file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8));
    }

    /** Looks at the first 8000 bytes only; never loads the whole file. */
    private static boolean looksBinary(File file) throws IOException {
        byte[] head = new byte[8000];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read = in.read(head);
            for (int i = 0; i < read; i++) if (head[i] == 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ write

    /**
     * Creates a text file, or replaces it only when {@code overwrite} is true.
     * Returns true when an existing file was replaced.
     */
    public boolean write(File project, String relativePath, String content, boolean overwrite) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        File file = resolve(project, path);
        if (file.isDirectory()) throw new IOException("A folder with this name already exists: " + path);
        boolean existed = file.exists();
        if (existed && !overwrite)
            throw new IOException("File already exists: " + path + " (" + file.length() + " bytes). To change part of it use edit_file, multi_edit or replace_lines. "
                + "To replace the whole file, call read_file first and then write_file with overwrite=true.");
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        requireWritable(bytes.length);
        makeParent(file, path);
        writeAtomically(file, bytes);
        touchProject(project);
        return existed;
    }

    /** Adds text at the end of a file (created when missing), like {@code >>}. Returns the new size in bytes. */
    public long append(File project, String relativePath, String content) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        File file = resolve(project, path);
        if (file.isDirectory()) throw new IOException("A folder with this name already exists: " + path);
        byte[] add = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (add.length == 0) throw new IOException("'content' is empty.");
        long existing = file.isFile() ? file.length() : 0;
        if (file.isFile()) requireTextFile(file, path);
        requireWritable(existing + add.length);
        makeParent(file, path);
        byte[] old = file.isFile() ? Files.readAllBytes(file.toPath()) : new byte[0];
        byte[] merged = new byte[old.length + add.length];
        System.arraycopy(old, 0, merged, 0, old.length);
        System.arraycopy(add, 0, merged, old.length, add.length);
        writeAtomically(file, merged);
        touchProject(project);
        return merged.length;
    }

    /** Replaces text inside an existing file. Returns how many occurrences were replaced. */
    public int edit(File project, String relativePath, String oldText, String newText, boolean replaceAll) throws IOException {
        return multiEdit(project, relativePath, Collections.singletonList(new Edit(oldText, newText, replaceAll)));
    }

    /** Several replacements in one file; all of them apply or none does. Returns the total replacements. */
    public int multiEdit(File project, String relativePath, List<Edit> edits) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        if (edits == null || edits.isEmpty()) throw new IOException("'edits' must contain at least one item.");
        if (edits.size() > 100) throw new IOException("Too many edits in one call (limit 100).");
        File file = resolve(project, path);
        String text = readEditable(file, path);
        int total = 0;
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String label = edits.size() == 1 ? "'old_text'" : "edits[" + i + "].old_text";
            if (edit.oldText == null || edit.oldText.isEmpty()) throw new IOException(label + " must not be empty. To create a file use write_file.");
            int count = countOccurrences(text, edit.oldText);
            if (count == 0) throw new IOException(label + " was not found in " + path + " (nothing was changed). Read the file again and copy the text exactly, including spaces and line breaks. "
                + (i > 0 ? "Remember earlier edits in this call were already applied in order." : ""));
            if (count > 1 && !edit.replaceAll)
                throw new IOException(label + " appears " + count + " times in " + path + " (nothing was changed). Add more surrounding text to make it unique, or set replace_all to true.");
            String replacement = edit.newText == null ? "" : edit.newText;
            text = edit.replaceAll ? text.replace(edit.oldText, replacement) : replaceFirst(text, edit.oldText, replacement);
            total += edit.replaceAll ? count : 1;
        }
        saveText(file, text, project);
        return total;
    }

    /**
     * Replaces lines startLine..endLine (1 based, inclusive). When {@code expectedOldText} is given it must equal the
     * current text of those lines, so a shifted file cannot be damaged. Returns the number of lines replaced.
     */
    public int replaceLines(File project, String relativePath, int startLine, int endLine, String newText, String expectedOldText) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        File file = resolve(project, path);
        String text = readEditable(file, path);
        List<String> lines = splitLines(text);
        if (startLine < 1 || endLine < startLine || endLine > lines.size())
            throw new IOException("Line range " + startLine + "-" + endLine + " is outside the file, which has " + lines.size() + " line(s).");
        if (expectedOldText != null && !expectedOldText.isEmpty()) {
            String current = String.join("\n", lines.subList(startLine - 1, endLine));
            if (!sameLines(current, expectedOldText))
                throw new IOException("expected_old_text does not match lines " + startLine + "-" + endLine + " (nothing was changed). Current text of those lines:\n" + current);
        }
        List<String> result = new ArrayList<>(lines.subList(0, startLine - 1));
        result.addAll(splitLines(newText == null ? "" : newText));
        result.addAll(lines.subList(endLine, lines.size()));
        saveText(file, joinLines(result, text.endsWith("\n")), project);
        return endLine - startLine + 1;
    }

    /** Inserts text after line {@code afterLine} (0 = at the very top). Returns the number of inserted lines. */
    public int insertLines(File project, String relativePath, int afterLine, String newText) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        File file = resolve(project, path);
        String text = readEditable(file, path);
        List<String> lines = splitLines(text);
        if (afterLine < 0 || afterLine > lines.size())
            throw new IOException("after_line " + afterLine + " is outside the file, which has " + lines.size() + " line(s). Use 0 for the top.");
        List<String> add = splitLines(newText == null ? "" : newText);
        if (add.isEmpty()) throw new IOException("'content' is empty.");
        List<String> result = new ArrayList<>(lines.subList(0, afterLine));
        result.addAll(add);
        result.addAll(lines.subList(afterLine, lines.size()));
        saveText(file, joinLines(result, text.endsWith("\n") || text.isEmpty()), project);
        return add.size();
    }

    public void createDirectory(File project, String relativePath) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        File dir = resolve(project, path);
        if (dir.isFile()) throw new IOException("A file with this name already exists: " + path);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the folder: " + path);
        touchProject(project);
    }

    /** Copies a file or a folder inside the project. Never replaces anything and never follows symbolic links. */
    public int copy(File project, String fromPath, String toPath) throws IOException {
        String from = requireInner(fromPath, "from");
        String to = requireInner(toPath, "to");
        requireNotProtected(from);
        requireNotProtected(to);
        File source = resolve(project, from);
        File destination = resolve(project, to);
        if (!source.exists()) throw new IOException("Source not found: " + from);
        if (isSymlink(source)) throw new IOException("Symbolic links cannot be copied.");
        if (destination.exists()) throw new IOException("Destination already exists: " + to + ". Copy never replaces; choose a new name.");
        String sourceCanonical = source.getCanonicalPath();
        if (source.isDirectory() && destination.getCanonicalPath().startsWith(sourceCanonical + File.separator))
            throw new IOException("A folder cannot be copied into itself.");
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the destination folder for " + to);
        int[] copied = { 0 };
        copyRecursively(source, destination, copied);
        touchProject(project);
        return copied[0];
    }

    private static void copyRecursively(File source, File target, int[] copied) throws IOException {
        if (copied[0] >= MAX_COPY_ENTRIES) throw new IOException("Too many entries to copy (limit " + MAX_COPY_ENTRIES + ").");
        if (isSymlink(source)) return;
        if (source.isDirectory()) {
            if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Could not create " + target.getName());
            copied[0]++;
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copyRecursively(child, new File(target, child.getName()), copied);
        } else {
            Files.copy(source.toPath(), target.toPath());
            copied[0]++;
        }
    }

    /**
     * Moves or renames a file or folder inside the project.
     * An existing destination file is only replaced when {@code overwrite} is true; an existing destination folder never is.
     */
    public void move(File project, String fromPath, String toPath, boolean overwrite) throws IOException {
        String from = requireInner(fromPath, "from");
        String to = requireInner(toPath, "to");
        requireNotProtected(from);
        requireNotProtected(to);
        File source = resolve(project, from);
        File destination = resolve(project, to);
        if (!source.exists()) throw new IOException("Source not found: " + from);
        String sourceCanonical = source.getCanonicalPath();
        String destinationCanonical = destination.getCanonicalPath();
        if (sourceCanonical.equals(destinationCanonical)) throw new IOException("Source and destination are the same.");
        if (source.isDirectory() && destinationCanonical.startsWith(sourceCanonical + File.separator))
            throw new IOException("A folder cannot be moved into itself.");
        if (destination.exists()) {
            if (destination.isDirectory()) throw new IOException("Destination folder already exists: " + to + ". Choose a new name, or move into it with a full path such as '" + to + "/" + source.getName() + "'.");
            if (!overwrite) throw new IOException("Destination already exists: " + to + ". Set overwrite to true to replace it.");
            if (source.isDirectory()) throw new IOException("A folder cannot replace an existing file.");
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the destination folder for " + to);
        if (overwrite && destination.isFile()) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source.toPath(), destination.toPath());
        }
        touchProject(project);
    }

    /** Deletes a file or a folder with everything inside it. The project folder itself can never be deleted. */
    public int delete(File project, String relativePath) throws IOException {
        String path = requireInner(relativePath, "path");
        requireNotProtected(path);
        File target = resolve(project, path);
        if (!target.exists() && !isSymlink(target)) throw new IOException("Not found: " + path);
        // Belt and braces: whatever the string said, the target must not be the project folder.
        if (target.getCanonicalPath().equals(project.getCanonicalPath())) throw new IOException("The project folder itself cannot be deleted.");
        int removed = deleteRecursively(target);
        touchProject(project);
        return removed;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireWritable(long bytes) throws IOException {
        if (bytes > MAX_WRITE_BYTES) throw new IOException("Content is too large (" + bytes + " bytes, limit " + MAX_WRITE_BYTES + ").");
    }

    private static void requireTextFile(File file, String path) throws IOException {
        if (looksBinary(file)) throw new IOException("This looks like a binary file and cannot be edited as text: " + path);
    }

    /** Size and binary checks happen before the file is loaded. */
    private static String readEditable(File file, String path) throws IOException {
        if (!file.isFile()) throw new IOException("File not found: " + path);
        if (file.length() > MAX_WRITE_BYTES) throw new IOException("File is too large to edit (" + file.length() + " bytes, limit " + MAX_WRITE_BYTES + ").");
        requireTextFile(file, path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void saveText(File file, String text, File project) throws IOException {
        byte[] out = text.getBytes(StandardCharsets.UTF_8);
        if (out.length > MAX_WRITE_BYTES) throw new IOException("Result would be too large (" + out.length + " bytes, limit " + MAX_WRITE_BYTES + ").");
        writeAtomically(file, out);
        touchProject(project);
    }

    private static void makeParent(File file, String path) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the parent folder of " + path);
    }

    /** Lines without terminators; a trailing newline does not create an extra empty line. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    private static String joinLines(List<String> lines, boolean trailingNewline) {
        String joined = String.join("\n", lines);
        return trailingNewline && !lines.isEmpty() ? joined + "\n" : joined;
    }

    private static boolean sameLines(String a, String b) {
        return stripEnds(a.replace("\r", "")).equals(stripEnds(b.replace("\r", "")));
    }

    private static String stripEnds(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') end--;
        return s.substring(0, end);
    }

    private static int deleteRecursively(File file) throws IOException {
        int removed = 0;
        // A symbolic link is removed as a link; its target is never followed.
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) removed += deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Could not delete " + file.getName());
        return removed + 1;
    }

    private static void writeAtomically(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        File temp = new File(parent, "." + file.getName() + ".mcp-tmp");
        try {
            Files.write(temp.toPath(), bytes);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static int countOccurrences(String text, String part) {
        int count = 0, index = 0;
        while ((index = text.indexOf(part, index)) != -1) { count++; index += part.length(); }
        return count;
    }

    private static String replaceFirst(String text, String oldText, String newText) {
        int index = text.indexOf(oldText);
        return text.substring(0, index) + newText + text.substring(index + oldText.length());
    }

    /** The home screen sorts projects by last modified, so a change from outside keeps the order honest. */
    private static void touchProject(File project) {
        project.setLastModified(System.currentTimeMillis());
    }
}
