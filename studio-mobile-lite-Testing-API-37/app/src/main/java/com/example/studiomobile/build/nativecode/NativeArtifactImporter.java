package com.example.studiomobile.build.nativecode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports the zip produced by the native-build.yml GitHub Actions template (entries {@code lib/<abi>/lib*.so} or {@code <abi>/lib*.so})
 * into {@code src/main/jniLibs}. Only ELF shared libraries for known ABIs are accepted; paths cannot escape the target folder.
 */
public final class NativeArtifactImporter {
    private static final List<String> ABIS = Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
    private static final long MAX_LIBRARY_BYTES = 128L * 1024 * 1024, MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private NativeArtifactImporter() { }

    /** @return the number of libraries written. */
    public static int importZip(File zip, File jniLibsDirectory) throws IOException {
        int count = 0; long total = 0;
        File canonicalRoot = jniLibsDirectory.getCanonicalFile();
        try (ZipFile file = new ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String[] parts = entry.getName().replace('\\', '/').split("/");
                int n = parts.length;
                if (n < 2) continue;
                String abi = parts[n - 2], name = parts[n - 1];
                if (!ABIS.contains(abi) || !name.startsWith("lib") || !name.endsWith(".so") || name.contains("..")) continue;
                File target = new File(new File(jniLibsDirectory, abi), name);
                if (!target.getCanonicalPath().startsWith(canonicalRoot.getPath() + File.separator)) throw new IOException("Unsafe path in archive: " + entry.getName());
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Could not create " + target.getParentFile());
                long written = copyElf(file.getInputStream(entry), target, entry.getName());
                total += written;
                if (total > MAX_TOTAL_BYTES) throw new IOException("Archive is larger than " + (MAX_TOTAL_BYTES >> 20) + " MB of libraries.");
                count++;
            }
        }
        if (count == 0) throw new IOException("The archive contains no lib/<abi>/lib*.so files.");
        return count;
    }

    private static long copyElf(InputStream in, File target, String label) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        long size = 0;
        try (InputStream input = in; OutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[65536]; int read; boolean first = true;
            while ((read = input.read(buffer)) != -1) {
                if (first) { first = false; if (read < 4 || buffer[0] != 0x7f || buffer[1] != 'E' || buffer[2] != 'L' || buffer[3] != 'F') throw new IOException(label + " is not an ELF shared library."); }
                size += read;
                if (size > MAX_LIBRARY_BYTES) throw new IOException(label + " is larger than " + (MAX_LIBRARY_BYTES >> 20) + " MB.");
                out.write(buffer, 0, read);
            }
        } catch (IOException error) { temporary.delete(); throw error; }
        if (target.exists() && !target.delete()) { temporary.delete(); throw new IOException("Could not replace " + target); }
        if (!temporary.renameTo(target)) { temporary.delete(); throw new IOException("Could not write " + target); }
        return size;
    }
}
