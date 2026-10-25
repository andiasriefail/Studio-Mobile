package com.example.studiomobile.workspace;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The working copy of a project for the Gradle route, kept between builds. Gradle decides what to redo by comparing the files of the
 * project with what it saw last time, so a copy that is deleted after every build makes every build a full build (resources, Kotlin,
 * dex, packaging all again). Here the copy stays; only the files that differ from the original are copied again, files that were
 * removed from the original are removed, and the build outputs and Gradle's own project state are never touched.
 */
public final class ProjectWorkspace {
    private ProjectWorkspace() { }

    /** Never copied from the original project (the same names the old per-build copy skipped). */
    private static final Set<String> NOT_COPIED = new HashSet<>(Arrays.asList(".git", ".gradle", "build", "outputs", ".idea"));
    /** Left alone in the working copy: build outputs and the state Gradle and Kotlin keep next to the project. */
    private static final Set<String> KEPT = new HashSet<>(Arrays.asList(".git", ".gradle", "build", "outputs", ".idea", ".kotlin", ".cxx", ".externalNativeBuild"));
    static final String STAMP_FILE = ".studio-workspace";
    static final int MAX_WORKSPACES = 3;

    /** A folder name that is the same for the same project every time and different for two projects with the same name. */
    public static String idFor(File project) {
        String name = project.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty()) name = "project";
        if (name.length() > 40) name = name.substring(0, 40);
        int hash = project.getAbsolutePath().hashCode();
        return name + "-" + String.format("%08x", hash);
    }

    public static File folderFor(File workspaces, File project) { return new File(workspaces, idFor(project)); }

    /**
     * Makes sure the working copy belongs to the current version of the app (a new app version may build differently, so the old state is
     * dropped once). Returns true when the folder was emptied.
     */
    public static boolean ensureStamp(File folder, String stamp) throws IOException {
        File stampFile = new File(folder, STAMP_FILE);
        String saved = null;
        if (stampFile.isFile()) saved = new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8).trim();
        boolean wipe = folder.exists() && !stamp.equals(saved);
        if (wipe) deleteTree(folder);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create " + folder);
        if (wipe || !stampFile.isFile()) Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
        return wipe;
    }

    /** Keeps at most {@link #MAX_WORKSPACES} working copies, removing the ones used longest ago. The folder in use is never removed. */
    public static void evictOldest(File workspaces, File inUse) {
        File[] all = workspaces.listFiles();
        if (all == null || all.length <= MAX_WORKSPACES) return;
        Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int kept = 0;
        for (File folder : all) {
            if (folder.equals(inUse) || kept < MAX_WORKSPACES - 1) { kept++; continue; }
            deleteTree(folder);
        }
    }

    /** Brings [work] in line with [source]: changed files are copied, removed files are removed, outputs and Gradle state stay. */
    public static void sync(File source, File work) throws IOException {
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Could not create directory: " + work);
        Set<String> present = new HashSet<>();
        File[] children = source.listFiles();
        if (children != null) {
            for (File child : children) {
                String name = child.getName();
                if (NOT_COPIED.contains(name)) continue;
                present.add(name);
                File target = new File(work, name);
                if (child.isDirectory()) {
                    if (target.exists() && !target.isDirectory()) deleteTree(target);
                    sync(child, target);
                } else {
                    if (target.isDirectory()) deleteTree(target);
                    if (!sameFile(child, target)) copyFile(child, target);
                }
            }
        }
        File[] existing = work.listFiles();
        if (existing != null) {
            for (File entry : existing) {
                String name = entry.getName();
                if (present.contains(name) || KEPT.contains(name) || name.equals(STAMP_FILE)) continue;
                deleteTree(entry);
            }
        }
    }

    private static boolean sameFile(File source, File target) {
        return target.isFile() && target.length() == source.length() && target.lastModified() == source.lastModified();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        target.setLastModified(source.lastModified());   // same size and time next build means "unchanged"
    }

    /** Deletes a file or folder tree; symbolic links are removed, never followed. Never throws. */
    public static void deleteTree(File file) {
        if (file == null) return;
        try {
            if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) deleteTree(child);
            }
            file.delete();
        } catch (RuntimeException ignored) { }
    }
}
