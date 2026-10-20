package com.example.studiomobile.build.gradle;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Gives the Android Gradle Plugin an AAPT2 executable that is literally named {@code aapt2}.
 *
 * AGP accepts {@code -Pandroid.aapt2FromMavenOverride=<path>} only when the file name is exactly "aapt2". The binary that Android
 * is willing to execute is packaged as {@code libaapt2.so} in the native library directory, so a symbolic link named
 * {@code aapt2} that points to it is created in app-private storage. Executing through such a link is allowed on Android 10+
 * because the link target lives in the native library directory.
 */
final class Aapt2Link {
    private static final String DIRECTORY = "build-tools/aapt2-bin";
    private Aapt2Link() { }

    /** Returns a file named aapt2 that AGP accepts, or the original file when no link could be created. Idempotent. */
    static File ensure(File filesDirectory, File aapt2, BuildLogger logger) {
        if (aapt2 == null || aapt2.getName().equals("aapt2")) return aapt2;
        File directory = new File(filesDirectory, DIRECTORY);
        File link = new File(directory, "aapt2");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("directory was not created");
            if (Files.isSymbolicLink(link.toPath()) && Files.readSymbolicLink(link.toPath()).equals(aapt2.toPath())) return link;
            Files.deleteIfExists(link.toPath());
            Files.createSymbolicLink(link.toPath(), aapt2.toPath());
            return link;
        } catch (IOException | UnsupportedOperationException exception) {
            logger.onInfo("Native tool link was not created (" + exception.getClass().getSimpleName() + "); the original file is used instead.");
            return aapt2;
        }
    }
}
