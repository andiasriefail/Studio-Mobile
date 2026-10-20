package com.example.studiomobile.build.stages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.BuildConfiguration;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ZipAligner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApkPackagingStageTest {
    private File root, project, work, dex, dependencies;
    private BuildStageContext context;
    private final List<String> warnings = new ArrayList<>();
    private final BuildLogger logger = new BuildLogger() {
        @Override public void onInfo(String message) { }
        @Override public void onWarning(String message) { warnings.add(message); }
        @Override public void onError(String message, Throwable cause) { }
    };

    @Before public void setUp() throws IOException {
        root = Files.createTempDirectory("apk-packaging-test").toFile();
        project = new File(root, "project"); work = new File(root, "work"); dex = new File(root, "dex"); dependencies = new File(root, "dependencies");
        for (File directory : new File[] { project, work, dex, dependencies }) assertTrue(directory.mkdirs());
        context = new BuildStageContext(new BuildConfiguration(project, new File(root, "out"), false), work);
        File resourceApk = new File(root, "resources.ap_");
        writeZip(resourceApk, new Entry("AndroidManifest.xml", "<manifest/>", false), new Entry("resources.arsc", "ARSC-DATA", true));
        context.putOutput("resourceApk", resourceApk);
        Files.write(new File(dex, "classes.dex").toPath(), "dex035".getBytes(StandardCharsets.UTF_8));
        context.putOutput("dexDirectory", dex);
        context.putOutput("dependencyDirectory", dependencies);
    }

    @After public void tearDown() { delete(root); }

    @Test public void packagesNativeLibrariesUncompressedAndAligned() throws Exception {
        write(new File(project, "src/main/jniLibs/arm64-v8a/libnative.so"), "ELF-ARM64");
        write(new File(project, "src/main/jniLibs/x86_64/libnative.so"), "ELF-X64");
        write(new File(project, "src/main/jniLibs/mystery-abi/libignored.so"), "ELF");
        File aar = new File(root, "aar/lib-1.0"); write(new File(aar, "jni/arm64-v8a/libfromaar.so"), "ELF-AAR");
        context.putFiles("aarDirectories", Arrays.asList(aar));
        new ApkPackagingStage().execute(context, logger);

        File apk = context.getOutput("unsignedApk");
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name : new String[] { "lib/arm64-v8a/libnative.so", "lib/x86_64/libnative.so", "lib/arm64-v8a/libfromaar.so" }) {
                ZipEntry entry = zip.getEntry(name);
                assertNotNull(name + " missing", entry);
                assertEquals(name + " must be STORED", ZipEntry.STORED, entry.getMethod());
            }
            assertNull(zip.getEntry("lib/mystery-abi/libignored.so"));
            assertEquals("ELF-ARM64", read(zip, "lib/arm64-v8a/libnative.so"));
            assertNotNull(zip.getEntry("classes.dex"));
            assertEquals("resources.arsc stays STORED", ZipEntry.STORED, zip.getEntry("resources.arsc").getMethod());
        }
        assertFalse("an unsupported ABI should be reported", warnings.isEmpty());
        File aligned = new File(root, "aligned.apk");
        ZipAligner.align(apk, aligned, 4, 4096);
        assertTrue(ZipAligner.findMisaligned(aligned, 4, 4096).isEmpty());
    }

    @Test public void packagesProjectResourcesAndDependencyFiles() throws Exception {
        write(new File(project, "src/main/resources/config/app.properties"), "from=app");
        write(new File(project, "src/main/resources/META-INF/LICENSE.txt"), "license");
        writeZip(new File(dependencies, "000-first.jar"),
            new Entry("com/example/Thing.class", "bytecode", false),
            new Entry("config/app.properties", "from=library", false),
            new Entry("data/library.txt", "library-data", false),
            new Entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0", false),
            new Entry("META-INF/CERT.SF", "signature", false),
            new Entry("META-INF/CERT.RSA", "signature", false),
            new Entry("META-INF/versions/9/Thing.class", "multi-release", false),
            new Entry("META-INF/library.kotlin_module", "module", false),
            new Entry("META-INF/NOTICE", "notice", false),
            new Entry("META-INF/DEPENDENCIES", "deps", false),
            new Entry("license/third-party.txt", "license", false));
        new ApkPackagingStage().execute(context, logger);
        try (ZipFile zip = new ZipFile(context.getOutput("unsignedApk"))) {
            assertEquals("library-data", read(zip, "data/library.txt"));
            assertEquals("the app's resource wins over the library's", "from=app", read(zip, "config/app.properties"));
            for (String excluded : new String[] { "com/example/Thing.class", "META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA",
                "META-INF/versions/9/Thing.class", "META-INF/library.kotlin_module", "META-INF/NOTICE", "META-INF/DEPENDENCIES", "META-INF/LICENSE.txt", "license/third-party.txt" })
                assertNull(excluded + " must be excluded", zip.getEntry(excluded));
        }
    }

    @Test public void mergesServiceFilesInsteadOfOverwriting() throws Exception {
        writeZip(new File(dependencies, "000-a.jar"), new Entry("META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler", "com.a.Handler\n", false));
        writeZip(new File(dependencies, "001-b.jar"), new Entry("META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler", "# comment\r\ncom.b.Handler\r\ncom.a.Handler\r\n", false));
        new ApkPackagingStage().execute(context, logger);
        try (ZipFile zip = new ZipFile(context.getOutput("unsignedApk"))) {
            assertEquals("com.a.Handler\n# comment\ncom.b.Handler\n", read(zip, "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler"));
        }
    }

    @Test public void firstDuplicateEntryWins() throws Exception {
        writeZip(new File(dependencies, "000-a.jar"), new Entry("shared/file.txt", "first", false));
        writeZip(new File(dependencies, "001-b.jar"), new Entry("shared/file.txt", "second", false));
        new ApkPackagingStage().execute(context, logger);
        try (ZipFile zip = new ZipFile(context.getOutput("unsignedApk"))) { assertEquals("first", read(zip, "shared/file.txt")); }
    }

    @Test public void rejectsEntryNamesThatEscapeTheArchive() throws Exception {
        writeZip(new File(dependencies, "000-evil.jar"), new Entry("../../evil.txt", "boom", false));
        try { new ApkPackagingStage().execute(context, logger); fail("an entry containing .. must be rejected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("..")); }
    }

    // ---- helpers ----
    private static final class Entry {
        final String name, content; final boolean stored;
        Entry(String name, String content, boolean stored) { this.name = name; this.content = content; this.stored = stored; }
    }
    private static void writeZip(File file, Entry... entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (Entry entry : entries) {
                byte[] data = entry.content.getBytes(StandardCharsets.UTF_8);
                ZipEntry zipEntry = new ZipEntry(entry.name);
                if (entry.stored) { CRC32 crc = new CRC32(); crc.update(data); zipEntry.setMethod(ZipEntry.STORED); zipEntry.setSize(data.length); zipEntry.setCompressedSize(data.length); zipEntry.setCrc(crc.getValue()); }
                out.putNextEntry(zipEntry); out.write(data); out.closeEntry();
            }
        }
    }
    private static void write(File file, String content) throws IOException {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(name + " missing", entry);
        try (InputStream in = zip.getInputStream(entry)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }
    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
