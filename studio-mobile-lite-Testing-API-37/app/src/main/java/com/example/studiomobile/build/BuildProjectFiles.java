package com.example.studiomobile.build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves common single-module Android project layouts without generating source files. */
public final class BuildProjectFiles {
    private BuildProjectFiles() { }
    public static File moduleDirectory(File project) {
        File app = new File(project, "app");
        if (new File(app, "src/main/AndroidManifest.xml").isFile() || new File(app, "build.gradle").isFile() || new File(app, "build.gradle.kts").isFile()) return app;
        return project;
    }
    public static File mainDirectory(File project) { return new File(moduleDirectory(project), "src/main"); }
    public static File manifest(File project) { return new File(mainDirectory(project), "AndroidManifest.xml"); }
    public static List<File> filesWithExtension(File root, String extension) {
        List<File> result = new ArrayList<>(); collect(root, extension, result); return result;
    }
    private static void collect(File root, String extension, List<File> result) {
        if (root == null || !root.isDirectory()) return;
        File[] children = root.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collect(child, extension, result); else if (child.getName().endsWith(extension)) result.add(child); }
    }
    public static String readNumericSdkValue(File project, boolean minimum, String fallback) {
        File module = moduleDirectory(project);
        File buildFile = new File(module, "build.gradle");
        if (!buildFile.isFile()) buildFile = new File(module, "build.gradle.kts");
        if (!buildFile.isFile()) return readManifestSdkValue(project, minimum, fallback);
        String keys = minimum ? "(?:minSdkVersion|minSdk)" : "(?:targetSdkVersion|targetSdk)";
        try {
            String source = new String(Files.readAllBytes(buildFile.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(keys + "\\s*(?:=\\s*)?(\\d+)").matcher(source);
            if (matcher.find()) return matcher.group(1);
            // AGP 9 new DSL: minSdk { version = release(26) } / targetSdk { version = release(36) }
            Matcher block = Pattern.compile((minimum ? "minSdk" : "targetSdk") + "\\s*\\{[^}]*?release\\(\\s*(\\d+)").matcher(source);
            if (block.find()) return block.group(1);
        } catch (Exception ignored) { }
        return fallback;
    }
    /** applicationId (or namespace-style package) declared in the module Gradle file, or {@code fallback} when it is not a literal. */
    public static String readApplicationId(File project, String fallback) {
        File module = moduleDirectory(project);
        File buildFile = new File(module, "build.gradle");
        if (!buildFile.isFile()) buildFile = new File(module, "build.gradle.kts");
        if (!buildFile.isFile()) return fallback;
        try {
            String source = new String(Files.readAllBytes(buildFile.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\\bapplicationId\\s*(?:=\\s*)?[\"']([A-Za-z0-9_.]+)[\"']").matcher(source);
            if (matcher.find()) return matcher.group(1);
        } catch (Exception ignored) { }
        return fallback;
    }
    /** Reads android:minSdkVersion / android:targetSdkVersion from the manifest's uses-sdk element. */
    static String readManifestSdkValue(File project, boolean minimum, String fallback) {
        File manifest = manifest(project);
        if (!manifest.isFile()) return fallback;
        try {
            String source = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("android:" + (minimum ? "minSdkVersion" : "targetSdkVersion") + "\\s*=\\s*\"(\\d+)\"").matcher(source);
            if (matcher.find()) return matcher.group(1);
        } catch (Exception ignored) { }
        return fallback;
    }
    public static List<File> localLibraries(File project) {
        List<File> result = new ArrayList<>();
        File module = moduleDirectory(project);
        result.addAll(filesWithExtension(new File(module, "libs"), ".jar"));
        if (!module.equals(project)) result.addAll(filesWithExtension(new File(project, "libs"), ".jar"));
        // Library modules of a multi-module project contribute their own libs/*.jar.
        for (ProjectModules.Module library : ProjectModules.load(project).libraries) {
            for (File jar : filesWithExtension(new File(library.directory, "libs"), ".jar")) if (!result.contains(jar)) result.add(jar);
        }
        return result;
    }
    /** Sorted jar files directly inside a directory (null-safe). */
    public static List<File> jarsIn(File directory) {
        List<File> result = new ArrayList<>();
        File[] files = directory == null ? null : directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files != null) { java.util.Arrays.sort(files); result.addAll(java.util.Arrays.asList(files)); }
        return result;
    }
    public static String joinPaths(List<File> files) {
        StringBuilder value = new StringBuilder();
        for (File file : files) { if (value.length() > 0) value.append(File.pathSeparator); value.append(file.getAbsolutePath()); }
        return value.toString();
    }
}
