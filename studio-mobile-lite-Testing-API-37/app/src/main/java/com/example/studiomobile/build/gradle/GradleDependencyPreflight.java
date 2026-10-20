package com.example.studiomobile.build.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statically lists the plugins and libraries a project declares (version catalog and build scripts) and
 * checks them against the local Gradle module cache. This is a best-effort readiness check for
 * offline builds, not a replacement for Gradle's own resolver: dynamic versions, BOM-managed versions,
 * transitive dependencies and variables are not evaluated, and the report says so.
 */
public final class GradleDependencyPreflight {
    public static final class Report {
        public final List<String> declared, missing, unverifiable;
        Report(List<String> declared, List<String> missing, List<String> unverifiable) {
            this.declared = Collections.unmodifiableList(declared); this.missing = Collections.unmodifiableList(missing);
            this.unverifiable = Collections.unmodifiableList(unverifiable);
        }
        public boolean isOfflineReady() { return missing.isEmpty(); }
    }
    private static final Pattern COORDINATE = Pattern.compile("['\"]([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.+\\-]+)['\"]");
    private static final Pattern PLUGIN_ID = Pattern.compile("id\\s*\\(?\\s*['\"]([A-Za-z0-9_.\\-]+)['\"]\\s*\\)?\\s*version\\s*['\"]([A-Za-z0-9_.\\-]+)['\"]");
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[([A-Za-z\\-]+)\\]\\s*$");
    private static final Pattern TOML_VERSION = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_ENTRY = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=\\s*\\{(.*)\\}");

    private final File modulesDirectory;
    public GradleDependencyPreflight(File modulesDirectory) { this.modulesDirectory = modulesDirectory; }

    public Report inspect(File project) {
        Set<String> declared = new LinkedHashSet<>(); // "group:artifact:version"
        readVersionCatalog(new File(project, "gradle/libs.versions.toml"), declared);
        String[] scripts = {"build.gradle", "build.gradle.kts", "app/build.gradle", "app/build.gradle.kts", "settings.gradle", "settings.gradle.kts"};
        for (String script : scripts) readScript(new File(project, script), declared);
        List<String> missing = new ArrayList<>(), unverifiable = new ArrayList<>();
        for (String coordinate : declared) {
            String[] part = coordinate.split(":");
            if (part[2].contains("+") || part[2].startsWith("[") || part[2].startsWith("(") || "latest.release".equals(part[2])) { unverifiable.add(coordinate); continue; }
            if (!new File(modulesDirectory, part[0] + "/" + part[1] + "/" + part[2]).isDirectory()) missing.add(coordinate);
        }
        return new Report(new ArrayList<>(declared), missing, unverifiable);
    }

    private static void readScript(File file, Set<String> declared) {
        String text = read(file);
        if (text == null) return;
        Matcher coordinates = COORDINATE.matcher(text);
        while (coordinates.find()) declared.add(coordinates.group(1) + ":" + coordinates.group(2) + ":" + coordinates.group(3));
        Matcher plugins = PLUGIN_ID.matcher(text);
        while (plugins.find()) declared.add(pluginMarker(plugins.group(1), plugins.group(2)));
    }

    private static void readVersionCatalog(File file, Set<String> declared) {
        String text = read(file);
        if (text == null) return;
        Map<String, String> versions = new HashMap<>();
        String section = "";
        for (String line : text.split("\\r?\\n")) {
            Matcher header = TOML_SECTION.matcher(line);
            if (header.matches()) { section = header.group(1); continue; }
            if ("versions".equals(section)) { Matcher v = TOML_VERSION.matcher(line); if (v.find()) versions.put(v.group(1), v.group(2)); continue; }
            Matcher entry = TOML_ENTRY.matcher(line);
            if (!entry.find()) continue;
            String body = entry.group(2);
            String version = attribute(body, "version\\.ref", true) != null ? versions.get(attribute(body, "version\\.ref", true)) : attribute(body, "version", true);
            if (version == null) continue;
            if ("libraries".equals(section)) {
                String module = attribute(body, "module", true);
                if (module == null && attribute(body, "group", true) != null && attribute(body, "name", true) != null) module = attribute(body, "group", true) + ":" + attribute(body, "name", true);
                if (module != null && module.split(":").length == 2) declared.add(module + ":" + version);
            } else if ("plugins".equals(section)) {
                String id = attribute(body, "id", true);
                if (id != null) declared.add(pluginMarker(id, version));
            }
        }
    }
    private static String attribute(String body, String key, boolean quoted) {
        Matcher matcher = Pattern.compile("(?:^|[\\s,])" + key + "\\s*=\\s*\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }
    /** Gradle resolves plugins through a marker artifact: <id>:<id>.gradle.plugin:<version>. */
    private static String pluginMarker(String id, String version) { return id + ":" + id + ".gradle.plugin:" + version; }
    private static String read(File file) {
        if (!file.isFile() || file.length() > 1024 * 1024) return null;
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) { return null; }
    }
}
