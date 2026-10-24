package com.example.studiomobile.build.flutter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * Recognises a Flutter project (for example one moved over from Android Studio): a {@code pubspec.yaml} that depends on the Flutter SDK
 * ({@code flutter: sdk: flutter}) plus an {@code android/} folder with Gradle files. A plain Dart project (no Flutter dependency) is not
 * a Flutter project and keeps going through the Dart pipeline.
 */
public final class FlutterProjectDetector {
    private static final long MAX_PUBSPEC_BYTES = 512 * 1024;
    private static final Pattern FULL_LINE_COMMENT = Pattern.compile("(?m)^[ \\t]*#.*$");
    /** {@code flutter:} as a dependency, with {@code sdk: flutter} on the next line or inline ({@code flutter: {sdk: flutter}}). */
    private static final Pattern SDK_FLUTTER = Pattern.compile(
        "(?m)^[ \\t]+flutter:[ \\t]*(?:\\r?\\n[ \\t]+sdk:[ \\t]*flutter\\b|\\{[^}\\r\\n]*\\bsdk:[ \\t]*flutter\\b)");

    private FlutterProjectDetector() { }

    /** The result of looking at a project folder. */
    public static final class Detection {
        private final File root, androidDirectory;
        private final String problem;
        private Detection(File root, File androidDirectory, String problem) { this.root = root; this.androidDirectory = androidDirectory; this.problem = problem; }
        /** True when the folder is a Flutter project, even one that cannot be built (see {@link #getProblem()}). */
        public boolean isFlutter() { return root != null; }
        /** True when it is a Flutter project that has the android/ build files. */
        public boolean isBuildable() { return root != null && problem == null; }
        public File getRoot() { return root; }
        public File getAndroidDirectory() { return androidDirectory; }
        /** Why a Flutter project cannot be built, or null. */
        public String getProblem() { return problem; }
    }

    public static Detection detect(File project) {
        if (project == null || !project.isDirectory()) return new Detection(null, null, null);
        File pubspec = new File(project, "pubspec.yaml");
        if (!pubspec.isFile() || pubspec.length() > MAX_PUBSPEC_BYTES || !dependsOnFlutter(pubspec)) return new Detection(null, null, null);
        File android = new File(project, "android");
        if (!android.isDirectory() || !hasGradleFiles(android))
            return new Detection(project, android, "This Flutter project has no android/ folder with Gradle files. Create it on a computer with "
                + "'flutter create --platforms=android .' and move the project again.");
        return new Detection(project, android, null);
    }

    static boolean dependsOnFlutter(File pubspec) {
        try {
            String text = new String(Files.readAllBytes(pubspec.toPath()), StandardCharsets.UTF_8);
            return SDK_FLUTTER.matcher(FULL_LINE_COMMENT.matcher(text).replaceAll("")).find();
        } catch (IOException unreadable) { return false; }
    }

    private static boolean hasGradleFiles(File android) {
        return new File(android, "settings.gradle").isFile() || new File(android, "settings.gradle.kts").isFile()
            || new File(android, "build.gradle").isFile() || new File(android, "build.gradle.kts").isFile();
    }
}
