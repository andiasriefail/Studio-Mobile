package com.example.studiomobile.build.nativecode;

import com.example.studiomobile.build.BuildLogger;
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
 * Makes the Dart SDK available on the device. Dart is a mandatory part of the toolchain, so every problem here is an {@link IOException}
 * that stops the build; nothing is skipped silently.
 *
 * <p>Two parts, both taken from the Dart release of the separate dart-android repository named in tools/dart-sdk.json:
 * <ul>
 *   <li>The executables {@code dartaotruntime} and {@code dartvm} ship inside the APK as {@code libdartaotruntime.so} /
 *       {@code libdartvm.so}, because Android 10+ only lets an app execute files from its native library directory
 *       ({@link #runtime(File)}).</li>
 *   <li>Everything else the compiler reads (core libraries in {@code lib/}, the compiler snapshots in {@code bin/snapshots/}, ...) is
 *       downloaded once from the checksum-pinned catalog entry {@code dart-sdk-<version>} (written into the catalog by
 *       tools/pin_dart_catalog.py) and unpacked into {@code files/build-tools/dart-sdk} ({@link #install(File, BuildLogger)}).</li>
 * </ul>
 */
public final class DartProvisioner {
    public static final String COMPONENT_PREFIX = "dart-sdk-";
    public static final String RUNTIME_LIBRARY = "libdartaotruntime.so";
    public static final String VM_LIBRARY = "libdartvm.so";
    static final String INSTALL_DIRECTORY = "dart-sdk";
    static final String MARKER_FILE = ".studio-dart-component";
    private static final long MAX_UNPACKED_BYTES = 1536L * 1024 * 1024;

    private final File filesDirectory;
    private final BuildToolCatalog catalog;
    private final List<String> supportedAbis;
    private final BuildComponentInstaller installer = new BuildComponentInstaller();

    public DartProvisioner(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis) {
        this.filesDirectory = filesDirectory; this.catalog = catalog; this.supportedAbis = supportedAbis;
    }

    public void cancel() { installer.cancel(); }

    /** The bundled {@code dartaotruntime} executable inside the native library directory. */
    public static File runtime(File nativeLibraryDirectory) throws IOException { return bundled(nativeLibraryDirectory, RUNTIME_LIBRARY); }

    /** The bundled {@code dartvm} executable (JIT) inside the native library directory. */
    public static File vm(File nativeLibraryDirectory) throws IOException { return bundled(nativeLibraryDirectory, VM_LIBRARY); }

    private static File bundled(File nativeLibraryDirectory, String name) throws IOException {
        File file = new File(nativeLibraryDirectory, name);
        if (!file.isFile() || file.length() == 0)
            throw new IOException("The Dart runtime (" + name + ") is missing from this build of Studio Mobile (" + file + "). The 'Bundle the Dart runtime' step of the CI build must have bundled it; Dart is not optional.");
        return file;
    }

    /**
     * Keeps what the Dart compiler reads from the SDK (paths are relative to the SDK root, for example {@code lib/core/core.dart} or
     * {@code bin/snapshots/frontend_server_aot.dart.snapshot}). Executables directly in {@code bin/} are left out: they cannot be run from
     * app storage and the runtime comes from the native library directory instead.
     */
    static boolean isNeeded(String path) {
        if (path.isEmpty() || path.startsWith("include/")) return false;
        if (path.startsWith("bin/")) return path.indexOf('/', "bin/".length()) > 0;
        return true;
    }

    /**
     * @return the installed SDK root ({@code files/build-tools/dart-sdk}); reuses an earlier installation of the same catalog entry.
     * @throws IOException when the SDK cannot be installed, with the reason; the build must not continue without Dart.
     */
    public File install(File toolsDirectory, BuildLogger logger) throws IOException {
        BuildToolCatalog.Component component = find();
        if (component == null)
            throw new IOException("The bundled catalog has no '" + COMPONENT_PREFIX + "<version>' entry for this device (ABIs " + supportedAbis + "). tools/pin_dart_catalog.py adds it in CI after the Dart runtime is bundled; Dart is not optional.");
        File destination = new File(toolsDirectory, INSTALL_DIRECTORY);
        String marker = component.id + " " + component.sha256;
        if (isInstalled(destination, marker)) { logger.onInfo("Dart SDK " + component.version + " is already installed."); return destination; }
        File staging = new File(toolsDirectory, ".staging-dart-" + System.currentTimeMillis());
        File archive = null;
        try {
            if (!toolsDirectory.isDirectory() && !toolsDirectory.mkdirs()) throw new IOException("Could not create " + toolsDirectory);
            logger.onInfo("Downloading Dart SDK " + component.version + " (" + (component.maxBytes >> 20) + " MiB max, SHA-256 verified, license " + component.license + "). This is a one-time download.");
            archive = installer.install(component.toDownloadableBuildComponent(), new File(filesDirectory, "downloads"), new BuildComponentInstaller.ProgressListener() {
                private long lastReported;
                @Override public void onProgress(String id, long done, long expected) {
                    if (done - lastReported >= 16L * 1024 * 1024) { lastReported = done; logger.onInfo("Dart SDK: " + (done >> 20) + (expected > 0 ? " / " + (expected >> 20) : "") + " MiB downloaded"); }
                }
            });
            logger.onInfo("Extracting the Dart SDK...");
            Predicate<String> filter = DartProvisioner::isNeeded;
            int files = SecureTarExtractor.extract(archive, staging, 1, filter, MAX_UNPACKED_BYTES); // strips the leading dart-sdk/
            String problem = verifyLayout(staging);
            if (files == 0 || problem != null) throw new IOException("The Dart SDK archive is incomplete: " + (problem == null ? "no files were extracted" : problem) + ".");
            Files.write(new File(staging, MARKER_FILE).toPath(), marker.getBytes(StandardCharsets.UTF_8));
            deleteTree(destination);
            if (!staging.renameTo(destination)) throw new IOException("Could not move the extracted Dart SDK into " + destination);
            logger.onInfo("Dart SDK installed at " + destination + " (" + files + " files).");
            return destination;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException("Dart SDK installation failed: " + exception.getMessage(), exception);
        } finally {
            deleteTree(staging);
            if (archive != null) archive.delete();
        }
    }

    /** @return null when the unpacked SDK has the parts the compiler needs, otherwise what is missing. */
    static String verifyLayout(File sdkRoot) {
        if (!new File(sdkRoot, "lib/core/core.dart").isFile()) return "lib/core/core.dart is missing";
        File[] snapshots = new File(sdkRoot, "bin/snapshots").listFiles((directory, name) -> name.endsWith(".snapshot"));
        if (snapshots == null || snapshots.length == 0) return "bin/snapshots contains no compiler snapshots";
        return null;
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
            if (component.id.startsWith(COMPONENT_PREFIX) && "tar.gz".equals(component.archive) && ("any".equals(component.abi) || supportedAbis.contains(component.abi))) best = component;
        return best; // pin_dart_catalog.py keeps exactly one dart-sdk entry
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        file.delete();
    }
}
