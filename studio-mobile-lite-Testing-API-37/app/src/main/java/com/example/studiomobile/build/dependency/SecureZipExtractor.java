package com.example.studiomobile.build.dependency;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts a verified ZIP with zip-slip, entry-count and total-size protection. Used for Gradle distributions and JDK archives. */
public final class SecureZipExtractor {
    private static final int MAX_ENTRIES = 60000;
    private SecureZipExtractor() { }

    /**
     * @param stripTopLevel drop the single leading directory (gradle-8.9/...) so files land directly in destination
     * @param maxTotalBytes upper bound on the uncompressed size, protecting against zip bombs
     */
    public static void extract(File zip, File destination, boolean stripTopLevel, long maxTotalBytes) throws IOException {
        File root = destination.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create extraction directory: " + root);
        long total = 0; int entries = 0;
        try (ZipFile archive = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (++entries > MAX_ENTRIES) throw new IOException("Archive has too many entries.");
                String name = entry.getName().replace('\\', '/');
                if (stripTopLevel) { int slash = name.indexOf('/'); if (slash < 0) continue; name = name.substring(slash + 1); }
                if (name.isEmpty()) continue;
                File target = new File(root, name).getCanonicalFile();
                if (!target.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("Archive entry escapes destination: " + entry.getName());
                if (entry.isDirectory()) { if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create " + target); continue; }
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
                try (InputStream in = archive.getInputStream(entry); BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > maxTotalBytes) throw new IOException("Archive expands beyond the permitted size.");
                        out.write(buffer, 0, count);
                    }
                }
                // ZIP loses POSIX modes: launchers and native tools must be re-marked executable.
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("/bin/") || lower.startsWith("bin/") || lower.endsWith("/jspawnhelper") || lower.endsWith("/lib/jspawnhelper")) target.setExecutable(true, true);
            }
        }
    }
}
