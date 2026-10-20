package com.example.studiomobile.build.gradle;

import android.content.res.AssetManager;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.dependency.BuildComponentInstaller;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.dependency.SecureTarExtractor;
import com.example.studiomobile.build.dependency.SecureZipExtractor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Installs a Gradle distribution or JDK from the checksum-pinned catalog. Component ids are
 * "gradle-<version>" and "jdk-<major>". A component whose archive is shipped inside the APK
 * (assets/jdk/&lt;installPath&gt;, put there by CI) is installed from the APK after its SHA-256 matched the catalog;
 * only when the APK does not carry it is the catalog URL downloaded.
 */
public final class GradleRuntimeProvisioner {
    private static final long MAX_EXTRACTED_BYTES = 3L * 1024 * 1024 * 1024;
    private final File filesDirectory;
    private final BuildToolCatalog catalog;
    private final List<String> supportedAbis;
    private final BuildComponentInstaller installer = new BuildComponentInstaller();
    private volatile AssetManager assets;

    public GradleRuntimeProvisioner(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis) {
        this.filesDirectory = filesDirectory; this.catalog = catalog; this.supportedAbis = supportedAbis;
    }
    public void cancel() { installer.cancel(); }

    /** Lets the provisioner look for archives that CI packaged into the APK. Optional. */
    public void setAssets(AssetManager assets) { this.assets = assets; }

    public BuildToolCatalog.Component find(String componentId) {
        if (catalog == null) return null;
        for (BuildToolCatalog.Component component : catalog.getComponents())
            if (component.id.equals(componentId) && ("any".equals(component.abi) || supportedAbis.contains(component.abi))) return component;
        return null;
    }

    /** Lowest JDK major in [min, max] that the catalog offers for this device, or -1. */
    public int findJdkMajor(int min, int max) {
        for (int major = min; major <= max; major++) if (find("jdk-" + major) != null) return major;
        return -1;
    }

    /** Returns the installed home directory, or null (with the reason logged) when installation is impossible. */
    public File install(String componentId, File finalHome, String requiredBinary, BuildLogger logger) {
        BuildToolCatalog.Component component = find(componentId);
        if (component == null) { logger.onWarning("No ABI-compatible catalog entry for '" + componentId + "'; automatic installation is unavailable."); return null; }
        if (!"zip".equals(component.archive) && !"tar.xz".equals(component.archive)) { logger.onError("Catalog entry '" + componentId + "' must declare archive=zip or archive=tar.xz.", null); return null; }
        File staging = new File(finalHome.getParentFile(), ".staging-" + componentId + "-" + System.currentTimeMillis());
        File archiveFile = null;
        try {
            archiveFile = copyBundledArchive(component, logger);
            if (archiveFile == null) {
                logger.onInfo("Downloading " + componentId + " " + component.version + " (SHA-256 verified, license " + component.license + ")...");
                archiveFile = installer.install(component.toDownloadableBuildComponent(), new File(filesDirectory, "downloads"), new BuildComponentInstaller.ProgressListener() {
                    private long lastReported;
                    @Override public void onProgress(String id, long done, long expected) {
                        if (done - lastReported >= 8L * 1024 * 1024) { lastReported = done; logger.onInfo(id + ": " + (done / (1024 * 1024)) + " MiB downloaded"); }
                    }
                });
            }
            long extractedLimit = Math.min(component.maxBytes * 4, MAX_EXTRACTED_BYTES);
            if ("tar.xz".equals(component.archive)) SecureTarExtractor.extract(archiveFile, staging, 1, null, extractedLimit);
            else SecureZipExtractor.extract(archiveFile, staging, true, extractedLimit);
            File binary = new File(staging, requiredBinary);
            if (!binary.isFile() || !binary.setExecutable(true, true)) throw new IOException("Archive does not contain an executable " + requiredBinary);
            if (finalHome.exists()) throw new IOException("Destination already exists: " + finalHome);
            if (!staging.renameTo(finalHome)) throw new IOException("Could not move the extracted runtime into place.");
            logger.onInfo("Ready: " + componentId + " at " + finalHome);
            return finalHome;
        } catch (IOException exception) {
            logger.onError("Runtime installation failed: " + exception.getMessage(), exception);
            return null;
        } finally {
            GradleCacheManager.deleteTree(staging);
            if (archiveFile != null) archiveFile.delete();
        }
    }

    /**
     * Copies assets/jdk/&lt;installPath&gt; to the downloads folder and checks it against the SHA-256 pinned in the catalog.
     * Returns null when the APK does not carry the archive or when its hash does not match (the caller then downloads it).
     */
    private File copyBundledArchive(BuildToolCatalog.Component component, BuildLogger logger) {
        AssetManager manager = assets;
        if (manager == null) return null;
        File downloads = new File(filesDirectory, "downloads");
        File target = new File(downloads, component.installPath);
        try {
            if (!downloads.isDirectory() && !downloads.mkdirs()) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = manager.open("jdk/" + component.installPath); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value));
            if (!hex.toString().equals(component.sha256)) {
                logger.onWarning("The " + component.id + " archive inside the app does not match the catalog SHA-256; downloading it instead.");
                target.delete();
                return null;
            }
            logger.onInfo("Unpacking bundled " + component.id + " (" + component.version + ", SHA-256 verified, license " + component.license + ")...");
            return target;
        } catch (FileNotFoundException notBundled) {
            target.delete();
            return null;
        } catch (Exception exception) {
            logger.onWarning("Could not read the " + component.id + " archive from the app package (" + exception.getMessage() + "); downloading it instead.");
            target.delete();
            return null;
        }
    }
}
