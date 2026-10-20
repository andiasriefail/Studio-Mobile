package com.example.studiomobile.build.nativecode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.diagnostics.BuildDiagnostic;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class NativeAnalysisTest {
    private static File write(String name, String text) throws Exception {
        File file = new File(Files.createTempDirectory("native-analysis").toFile(), name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void parsesClangLldAndCMakeOutput() {
        List<BuildDiagnostic> list = NativeDiagnosticParser.parse("native-lib.c:5:12: error: use of undeclared identifier 'x'\nnative-lib.c:7:1: warning: unused\nld.lld: error: undefined symbol: foo\nCMake Error at CMakeLists.txt:9 (add_library):");
        assertEquals(4, list.size());
        assertEquals(5, list.get(0).getLineNumber());
        assertEquals(BuildDiagnostic.Severity.WARNING, list.get(1).getSeverity());
        assertNull(list.get(2).getFilePath());
        assertEquals("CMakeLists.txt", list.get(3).getFilePath());
        assertTrue(NativeDiagnosticParser.summarizeErrors(list, 1).contains("more error"));
    }

    @Test public void missingLoadLibraryIsAProblemOnlyWhenNothingProvidesIt() throws Exception {
        File java = write("MainActivity.java", "package a; class MainActivity { static { System.loadLibrary(\"native-lib\"); System.loadLibrary(\"log\"); } }");
        assertTrue(NativeLibraryRiskAnalyzer.findMissingLibraries(Arrays.asList(java), new HashSet<>(Arrays.asList("native-lib"))).isEmpty());
        assertEquals(1, NativeLibraryRiskAnalyzer.findMissingLibraries(Arrays.asList(java), Collections.<String>emptySet()).size());
    }

    @Test public void nativeMethodWithoutCFunctionIsReported() throws Exception {
        File java = write("MainActivity.java", "package com.example.app;\npublic class MainActivity { public native String hello(); public native int gone(); }");
        File c = write("native-lib.c", "JNIEXPORT jstring JNICALL Java_com_example_app_MainActivity_hello(JNIEnv* e, jobject t) { return 0; }");
        List<String> warnings = NativeLibraryRiskAnalyzer.findUnimplementedNativeMethods(Arrays.asList(java), Arrays.asList(c));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Java_com_example_app_MainActivity_gone"));
    }

    @Test public void importerAcceptsElfAndRejectsOtherFiles() throws Exception {
        File zip = new File(Files.createTempDirectory("artifact").toFile(), "native.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("lib/arm64-v8a/libok.so")); out.write(new byte[] {0x7f, 'E', 'L', 'F', 1}); out.closeEntry();
        }
        File jni = Files.createTempDirectory("jni").toFile();
        assertEquals(1, NativeArtifactImporter.importZip(zip, jni));
        assertTrue(new File(jni, "arm64-v8a/libok.so").isFile());
        File bad = new File(zip.getParentFile(), "bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(bad))) {
            out.putNextEntry(new ZipEntry("lib/arm64-v8a/libfake.so")); out.write("not elf".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        try { NativeArtifactImporter.importZip(bad, jni); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("not an ELF")); }
    }
}
