package com.example.studiomobile.build.nativecode;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.DownloadPrompt;
import com.example.studiomobile.build.dependency.BuildComponentInstaller;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.dependency.SecureTarExtractor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/**
 * Downloads the Android-host NDK listed in the checksum-pinned catalog (component id {@value #COMPONENT_ID}) and unpacks only what the
 * on-device compiler needs into {@code files/build-tools/ndk}: the sysroot (headers + libc/liblog/... stubs), clang's resource headers and
 * the CMake toolchain files. The clang and ld.lld executables themselves are not taken from the archive; they ship inside the APK as
 * {@code libclang.so} / {@code libld.lld.so} because Android 10+ only lets an app execute files from its native library directory.
 */
public final class NativeNdkProvisioner {
    public static final String COMPONENT_ID = "ndk-r29";
    private final File filesDirectory;
    private final BuildToolCatalog catalog;
    private final List<String> supportedAbis;
    private final BuildComponentInstaller installer = new BuildComponentInstaller();

    public NativeNdkProvisioner(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis) {
        this.filesDirectory = filesDirectory; this.catalog = catalog; this.supportedAbis = supportedAbis;
    }

    public void cancel() { installer.cancel(); }

    /** Keeps only the parts of the NDK that compiling needs (paths are relative to the NDK root, e.g. toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/stdio.h). */
    static boolean isNeeded(String path) {
        if (path.startsWith("build/cmake/") || path.startsWith("meta/")) return true;
        if (!path.startsWith("toolchains/llvm/prebuilt/")) return false;
        String[] parts = path.split("/", 5); // toolchains, llvm, prebuilt, <host>, rest
        if (parts.length < 5) return false;
        return parts[4].startsWith("sysroot/") || parts[4].startsWith("lib/clang/");
    }

    /** @return the installed NDK root, or null (reason logged) when it could not be installed. */
    public File install(File toolsDirectory, BuildLogger logger) {
        BuildToolCatalog.Component component = find();
        if (component == null) {
            logger.onWarning("The bundled catalog has no '" + COMPONENT_ID + "' entry for this device (ABIs " + supportedAbis + "); the NDK cannot be downloaded automatically.");
            return null;
        }
        if (!DownloadPrompt.allow("Android NDK")) { logger.onInfo("Download cancelled."); return null; }
        File destination = new File(toolsDirectory, "ndk");
        File staging = new File(toolsDirectory, ".staging-ndk-" + System.currentTimeMillis());
        File archive = null;
        try {
            if (!toolsDirectory.isDirectory() && !toolsDirectory.mkdirs()) throw new IOException("Could not create " + toolsDirectory);
            logger.onInfo("Downloading Android NDK " + component.version + " (SHA-256 verified, license " + component.license + "). This is a one-time download.");
            archive = installer.install(component.toDownloadableBuildComponent(), new File(filesDirectory, "downloads"), new BuildComponentInstaller.ProgressListener() {
                private long lastReported;
                @Override public void onProgress(String id, long done, long expected) {
                    if (done - lastReported >= 16L * 1024 * 1024) { lastReported = done; logger.onInfo("NDK: " + (done >> 20) + (expected > 0 ? " / " + (expected >> 20) : "") + " MiB downloaded"); }
                }
            });
            logger.onInfo("Extracting the NDK sysroot (this can take a few minutes)...");
            Predicate<String> filter = NativeNdkProvisioner::isNeeded;
            int files = SecureTarExtractor.extract(archive, staging, 1, filter, 3L * 1024 * 1024 * 1024);
            File[] hosts = new File(staging, "toolchains/llvm/prebuilt").listFiles(File::isDirectory);
            if (files == 0 || hosts == null || hosts.length == 0 || !new File(hosts[0], "sysroot/usr/include").isDirectory())
                throw new IOException("The archive does not contain toolchains/llvm/prebuilt/<host>/sysroot/usr/include.");
            deleteTree(destination);
            if (!staging.renameTo(destination)) throw new IOException("Could not move the extracted NDK into " + destination);
            logger.onInfo("NDK installed at " + destination + " (" + files + " files).");
            return destination;
        } catch (IOException | RuntimeException exception) {
            logger.onError("NDK installation failed: " + exception.getMessage(), exception);
            return null;
        } finally {
            deleteTree(staging);
            if (archive != null) archive.delete();
        }
    }

    private BuildToolCatalog.Component find() {
        if (catalog == null) return null;
        for (BuildToolCatalog.Component component : catalog.getComponents())
            if (component.id.equals(COMPONENT_ID) && "tar.xz".equals(component.archive) && ("any".equals(component.abi) || supportedAbis.contains(component.abi))) return component;
        return null;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        file.delete();
    }
}
