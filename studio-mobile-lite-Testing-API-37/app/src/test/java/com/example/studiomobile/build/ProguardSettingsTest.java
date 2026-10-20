package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProguardSettingsTest {
    private File project;

    @Before public void setUp() throws IOException { project = Files.createTempDirectory("proguard-settings-test").toFile(); }
    @After public void tearDown() { delete(project); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private void write(String relativePath, String content) throws IOException {
        File file = new File(project, relativePath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void noBuildFileMeansDisabled() {
        assertFalse(ProguardSettings.read(project).isMinifyEnabled());
    }

    @Test public void minifyFalseIsDisabled() throws IOException {
        write("app/build.gradle", "android { buildTypes { release { minifyEnabled false\n proguardFiles 'proguard-rules.pro' } } }");
        assertFalse(ProguardSettings.read(project).isMinifyEnabled());
    }

    @Test public void groovyReleaseWithDefaultsAndRuleFile() throws IOException {
        write("app/proguard-rules.pro", "-keep class a.B\n");
        write("app/build.gradle",
            "android {\n buildTypes {\n  release {\n   minifyEnabled true\n"
            + "   proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n  }\n }\n}\n");
        ProguardSettings settings = ProguardSettings.read(project);
        assertTrue(settings.isMinifyEnabled());
        assertEquals(1, settings.getRuleFiles().size());
        assertEquals("proguard-rules.pro", settings.getRuleFiles().get(0).getName());
        assertTrue(settings.getMissingRuleFiles().isEmpty());
        assertFalse(settings.getBuiltInRules().isEmpty());
        assertFalse(settings.getBuiltInRules().contains("-dontoptimize"));
    }

    @Test public void kotlinDslWithNonOptimizingDefaults() throws IOException {
        write("app/proguard-rules.pro", "");
        write("app/build.gradle.kts",
            "android { buildTypes { release {\n isMinifyEnabled = true\n"
            + " proguardFiles(getDefaultProguardFile(\"proguard-android.txt\"), \"proguard-rules.pro\")\n} } }\n");
        ProguardSettings settings = ProguardSettings.read(project);
        assertTrue(settings.isMinifyEnabled());
        assertTrue(settings.getBuiltInRules().contains("-dontoptimize"));
        assertEquals(1, settings.getRuleFiles().size());
    }

    @Test public void debugBuildTypeCanEnableMinify() throws IOException {
        write("app/build.gradle", "android { buildTypes { debug { minifyEnabled true } release { minifyEnabled false } } }");
        ProguardSettings settings = ProguardSettings.read(project);
        assertTrue(settings.isMinifyEnabled());
        assertTrue(settings.getBuiltInRules().isEmpty());
    }

    @Test public void missingRuleFileIsReported() throws IOException {
        write("app/build.gradle", "android { buildTypes { release { minifyEnabled true\n proguardFiles 'nope.pro' } } }");
        ProguardSettings settings = ProguardSettings.read(project);
        assertEquals(1, settings.getMissingRuleFiles().size());
        assertTrue(settings.getRuleFiles().isEmpty());
    }

    @Test public void commentedOutMinifyIsIgnored() throws IOException {
        write("app/build.gradle", "android { buildTypes { release {\n // minifyEnabled true\n /* minifyEnabled true */\n minifyEnabled false\n } } }");
        assertFalse(ProguardSettings.read(project).isMinifyEnabled());
    }

    @Test public void multiLineProguardFilesWithTrailingComma() throws IOException {
        write("app/a.pro", "");
        write("app/b.pro", "");
        write("app/build.gradle", "android { buildTypes { release {\n minifyEnabled true\n proguardFiles 'a.pro',\n   'b.pro'\n } } }");
        assertEquals(2, ProguardSettings.read(project).getRuleFiles().size());
    }

    @Test public void shrinkResourcesProducesWarning() throws IOException {
        write("app/build.gradle", "android { buildTypes { release { minifyEnabled true\n shrinkResources true } } }");
        assertFalse(ProguardSettings.read(project).getWarnings().isEmpty());
    }
}
