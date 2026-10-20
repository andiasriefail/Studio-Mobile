package com.example.studiomobile.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports user-supplied toolchain files (android.jar platform, kotlin-stdlib.jar) into files/build-tools after
 * checking what they really contain. Nothing is downloaded and no checksum is assumed: the SHA-256 of
 * what was imported is reported so it can be recorded. Git LFS pointer files (a common result of copying a
 * repository without LFS) are rejected with an explicit message instead of being installed.
 */
public final class ToolchainImporter {
    public static final class Result {
        public final File installedFile; public final String sha256; public final String summary;
        Result(File file, String sha256, String summary) { this.installedFile = file; this.sha256 = sha256; this.summary = summary; }
    }
    private static final long MAX_PLATFORM_BYTES = 300L * 1024 * 1024, MAX_STDLIB_BYTES = 50L * 1024 * 1024;
    private ToolchainImporter() { }

    /** Accepts android.jar itself or a ZIP that contains android.jar. Installs to platforms/android-<api>/android.jar. */
    public static Result importAndroidPlatform(InputStream source, int api, File toolRoot) throws IOException {
        if (api < 21 || api > 100) throw new IOException("API level must be a number such as 34 or 35.");
        File jar = stage(source, toolRoot, "android.jar", MAX_PLATFORM_BYTES);
        try {
            requireEntries(jar, "android.jar", "android/app/Activity.class", "AndroidManifest.xml", "resources.arsc");
            File destination = new File(toolRoot, "platforms/android-" + api + "/android.jar");
            return install(jar, destination, "Android platform API " + api + " imported. Make sure the file really is API " + api + "; it cannot be detected automatically.");
        } finally { jar.delete(); }
    }

    /** Accepts kotlin-stdlib.jar (or a ZIP containing it). Installs to kotlin-stdlib.jar. */
    public static Result importKotlinStdlib(InputStream source, File toolRoot) throws IOException {
        File jar = stage(source, toolRoot, "kotlin-stdlib", MAX_STDLIB_BYTES);
        try {
            requireEntries(jar, "kotlin-stdlib.jar", "kotlin/Unit.class", "META-INF/MANIFEST.MF");
            String version = readImplementationVersion(jar);
            String note = "Kotlin standard library imported" + (version == null ? "." : " (version " + version + ").")
                + " Keep it equal to the bundled Kotlin compiler version (2.4.20) to avoid metadata errors.";
            return install(jar, new File(toolRoot, "kotlin-stdlib.jar"), note);
        } finally { jar.delete(); }
    }

    /** Copies the stream to a bounded temp file, rejects LFS pointers, and unwraps a ZIP holding the wanted jar. */
    private static File stage(InputStream source, File toolRoot, String wantedName, long maxBytes) throws IOException {
        if (source == null) throw new IOException("The selected file could not be opened.");
        if (!toolRoot.isDirectory() && !toolRoot.mkdirs()) throw new IOException("Could not create " + toolRoot);
        File temporary = File.createTempFile("import-", ".partial", toolRoot);
        boolean keep = false;
        try {
            copyBounded(source, temporary, maxBytes);
            if (isLfsPointer(temporary)) throw new IOException("This file is a Git LFS pointer (a few lines of text), not the real file. Download the actual file with Git LFS or from the original source, then import that.");
            if (temporary.length() < 4) throw new IOException("The selected file is empty or too small.");
            if (hasEntry(temporary, wantedName) == null && !looksLikeJarForName(temporary, wantedName)) {
                // Not the jar itself: maybe a ZIP wrapper containing it.
                String inner = findInnerJar(temporary, wantedName);
                if (inner == null) throw new IOException("The selected ZIP does not contain " + wantedName + ".");
                File extracted = File.createTempFile("import-inner-", ".partial", toolRoot);
                try (ZipFile zip = new ZipFile(temporary); InputStream in = zip.getInputStream(zip.getEntry(inner))) { copyBounded(in, extracted, maxBytes); }
                temporary.delete(); temporary = extracted;
            }
            keep = true; return temporary;
        } finally { if (!keep) temporary.delete(); }
    }

    // A jar is itself a ZIP; android.jar contains android/app/Activity.class, stdlib contains kotlin/Unit.class.
    private static boolean looksLikeJarForName(File file, String wantedName) throws IOException {
        String marker = wantedName.startsWith("android") ? "android/app/Activity.class" : "kotlin/Unit.class";
        return hasEntry(file, marker) != null;
    }
    private static String hasEntry(File file, String name) throws IOException {
        try (ZipFile zip = new ZipFile(file)) { return zip.getEntry(name) != null ? name : null; }
        catch (java.util.zip.ZipException notZip) { throw new IOException("The selected file is not a valid ZIP/JAR archive."); }
    }
    private static String findInnerJar(File zipFile, String wantedName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements();) {
                ZipEntry entry = all.nextElement(); String name = entry.getName().toLowerCase(Locale.ROOT);
                String base = name.substring(name.lastIndexOf('/') + 1);
                boolean match = wantedName.startsWith("android") ? base.equals("android.jar") : base.startsWith("kotlin-stdlib") && base.endsWith(".jar") && !base.contains("sources") && !base.contains("common") && !base.contains("jdk");
                if (!entry.isDirectory() && match) return entry.getName();
            }
        }
        return null;
    }
    private static void requireEntries(File jar, String label, String... required) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            for (String entry : required) if (zip.getEntry(entry) == null) throw new IOException(label + " is not valid: missing " + entry + ".");
        } catch (java.util.zip.ZipException notZip) { throw new IOException(label + " is not a valid ZIP/JAR archive."); }
    }
    private static String readImplementationVersion(File jar) {
        try (ZipFile zip = new ZipFile(jar); InputStream in = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
            return new Manifest(in).getMainAttributes().getValue("Implementation-Version");
        } catch (Exception ignored) { return null; }
    }
    private static Result install(File staged, File destination, String note) throws IOException {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        String hash = sha256(staged);
        try { Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException atomicFailure) { Files.copy(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        return new Result(destination, hash, note + "\nInstalled at " + destination.getAbsolutePath() + "\nSHA-256: " + hash);
    }
    private static void copyBounded(InputStream input, File target, long maxBytes) throws IOException {
        try (InputStream in = input; OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; long total = 0; int count;
            while ((count = in.read(buffer)) != -1) { total += count; if (total > maxBytes) throw new IOException("The file is larger than the allowed " + (maxBytes / (1024 * 1024)) + " MiB."); out.write(buffer, 0, count); }
        }
    }
    private static boolean isLfsPointer(File file) throws IOException {
        byte[] head = new byte[128];
        try (InputStream in = new FileInputStream(file)) { int count = in.read(head); return count > 0 && new String(head, 0, count, StandardCharsets.UTF_8).startsWith("version https://git-lfs.github.com/spec/v1"); }
    }
    private static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IOException("SHA-256 unavailable.", exception); }
    }
}
