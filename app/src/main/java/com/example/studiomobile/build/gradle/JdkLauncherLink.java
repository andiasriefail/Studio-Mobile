package com.example.studiomobile.build.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Makes {@code <jdk>/bin/java} a symbolic link to the java launcher packaged in the native library directory.
 *
 * Gradle starts a child JVM from {@code <java.home>/bin/java}. A regular file in app-private storage cannot be executed on
 * Android 10+, but a link whose target is in the native library directory can. The launcher then reports its own location as
 * java.home, which is why the runtime also exports NLAUNCHER_JAVA_HOME.
 */
final class JdkLauncherLink {
    private JdkLauncherLink() { }

    /** Returns null on success, otherwise a warning to log. Idempotent. */
    static String ensure(File jdkHome, File launcher) { return ensureTool(jdkHome, "java", launcher); }

    /**
     * Same link for another JDK tool that Gradle or the Android Gradle Plugin starts as a process, such as {@code jlink} (AGP runs it
     * to build the JDK image for compileSdk 34 and higher). The tool's own binary in app-private storage is refused with
     * "Exec failed, error: 13 (Permission denied)", so a copy packaged in the native library directory takes its place.
     */
    static String ensureTool(File jdkHome, String tool, File launcher) {
        File link = new File(jdkHome, "bin/" + tool);
        try {
            File bin = link.getParentFile();
            if (!bin.isDirectory() && !bin.mkdirs()) return "Could not create " + bin;
            if (Files.isSymbolicLink(link.toPath()) && Files.readSymbolicLink(link.toPath()).equals(launcher.toPath())) return null;
            Files.deleteIfExists(link.toPath());
            Files.createSymbolicLink(link.toPath(), launcher.toPath());
            return null;
        } catch (IOException | UnsupportedOperationException exception) {
            return "Could not link " + link + " to " + launcher.getName() + ": " + exception.getMessage() + ". The build may fail when it starts " + tool + ".";
        }
    }
}
