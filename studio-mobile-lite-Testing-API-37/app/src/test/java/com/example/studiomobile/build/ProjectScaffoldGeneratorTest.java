package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.dependency.GradleDependencyParser;
import com.example.studiomobile.workspace.ProjectScaffoldGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProjectScaffoldGeneratorTest {
    private File project;

    @Before public void setUp() throws IOException { project = Files.createTempDirectory("scaffold-test").toFile(); }
    @After public void tearDown() { delete(project); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(new File(project, relativePath).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void createsExpectedFiles() throws IOException {
        List<String> created = ProjectScaffoldGenerator.generate(project, "My App", "My App", "com.example.myapp");
        assertTrue(created.contains("app/src/main/AndroidManifest.xml"));
        assertTrue(created.contains("gradle/libs.versions.toml"));
        assertTrue(created.contains("app/proguard-rules.pro"));
        assertTrue(new File(project, "app/src/main/java/com/example/myapp/MainActivity.java").isFile());
        assertFalse(read("app/src/main/AndroidManifest.xml").contains("package="));
        assertEquals("com.example.myapp", ManifestPackage.effectivePackage(project, new File(project, "app/src/main/AndroidManifest.xml")));
        assertTrue(read("settings.gradle").contains("rootProject.name = \"My App\""));
        // Optional files are left for the user to create.
        assertFalse(new File(project, ".gitignore").exists());
        assertFalse(new File(project, "gradle.properties").exists());
        assertFalse(new File(project, "local.properties").exists());
        assertFalse(new File(project, "gradle/wrapper").exists());
    }

    @Test public void scaffoldPassesTheRealPipelineChecks() throws IOException {
        ProjectScaffoldGenerator.generate(project, "demo", "Demo", "com.example.demo");
        assertTrue(new ProjectValidator().validate(project).isValid());
        assertTrue(JavaLanguageLevel.inspect(project).isValid());
        assertEquals("com.example.demo", BuildProjectFiles.readApplicationId(project, "x"));
        assertEquals("24", BuildProjectFiles.readNumericSdkValue(project, true, "0"));
        assertEquals("36", BuildProjectFiles.readNumericSdkValue(project, false, "0"));
        GradleDependencyParser dependencies = GradleDependencyParser.parse(project, BuildProjectFiles.moduleDirectory(project));
        assertTrue(dependencies.declarations.isEmpty());
    }

    @Test public void minifyIsOffByDefaultButRulesFileIsReady() throws IOException {
        ProjectScaffoldGenerator.generate(project, "demo", "Demo", "com.example.demo");
        assertFalse(ProguardSettings.read(project).isMinifyEnabled());
        // Flipping the flag turns R8 on and finds the generated rules file.
        File build = new File(project, "app/build.gradle");
        String flipped = read("app/build.gradle").replace("minifyEnabled = false", "minifyEnabled = true");
        Files.write(build.toPath(), flipped.getBytes(StandardCharsets.UTF_8));
        ProguardSettings settings = ProguardSettings.read(project);
        assertTrue(settings.isMinifyEnabled());
        assertEquals(1, settings.getRuleFiles().size());
        assertTrue(settings.getMissingRuleFiles().isEmpty());
        assertFalse(settings.getBuiltInRules().isEmpty());
    }

    @Test public void usingACatalogAliasResolvesItsVersion() throws IOException {
        ProjectScaffoldGenerator.generate(project, "demo", "Demo", "com.example.demo");
        File catalog = new File(project, "gradle/libs.versions.toml");
        String withLibrary = read("gradle/libs.versions.toml").replace("# Example: androidx-appcompat", "androidx-appcompat");
        Files.write(catalog.toPath(), withLibrary.getBytes(StandardCharsets.UTF_8));
        File build = new File(project, "app/build.gradle");
        String edited = read("app/build.gradle").replace("// implementation(libs.androidx.appcompat)", "implementation(libs.androidx.appcompat)");
        Files.write(build.toPath(), edited.getBytes(StandardCharsets.UTF_8));
        GradleDependencyParser dependencies = GradleDependencyParser.parse(project, BuildProjectFiles.moduleDirectory(project));
        assertEquals(1, dependencies.declarations.size());
        assertEquals("androidx.appcompat:appcompat:1.7.0", dependencies.declarations.get(0).group + ":" + dependencies.declarations.get(0).artifact + ":" + dependencies.declarations.get(0).version);
    }

    @Test public void neverOverwritesExistingFiles() throws IOException {
        File manifest = new File(project, "app/src/main/AndroidManifest.xml");
        manifest.getParentFile().mkdirs();
        Files.write(manifest.toPath(), "mine".getBytes(StandardCharsets.UTF_8));
        List<String> created = ProjectScaffoldGenerator.generate(project, "demo", "Demo", "com.example.demo");
        assertFalse(created.contains("app/src/main/AndroidManifest.xml"));
        assertEquals("mine", read("app/src/main/AndroidManifest.xml"));
    }

    @Test public void rejectsInvalidPackage() throws IOException {
        try { ProjectScaffoldGenerator.generate(project, "demo", "Demo", "Not A Package"); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { assertEquals(0, project.list().length); }
    }

    @Test public void escapesSpecialCharacters() throws IOException {
        ProjectScaffoldGenerator.generate(project, "a\"b$c", "Tom's <App> & \"Co\"", "com.example.demo");
        assertTrue(read("settings.gradle").contains("rootProject.name = \"a\\\"b\\$c\""));
        assertTrue(read("app/src/main/res/values/strings.xml").contains("Tom\\'s &lt;App&gt; &amp; \\\"Co\\\""));
    }
}
