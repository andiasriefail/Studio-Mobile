package com.example.studiomobile.build.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code repositories { }} blocks of settings.gradle(.kts), the root build file and each module's build file, in that order, and
 * turns them into the list of Maven repositories to download from. Like {@link GradleDependencyParser} this is NOT a Gradle interpreter:
 * it understands literal forms and says so in {@link #warnings} for everything else instead of guessing.
 *
 * <p>Understood: google(), mavenCentral(), jcenter(), gradlePluginPortal(), {@code maven("https://...")}, {@code maven(url = "...")},
 * {@code maven { url = uri("...") }}, {@code maven { url "..." }}, {@code maven { setUrl("...") }}, {@code content { includeGroup(...) }}
 * (also {@code includeGroupAndSubgroups}, {@code includeGroupByRegex}, the exclude forms and {@code includeModule}) and
 * {@code exclusiveContent { forRepository { ... } filter { ... } }}.
 *
 * <p>Not supported, and reported: http:// addresses, repositories that need credentials (the repository is still tried without them),
 * addresses held in variables, flatDir, ivy and mavenLocal().
 */
public final class RepositoryDeclarations {
    public final List<MavenRepository> repositories = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    private static final Pattern REPOSITORIES = Pattern.compile("\\brepositories\\s*\\{");
    private static final Pattern ENTRY = Pattern.compile("\\b(google|mavenCentral|jcenter|gradlePluginPortal|mavenLocal|maven|flatDir|ivy|exclusiveContent)\\b");
    private static final Pattern URL_EXPRESSION = Pattern.compile("\\b(?:setUrl|url)\\b\\s*[=(]?\\s*(?:[A-Za-z_.]+\\s*\\(\\s*)?([\"'])([^\"']+)\\1");
    private static final Pattern URL_WORD = Pattern.compile("\\b(?:setUrl|url)\\b");
    private static final Pattern BARE_LITERAL = Pattern.compile("^\\s*(?:[A-Za-z_.]+\\s*\\(\\s*)?([\"'])([^\"']+)\\1\\s*\\)?\\s*$");
    private static final Pattern CONTENT = Pattern.compile("\\b(?:content|mavenContent)\\s*\\{");
    private static final Pattern FILTER_CALL = Pattern.compile("\\b(include|exclude)(Group|GroupAndSubgroups|GroupByRegex|Module|ModuleByRegex|Version|VersionByRegex)\\b\\s*\\(?\\s*([\"'])([^\"']*)\\3");
    private static final Pattern FOR_REPOSITORY = Pattern.compile("\\bforRepository\\s*\\(?\\s*\\{");
    private static final Pattern FILTER_BLOCK = Pattern.compile("\\bfilter\\s*\\{");

    public static RepositoryDeclarations read(File projectDirectory, List<File> moduleDirectories) throws IOException {
        RepositoryDeclarations out = new RepositoryDeclarations();
        List<File> scripts = new ArrayList<>();
        addScripts(scripts, projectDirectory, "settings.gradle.kts", "settings.gradle");
        addScripts(scripts, projectDirectory, "build.gradle.kts", "build.gradle");
        for (File module : moduleDirectories) if (!sameFile(module, projectDirectory)) addScripts(scripts, module, "build.gradle.kts", "build.gradle");
        for (File script : scripts) out.readScript(new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8), script.getName());
        return out;
    }

    private static void addScripts(List<File> into, File directory, String kotlin, String groovy) {
        File file = new File(directory, kotlin);
        if (!file.isFile()) file = new File(directory, groovy);
        if (file.isFile()) into.add(file);
    }

    private static boolean sameFile(File a, File b) {
        try { return a.getCanonicalFile().equals(b.getCanonicalFile()); } catch (IOException e) { return a.equals(b); }
    }

    /** The declared repositories in order, then Google and Maven Central when they were not declared: projects that never listed them keep working. */
    public List<MavenRepository> withDefaults() {
        List<MavenRepository> all = new ArrayList<>(repositories);
        MavenRepository google = MavenRepository.google(), central = MavenRepository.central();
        if (!all.contains(google)) all.add(google);
        if (!all.contains(central)) all.add(central);
        return all;
    }

    // ------------------------------------------------------------------ reading

    void readScript(String source, String label) {
        String text = stripComments(source);
        Matcher blocks = REPOSITORIES.matcher(text);
        int from = 0;
        while (from < text.length() && blocks.find(from)) {
            int open = blocks.end() - 1, close = matching(text, open, '{', '}');
            if (close < 0) { warnings.add(label + ": a repositories { } block is not closed. Skipped."); return; }
            readBlock(text.substring(open + 1, close), label);
            from = close + 1;
        }
    }

    private void readBlock(String block, String label) {
        Matcher entry = ENTRY.matcher(block);
        int position = 0;
        while (position < block.length() && entry.find(position)) {
            String name = entry.group(1);
            int next = skipSpaces(block, entry.end());
            String arguments = null, closure = null;
            if (next < block.length() && block.charAt(next) == '(') {
                int close = matching(block, next, '(', ')');
                if (close < 0) { position = entry.end(); continue; }
                arguments = block.substring(next + 1, close);
                next = skipSpaces(block, close + 1);
            }
            if (next < block.length() && block.charAt(next) == '{') {
                int close = matching(block, next, '{', '}');
                if (close < 0) close = block.length() - 1;
                closure = block.substring(next + 1, close);
                next = close + 1;
            }
            handle(name, arguments, closure, label);
            position = Math.max(next, entry.end());
        }
    }

    private void handle(String name, String arguments, String closure, String label) {
        switch (name) {
            case "google": add(MavenRepository.google(), closure, label); return;
            case "mavenCentral": add(MavenRepository.central(), closure, label); return;
            case "gradlePluginPortal": add(MavenRepository.gradlePluginPortal(), closure, label); return;
            case "jcenter":
                warnings.add(label + ": jcenter() is shut down for new publishing and may be slow or stop answering. It is used only when earlier repositories do not have the file.");
                add(MavenRepository.jcenter(), closure, label); return;
            case "mavenLocal": warnings.add(label + ": mavenLocal() does not exist on the phone. Skipped."); return;
            case "flatDir": case "ivy": warnings.add(label + ": " + name + " repositories are not Maven repositories and are not supported. Skipped."); return;
            case "exclusiveContent": readExclusiveContent(closure, label); return;
            default: readMaven(arguments, closure, label);
        }
    }

    private void readMaven(String arguments, String closure, String label) {
        String url = null; boolean mentionsUrl = false;
        if (arguments != null && !arguments.trim().isEmpty()) {
            Matcher named = URL_EXPRESSION.matcher(arguments);
            if (named.find()) url = named.group(2);
            else { Matcher bare = BARE_LITERAL.matcher(arguments); if (bare.matches()) url = bare.group(2); }
            mentionsUrl = true;
        }
        if (url == null && closure != null) {
            Matcher found = URL_EXPRESSION.matcher(closure);
            if (found.find()) url = found.group(2);
            mentionsUrl |= URL_WORD.matcher(closure).find();
        }
        if (url == null) {
            warnings.add(label + ": a maven repository " + (mentionsUrl ? "has an address that is not a plain text string (a variable?)" : "has no address") + ". Skipped.");
            return;
        }
        if (url.contains("$")) { warnings.add(label + ": the repository address " + url + " uses a variable. Skipped."); return; }
        if (!url.regionMatches(true, 0, "https://", 0, 8)) {
            warnings.add(label + ": the repository " + url + " is not HTTPS; only HTTPS repositories are supported. Skipped.");
            return;
        }
        if (closure != null && closure.contains("credentials"))
            warnings.add(label + ": " + url + " declares credentials, which are not supported here. It is tried without them, so only public files can be downloaded.");
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("oss.sonatype.org"))
            warnings.add(label + ": " + url + " is Sonatype OSSRH, which Sonatype retired in 2025. If downloads fail, use Maven Central instead.");
        if (lower.contains("snapshot"))
            warnings.add(label + ": " + url + " looks like a snapshot repository. SNAPSHOT versions are not supported; use release versions.");
        add(new MavenRepository(nameOf(url), url), closure, label);
    }

    private static String nameOf(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("jitpack.io")) return "JitPack";
        return url;
    }

    private void readExclusiveContent(String closure, String label) {
        if (closure == null) return;
        int before = repositories.size();
        Matcher forRepository = FOR_REPOSITORY.matcher(closure);
        if (!forRepository.find()) { warnings.add(label + ": exclusiveContent without forRepository was not understood. Skipped."); return; }
        int open = forRepository.end() - 1, close = matching(closure, open, '{', '}');
        if (close < 0) return;
        readBlock(closure.substring(open + 1, close), label);
        Matcher filter = FILTER_BLOCK.matcher(closure);
        if (filter.find(close)) {
            int filterOpen = filter.end() - 1, filterClose = matching(closure, filterOpen, '{', '}');
            if (filterClose > 0) for (int i = before; i < repositories.size(); i++) applyFilters(repositories.get(i), closure.substring(filterOpen + 1, filterClose), label);
        }
    }

    private void add(MavenRepository repository, String closure, String label) {
        if (closure != null) {
            Matcher content = CONTENT.matcher(closure);
            int from = 0;
            while (from < closure.length() && content.find(from)) {
                int open = content.end() - 1, close = matching(closure, open, '{', '}');
                if (close < 0) break;
                applyFilters(repository, closure.substring(open + 1, close), label);
                from = close + 1;
            }
        }
        if (!repositories.contains(repository)) repositories.add(repository);
    }

    private void applyFilters(MavenRepository repository, String block, String label) {
        Matcher call = FILTER_CALL.matcher(block);
        while (call.find()) {
            boolean include = call.group(1).equals("include");
            String kind = call.group(2), value = call.group(4);
            switch (kind) {
                case "Group": case "Module":   // includeModule("g", "a") is read as the whole group: a superset, so nothing is wrongly refused
                    if (include) repository.includeGroup(value); else if (kind.equals("Group")) repository.excludeGroup(value); else unsupported(repository, kind, label);
                    break;
                case "GroupAndSubgroups":
                    if (include) repository.includeGroupAndSubgroups(value); else repository.excludeGroupAndSubgroups(value);
                    break;
                case "GroupByRegex": {
                    boolean ok = include ? repository.includeGroupByRegex(value) : repository.excludeGroupByRegex(value);
                    if (!ok) warnings.add(label + ": the pattern " + value + " for " + repository.url + " is not a valid regular expression. Ignored.");
                    break;
                }
                default: unsupported(repository, kind, label);
            }
        }
    }

    private void unsupported(MavenRepository repository, String kind, String label) {
        warnings.add(label + ": the content filter " + kind + " for " + repository.url + " is not supported and was ignored.");
    }

    // ------------------------------------------------------------------ text helpers

    /** Removes // and slash-star comments but leaves strings alone, so "https://jitpack.io" survives. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length(), i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                if (source.startsWith("\"\"\"", i)) { int end = source.indexOf("\"\"\"", i + 3); end = end < 0 ? n : end + 3; out.append(source, i, end); i = end; continue; }
                int end = endOfString(source, i);
                out.append(source, i, end); i = end;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2); i = end < 0 ? n : end + 2; out.append(' ');
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    /** Index just after the string that starts at {@code start} (a string does not run past the end of its line). */
    private static int endOfString(String s, int start) {
        char quote = s.charAt(start); int n = s.length(), j = start + 1;
        while (j < n && s.charAt(j) != quote && s.charAt(j) != '\n') { if (s.charAt(j) == '\\' && j + 1 < n) j++; j++; }
        return j < n && s.charAt(j) == quote ? j + 1 : Math.min(j, n);
    }

    /** Index of the bracket that closes the one at {@code open}, ignoring brackets inside strings; -1 when it is never closed. */
    static int matching(String s, int open, char opening, char closing) {
        int depth = 0, n = s.length();
        for (int i = open; i < n; ) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') { i = endOfString(s, i); continue; }
            if (c == opening) depth++;
            else if (c == closing && --depth == 0) return i;
            i++;
        }
        return -1;
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
