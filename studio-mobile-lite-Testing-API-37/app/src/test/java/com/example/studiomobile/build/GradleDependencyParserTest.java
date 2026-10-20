package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.build.dependency.GradleDependencyParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GradleDependencyParserTest {
    private static final String CATALOG =
        "[versions]\nappcompat = \"1.7.0\"\ncore = { strictly = \"1.13.1\" }\n"
        + "[libraries]\n"
        + "androidx-appcompat = { module = \"androidx.appcompat:appcompat\", version.ref = \"appcompat\" }\n"
        + "androidx-core = { module = \"androidx.core:core\", version.ref = \"core\" }\n"
        + "androidx-fragment = \"androidx.fragment:fragment:1.8.5\"\n"
        + "ui-bom = \"androidx.compose:compose-bom:2024.10.00\"\n"
        + "ui-graphics = \"androidx.compose.ui:ui-graphics\"\n"
        + "ranged = { module = \"x:ranged\", version = { require = \"[1.0,2.0)\" } }\n"
        + "[bundles]\n"
        + "base = [\"androidx-appcompat\", \"androidx-core\"]\n"
        + "broken = [\"androidx-appcompat\", \"nope\"]\n";
    private File project;

    @Before public void setUp() throws IOException {
        project = Files.createTempDirectory("dependency-parser-test").toFile();
        write("gradle/libs.versions.toml", CATALOG);
    }
    @After public void tearDown() { delete(project); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private void write(String path, String content) throws IOException {
        File file = new File(project, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
    private GradleDependencyParser parse(String dependencies) throws IOException {
        write("app/build.gradle", "dependencies {\n" + dependencies + "\n}\n");
        return GradleDependencyParser.parse(project, new File(project, "app"));
    }
    private static String coordinates(GradleDependencyParser parser, int index) {
        GradleDependencyParser.Declaration d = parser.declarations.get(index);
        return d.group + ":" + d.artifact + ":" + d.version;
    }

    // ---- alias forms

    @Test public void groovyCommandFormWithoutParentheses() throws IOException {
        GradleDependencyParser parser = parse("    implementation libs.androidx.appcompat");
        assertEquals(1, parser.declarations.size());
        assertEquals("androidx.appcompat:appcompat:1.7.0", coordinates(parser, 0));
    }

    @Test public void parenthesisedFormStillWorks() throws IOException {
        assertEquals(1, parse("    implementation(libs.androidx.appcompat)").declarations.size());
    }

    @Test public void testConfigurationsAreStillIgnored() throws IOException {
        assertTrue(parse("    testImplementation libs.androidx.appcompat").declarations.isEmpty());
    }

    // ---- bundles

    @Test public void bundleIsExpandedIntoItsLibraries() throws IOException {
        GradleDependencyParser parser = parse("    implementation libs.bundles.base");
        assertEquals(2, parser.declarations.size());
        assertEquals("androidx.appcompat:appcompat:1.7.0", coordinates(parser, 0));
        assertEquals("androidx.core:core:1.13.1", coordinates(parser, 1));
        assertEquals("implementation", parser.declarations.get(0).configuration);
        assertTrue(parser.warnings.isEmpty());
    }

    @Test public void bundleAndSingleDeclarationDoNotDuplicate() throws IOException {
        assertEquals(2, parse("    implementation(libs.bundles.base)\n    implementation(libs.androidx.appcompat)").declarations.size());
    }

    @Test public void bundleWithUnknownMemberResolvesTheRestAndWarns() throws IOException {
        GradleDependencyParser parser = parse("    implementation libs.bundles.broken");
        assertEquals(1, parser.declarations.size());
        assertTrue(parser.warnings.toString().contains("nope"));
    }

    @Test public void unknownBundleWarns() throws IOException {
        GradleDependencyParser parser = parse("    implementation libs.bundles.missing");
        assertTrue(parser.declarations.isEmpty());
        assertTrue(parser.warnings.toString().contains("libs.bundles.missing"));
    }

    @Test public void testBundleIsIgnored() throws IOException {
        assertTrue(parse("    testImplementation libs.bundles.base").declarations.isEmpty());
    }

    // ---- rich versions / problems

    @Test public void richVersionFromVersionsTable() throws IOException {
        assertEquals("androidx.core:core:1.13.1", coordinates(parse("    implementation libs.androidx.core"), 0));
    }

    @Test public void unsupportedRangeIsSkippedWithAReason() throws IOException {
        GradleDependencyParser parser = parse("    implementation libs.ranged");
        assertTrue(parser.declarations.isEmpty());
        assertTrue(parser.warnings.toString().contains("range"));
    }

    // ---- platform / BOM declarations

    @Test public void platformFromCatalogAliasAndLiteral() throws IOException {
        GradleDependencyParser parser = parse("    implementation platform(libs.ui.bom)\n    implementation(platform(\"com.example:bom:1.0\"))");
        assertEquals(2, parser.platforms.size());
        assertEquals("compose-bom", parser.platforms.get(0).artifact);
        assertEquals("2024.10.00", parser.platforms.get(0).version);
        assertFalse(parser.platforms.get(0).enforced);
        assertEquals("com.example", parser.platforms.get(1).group);
    }

    @Test public void enforcedPlatformIsMarked() throws IOException {
        assertTrue(parse("    implementation(enforcedPlatform(libs.ui.bom))").platforms.get(0).enforced);
    }

    @Test public void platformItselfIsNotADependency() throws IOException {
        GradleDependencyParser parser = parse("    implementation platform(libs.ui.bom)");
        assertTrue(parser.declarations.isEmpty());
        assertTrue(parser.warnings.isEmpty());
    }

    @Test public void versionlessLibraryIsManagedWhenAPlatformExists() throws IOException {
        GradleDependencyParser parser = parse("    implementation platform(libs.ui.bom)\n    implementation libs.ui.graphics\n    implementation(\"androidx.compose.ui:ui\")");
        assertEquals(2, parser.declarations.size());
        assertTrue(parser.declarations.get(0).isManaged());
        assertTrue(parser.declarations.get(1).isManaged());
        assertEquals("androidx.compose.ui:ui", parser.declarations.get(1).group + ":" + parser.declarations.get(1).artifact);
    }

    @Test public void versionlessLibraryWithoutPlatformIsSkippedWithAWarning() throws IOException {
        GradleDependencyParser parser = parse("    implementation libs.ui.graphics");
        assertTrue(parser.declarations.isEmpty());
        assertTrue(parser.warnings.toString().contains("no version"));
    }

    @Test public void platformInTestConfigurationDoesNotCount() throws IOException {
        GradleDependencyParser parser = parse("    testImplementation platform(libs.ui.bom)\n    implementation libs.ui.graphics");
        assertTrue(parser.platforms.isEmpty());
        assertTrue(parser.declarations.isEmpty());
    }

    @Test public void platformWithoutFixedVersionIsRejected() throws IOException {
        GradleDependencyParser parser = parse("    implementation platform(\"com.example:bom\")");
        assertTrue(parser.platforms.isEmpty());
        assertTrue(parser.warnings.toString().contains("fixed"));
    }

    @Test public void fileDependenciesStillWarnButProjectDependenciesAreHandledByProjectModules() throws IOException {
        assertTrue(parse("    implementation(files(\"libs/a.jar\"))").warnings.toString().contains("files"));
        assertTrue(parse("    implementation(project(\":lib\"))").warnings.isEmpty());
    }
}
