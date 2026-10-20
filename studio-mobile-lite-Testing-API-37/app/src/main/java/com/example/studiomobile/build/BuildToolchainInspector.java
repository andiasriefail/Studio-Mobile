package com.example.studiomobile.build;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Verifies real local toolchain files before a build is attempted. */
public final class BuildToolchainInspector {
    private final Context context;
    public BuildToolchainInspector(Context context) { this.context = context.getApplicationContext(); BuildExecutableResolver.registerNativeLibraryDirectory(new File(this.context.getApplicationInfo().nativeLibraryDir)); }

    public List<String> inspect() {
        File root = new File(context.getFilesDir(), "build-tools");
        List<String> missing = new ArrayList<>();
        if (!hasAnyAndroidPlatform(root)) missing.add("No Android platform found. Install build-tools/platforms/android-<API>/android.jar (or legacy build-tools/android.jar). API-specific platform is selected from compileSdk.");
        if (!hasExecutable(root, "aapt2")) missing.add("AAPT2 resource compiler is missing or not executable. Expected lib aapt2 .so in the native library directory, or build-tools/aapt2, build-tools/bin/aapt2, build-tools/native/aapt2.");
        else { File aapt2 = BuildExecutableResolver.resolve(root, "aapt2"); String problem = aapt2 == null ? null : NativeToolSelfTest.check(aapt2); if (problem != null) missing.add(problem); }
        requireFile(root, "kotlin-stdlib.jar", "Kotlin standard library for project compilation", missing);
        // zipalign is optional: ApkAlignmentStage falls back to the verified in-process ZipAligner.
        return Collections.unmodifiableList(missing);
    }

    private boolean hasExecutable(File root, String name) {
        for (File candidate : BuildExecutableResolver.candidates(root, name)) if (!isGitLfsPointer(candidate)) return true;
        return false;
    }

    private boolean hasAnyAndroidPlatform(File root) {
        File legacy = new File(root, "android.jar");
        if (legacy.isFile() && legacy.length() > 0 && !isGitLfsPointer(legacy)) return true;
        File platforms = new File(root, "platforms");
        File[] entries = platforms.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            File jar = new File(entry, "android.jar");
            if (entry.isDirectory() && jar.isFile() && jar.length() > 0 && !isGitLfsPointer(jar)) return true;
        }
        return false;
    }

    private void requireFile(File root, String name, String description, List<String> missing) {
        File candidate = new File(root, name);
        if (!candidate.isFile() || candidate.length() == 0) {
            missing.add(description + " is missing (expected " + candidate.getAbsolutePath() + ").");
        } else if (isGitLfsPointer(candidate)) {
            missing.add(description + " is only a Git LFS pointer, not the actual file (" + name + ").");
        }
    }

    private void requireExecutable(File root, String name, String description, List<String> missing) {
        requireFile(root, name, description, missing);
        File candidate = new File(root, name);
        if (candidate.isFile() && !candidate.canExecute()) {
            missing.add(description + " is not executable (" + candidate.getAbsolutePath() + ").");
        }
    }

    private void requireDirectoryWithJars(File root, String name, String description, List<String> missing) {
        File directory = new File(root, name);
        File[] jars = directory.listFiles((parent, filename) -> filename.endsWith(".jar"));
        if (!directory.isDirectory() || jars == null || jars.length == 0) missing.add(description + " directory is missing or contains no JAR files (" + directory.getAbsolutePath() + ").");
        else for (File jar : jars) if (isGitLfsPointer(jar)) missing.add(description + " contains a Git LFS pointer instead of a real JAR (" + jar.getName() + ").");
    }

    private boolean isGitLfsPointer(File file) {
        byte[] bytes = new byte[128];
        try (FileInputStream input = new FileInputStream(file)) {
            int count = input.read(bytes);
            return count > 0 && new String(bytes, 0, count, StandardCharsets.UTF_8)
                    .startsWith("version https://git-lfs.github.com/spec/v1");
        } catch (IOException ignored) { return false; }
    }
}
