package com.example.studiomobile.build.flutter;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.DownloadPrompt;
import com.example.studiomobile.build.dependency.BuildComponentInstaller;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.dependency.SecureTarExtractor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Predicate;

/**
 * Makes the Flutter SDK available on the device. Two parts:
 * <ul>
 *   <li>Three executables ship inside the APK as {@code lib*.so} ({@link #DART_LIBRARY}, {@link #GEN_SNAPSHOT_LIBRARY},
 *       {@link #IMPELLERC_LIBRARY}), because Android 10+ only lets an app execute files from its native library directory. The CI build
 *       copies them out of the Flutter SDK archive.</li>
 *   <li>The rest of the SDK (framework, tool sources, engine artifacts) is downloaded once from the checksum-pinned catalog entry
 *       {@code flutter-sdk-<version>} (written by tools/pin_flutter_catalog.py) and unpacked into {@code files/build-tools/flutter-sdk}.</li>
 * </ul>
 */
public final class FlutterSdkProvisioner {
    public static final String COMPONENT_PREFIX = "flutter-sdk-";
    public static final String DART_LIBRARY = "libflutterdart.so";
    public static final String GEN_SNAPSHOT_LIBRARY = "libgensnapshot.so";
    public static final String IMPELLERC_LIBRARY = "libimpellerc.so";
    static final String INSTALL_DIRECTORY = "flutter-sdk";
    static final String MARKER_FILE = ".studio-flutter-component";
    static final long REQUIRED_FREE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 2L * 1024 * 1024 * 1024;

    /** Files the Flutter build reads; all of them were checked to exist in the archive by the slimming workflow. */
    static final String[] REQUIRED_FILES = {
        "bin/internal/engine.version",
        "packages/flutter/lib/widgets.dart",
        "packages/flutter_tools/lib/executable.dart",
        "bin/cache/dart-sdk/lib/_internal/vm_platform_strong.dill",
        "bin/cache/artifacts/engine/linux-arm64/gen_snapshot",
        "bin/cache/artifacts/engine/linux-arm64/frontend_server_aot.dart.snapshot",
        "bin/cache/artifacts/engine/common/flutter_patched_sdk_product/platform_strong.dill"
    };

    private final File filesDirectory;
    private final BuildToolCatalog catalog;
    private final List<String> supportedAbis;
    private final BuildComponentInstaller installer = new BuildComponentInstaller();

    public FlutterSdkProvisioner(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis) {
        this.filesDirectory = filesDirectory; this.catalog = catalog; this.supportedAbis = supportedAbis;
    }

    public void cancel() { installer.cancel(); }

    /** A bundled executable inside the native library directory. */
    public static File bundled(File nativeLibraryDirectory, String name) throws IOException {
        File file = new File(nativeLibraryDirectory, name);
        if (!file.isFile() || file.length() == 0)
            throw new IOException("The Flutter executable " + name + " is missing from this build of Studio Mobile (" + file + "). "
                + "The 'Bundle the Flutter executables' step of the CI build must have bundled it.");
        return file;
    }

    /** Every file of the archive is kept: the slimming already happened when the archive was made. */
    static boolean isNeeded(String path) { return !path.isEmpty(); }

    /** @return null when the unpacked SDK has the parts the build needs, otherwise the first one that is missing. */
    static String verifyLayout(File sdkRoot) {
        for (String relative : REQUIRED_FILES) if (!new File(sdkRoot, relative).isFile()) return relative + " is missing";
        return null;
    }

    /**
     * @return the installed SDK root ({@code files/build-tools/flutter-sdk}), or null when the person declined the download.
     * @throws IOException when the SDK cannot be installed, with the reason.
     */
    public File install(File toolsDirectory, BuildLogger logger) throws IOException {
        BuildToolCatalog.Component component = find();
        if (component == null)
            throw new IOException("The bundled catalog has no '" + COMPONENT_PREFIX + "<version>' entry for this device (ABIs " + supportedAbis + "). "
                + "tools/pin_flutter_catalog.py adds it in CI; Flutter builds need an arm64 device.");
        File destination = new File(toolsDirectory, INSTALL_DIRECTORY);
        String marker = component.id + " " + component.sha256;
        if (isInstalled(destination, marker)) { logger.onInfo("Flutter SDK " + component.version + " is already installed."); return destination; }
        if (!toolsDirectory.isDirectory() && !toolsDirectory.mkdirs()) throw new IOException("Could not create " + toolsDirectory);
        long free = toolsDirectory.getUsableSpace();
        if (free < REQUIRED_FREE_BYTES)
            throw new IOException("Not enough free storage for the Flutter SDK: about " + (REQUIRED_FREE_BYTES >> 20) + " MiB are needed (download plus unpacked files) and "
                + (free >> 20) + " MiB are free.");
        if (!DownloadPrompt.allow("Flutter SDK")) { logger.onInfo("Download cancelled."); return null; }
        File staging = new File(toolsDirectory, ".staging-flutter-" + System.currentTimeMillis());
        File archive = null;
        try {
            logger.onInfo("Downloading Flutter SDK " + component.version + " (SHA-256 verified, license " + component.license + "). This is a one-time download.");
            archive = installer.install(component.toDownloadableBuildComponent(), new File(filesDirectory, "downloads"), new BuildComponentInstaller.ProgressListener() {
                private long lastReported;
                @Override public void onProgress(String id, long done, long expected) {
                    if (done - lastReported >= 32L * 1024 * 1024) { lastReported = done; logger.onInfo("Flutter SDK: " + (done >> 20) + (expected > 0 ? " / " + (expected >> 20) : "") + " MiB downloaded"); }
                }
            });
            logger.onInfo("Extracting the Flutter SDK...");
            Predicate<String> filter = FlutterSdkProvisioner::isNeeded;
            int files = SecureTarExtractor.extract(archive, staging, 1, filter, MAX_UNPACKED_BYTES); // strips the leading flutter/
            String problem = verifyLayout(staging);
            if (files == 0 || problem != null) throw new IOException("The Flutter SDK archive is incomplete: " + (problem == null ? "no files were extracted" : problem) + ".");
            Files.write(new File(staging, MARKER_FILE).toPath(), marker.getBytes(StandardCharsets.UTF_8));
            deleteTree(destination);
            if (!staging.renameTo(destination)) throw new IOException("Could not move the extracted Flutter SDK into " + destination);
            logger.onInfo("Flutter SDK installed at " + destination + " (" + files + " files).");
            return destination;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException("Flutter SDK installation failed: " + exception.getMessage(), exception);
        } finally {
            deleteTree(staging);
            if (archive != null) archive.delete();
        }
    }

    private static boolean isInstalled(File destination, String marker) {
        File markerFile = new File(destination, MARKER_FILE);
        try { return markerFile.isFile() && marker.equals(new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8)) && verifyLayout(destination) == null; }
        catch (IOException ignored) { return false; }
    }

    private BuildToolCatalog.Component find() {
        if (catalog == null) return null;
        BuildToolCatalog.Component best = null;
        for (BuildToolCatalog.Component component : catalog.getComponents())
            if (component.id.startsWith(COMPONENT_PREFIX) && "tar.xz".equals(component.archive) && ("any".equals(component.abi) || supportedAbis.contains(component.abi))) best = component;
        return best; // pin_flutter_catalog.py keeps exactly one flutter-sdk entry
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        file.delete();
    }
}
