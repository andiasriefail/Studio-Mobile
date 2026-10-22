package com.example.studiomobile.build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for the Java level Studio Mobile builds with: from Java 8 up to Java 25.
 * A project is compiled with the level it declares (the highest one found), or Java 25 when it declares none.
 * A project that declares a HIGHER level (or ships libraries compiled for a higher level) is rejected before any
 * compilation starts. Levels below 8 only produce a warning and are compiled as Java 8.
 */
public final class JavaLanguageLevel {
    public static final int REQUIRED = 25;
    public static final int MINIMUM = 8;
    /** Class-file major version of Java 25. */
    public static final int REQUIRED_CLASS_MAJOR = 69;

    private JavaLanguageLevel() { }

    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public boolean isValid() { return errors.isEmpty(); }
    }

    private static final Pattern JAVA_VERSION = Pattern.compile("JavaVersion\\.VERSION_(\\d+)(?:_(\\d+))?");
    private static final Pattern COMPATIBILITY = Pattern.compile("(?:source|target)Compatibility\\s*(?:=\\s*)?['\"]?(\\d+(?:\\.\\d+)?)['\"]?");
    private static final Pattern JVM_TARGET_STRING = Pattern.compile("jvmTarget\\s*(?:=|\\.set\\()\\s*['\"](\\d+(?:\\.\\d+)?)['\"]");
    private static final Pattern JVM_TARGET_ENUM = Pattern.compile("JvmTarget\\.JVM_(\\d+)(?:_(\\d+))?");
    private static final Pattern TOOLCHAIN = Pattern.compile("jvmToolchain\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern LANGUAGE_VERSION = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)");

    private static Set<File> projectScripts(File project) {
        Set<File> scripts = new LinkedHashSet<>();
        File module = BuildProjectFiles.moduleDirectory(project);
        for (File dir : new File[] { module, project }) {
            scripts.add(new File(dir, "build.gradle"));
            scripts.add(new File(dir, "build.gradle.kts"));
        }
        for (ProjectModules.Module library : ProjectModules.load(project).libraries) {
            scripts.add(new File(library.directory, "build.gradle"));
            scripts.add(new File(library.directory, "build.gradle.kts"));
        }
        return scripts;
    }

    private static String readScript(File script) {
        if (!script.isFile() || script.length() > 1024 * 1024) return null;
        try { return stripComments(new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return null; }
    }

    /** Cheap, tool-free checks: declared Java level in Gradle files and class versions of local libs/*.jar. */
    public static Report inspect(File project) {
        Report report = new Report();
        Set<File> scripts = projectScripts(project);
        Set<String> errors = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (File script : scripts) {
            String text = readScript(script);
            if (text != null) classify(script.getName(), text, errors, warnings);
        }
        report.errors.addAll(errors);
        report.warnings.addAll(warnings);

        List<File> libs = BuildProjectFiles.localLibraries(project);
        for (String problem : ClassFileVersionGuard.findTooNew(libs, REQUIRED_CLASS_MAJOR))
            report.errors.add("Local library not supported: " + problem);
        return report;
    }

    public static int forProject(File project) {
        int highest = 0;
        if (project != null) {
            for (File script : projectScripts(project)) {
                String text = readScript(script);
                if (text == null) continue;
                for (String[] item : declaredLevels(text)) highest = Math.max(highest, Integer.parseInt(item[0]));
            }
        }
        if (highest <= 0) return REQUIRED;
        return Math.max(MINIMUM, Math.min(REQUIRED, highest));
    }

    public static int classMajor(int level) { return level + 44; }

    public static String flag(int level) { return level == 8 ? "1.8" : String.valueOf(level); }

    /**
     * Shared gate for every pipeline: reports warnings and errors to the logger and returns null when the project may be built,
     * otherwise the message to fail the build with. Call it before any compiler or Gradle process starts.
     */
    public static String check(File project, BuildLogger logger) {
        Report report = inspect(project);
        if (logger != null) {
            for (String warning : report.warnings) logger.onWarning(warning);
            for (String error : report.errors) logger.onError(error, null);
        }
        if (report.isValid()) return null;
        return "Unsupported Java level. Studio Mobile builds up to Java " + REQUIRED + "; the build was rejected before it started and no APK was generated.";
    }

    private static List<String[]> declaredLevels(String text) {
        List<String[]> found = new ArrayList<>();
        Matcher m = JAVA_VERSION.matcher(text);
        while (m.find()) found.add(new String[] { levelOf(m.group(1), m.group(2)), m.group(0) });
        m = COMPATIBILITY.matcher(text);
        while (m.find()) found.add(new String[] { levelOf(m.group(1), null), m.group(0).trim() });
        m = JVM_TARGET_STRING.matcher(text);
        while (m.find()) found.add(new String[] { levelOf(m.group(1), null), m.group(0).trim() });
        m = JVM_TARGET_ENUM.matcher(text);
        while (m.find()) found.add(new String[] { levelOf(m.group(1), m.group(2)), m.group(0) });
        m = TOOLCHAIN.matcher(text);
        while (m.find()) found.add(new String[] { levelOf(m.group(1), null), m.group(0) });
        m = LANGUAGE_VERSION.matcher(text);
        while (m.find()) found.add(new String[] { levelOf(m.group(1), null), m.group(0) });
        return found;
    }

    private static void classify(String file, String text, Set<String> errors, Set<String> warnings) {
        for (String[] item : declaredLevels(text)) {
            int level = Integer.parseInt(item[0]);
            if (level <= 0 || (level >= MINIMUM && level <= REQUIRED)) continue;
            if (level > REQUIRED)
                errors.add(file + " declares Java " + level + " (" + item[1] + "). Studio Mobile builds up to Java " + REQUIRED
                        + ". Change it to Java " + REQUIRED + " or lower (e.g. JavaVersion.VERSION_" + REQUIRED + ").");
            else
                warnings.add(file + " declares Java " + level + " (" + item[1] + "). It will be compiled as Java " + MINIMUM + ".");
        }
    }

    /** "1","8" -> 8 (VERSION_1_8); "17",null -> 17; "1.8" -> 8; "17.0" -> 17. Returns "0" when unknown. */
    private static String levelOf(String first, String second) {
        try {
            if (second != null) return first.equals("1") ? String.valueOf(Integer.parseInt(second)) : first;
            if (first.startsWith("1.")) return String.valueOf(Integer.parseInt(first.substring(2).split("\\.")[0]));
            return String.valueOf(Integer.parseInt(first.split("\\.")[0]));
        } catch (NumberFormatException ignored) { return "0"; }
    }

    /** Removes block and line comments so that commented-out settings are not read as declarations (also used by the Gradle planner). */
    public static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(?<!:)//.*$", "");
    }
}
