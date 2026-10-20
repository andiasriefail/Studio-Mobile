package com.example.studiomobile.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Java 25 "module import declarations" ({@code import module java.base;}) need the JDK module system, which the Android platform
 * does not have: the engine compiles against android.jar on the class path, so ECJ answers "The import java.base cannot be resolved".
 * <p>
 * A module import is defined as an on-demand import of every package the module exports, so this class rewrites each one, in the
 * build copy of the sources, into ordinary {@code import package.*;} lines. Only packages that exist in android.jar are imported
 * (an import of a missing package is an error), and only those that hold a class whose simple name appears in the file: ECJ warns
 * "The import x is never used" for every unused on-demand import, and a real module import would not. Every package that can
 * resolve a name the file uses is still imported, so an ambiguous name stays ambiguous exactly as with the real thing.
 * The replacement stays on the same line, so line numbers in diagnostics do not move.
 * Modules that are not known here are left alone and ECJ reports them as before.
 */
public final class ModuleImportExpander {
    private ModuleImportExpander() { }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern MODULE_IMPORT = Pattern.compile("import\\s+module\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;

    /** Exported packages per module, including the modules it requires transitively (JEP 511). java.lang itself is always imported. */
    private static final Map<String, List<String>> MODULES = new HashMap<>();
    static {
        List<String> base = Arrays.asList(
            "java.io", "java.lang.annotation", "java.lang.classfile", "java.lang.constant", "java.lang.foreign", "java.lang.invoke",
            "java.lang.module", "java.lang.ref", "java.lang.reflect", "java.lang.runtime", "java.math", "java.net", "java.net.spi",
            "java.nio", "java.nio.channels", "java.nio.channels.spi", "java.nio.charset", "java.nio.charset.spi", "java.nio.file",
            "java.nio.file.attribute", "java.nio.file.spi", "java.security", "java.security.cert", "java.security.interfaces",
            "java.security.spec", "java.text", "java.text.spi", "java.time", "java.time.chrono", "java.time.format",
            "java.time.temporal", "java.time.zone", "java.util", "java.util.concurrent", "java.util.concurrent.atomic",
            "java.util.concurrent.locks", "java.util.function", "java.util.jar", "java.util.random", "java.util.regex", "java.util.spi",
            "java.util.stream", "java.util.zip", "javax.crypto", "javax.crypto.interfaces", "javax.crypto.spec", "javax.net",
            "javax.net.ssl", "javax.security.auth", "javax.security.auth.callback", "javax.security.auth.login",
            "javax.security.auth.spi", "javax.security.auth.x500", "javax.security.cert");
        List<String> logging = Collections.singletonList("java.util.logging");
        List<String> xml = Arrays.asList(
            "javax.xml", "javax.xml.datatype", "javax.xml.namespace", "javax.xml.parsers", "javax.xml.stream", "javax.xml.stream.events",
            "javax.xml.stream.util", "javax.xml.transform", "javax.xml.transform.dom", "javax.xml.transform.sax",
            "javax.xml.transform.stax", "javax.xml.transform.stream", "javax.xml.validation", "javax.xml.xpath", "org.w3c.dom",
            "org.w3c.dom.bootstrap", "org.w3c.dom.events", "org.w3c.dom.ls", "org.w3c.dom.ranges", "org.w3c.dom.traversal",
            "org.w3c.dom.views", "org.xml.sax", "org.xml.sax.ext", "org.xml.sax.helpers");
        List<String> sql = new ArrayList<>(Arrays.asList("java.sql", "javax.sql", "javax.transaction.xa"));
        sql.addAll(logging);
        sql.addAll(xml);
        MODULES.put("java.base", base);
        MODULES.put("java.logging", logging);
        MODULES.put("java.xml", xml);
        MODULES.put("java.sql", sql);
    }

    /**
     * Rewrites the module imports of every given Java file in place.
     * @return how many files were changed
     */
    public static int expandAll(List<File> files, File androidJar, BuildLogger logger) {
        if (files == null || files.isEmpty()) return 0;
        Map<String, Set<String>> available = null; // android.jar is only scanned when a file really has a module import
        int changed = 0;
        for (File file : files) {
            try {
                if (file == null || !file.isFile() || file.length() > MAX_SOURCE_BYTES) continue;
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (!text.contains("module")) continue;
                if (available == null) available = classIndex(androidJar);
                String expanded = expand(text, available);
                if (expanded.equals(text)) continue;
                Files.write(file.toPath(), expanded.getBytes(StandardCharsets.UTF_8));
                changed++;
            } catch (IOException | RuntimeException e) {
                if (logger != null) logger.onWarning("import module: could not process " + (file == null ? "?" : file.getName()) + ": " + e.getMessage());
            }
        }
        if (changed > 0 && logger != null)
            logger.onInfo("import module: " + changed + " file(s) rewritten to ordinary package imports (Android has no JDK module system).");
        return changed;
    }

    /** Top-level class names (simple) of every package (dotted) in the jar. */
    static Map<String, Set<String>> classIndex(File jar) throws IOException {
        Map<String, Set<String>> packages = new HashMap<>();
        if (jar == null || !jar.isFile()) return packages;
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                int slash = name.lastIndexOf('/');
                if (slash <= 0) continue;
                String simple = name.substring(slash + 1, name.length() - ".class".length());
                if (simple.indexOf('$') >= 0) continue; // nested classes are reached through their top-level class
                packages.computeIfAbsent(name.substring(0, slash).replace('/', '.'), k -> new HashSet<>()).add(simple);
            }
        }
        return packages;
    }

    /** Returns the source with every known module import replaced; the same instance of equal text when there is nothing to replace. */
    static String expand(String text, Map<String, Set<String>> availablePackages) {
        StringBuilder out = null;
        Set<String> usedNames = null;
        int copied = 0, i = 0, n = text.length();
        Set<String> alreadyImported = new LinkedHashSet<>();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' && text.startsWith("\"\"\"", i)) {
                i = endOfTextBlock(text, i + 3);
            } else if (c == '"' || c == '\'') {
                i = endOfQuoted(text, i + 1, c);
            } else if (c == 'i' && text.startsWith("import", i) && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))) {
                Matcher m = MODULE_IMPORT.matcher(text);
                m.region(i, n);
                List<String> packages = m.lookingAt() ? MODULES.get(m.group(1).replaceAll("\\s+", "")) : null;
                if (packages == null) { i += 6; continue; }
                if (out == null) out = new StringBuilder(n + 256);
                out.append(text, copied, i);
                if (usedNames == null) usedNames = identifiersOf(text);
                StringBuilder imports = new StringBuilder();
                for (String pkg : packages) {
                    Set<String> classes = availablePackages.get(pkg);
                    if (classes == null || Collections.disjoint(classes, usedNames) || !alreadyImported.add(pkg)) continue;
                    if (imports.length() > 0) imports.append(' ');
                    imports.append("import ").append(pkg).append(".*;");
                }
                out.append(imports);
                for (int k = i; k < m.end(); k++) if (text.charAt(k) == '\n') out.append('\n'); // a statement split over lines keeps its line count
                copied = m.end();
                i = m.end();
            } else {
                i++;
            }
        }
        if (out == null) return text;
        out.append(text, copied, n);
        return out.toString();
    }

    private static Set<String> identifiersOf(String text) {
        Set<String> names = new HashSet<>();
        Matcher m = IDENTIFIER.matcher(text);
        while (m.find()) names.add(m.group());
        return names;
    }

    private static int endOfQuoted(String text, int from, char quote) {
        int i = from, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') i += 2;
            else if (c == quote) return i + 1;
            else if (c == '\n') return i; // unterminated literal: stop at the line end
            else i++;
        }
        return n;
    }

    private static int endOfTextBlock(String text, int from) {
        int i = from, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') i += 2;
            else if (text.startsWith("\"\"\"", i)) return i + 3;
            else i++;
        }
        return n;
    }
}
