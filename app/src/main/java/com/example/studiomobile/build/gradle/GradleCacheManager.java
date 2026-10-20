package com.example.studiomobile.build.gradle;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;

/**
 * Owns the app-private GRADLE_USER_HOME (files/gradle-home) so dependency caches survive between
 * builds, stale locks from killed processes are removed and disk use stays bounded.
 */
public final class GradleCacheManager {
    private static final long STALE_LOCK_MILLIS = 10L * 60 * 1000;
    private final File gradleUserHome;

    public GradleCacheManager(File filesDirectory) { this.gradleUserHome = new File(filesDirectory, "gradle-home"); }
    public File getGradleUserHome() { return gradleUserHome; }
    public File getModulesDirectory() { return new File(gradleUserHome, "caches/modules-2/files-2.1"); }

    public boolean prepare(BuildLogger logger) {
        if (!gradleUserHome.isDirectory() && !gradleUserHome.mkdirs()) { logger.onError("Could not create Gradle user home: " + gradleUserHome, null); return false; }
        int removed = removeStaleLocks(new File(gradleUserHome, "caches"), System.currentTimeMillis());
        if (removed > 0) logger.onInfo("Removed " + removed + " stale Gradle lock file(s) left by an interrupted build.");
        return true;
    }

    /** Deletes only *.lock files untouched for 10 minutes; must run while no Gradle process is active. */
    int removeStaleLocks(File directory, long now) {
        File[] children = directory.listFiles();
        if (children == null) return 0;
        int removed = 0;
        for (File child : children) {
            if (child.isDirectory()) removed += removeStaleLocks(child, now);
            else if (child.getName().endsWith(".lock") && now - child.lastModified() > STALE_LOCK_MILLIS && child.delete()) removed++;
        }
        return removed;
    }

    public long sizeBytes() { return size(gradleUserHome); }

    /** Removes disposable data first (daemon logs, build-cache, file-hash/transform caches); never touches modules-2. */
    public long trimDisposable() {
        long before = sizeBytes();
        deleteTree(new File(gradleUserHome, "daemon"));
        deleteTree(new File(gradleUserHome, "caches/build-cache-1"));
        deleteTree(new File(gradleUserHome, "caches/journal-1"));
        return before - sizeBytes();
    }

    /** Full reset for corrupted caches (checksum / "Could not read" errors). The next build must be online. */
    public boolean clearDependencyCache() {
        deleteTree(new File(gradleUserHome, "caches"));
        return !new File(gradleUserHome, "caches").exists();
    }

    public boolean hasCachedDependencies() {
        String[] groups = getModulesDirectory().list();
        return groups != null && groups.length > 0;
    }

    private static long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0; File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }
    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
