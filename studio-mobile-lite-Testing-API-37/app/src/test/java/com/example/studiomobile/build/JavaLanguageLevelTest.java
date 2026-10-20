package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JavaLanguageLevelTest {
    private File project;
    private final List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();
    private final BuildLogger logger = new BuildLogger() {
        @Override public void onInfo(String message) { }
        @Override public void onWarning(String message) { warnings.add(message); }
        @Override public void onError(String message, Throwable cause) { errors.add(message); }
    };

    @Before public void setUp() throws IOException { project = Files.createTempDirectory("java-level-test").toFile(); }
    @After public void tearDown() { delete(project); }

    @Test public void acceptsJava17() throws IOException {
        write("build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_17\n targetCompatibility = JavaVersion.VERSION_17 } }");
        assertNull(JavaLanguageLevel.check(project, logger));
        assertTrue(errors.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test public void acceptsJava21() throws IOException {
        write("build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_21\n targetCompatibility = JavaVersion.VERSION_21 } }");
        assertNull(JavaLanguageLevel.check(project, logger));
        assertTrue(errors.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test public void acceptsProjectsThatDeclareNoLevel() throws IOException {
        write("build.gradle", "plugins { id 'com.android.application' }");
        assertNull(JavaLanguageLevel.check(project, logger));
    }

    @Test public void rejectsJavaAbove21BeforeAnythingRuns() throws IOException {
        write("build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_26 } }");
        String problem = JavaLanguageLevel.check(project, logger);
        assertNotNull(problem);
        assertTrue(problem.contains("25"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Java 26"));
    }

    @Test public void rejectsKotlinToolchainAbove21InTheModuleScript() throws IOException {
        write("app/src/main/AndroidManifest.xml", "<manifest/>");
        write("app/build.gradle.kts", "kotlin { jvmToolchain(26) }");
        assertNotNull(JavaLanguageLevel.check(project, logger));
    }

    @Test public void rejectsJvmTargetAbove21() throws IOException {
        write("build.gradle", "kotlinOptions { jvmTarget = '26' }");
        assertNotNull(JavaLanguageLevel.check(project, logger));
    }

    @Test public void olderLevelsOnlyWarn() throws IOException {
        write("build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8 } }");
        assertNull(JavaLanguageLevel.check(project, logger));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Java 8"));
        assertTrue(errors.isEmpty());
    }

    @Test public void ignoresLevelsThatAreOnlyInComments() throws IOException {
        write("build.gradle", "// sourceCompatibility = JavaVersion.VERSION_26\n/* jvmToolchain(26) */\nplugins { }");
        assertNull(JavaLanguageLevel.check(project, logger));
    }

    @Test public void rejectsLocalLibraryCompiledForNewerJava() throws IOException {
        write("build.gradle", "plugins { }");
        File libs = new File(project, "libs"); assertTrue(libs.mkdirs());
        try (ZipOutputStream jar = new ZipOutputStream(new FileOutputStream(new File(libs, "newer.jar")))) {
            jar.putNextEntry(new ZipEntry("Newer.class"));
            jar.write(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 70 }); // class-file major 70 = Java 26
            jar.closeEntry();
        }
        String problem = JavaLanguageLevel.check(project, logger);
        assertNotNull(problem);
        assertFalse(errors.isEmpty());
    }

    @Test public void worksWithoutALogger() throws IOException {
        write("build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_26 } }");
        assertNotNull(JavaLanguageLevel.check(project, null));
    }

    private void write(String relative, String content) throws IOException {
        File file = new File(project, relative);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
