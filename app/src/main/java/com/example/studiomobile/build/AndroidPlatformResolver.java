package com.example.studiomobile.build;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the requested compile SDK platform without hard-coding an API list into the UI. */
public final class AndroidPlatformResolver {
    private AndroidPlatformResolver() { }
    public static File resolve(File toolRoot, File projectDirectory) {
        File platforms = new File(toolRoot, "platforms");
        File module = BuildProjectFiles.moduleDirectory(projectDirectory);
        File gradle = new File(module, "build.gradle");
        if (!gradle.isFile()) gradle = new File(module, "build.gradle.kts");
        if (gradle.isFile()) {
            try {
                String source = new String(java.nio.file.Files.readAllBytes(gradle.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("compileSdk(?:Version)?\\s*(?:=\\s*)?(\\d+(?:\\.\\d+)?)").matcher(source);
                // AGP 9 new DSL: compileSdk { version = release(36) { minorApiLevel = 1 } }
                if (!matcher.find()) matcher = Pattern.compile("compileSdk\\s*\\{[^}]*?release\\(\\s*(\\d+)").matcher(source);
                if (matcher.reset().find()) {
                    File requested = new File(new File(platforms, "android-" + matcher.group(1)), "android.jar");
                    if (requested.isFile() && requested.length() > 0) return requested;
                    // The exact platform is not installed: a newer android.jar still compiles a project that asks for an older compileSdk,
                    // so use the closest installed platform above it. A project asking for a newer API than any installed one cannot be built.
                    File newer = pickNewerPlatform(platforms, Integer.parseInt(matcher.group(1).split("\\.")[0]));
                    if (newer != null) return newer;
                    throw new IllegalStateException("Required compileSdk " + matcher.group(1) + " is newer than the installed Android platforms. Expected platform directory: " + requested.getParentFile().getAbsolutePath() + ". Install that platform or lower compileSdk in the module Gradle file.");
                }
            } catch (java.io.IOException exception) { throw new IllegalStateException("Could not read the module Gradle file to resolve compileSdk.", exception); }
        }
        // No explicit compileSdk (plain project without Gradle): use the platform that matches the manifest targetSdk,
        // otherwise the highest installed one. A newer android.jar still compiles apps that target an older API.
        File installed = pickInstalledPlatform(platforms, BuildProjectFiles.readNumericSdkValue(projectDirectory, false, "36"));
        if (installed != null) return installed;
        File legacy = new File(toolRoot, "android.jar");
        if (legacy.isFile() && legacy.length() > 0) return legacy;
        throw new IllegalStateException("No Android platform android.jar found. Install platforms/android-<compileSdk>/android.jar or configure compileSdk in the module Gradle file.");
    }

    /** The lowest installed platform whose API is at least {@code minimumApi}, else null. */
    static File pickNewerPlatform(File platforms, int minimumApi) {
        File[] entries = platforms.listFiles();
        if (entries == null) return null;
        File best = null; int bestApi = Integer.MAX_VALUE;
        for (File entry : entries) {
            File jar = new File(entry, "android.jar");
            Matcher matcher = Pattern.compile("android-(\\d+)").matcher(entry.getName());
            if (!entry.isDirectory() || !jar.isFile() || jar.length() == 0 || !matcher.find()) continue;
            int api = Integer.parseInt(matcher.group(1));
            if (api >= minimumApi && api < bestApi) { bestApi = api; best = jar; }
        }
        return best;
    }

    /** Exact match for the wanted API when installed, else the highest installed platform, else null. */
    static File pickInstalledPlatform(File platforms, String wantedApi) {
        File[] entries = platforms.listFiles();
        if (entries == null) return null;
        File best = null; int bestApi = -1;
        for (File entry : entries) {
            File jar = new File(entry, "android.jar");
            Matcher matcher = Pattern.compile("android-(\\d+)").matcher(entry.getName());
            if (!entry.isDirectory() || !jar.isFile() || jar.length() == 0 || !matcher.find()) continue;
            int api = Integer.parseInt(matcher.group(1));
            if (String.valueOf(api).equals(wantedApi)) return jar;
            if (api > bestApi) { bestApi = api; best = jar; }
        }
        return best;
    }
}
