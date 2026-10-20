package com.example.studiomobile.javarun;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the runner needs to know about one .java file before compiling it: its package, its source root and which classes could hold main. */
public final class JavaSourceInfo {
    private JavaSourceInfo() { }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*(?:;|$)");   // Kotlin writes no semicolon
    private static final Pattern TYPE = Pattern.compile("(?m)^\\s*(?:(?:public|final|abstract|sealed|non-sealed|strictfp|static)\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    /** @return the declared package, or "" for the default package. */
    public static String packageName(String source) {
        Matcher match = PACKAGE.matcher(stripComments(source));
        return match.find() ? match.group(1) : "";
    }

    /**
     * Binary names of the classes that may contain the entry point, most likely first: the class named like the file, then the other
     * types of the file in order. A file without any type (Java 25 implicit class) is the class named like the file.
     */
    public static List<String> candidates(String source, String fileBaseName) {
        String clean = stripComments(source);
        String prefix = packageName(source).isEmpty() ? "" : packageName(source) + ".";
        Set<String> names = new LinkedHashSet<>();
        names.add(prefix + fileBaseName);
        Matcher match = TYPE.matcher(clean);
        while (match.find()) names.add(prefix + match.group(1));
        return new ArrayList<>(names);
    }

    private static final Pattern KOTLIN_TYPE = Pattern.compile("(?m)^\\s*(?:(?:public|internal|private|open|final|abstract|sealed|data|enum|annotation|inner|value)\\s+)*(?:class|interface|object)\\s+([A-Za-z_]\\w*)");
    private static final Pattern FILE_JVM_NAME = Pattern.compile("(?m)^\\s*@file:JvmName\\(\\s*\"([\\w$]+)\"\\s*\\)");

    /**
     * Kotlin version of {@link #candidates}: the class named by {@code @file:JvmName}, then the file facade Kotlin makes for top level code
     * ({@code Main.kt} becomes {@code MainKt}), then the classes and objects declared in the file (for {@code @JvmStatic fun main}).
     */
    public static List<String> kotlinCandidates(String source, String fileBaseName) {
        String clean = stripComments(source);
        String pkg = packageName(source);
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        Set<String> names = new LinkedHashSet<>();
        Matcher custom = FILE_JVM_NAME.matcher(source);
        if (custom.find()) names.add(prefix + custom.group(1));
        names.add(prefix + facadeName(fileBaseName));
        Matcher match = KOTLIN_TYPE.matcher(clean);
        while (match.find()) names.add(prefix + match.group(1));
        return new ArrayList<>(names);
    }

    /** Kotlin's name for the class of a file's top level code: invalid characters become '_', the first letter is upper case, then "Kt". */
    static String facadeName(String fileBaseName) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < fileBaseName.length(); i++) {
            char c = fileBaseName.charAt(i);
            name.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (name.length() == 0 || !Character.isJavaIdentifierStart(name.charAt(0))) name.insert(0, '_');
        name.setCharAt(0, Character.toUpperCase(name.charAt(0)));
        return name + "Kt";
    }

    /** The folder that holds the first segment of the package (the root other classes of the project are found from). */
    public static File sourceRoot(File file, String packageName) {
        File dir = file.getParentFile();
        if (packageName.isEmpty()) return dir;
        String[] segments = packageName.split("\\.");
        File root = dir;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (root == null || !root.getName().equals(segments[i])) return dir;   // the folders do not follow the package: use the file's own folder
            root = root.getParentFile();
        }
        return root == null ? dir : root;
    }

    /** Blanks out comments and string/char literals so words inside them are not mistaken for declarations. Line structure is kept. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0, n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') { while (i < n && source.charAt(i) != '\n') i++; }
            else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) { if (source.charAt(i) == '\n') out.append('\n'); i++; }
                i += 2;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) { if (source.charAt(i) == '\\') i++; else if (source.charAt(i) == '\n') out.append('\n'); i++; }
                i += 3; out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') { if (source.charAt(i) == '\\') i++; i++; }
                i++; out.append(c).append(c);
            } else { out.append(c); i++; }
        }
        return out.toString();
    }
}
