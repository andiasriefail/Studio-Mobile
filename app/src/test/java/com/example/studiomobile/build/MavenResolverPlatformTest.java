package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.dependency.GradleDependencyParser;
import com.example.studiomobile.build.dependency.MavenResolver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Resolves against an in-memory Maven repository, so BOM handling is tested end to end without a network. */
public class MavenResolverPlatformTest {
    private static final String REPO = "https://repo.test/";
    private final Map<String, byte[]> files = new HashMap<>();
    private File project, cache;

    @Before public void setUp() throws IOException {
        project = Files.createTempDirectory("platform-test").toFile();
        cache = new File(project, "cache");
    }
    @After public void tearDown() { delete(project); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    // ---- fake repository

    private void publish(String group, String artifact, String version, String pomBody) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        String pom = "<project><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version>" + pomBody + "</project>";
        files.put(base + ".pom", pom.getBytes(StandardCharsets.UTF_8));
        if (!pomBody.contains("<packaging>pom</packaging>")) files.put(base + ".jar", ("jar " + artifact + " " + version).getBytes(StandardCharsets.UTF_8));
    }
    private static String dep(String g, String a, String v) { return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>" + (v == null ? "" : "<version>" + v + "</version>") + "</dependency>"; }
    private static String deps(String... items) { StringBuilder b = new StringBuilder("<dependencies>"); for (String i : items) b.append(i); return b.append("</dependencies>").toString(); }
    private static String managed(String... items) { StringBuilder b = new StringBuilder("<dependencyManagement><dependencies>"); for (String i : items) b.append(i); return b.append("</dependencies></dependencyManagement>").toString(); }
    private static String bomImport(String g, String a, String v) { return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version><type>pom</type><scope>import</scope></dependency>"; }
    private static String sha1(byte[] data) throws IOException {
        try { StringBuilder s = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-1").digest(data)) s.append(String.format("%02x", b & 0xff)); return s.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private final MavenResolver.Fetcher fetcher = new MavenResolver.Fetcher() {
        @Override public byte[] get(String url, long maxBytes) throws IOException {
            String path = url.substring(REPO.length());
            if (path.endsWith(".sha1")) { byte[] base = files.get(path.substring(0, path.length() - 5)); return base == null ? null : sha1(base).getBytes(StandardCharsets.UTF_8); }
            return files.get(path);
        }
    };

    // ---- helpers

    private MavenResolver.Result resolve(String buildGradleDependencies) throws IOException {
        File module = new File(project, "app");
        module.mkdirs();
        Files.write(new File(module, "build.gradle").toPath(), ("dependencies {\n" + buildGradleDependencies + "\n}\n").getBytes(StandardCharsets.UTF_8));
        GradleDependencyParser parsed = GradleDependencyParser.parse(project, module);
        return new MavenResolver(cache, fetcher, Collections.singletonList(REPO)).resolve(parsed.declarations, parsed.platforms);
    }
    private static List<String> jarNames(MavenResolver.Result result) {
        List<String> names = new ArrayList<>();
        for (File jar : result.jars) names.add(jar.getName());
        return names;
    }

    // ---- tests

    @Test public void versionlessDeclarationTakesItsVersionFromTheBom() throws IOException {
        publish("test.bom", "bom", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "a", "2.0")));
        publish("lib", "a", "2.0", "");
        MavenResolver.Result result = resolve("implementation platform(\"test.bom:bom:1.0\")\nimplementation(\"lib:a\")");
        assertEquals(java.util.Arrays.asList("a-2.0.jar"), jarNames(result));
        assertTrue(result.notes.toString().contains("manages 1 version"));
    }

    @Test public void bomRaisesATransitiveVersion() throws IOException {
        publish("test.bom", "bom", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "b", "2.0")));
        publish("lib", "a", "1.0", deps(dep("lib", "b", "1.0")));
        publish("lib", "b", "1.0", "");
        publish("lib", "b", "2.0", "");
        assertEquals(java.util.Arrays.asList("a-1.0.jar", "b-2.0.jar"), jarNames(resolve("implementation platform(\"test.bom:bom:1.0\")\nimplementation(\"lib:a:1.0\")")));
    }

    @Test public void platformNeverDowngradesButEnforcedPlatformDoes() throws IOException {
        publish("test.bom", "bom", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "b", "0.5")));
        publish("lib", "a", "1.0", deps(dep("lib", "b", "1.0")));
        publish("lib", "b", "1.0", "");
        publish("lib", "b", "0.5", "");
        assertTrue(jarNames(resolve("implementation platform(\"test.bom:bom:1.0\")\nimplementation(\"lib:a:1.0\")")).contains("b-1.0.jar"));
        tearDown(); setUp();
        assertTrue(jarNames(resolve("implementation enforcedPlatform(\"test.bom:bom:1.0\")\nimplementation(\"lib:a:1.0\")")).contains("b-0.5.jar"));
    }

    @Test public void importedBomInsideTheBomIsFollowed() throws IOException {
        publish("test.bom", "child", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "c", "3.0")));
        publish("test.bom", "root", "1.0", "<packaging>pom</packaging>" + managed(bomImport("test.bom", "child", "1.0"), dep("lib", "d", "4.0")));
        publish("lib", "c", "3.0", "");
        publish("lib", "d", "4.0", "");
        assertEquals(java.util.Arrays.asList("c-3.0.jar", "d-4.0.jar"), jarNames(resolve("implementation platform(\"test.bom:root:1.0\")\nimplementation(\"lib:c\")\nimplementation(\"lib:d\")")));
    }

    @Test public void ownManagementWinsOverAnImportedBom() throws IOException {
        publish("test.bom", "child", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "c", "3.0")));
        publish("test.bom", "root", "1.0", "<packaging>pom</packaging>" + managed(bomImport("test.bom", "child", "1.0"), dep("lib", "c", "3.5")));
        publish("lib", "c", "3.0", "");
        publish("lib", "c", "3.5", "");
        assertEquals(java.util.Arrays.asList("c-3.5.jar"), jarNames(resolve("implementation platform(\"test.bom:root:1.0\")\nimplementation(\"lib:c\")")));
    }

    @Test public void bomVersionsMayUseProperties() throws IOException {
        publish("test.bom", "bom", "1.0", "<packaging>pom</packaging><properties><lib.version>5.1</lib.version></properties>" + managed(dep("lib", "e", "${lib.version}")));
        publish("lib", "e", "5.1", "");
        assertEquals(java.util.Arrays.asList("e-5.1.jar"), jarNames(resolve("implementation platform(\"test.bom:bom:1.0\")\nimplementation(\"lib:e\")")));
    }

    @Test public void libraryPomUsesItsOwnImportedBomForVersionlessDependencies() throws IOException {
        publish("test.bom", "bom", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "g", "7.0")));
        publish("lib", "f", "1.0", managed(bomImport("test.bom", "bom", "1.0")) + deps(dep("lib", "g", null)));
        publish("lib", "g", "7.0", "");
        assertEquals(java.util.Arrays.asList("f-1.0.jar", "g-7.0.jar"), jarNames(resolve("implementation(\"lib:f:1.0\")")));
    }

    @Test public void versionlessDeclarationNobodyManagesIsSkippedWithAWarning() throws IOException {
        publish("test.bom", "bom", "1.0", "<packaging>pom</packaging>" + managed(dep("lib", "a", "2.0")));
        publish("lib", "a", "2.0", "");
        MavenResolver.Result result = resolve("implementation platform(\"test.bom:bom:1.0\")\nimplementation(\"lib:unmanaged\")\nimplementation(\"lib:a\")");
        assertEquals(java.util.Arrays.asList("a-2.0.jar"), jarNames(result));
        assertTrue(result.warnings.toString().contains("lib:unmanaged"));
    }

    @Test public void missingBomFailsWithAClearMessage() throws IOException {
        try { resolve("implementation platform(\"test.bom:absent:1.0\")\nimplementation(\"lib:a:1.0\")"); fail("expected IOException"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("test.bom:absent:1.0")); }
    }

    @Test public void buildWithoutPlatformBehavesAsBefore() throws IOException {
        publish("lib", "a", "1.0", deps(dep("lib", "b", "1.0")));
        publish("lib", "b", "1.0", "");
        MavenResolver.Result result = resolve("implementation(\"lib:a:1.0\")");
        assertEquals(java.util.Arrays.asList("a-1.0.jar", "b-1.0.jar"), jarNames(result));
        assertFalse(result.notes.toString().contains("platform"));
    }
}
