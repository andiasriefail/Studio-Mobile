package com.example.studiomobile.workspace;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Read-only health checks for the AI: what is in the Studio Mobile root, are the local libraries there and
 * intact. Only a fixed set of places is inspected (the projects folder and {@code build-tools}); nothing
 * outside them can be reached, and results use short labels instead of the app's private absolute paths.
 */
public final class McpWorkspaceInspector {

    private static final long DEEP_CHECK_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_WALK = 20_000;

    private McpWorkspaceInspector() { }

    // ------------------------------------------------------------------ whole root

    /** One line per project plus the toolchain, so the AI sees the whole root in one call. */
    public static String overview(File projectsRoot, File toolsDirectory) {
        StringBuilder out = new StringBuilder("STUDIO MOBILE ROOT\n");
        File[] projects = projectsRoot.listFiles(File::isDirectory);
        List<File> sorted = new ArrayList<>();
        if (projects != null) Collections.addAll(sorted, projects);
        Collections.sort(sorted);
        out.append("Projects (").append(sorted.size()).append("):\n");
        if (sorted.isEmpty()) out.append("  (none)\n");
        for (File project : sorted) {
            long[] stats = { 0, 0 };
            walk(project, stats);
            out.append("  ").append(project.getName()).append("/  ")
                .append(stats[0]).append(" files, ").append(human(stats[1]))
                .append(hasBuildScript(project) ? "" : "  [no build.gradle found]")
                .append(countLibs(project) > 0 ? "  libs: " + countLibs(project) : "")
                .append('\n');
        }
        out.append("\nTOOLCHAIN (build-tools/)\n").append(toolchainLines(toolsDirectory));
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ local libs

    /**
     * Checks the toolchain jars and, when {@code project} is not null, the jar/aar files in the project's libs folders.
     * {@code deep} reads every entry so a truncated or corrupted archive is found (slower).
     */
    public static String localLibs(File toolsDirectory, File project, boolean deep) {
        StringBuilder out = new StringBuilder("LOCAL LIBS CHECK").append(deep ? " (deep)" : "").append('\n');
        out.append("Toolchain:\n");
        int problems = 0;

        File platforms = new File(toolsDirectory, "platforms");
        File[] entries = platforms.listFiles();
        boolean anyPlatform = false;
        if (entries != null) {
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, entries);
            Collections.sort(sorted);
            for (File entry : sorted) {
                File jar = new File(entry, "android.jar");
                if (!entry.isDirectory() || !entry.getName().startsWith("android-")) continue;
                anyPlatform = true;
                String label = "build-tools/platforms/" + entry.getName() + "/android.jar";
                String verdict = checkArchive(jar, "android/app/Activity.class", deep);
                if (!verdict.startsWith("OK")) problems++;
                out.append("  ").append(label).append(": ").append(verdict).append('\n');
            }
        }
        if (!anyPlatform) { problems++; out.append("  platforms/: MISSING. No android.jar installed; builds will fail.\n"); }

        String kotlin = checkArchive(new File(toolsDirectory, "kotlin-stdlib.jar"), "kotlin/Unit.class", deep);
        if (!kotlin.startsWith("OK")) problems++;
        out.append("  build-tools/kotlin-stdlib.jar: ").append(kotlin).append('\n');

        if (project != null) {
            out.append("Project libraries (").append(project.getName()).append("):\n");
            List<File> libs = new ArrayList<>();
            collectLibs(project, libs);
            if (libs.isEmpty()) out.append("  none (no jar/aar in libs folders)\n");
            for (File lib : libs) {
                String require = lib.getName().endsWith(".aar") ? "AndroidManifest.xml" : null;
                String verdict = checkArchive(lib, require, deep);
                if (!verdict.startsWith("OK")) problems++;
                out.append("  ").append(relative(project, lib)).append(": ").append(verdict).append('\n');
            }
        }
        out.append(problems == 0 ? "RESULT: all checked libraries look fine." : "RESULT: " + problems + " problem(s). A missing or corrupt library makes the build fail; ask the user to replace it.");
        return out.toString();
    }

    private static String toolchainLines(File toolsDirectory) {
        StringBuilder out = new StringBuilder();
        File[] entries = new File(toolsDirectory, "platforms").listFiles();
        List<String> apis = new ArrayList<>();
        if (entries != null) for (File entry : entries) {
            File jar = new File(entry, "android.jar");
            if (entry.getName().startsWith("android-") && jar.isFile() && jar.length() > 0) apis.add(entry.getName().substring(8));
        }
        Collections.sort(apis);
        out.append("  android.jar: ").append(apis.isEmpty() ? "none" : "API " + String.join(", ", apis)).append('\n');
        out.append("  kotlin-stdlib.jar: ").append(new File(toolsDirectory, "kotlin-stdlib.jar").length() > 0 ? "present" : "missing").append('\n');
        out.append("  Use check_local_libs for an integrity check.");
        return out.toString();
    }

    // ------------------------------------------------------------------ archive check

    /** Returns "OK ..." or a short reason the archive is unusable. Never throws. */
    static String checkArchive(File file, String requiredEntry, boolean deep) {
        if (!file.exists()) return "MISSING";
        if (!file.isFile()) return "NOT A FILE";
        if (file.length() == 0) return "EMPTY (0 bytes)";
        int count = 0;
        boolean found = requiredEntry == null;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                count++;
                if (requiredEntry != null && entry.getName().equals(requiredEntry)) found = true;
            }
        } catch (IOException | RuntimeException error) {
            return "CORRUPT (not a readable zip/jar: " + shortMessage(error) + ")";
        }
        if (count == 0) return "CORRUPT (archive has no entries)";
        if (!found) return "SUSPECT (" + requiredEntry + " not found; wrong or incomplete file?)";
        if (deep) {
            if (file.length() > DEEP_CHECK_LIMIT_BYTES) return "OK " + describe(file, count) + " (too large for deep check)";
            try (InputStream raw = Files.newInputStream(file.toPath()); ZipInputStream zip = new ZipInputStream(raw)) {
                byte[] buffer = new byte[16384];
                while (zip.getNextEntry() != null) { while (zip.read(buffer) != -1) { /* CRC is verified at entry end */ } }
            } catch (IOException | RuntimeException error) {
                return "CORRUPT (damaged data: " + shortMessage(error) + ")";
            }
        }
        return "OK " + describe(file, count);
    }

    private static String describe(File file, int entries) { return "(" + human(file.length()) + ", " + entries + " entries)"; }

    private static String shortMessage(Exception error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        message = message.replace('\n', ' ');
        return message.length() > 80 ? message.substring(0, 80) : message;
    }

    // ------------------------------------------------------------------ project helpers

    private static void collectLibs(File project, List<File> out) {
        for (File dir : new File[] { new File(project, "libs"), new File(project, "app/libs") }) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            Collections.sort(java.util.Arrays.asList(files));
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && (name.endsWith(".jar") || name.endsWith(".aar"))) out.add(file);
            }
        }
    }

    private static int countLibs(File project) {
        List<File> libs = new ArrayList<>();
        collectLibs(project, libs);
        return libs.size();
    }

    private static boolean hasBuildScript(File project) {
        for (String path : new String[] { "build.gradle", "build.gradle.kts", "app/build.gradle", "app/build.gradle.kts" })
            if (new File(project, path).isFile()) return true;
        return false;
    }

    private static void walk(File dir, long[] stats) {
        File[] children = dir.listFiles();
        if (children == null || stats[0] > MAX_WALK) return;
        for (File child : children) {
            String name = child.getName();
            if (name.equals(".git") || name.equals(".gradle") || name.equals("build") || Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) walk(child, stats);
            else { stats[0]++; stats[1] += child.length(); }
        }
    }

    private static String relative(File base, File file) {
        String path = base.toURI().relativize(file.toURI()).getPath();
        return path;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
