package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ManifestPackageTest {
    private static final String AGP_STYLE = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n<application/>\n</manifest>\n";
    private File project;
    private File manifest;

    @Before public void setUp() throws IOException {
        project = Files.createTempDirectory("manifest-package-test").toFile();
        manifest = write("app/src/main/AndroidManifest.xml", AGP_STYLE);
    }
    @After public void tearDown() { delete(project); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private File write(String path, String content) throws IOException {
        File file = new File(project, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
    private File work() { return new File(project, "work"); }

    @Test public void manifestWithPackageIsUsedUnchanged() throws IOException {
        File old = write("app/src/main/AndroidManifest.xml", AGP_STYLE.replace("<manifest ", "<manifest package=\"com.old.style\" "));
        assertEquals(old, ManifestPackage.withPackage(old, project, work()));
        assertFalse(work().exists());
    }

    @Test public void namespaceIsInjectedAndOriginalIsUntouched() throws IOException {
        write("app/build.gradle", "android {\n namespace = \"com.example.app\"\n defaultConfig { applicationId = \"com.example.other\" }\n}\n");
        File fixed = ManifestPackage.withPackage(manifest, project, work());
        assertEquals("com.example.app", ManifestMerger.readPackage(fixed));
        assertEquals(null, ManifestMerger.readPackage(manifest));
        assertTrue(new String(Files.readAllBytes(fixed.toPath()), StandardCharsets.UTF_8).startsWith("<?xml"));
    }

    @Test public void kotlinDslAndGroovyCommandFormsAreRead() throws IOException {
        write("app/build.gradle.kts", "android { namespace = \"com.kts.app\" }");
        assertEquals("com.kts.app", ManifestPackage.readNamespace(project));
        new File(project, "app/build.gradle.kts").delete();
        write("app/build.gradle", "android { namespace 'com.groovy.app' }");
        assertEquals("com.groovy.app", ManifestPackage.readNamespace(project));
    }

    @Test public void fallsBackToApplicationIdWithoutNamespace() throws IOException {
        write("app/build.gradle", "android { defaultConfig { applicationId = \"com.example.id\" } }");
        assertEquals("com.example.id", ManifestMerger.readPackage(ManifestPackage.withPackage(manifest, project, work())));
    }

    @Test public void failsClearlyWhenThereIsNoPackageAnywhere() throws IOException {
        write("app/build.gradle", "android { compileSdk = 35 }");
        try { ManifestPackage.withPackage(manifest, project, work()); fail("expected IllegalStateException"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("namespace")); }
    }

    @Test public void effectivePackagePrefersTheManifestAttribute() throws IOException {
        write("app/build.gradle", "android { namespace = \"com.example.app\" }");
        File declared = write("app/src/main/AndroidManifest.xml", AGP_STYLE.replace("<manifest ", "<manifest package=\"com.declared\" "));
        assertEquals("com.declared", ManifestPackage.effectivePackage(project, declared));
        assertEquals("com.example.app", ManifestPackage.effectivePackage(project, new File(project, "missing.xml")));
    }
}
