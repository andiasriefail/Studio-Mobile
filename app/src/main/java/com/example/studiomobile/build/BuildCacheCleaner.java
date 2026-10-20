package com.example.studiomobile.build;

import java.io.File;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/**
 * Removes the temporary folders a build leaves in the app's cache: the working folder of each build
 * ({@code local-build-*}, {@code offline-build-*}) and the copy of the project that was built ({@code user-project-build}).
 *
 * <p>A build normally deletes its own folders when it ends. {@link #cleanLeftovers} is the safety net for folders that older versions
 * never deleted, or that were left behind when a build was cut short. It does nothing while a build is running.
 */
public final class BuildCacheCleaner {
    private BuildCacheCleaner() { }

    /** Deletes a file or folder tree. Symbolic links are removed, never followed. Never throws. */
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

    /** @return how many leftover folders were removed */
    public static int cleanLeftovers(File cacheDir, BooleanSupplier buildRunning) {
        File[] children = cacheDir == null ? null : cacheDir.listFiles();
        if (children == null) return 0;
        int removed = 0;
        for (File child : children) {
            String name = child.getName();
            boolean ours = name.startsWith("local-build-") || name.startsWith("offline-build-") || name.equals("user-project-build");
            if (!ours) continue;
            if (buildRunning != null && buildRunning.getAsBoolean()) return removed;   // never touch the folders of a running build
            deleteTree(child);
            removed++;
        }
        return removed;
    }
}
