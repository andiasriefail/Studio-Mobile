package com.example.studiomobile.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the module structure of a Gradle project (settings.gradle(.kts) + each module's build script) without running Gradle.
 *
 * The app module is the one {@link BuildProjectFiles#moduleDirectory(File)} already selects. Every module it reaches through
 * {@code implementation/api(project(":x"))} (or the typesafe {@code projects.x} accessor) is a library module; the result lists them
 * dependencies-first. Both Groovy and Kotlin DSL are read with the same literal-oriented approach as {@code GradleDependencyParser}:
 * values that are not literals are reported in {@link #warnings}, never guessed.
 *
 * Understands old and new scripts: {@code compileSdkVersion}/{@code compileSdk}, {@code buildFeatures { viewBinding true }},
 * the legacy {@code viewBinding { enabled = true }}, {@code android.defaults.buildfeatures.buildconfig} in gradle.properties,
 * and the {@code namespace} / manifest {@code package} split.
 */
public final class ProjectModules {
    public enum Kind { ANDROID_APPLICATION, ANDROID_LIBRARY, JAVA_LIBRARY }

    /** One Gradle module with the facts the on-device pipeline needs. All fields are immutable after {@link #load(File)}. */
    public static final class Module {
        public final String path;
        public final File directory;
        public final Kind kind;
        /** Package of R / BuildConfig / binding classes: namespace, else manifest package, else applicationId. May be null for Java modules. */
        public final String namespace;
        public final String applicationId;
        public final String versionName;
        public final int versionCode;
        public final boolean viewBinding;
        /** buildConfig true in buildFeatures, or enabled through gradle.properties (AGP 7 default). */
        public final boolean buildConfigRequested;
        /** name -> {type, value}; defaultConfig first, then the debug build type (which overrides). */
        public final Map<String, String[]> buildConfigFields;
        public final List<String> dependencyPaths;
        final String script;

        Module(String path, File directory, Kind kind, String namespace, String applicationId, String versionName, int versionCode,
               boolean viewBinding, boolean buildConfigRequested, Map<String, String[]> buildConfigFields, List<String> dependencyPaths, String script) {
            this.path = path; this.directory = directory; this.kind = kind; this.namespace = namespace; this.applicationId = applicationId;
            this.versionName = versionName; this.versionCode = versionCode; this.viewBinding = viewBinding;
            this.buildConfigRequested = buildConfigRequested; this.buildConfigFields = buildConfigFields;
            this.dependencyPaths = dependencyPaths; this.script = script;
        }

        public boolean isAndroid() { return kind != Kind.JAVA_LIBRARY; }
        public File mainDirectory() { return new File(directory, "src/main"); }
        public File manifest() { return new File(mainDirectory(), "AndroidManifest.xml"); }
        public File buildScript() {
            File kts = new File(directory, "build.gradle.kts"), groovy = new File(directory, "build.gradle");
            return groovy.isFile() ? groovy : kts;
        }
        /** Java source roots: main and the debug source set (the pipeline always builds debug). */
        public List<File> javaRoots() { return existing(new File(directory, "src/main/java"), new File(directory, "src/debug/java")); }
        public List<File> kotlinRoots() { return existing(new File(directory, "src/main/kotlin"), new File(directory, "src/debug/kotlin")); }
        public List<File> resourceDirectories() { return existing(new File(directory, "src/main/res"), new File(directory, "src/debug/res")); }
        public boolean hasKotlinSources() {
            for (File root : kotlinRoots()) if (!BuildProjectFiles.filesWithExtension(root, ".kt").isEmpty()) return true;
            for (File root : javaRoots()) if (!BuildProjectFiles.filesWithExtension(root, ".kt").isEmpty()) return true;
            return false;
        }
        /** Raw (comment-free) build script text, for callers that need one more literal. */
        public String scriptText() { return script; }
        @Override public String toString() { return path + " (" + kind + ", " + directory.getName() + ")"; }
        private static List<File> existing(File... files) {
            List<File> result = new ArrayList<>();
            for (File file : files) if (file.isDirectory()) result.add(file);
            return result;
        }
    }

    public final Module app;
    /** Library modules, dependencies before the modules that use them. Does not contain the app. */
    public final List<Module> libraries;
    public final List<String> warnings;

    private ProjectModules(Module app, List<Module> libraries, List<String> warnings) {
        this.app = app; this.libraries = Collections.unmodifiableList(libraries); this.warnings = Collections.unmodifiableList(warnings);
    }

    /** Libraries first (dependency order), then the app. */
    public List<Module> all() { List<Module> all = new ArrayList<>(libraries); all.add(app); return all; }
    public boolean isMultiModule() { return !libraries.isEmpty(); }
    public List<Module> androidLibraries() {
        List<Module> result = new ArrayList<>();
        for (Module module : libraries) if (module.isAndroid()) result.add(module);
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------------------------------------

    private static final Pattern INCLUDE = Pattern.compile("\\binclude\\s*\\(?\\s*((?:[\"'][^\"']+[\"']\\s*,?\\s*)+)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern PROJECT_DIR = Pattern.compile(
        "project\\(\\s*[\"'](:[^\"']+)[\"']\\s*\\)\\.projectDir\\s*=\\s*(?:new\\s+)?(?:file|File)\\(\\s*(?:settingsDir\\s*,\\s*|rootDir\\s*,\\s*)?[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern PROJECT_DEP = Pattern.compile(
        "\\b(?:api|implementation|debugImplementation|runtimeOnly)\\s*\\(?\\s*project\\(\\s*(?:path\\s*[:=]\\s*)?[\"'](:[^\"']+)[\"']");
    private static final Pattern TYPESAFE_DEP = Pattern.compile(
        "\\b(?:api|implementation|debugImplementation|runtimeOnly)\\s*\\(?\\s*projects\\.([A-Za-z0-9_.]+)");

    /** Never throws: unreadable or odd scripts become warnings and a single-module result. */
    public static ProjectModules load(File project) {
        List<String> warnings = new ArrayList<>();
        File appDirectory = BuildProjectFiles.moduleDirectory(project);
        Map<String, File> declared = readSettings(project, warnings);
        Properties legacy = readProperties(project);
        Map<String, Module> loaded = new LinkedHashMap<>();
        List<Module> ordered = new ArrayList<>();
        String appPath = appDirectory.equals(project) ? ":" : ":" + appDirectory.getName();
        for (Map.Entry<String, File> entry : declared.entrySet()) if (entry.getValue().equals(appDirectory)) appPath = entry.getKey();
        Module app = readModule(appPath, appDirectory, Kind.ANDROID_APPLICATION, legacy, warnings);
        Set<String> visiting = new LinkedHashSet<>(); visiting.add(appPath);
        loaded.put(appPath, app);
        visit(project, app, declared, legacy, loaded, ordered, visiting, warnings);
        return new ProjectModules(app, ordered, warnings);
    }

    private static void visit(File project, Module from, Map<String, File> declared, Properties legacy, Map<String, Module> loaded,
                              List<Module> ordered, Set<String> visiting, List<String> warnings) {
        for (String path : from.dependencyPaths) {
            if (loaded.containsKey(path)) {
                if (visiting.contains(path) && !path.equals(from.path)) warnings.add("Circular module dependency: " + from.path + " -> " + path + ". The cycle is ignored.");
                continue;
            }
            File directory = declared.containsKey(path) ? declared.get(path) : new File(project, path.substring(1).replace(':', File.separatorChar));
            if (!directory.isDirectory()) { warnings.add("Module " + path + " is referenced by " + from.path + " but " + directory + " does not exist. Skipped."); continue; }
            Module module = readModule(path, directory, null, legacy, warnings);
            loaded.put(path, module);
            visiting.add(path);
            visit(project, module, declared, legacy, loaded, ordered, visiting, warnings);
            visiting.remove(path);
            ordered.add(module);
        }
    }

    /** include(...) paths with the default directory layout, plus project(':x').projectDir overrides. Insertion order is kept. */
    private static Map<String, File> readSettings(File project, List<String> warnings) {
        Map<String, File> modules = new LinkedHashMap<>();
        File settings = new File(project, "settings.gradle");
        if (!settings.isFile()) settings = new File(project, "settings.gradle.kts");
        if (!settings.isFile()) return modules;
        String text;
        try { text = stripComments(new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException unreadable) { warnings.add("Could not read " + settings.getName() + ": " + unreadable.getMessage()); return modules; }
        Matcher include = INCLUDE.matcher(text);
        while (include.find()) {
            Matcher name = QUOTED.matcher(include.group(1));
            while (name.find()) {
                String path = name.group(1).trim();
                if (!path.startsWith(":")) path = ":" + path;
                modules.put(path, new File(project, path.substring(1).replace(':', File.separatorChar)));
            }
        }
        Matcher override = PROJECT_DIR.matcher(text);
        while (override.find()) modules.put(override.group(1), new File(project, override.group(2)).getAbsoluteFile());
        return modules;
    }

    private static final class Properties { boolean buildConfigDefault; }

    private static Properties readProperties(File project) {
        Properties result = new Properties();
        File file = new File(project, "gradle.properties");
        if (!file.isFile()) return result;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            result.buildConfigDefault = Pattern.compile("(?m)^\\s*android\\.defaults\\.buildfeatures\\.buildconfig\\s*=\\s*true\\s*$").matcher(text).find();
        } catch (IOException ignored) { }
        return result;
    }

    private static Module readModule(String path, File directory, Kind forced, Properties legacy, List<String> warnings) {
        File kts = new File(directory, "build.gradle.kts"), groovy = new File(directory, "build.gradle");
        File scriptFile = groovy.isFile() ? groovy : kts;
        String text = "";
        if (scriptFile.isFile()) {
            try { text = stripComments(new String(Files.readAllBytes(scriptFile.toPath()), StandardCharsets.UTF_8)); }
            catch (IOException unreadable) { warnings.add("Could not read " + scriptFile + ": " + unreadable.getMessage()); }
        }
        Kind kind = forced != null ? forced : detectKind(text, directory);

        List<String> defaultConfig = blockBodies(text, Pattern.compile("\\bdefaultConfig\\s*\\{"));
        String defaults = defaultConfig.isEmpty() ? text : String.join("\n", defaultConfig);
        String applicationId = firstLiteral(defaults, "applicationId");
        String namespace = firstLiteral(text, "namespace");
        if (namespace == null) namespace = manifestPackage(new File(directory, "src/main/AndroidManifest.xml"));
        if (namespace == null) namespace = applicationId;

        String versionName = firstLiteral(defaults, "versionName");
        if (versionName != null && versionName.contains("$")) { warnings.add(path + ": versionName is not a literal; BuildConfig.VERSION_NAME will be empty."); versionName = null; }
        int versionCode = 1;
        Matcher code = Pattern.compile("\\bversionCode\\s*(?:=\\s*)?\\(?\\s*(\\d+)").matcher(defaults);
        if (code.find()) { try { versionCode = Integer.parseInt(code.group(1)); } catch (NumberFormatException ignored) { } }
        else if (Pattern.compile("\\bversionCode\\b").matcher(defaults).find()) warnings.add(path + ": versionCode is not a literal; BuildConfig.VERSION_CODE will be 1.");

        boolean viewBinding = Pattern.compile("\\bviewBinding\\s*(?:=\\s*)?true\\b").matcher(text).find()
            || Pattern.compile("\\bviewBinding\\s*\\{\\s*(?:enabled|isEnabled)\\s*=?\\s*true").matcher(text).find();
        boolean buildConfig = legacy.buildConfigDefault || Pattern.compile("\\bbuildConfig\\s*(?:=\\s*)?true\\b").matcher(text).find();

        Map<String, String[]> fields = new LinkedHashMap<>();
        for (String body : defaultConfig) readBuildConfigFields(path, body, fields, warnings);
        for (String buildTypes : blockBodies(text, Pattern.compile("\\bbuildTypes\\s*\\{")))
            for (String debug : blockBodies(buildTypes, Pattern.compile("(?:\\bdebug|\\b(?:getByName|named|maybeCreate|create)\\(\\s*[\"']debug[\"']\\s*\\))\\s*\\{")))
                readBuildConfigFields(path, debug, fields, warnings);
        if (Pattern.compile("\\bproductFlavors\\s*\\{").matcher(text).find())
            warnings.add(path + ": productFlavors are not supported on-device; only defaultConfig and the debug build type are used.");

        List<String> dependencies = new ArrayList<>();
        Matcher project = PROJECT_DEP.matcher(text);
        while (project.find()) if (!dependencies.contains(project.group(1))) dependencies.add(project.group(1));
        Matcher typesafe = TYPESAFE_DEP.matcher(text);
        while (typesafe.find()) {
            String wanted = normalize(typesafe.group(1));
            String found = findDeclaredByAccessor(directory, wanted);
            if (found != null && !dependencies.contains(found)) dependencies.add(found);
            else if (found == null) warnings.add(path + ": projects." + typesafe.group(1) + " could not be mapped to a module path. Skipped.");
        }
        return new Module(path, directory, kind, namespace, applicationId, versionName, versionCode, viewBinding, buildConfig, fields, dependencies, text);
    }

    /** projects.featureLogin / projects.feature.login -> a directory name or nested path whose normalized form matches. */
    private static String findDeclaredByAccessor(File moduleDirectory, String normalized) {
        File root = moduleDirectory.getParentFile();
        // The root project is the app directory itself or its parent; search both levels, two deep.
        List<File> candidates = new ArrayList<>();
        for (File base : new File[] { moduleDirectory, root }) {
            if (base == null) continue;
            File[] children = base.listFiles(File::isDirectory);
            if (children == null) continue;
            for (File child : children) {
                candidates.add(child);
                File[] nested = child.listFiles(File::isDirectory);
                if (nested != null) Collections.addAll(candidates, nested);
            }
        }
        for (File candidate : candidates) {
            if (!new File(candidate, "build.gradle").isFile() && !new File(candidate, "build.gradle.kts").isFile()) continue;
            String relative = relativeModulePath(candidate, root);
            if (relative != null && normalize(relative).equals(normalized)) return relative;
        }
        return null;
    }

    private static String relativeModulePath(File candidate, File root) {
        String full = candidate.getAbsolutePath(), base = root.getAbsolutePath();
        if (!full.startsWith(base + File.separator)) return null;
        return ":" + full.substring(base.length() + 1).replace(File.separatorChar, ':');
    }

    private static String normalize(String value) { return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    private static Kind detectKind(String text, File directory) {
        if (text.contains("com.android.application") || text.contains("com.android.dynamic-feature")
            || Pattern.compile("\\balias\\(\\s*libs\\.plugins\\.[\\w.]*application").matcher(text).find()
            || Pattern.compile("\\bapplicationId\\s*(?:=\\s*)?[\"']").matcher(text).find()) return Kind.ANDROID_APPLICATION;
        if (text.contains("com.android.library") || text.contains("com.android.kotlin.multiplatform.library")
            || Pattern.compile("\\balias\\(\\s*libs\\.plugins\\.[\\w.]*(?:android[\\w.]*library|library[\\w.]*android)").matcher(text).find()) return Kind.ANDROID_LIBRARY;
        if (Pattern.compile("java-library|kotlin\\(\\s*[\"']jvm[\"']\\s*\\)|org\\.jetbrains\\.kotlin\\.jvm|\\bid\\(?\\s*[\"']java[\"']|apply\\s+plugin\\s*:\\s*[\"']java[\"']").matcher(text).find()
            && !Pattern.compile("\\bandroid\\s*\\{").matcher(text).find()) return Kind.JAVA_LIBRARY;
        if (Pattern.compile("\\bandroid\\s*\\{").matcher(text).find() || new File(directory, "src/main/AndroidManifest.xml").isFile()) return Kind.ANDROID_LIBRARY;
        return Kind.JAVA_LIBRARY;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Small Gradle-script helpers (also used by the generators)
    // ---------------------------------------------------------------------------------------------------------------------------

    /** First {@code key "literal"} / {@code key = "literal"} / {@code key("literal")} value, or null. */
    static String firstLiteral(String text, String key) {
        Matcher matcher = Pattern.compile("\\b" + key + "\\s*(?:=\\s*)?\\(?\\s*([\"'])((?:\\\\.|(?!\\1).)*)\\1").matcher(text);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static final Pattern PACKAGE_ATTRIBUTE = Pattern.compile("<manifest\\b[^>]*?\\bpackage\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    static String manifestPackage(File manifest) {
        if (!manifest.isFile()) return null;
        try {
            Matcher matcher = PACKAGE_ATTRIBUTE.matcher(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException unreadable) { return null; }
    }

    /** Bodies (text between the braces) of every block whose header matches {@code header}; the header must end with the opening brace. */
    static List<String> blockBodies(String text, Pattern header) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = header.matcher(text);
        int from = 0;
        while (matcher.find(from)) {
            int open = matcher.end() - 1, close = matchingBrace(text, open);
            if (close < 0) break;
            bodies.add(text.substring(open + 1, close));
            from = close + 1;
        }
        return bodies;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') { i = skipString(text, i); continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** Index of the closing quote of the string literal that starts at {@code start} (triple quotes included); text length when unterminated. */
    private static int skipString(String text, int start) {
        char quote = text.charAt(start);
        if (text.startsWith("" + quote + quote + quote, start)) {
            int end = text.indexOf("" + quote + quote + quote, start + 3);
            return end < 0 ? text.length() : end + 2;
        }
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
            if (c == '\n') return i; // unterminated: do not swallow the rest of the file
        }
        return text.length();
    }

    /** Removes // and block comments but leaves string literals (URLs with "//") alone. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0, n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') { int end = skipString(source, i); out.append(source, i, Math.min(end + 1, n)); i = end + 1; continue; }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') { while (i < n && source.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') { int end = source.indexOf("*/", i + 2); i = end < 0 ? n : end + 2; out.append(' '); continue; }
            out.append(c); i++;
        }
        return out.toString();
    }

    private static final Pattern FIELD = Pattern.compile("\\bbuildConfigField\\s*\\(?\\s*");

    /** buildConfigField("String", "NAME", "\"value\"") in Kotlin or buildConfigField 'String', 'NAME', '"value"' in Groovy. Later wins. */
    private static void readBuildConfigFields(String module, String block, Map<String, String[]> into, List<String> warnings) {
        Matcher matcher = FIELD.matcher(block);
        int from = 0;
        while (matcher.find(from)) {
            int position = matcher.end();
            String[] parts = new String[3];
            boolean ok = true;
            for (int index = 0; index < 3 && ok; index++) {
                while (position < block.length() && Character.isWhitespace(block.charAt(position))) position++;
                if (position >= block.length() || (block.charAt(position) != '"' && block.charAt(position) != '\'')) { ok = false; break; }
                int end = skipString(block, position);
                if (end >= block.length() || block.charAt(end) != block.charAt(position)) { ok = false; break; }
                parts[index] = unescape(block.substring(position + 1, end));
                position = end + 1;
                while (position < block.length() && Character.isWhitespace(block.charAt(position))) position++;
                if (index < 2) { if (position < block.length() && block.charAt(position) == ',') position++; else ok = false; }
            }
            from = Math.max(position, matcher.end());
            if (!ok) { warnings.add(module + ": a buildConfigField uses non-literal arguments and was skipped."); continue; }
            if (parts[2].contains("$")) { warnings.add(module + ": buildConfigField " + parts[1] + " uses string interpolation and was skipped."); continue; }
            if (!parts[1].matches("[A-Za-z_][A-Za-z0-9_]*") || parts[0].trim().isEmpty()) { warnings.add(module + ": buildConfigField '" + parts[1] + "' is not valid and was skipped."); continue; }
            into.put(parts[1], new String[] { parts[0].trim(), parts[2] });
        }
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) return raw;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) { out.append(c); continue; }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                default: out.append(next);
            }
        }
        return out.toString();
    }
}
