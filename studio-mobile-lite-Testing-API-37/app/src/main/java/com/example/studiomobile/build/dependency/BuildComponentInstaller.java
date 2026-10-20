package com.example.studiomobile.build.dependency;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads one checksum-pinned component and installs it atomically after verification.
 * Host names are resolved by Google DNS, then Cloudflare DNS ({@link ResilientDns}); when both fail the install stops.
 * The file is streamed to disk in chunks sized from the phone's RAM ({@link DownloadMemoryPolicy}), never held in memory as a whole.
 */
public final class BuildComponentInstaller {
    public interface ProgressListener {
        void onProgress(String componentId, long bytesDownloaded, long expectedBytes);
    }

    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 30000;
    private static final int MAX_REDIRECTS = 5;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile HttpsConnector.Response activeConnection;

    public void cancel() {
        cancelled.set(true);
        HttpsConnector.Response connection = activeConnection;
        if (connection != null) connection.abort();
    }

    public synchronized File install(DownloadableBuildComponent component, File installationRoot,
                        ProgressListener progressListener) throws IOException {
        if (component == null) throw new IllegalArgumentException("Component metadata is required.");
        if (installationRoot == null) throw new IllegalArgumentException("Installation root is required.");
        cancelled.set(false);
        File root = installationRoot.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create component installation directory: " + root);
        File destination = new File(root, component.getRelativeInstallPath()).getCanonicalFile();
        String rootPrefix = root.getPath() + File.separator;
        if (!destination.getPath().startsWith(rootPrefix)) throw new IOException("Component destination escapes installation root.");
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Could not create component destination directory: " + parent);

        File temporary = File.createTempFile("component-download-", ".partial", parent);
        HttpsConnector.Response connection = null;
        try {
            URL url = component.getDownloadUri().toURL();
            // GitHub release assets answer with a 302 to another HTTPS host. Redirects are followed by hand (HTTPS only, at most 5 hops);
            // the pinned SHA-256 below is what makes it safe to trust whatever host finally serves the bytes.
            int status = 0;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                connection = HttpsConnector.get(url, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, null);   // Google DNS, then Cloudflare DNS; both failing stops here
                activeConnection = connection;
                if (cancelled.get()) throw new IOException("Download cancelled for " + component.getComponentId());
                status = connection.status();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) break;
                String location = connection.header("Location");
                connection.close();
                if (location == null || location.trim().isEmpty()) throw new IOException("Download failed for " + component.getComponentId() + ": redirect without a Location header.");
                URL next = new URL(url, location.trim());
                if (!"https".equalsIgnoreCase(next.getProtocol())) throw new IOException("Download failed for " + component.getComponentId() + ": refusing a redirect to a non-HTTPS URL.");
                if (hop == MAX_REDIRECTS) throw new IOException("Download failed for " + component.getComponentId() + ": too many redirects.");
                url = next;
            }
            if (status != 200) {
                throw new IOException("Download failed for " + component.getComponentId() + ": HTTP " + status + ".");
            }
            long contentLength = connection.contentLength();
            if (contentLength > component.getMaximumBytes()) throw new IOException("Download exceeds maximum permitted size for " + component.getComponentId());
            MessageDigest digest = sha256Digest();
            long total = 0;
            byte[] buffer = new byte[65536];
            int chunkBytes = DownloadMemoryPolicy.detect().getChunkBytes();   // 4 / 8 / 16 MB by RAM: written to disk one chunk at a time
            try (InputStream input = new BufferedInputStream(connection.body());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), chunkBytes)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled.get()) throw new IOException("Download cancelled for " + component.getComponentId());
                    total += count;
                    if (total > component.getMaximumBytes()) throw new IOException("Download exceeds maximum permitted size for " + component.getComponentId());
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    if (progressListener != null) progressListener.onProgress(component.getComponentId(), total, contentLength);
                }
            }
            if (cancelled.get()) throw new IOException("Download cancelled for " + component.getComponentId());
            if (contentLength >= 0 && total != contentLength) throw new IOException("Incomplete download for " + component.getComponentId() + ": expected " + contentLength + " bytes, received " + total + ".");
            String actualHash = toHex(digest.digest());
            if (!actualHash.equals(component.getSha256())) throw new IOException("SHA-256 verification failed for " + component.getComponentId()
                    + ". Expected " + component.getSha256() + ", received " + actualHash + ". The component was not installed.");
            if (cancelled.get()) throw new IOException("Installation cancelled for " + component.getComponentId());
            replaceVerifiedFile(temporary, destination, component.getComponentId());
            return destination;
        } catch (IOException exception) {
            throw new IOException("Could not install build component '" + component.getComponentId() + "': " + exception.getMessage(), exception);
        } finally {
            activeConnection = null;
            if (connection != null) connection.close();
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }


    /** Preserves an existing installation if the final replacement fails on a filesystem without atomic moves. */
    private static void replaceVerifiedFile(File temporary, File destination, String componentId) throws IOException {
        if (!destination.exists()) {
            moveWithFallback(temporary, destination);
            return;
        }

        File backup = File.createTempFile("component-backup-", ".previous", destination.getParentFile());
        if (!backup.delete()) throw new IOException("Could not prepare a backup for the existing component: " + destination);
        boolean originalMoved = false;
        boolean preserveBackup = false;
        try {
            moveWithFallback(destination, backup);
            originalMoved = true;
            moveWithFallback(temporary, destination);
        } catch (IOException replacementFailure) {
            if (originalMoved && backup.exists()) {
                try {
                    moveWithFallback(backup, destination);
                } catch (IOException restoreFailure) {
                    replacementFailure.addSuppressed(restoreFailure);
                    preserveBackup = true;
                    throw new IOException("Could not install component '" + componentId
                            + "' and could not restore the previous file. Previous file is preserved at: "
                            + backup.getAbsolutePath(), replacementFailure);
                }
            }
            throw replacementFailure;
        } finally {
            if (!preserveBackup && backup.exists() && !backup.delete()) backup.deleteOnExit();
        }
    }

    private static void moveWithFallback(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(atomicMoveFailure);
                throw moveFailure;
            }
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IOException("SHA-256 is unavailable on this runtime.", exception); }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
