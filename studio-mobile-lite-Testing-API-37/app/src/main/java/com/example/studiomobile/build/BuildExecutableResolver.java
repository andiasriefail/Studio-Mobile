package com.example.studiomobile.build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves tool binaries without silently substituting another tool.
 *
 * Android 10+ blocks executing files from an app's own data directory when the app targets API 29+.
 * Binaries packaged as {@code lib<name>.so} under jniLibs and extracted to the native library directory
 * are exempt, so that location is searched first. The data-directory locations remain as fallbacks
 * (they work on older devices or when targetSdk is 28).
 */
public final class BuildExecutableResolver {
    private static volatile File nativeLibraryDirectory;
    private BuildExecutableResolver() { }

    /** Registers ApplicationInfo.nativeLibraryDir. Call once at pipeline creation. */
    public static void registerNativeLibraryDirectory(File directory) { nativeLibraryDirectory = directory; }

    /** Every runnable candidate in priority order: native library directory, then root, root/bin, root/native. */
    public static List<File> candidates(File root, String name) {
        List<File> result = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) return result;
        File nativeDirectory = nativeLibraryDirectory;
        List<File> locations = new ArrayList<>();
        if (nativeDirectory != null) locations.add(new File(nativeDirectory, "lib" + name + ".so"));
        if (root != null) { locations.add(new File(root, name)); locations.add(new File(root, "bin/" + name)); locations.add(new File(root, "native/" + name)); }
        for (File candidate : locations) if (candidate.isFile() && candidate.canExecute() && candidate.length() > 0) result.add(candidate);
        return result;
    }

    public static File resolve(File root, String name) {
        List<File> all = candidates(root, name);
        return all.isEmpty() ? null : all.get(0);
    }
}
