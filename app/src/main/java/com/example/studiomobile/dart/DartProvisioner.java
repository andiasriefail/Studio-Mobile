package com.example.studiomobile.dart;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.DownloadPrompt;
import com.example.studiomobile.build.dependency.BuildComponentInstaller;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.dependency.SecureTarExtractor;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Downloads the checksum-pinned Dart SDK (catalog component {@value #COMPONENT_ID}, a 150 MB .tar.gz built by this project's own
 * workflow) once and keeps only what compiling needs: the front-end snapshot, the VM platform dill and the SDK library sources.
 */
public final class DartProvisioner {
    public static final String COMPONENT_ID = "dart-sdk-3.13.5";
    private final File filesDirectory;
    private final BuildToolCatalog catalog;
    private final List<String> supportedAbis;
    private final BuildComponentInstaller installer = new BuildComponentInstaller();

    public DartProvisioner(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis) { this.filesDirectory = filesDirectory; this.catalog = catalog; this.supportedAbis = supportedAbis; }

    public void cancel() { installer.cancel(); }

    /** Paths are relative to the SDK root (after dropping the dart-sdk/ folder). */
    static boolean isNeeded(String path) {
        if (path.equals(DartRuntime.FRONTEND_SNAPSHOT)) return true;
        if (!path.startsWith("lib/")) return false;
        if (path.startsWith("lib/_internal/")) return path.equals(DartRuntime.PLATFORM_DILL) || path.startsWith("lib/_internal/sdk_library_metadata/");
        return !path.endsWith(".dill");
    }

    public File install(BuildLogger logger) {
        BuildToolCatalog.Component component = find();
        if (component == null) { logger.onError("The bundled catalog has no '" + COMPONENT_ID + "' entry for this device (ABIs " + supportedAbis + "). Run the APK workflow after the Dart SDK build so it pins the SDK.", null); return null; }
        if (!DownloadPrompt.allow("Dart SDK")) { logger.onInfo("Download cancelled."); return null; }
        File destination = new File(filesDirectory, "dart-sdk");
        File staging = new File(filesDirectory, ".staging-dart-" + System.currentTimeMillis());
        File archive = null;
        try {
            logger.onInfo("Downloading Dart SDK " + component.version + " (SHA-256 verified). One-time download.");
            archive = installer.install(component.toDownloadableBuildComponent(), new File(filesDirectory, "downloads"), new BuildComponentInstaller.ProgressListener() {
                private long lastReported;
                @Override public void onProgress(String id, long done, long expected) {
                    if (done - lastReported >= 16L * 1024 * 1024) { lastReported = done; logger.onInfo("Dart: " + (done >> 20) + (expected > 0 ? " / " + (expected >> 20) : "") + " MiB downloaded"); }
                }
            });
            logger.onInfo("Extracting the Dart front end...");
            int files = SecureTarExtractor.extract(archive, staging, 1, DartProvisioner::isNeeded, 1L * 1024 * 1024 * 1024);
            if (!DartRuntime.isSdkInstalled(staging)) throw new IOException("The archive does not contain " + DartRuntime.FRONTEND_SNAPSHOT + " and " + DartRuntime.PLATFORM_DILL + " (" + files + " files extracted).");
            deleteTree(destination);
            if (!staging.renameTo(destination)) throw new IOException("Could not move the extracted SDK into " + destination);
            logger.onInfo("Dart SDK installed at " + destination + " (" + files + " files).");
            return destination;
        } catch (IOException | RuntimeException exception) {
            logger.onError("Dart SDK installation failed: " + exception.getMessage(), exception);
            return null;
        } finally {
            deleteTree(staging);
            if (archive != null) archive.delete();
        }
    }

    private BuildToolCatalog.Component find() {
        if (catalog == null) return null;
        for (BuildToolCatalog.Component component : catalog.getComponents())
            if (component.id.equals(COMPONENT_ID) && ("any".equals(component.abi) || supportedAbis.contains(component.abi))) return component;
        return null;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        file.delete();
    }
}
