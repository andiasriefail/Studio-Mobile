package com.example.studiomobile.python;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.DownloadPrompt;
import com.example.studiomobile.build.dependency.BuildComponentInstaller;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.dependency.SecureTarExtractor;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Downloads the checksum-pinned Python distribution from the catalog (component {@value #COMPONENT_ID}) and unpacks it into {@code files/python}. */
public final class PythonProvisioner {
    public static final String COMPONENT_ID = "python-3.14.8";
    private final File filesDirectory;
    private final BuildToolCatalog catalog;
    private final List<String> supportedAbis;
    private final BuildComponentInstaller installer = new BuildComponentInstaller();

    public PythonProvisioner(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis) {
        this.filesDirectory = filesDirectory; this.catalog = catalog; this.supportedAbis = supportedAbis;
    }

    public void cancel() { installer.cancel(); }

    /** @return the Python home, or null (reason logged) when it could not be installed. */
    public File install(BuildLogger logger) {
        BuildToolCatalog.Component component = find();
        if (component == null) { logger.onError("The bundled catalog has no '" + COMPONENT_ID + "' entry for this device (ABIs " + supportedAbis + ").", null); return null; }
        if (!DownloadPrompt.allow("Python")) { logger.onInfo("Download cancelled."); return null; }
        File destination = new File(filesDirectory, "python");
        File staging = new File(filesDirectory, ".staging-python-" + System.currentTimeMillis());
        File archive = null;
        try {
            logger.onInfo("Downloading Python " + component.version + " (SHA-256 verified, license " + component.license + "). One-time download.");
            archive = installer.install(component.toDownloadableBuildComponent(), new File(filesDirectory, "downloads"), new BuildComponentInstaller.ProgressListener() {
                private long lastReported;
                @Override public void onProgress(String id, long done, long expected) {
                    if (done - lastReported >= 4L * 1024 * 1024) { lastReported = done; logger.onInfo("Python: " + (done >> 20) + (expected > 0 ? " / " + (expected >> 20) : "") + " MiB downloaded"); }
                }
            });
            logger.onInfo("Extracting Python...");
            // The archive has a single top-level folder (python/); strip it so lib/ and bin/ land directly in files/python.
            int files = SecureTarExtractor.extract(archive, staging, 1, null, 1L * 1024 * 1024 * 1024);
            if (PythonRuntime.findStandardLibrary(staging) == null) throw new IOException("The archive does not contain lib/python3.x/os.py (" + files + " files were extracted).");
            deleteTree(destination);
            if (!staging.renameTo(destination)) throw new IOException("Could not move the extracted Python into " + destination);
            logger.onInfo("Python installed at " + destination + " (" + files + " files).");
            return destination;
        } catch (IOException | RuntimeException exception) {
            logger.onError("Python installation failed: " + exception.getMessage(), exception);
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
