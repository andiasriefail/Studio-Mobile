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
    static String ensure(File jdkHome, File launcher) {
        File link = new File(jdkHome, "bin/java");
        try {
            File bin = link.getParentFile();
            if (!bin.isDirectory() && !bin.mkdirs()) return "Could not create " + bin;
            if (Files.isSymbolicLink(link.toPath()) && Files.readSymbolicLink(link.toPath()).equals(launcher.toPath())) return null;
            Files.deleteIfExists(link.toPath());
            Files.createSymbolicLink(link.toPath(), launcher.toPath());
            return null;
        } catch (IOException | UnsupportedOperationException exception) {
            return "Could not link " + link + " to the java launcher: " + exception.getMessage() + ". Gradle may fail when it forks its build JVM.";
        }
    }
}
