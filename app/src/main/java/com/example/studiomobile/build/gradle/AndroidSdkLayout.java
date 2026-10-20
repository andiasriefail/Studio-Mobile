package com.example.studiomobile.build.gradle;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Presents the platform jars Studio Mobile installed under {@code files/build-tools/platforms/android-<API>/android.jar} as a
 * minimal Android SDK at {@code files/android-sdk/platforms/android-<API>/android.jar}, which is where the Gradle build
 * (through local.properties sdk.dir) looks for them. Nothing is downloaded. Links are used so the jars are not duplicated;
 * a copy is made only when a link cannot be created.
 */
final class AndroidSdkLayout {
    private static final Pattern PLATFORM = Pattern.compile("android-\\d+");
    private AndroidSdkLayout() { }

    /** Returns the SDK directory when at least one platform is available in it, otherwise null. Idempotent. */
    static File ensure(File filesDirectory, BuildLogger logger) {
        File sdk = new File(filesDirectory, "android-sdk");
        File[] installed = new File(filesDirectory, "build-tools/platforms").listFiles();
        if (installed != null) {
            for (File platform : installed) {
                File jar = new File(platform, "android.jar");
                if (!platform.isDirectory() || !PLATFORM.matcher(platform.getName()).matches() || jar.length() == 0) continue;
                File target = new File(sdk, "platforms/" + platform.getName() + "/android.jar");
                String problem = expose(jar, target);
                if (problem != null) logger.onInfo(problem);
            }
        }
        File[] exposed = new File(sdk, "platforms").listFiles();
        if (exposed != null) for (File platform : exposed) if (new File(platform, "android.jar").length() > 0) return sdk;
        return null;
    }

    /** Returns null on success, otherwise a message for the full log. */
    private static String expose(File source, File target) {
        try {
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return "Platform folder was not created: " + parent;
            if (Files.isSymbolicLink(target.toPath()) && Files.readSymbolicLink(target.toPath()).equals(source.toPath())) return null;
            if (!Files.isSymbolicLink(target.toPath()) && target.isFile() && target.length() == source.length()) return null;
            Files.deleteIfExists(target.toPath());
            try { Files.createSymbolicLink(target.toPath(), source.toPath()); }
            catch (IOException | UnsupportedOperationException linkFailed) { Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return null;
        } catch (IOException exception) {
            return "Platform jar was not prepared in " + target.getParentFile().getName() + " (" + exception.getClass().getSimpleName() + ").";
        }
    }
}
