package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.dependency.ChecksumFailure;
import com.example.studiomobile.build.dependency.ChecksumPins;
import com.example.studiomobile.build.dependency.GradleDependencyParser;
import com.example.studiomobile.build.dependency.MavenRepository;
import com.example.studiomobile.build.dependency.MavenResolver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** JitPack, a developer's own repository, pins and fallbacks between repositories, against in-memory servers. */
public class MavenResolverRepositoriesTest {
    private static final String JITPACK = "https://jitpack.io/", OWN = "https://repo.example.com/m/", GOOGLE = MavenRepository.GOOGLE_URL;
    private final Map<String, byte[]> files = new HashMap<>();
    private final Set<String> noSha1 = new HashSet<>(), denied = new HashSet<>();
    private final List<String> requested = new ArrayList<>();
    private File project, cache;

    @Before public void setUp() throws IOException { project = Files.createTempDirectory("repos-resolver").toFile(); cache = new File(project, "cache"); }
    @After public void tearDown() { delete(project); }
    private static void delete(File f) { File[] c = f.listFiles(); if (c != null) for (File x : c) delete(x); f.delete(); }

    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format("%02x", b & 0xff)); return s.toString(); }
    private static String digest(String algorithm, byte[] data) throws IOException {
        try { return hex(MessageDigest.getInstance(algorithm).digest(data)); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static String sha256(String text) throws IOException { return digest("SHA-256", text.getBytes(StandardCharsets.UTF_8)); }

    private static String base(String group, String artifact, String version) { return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version; }
    private void publish(String repo, String group, String artifact, String version) {
        String b = repo + base(group, artifact, version);
        files.put(b + ".pom", ("<project><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8));
        files.put(b + ".jar", ("jar " + artifact + " " + version).getBytes(StandardCharsets.UTF_8));
    }

    private final MavenResolver.Fetcher fetcher = new MavenResolver.Fetcher() {
        @Override public byte[] get(String url, long maxBytes) throws IOException {
            requested.add(url);
            for (String prefix : denied) if (url.startsWith(prefix)) throw new MavenResolver.AccessDenied("HTTP 403 for " + url);
            if (url.endsWith(".sha1")) {
                String main = url.substring(0, url.length() - 5);
                byte[] data = files.get(main);
                if (data == null || noSha1.contains(main)) return null;
                return digest("SHA-1", data).getBytes(StandardCharsets.UTF_8);
            }
            return files.get(url);
        }
    };

    private MavenResolver.Result resolve(ChecksumPins pins, String dependency, MavenRepository... repositories) throws IOException {
        File module = new File(project, "app"); module.mkdirs();
        Files.write(new File(module, "build.gradle").toPath(), ("dependencies {\n" + dependency + "\n}\n").getBytes(StandardCharsets.UTF_8));
        GradleDependencyParser parsed = GradleDependencyParser.parse(project, module);
        return MavenResolver.forRepositories(cache, fetcher, Arrays.asList(repositories)).withPins(pins).resolve(parsed.declarations, parsed.platforms);
    }
    private static ChecksumPins pins(String line) throws IOException {
        File project = Files.createTempDirectory("pins").toFile(); File f = new File(project, ChecksumPins.FILE); f.getParentFile().mkdirs();
        Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8));
        return ChecksumPins.load(project);
    }
    private static MavenRepository own() { return MavenRepository.of(OWN); }

    @Test public void libraryFoundOnlyOnJitpackIsDownloadedAfterGoogleAndCentralAnswerNotFound() throws IOException {
        publish(JITPACK, "com.github.user", "repo", "1.0");
        MavenResolver.Result r = resolve(ChecksumPins.empty(), "implementation(\"com.github.user:repo:1.0\")", MavenRepository.google(), MavenRepository.central(), MavenRepository.jitpack());
        assertEquals(1, r.jars.size());
        assertTrue(requested.get(0).startsWith(GOOGLE));
    }

    @Test public void unpinnedFileFromJitpackIsUsedButWarnedAboutWithTheLineToPin() throws IOException {
        publish(JITPACK, "com.github.user", "repo", "1.0");
        MavenResolver.Result r = resolve(ChecksumPins.empty(), "implementation(\"com.github.user:repo:1.0\")", MavenRepository.jitpack());
        String warnings = r.warnings.toString();
        assertTrue(warnings, warnings.contains("not pinned"));
        assertTrue(warnings, warnings.contains("com.github.user:repo:1.0=" + sha256("jar repo 1.0")));
    }

    @Test public void pinThatMatchesIsAcceptedEvenWhenTheServerPublishesNoSha1() throws IOException {
        publish(OWN, "org.dev", "lib", "2.0");
        noSha1.add(OWN + base("org.dev", "lib", "2.0") + ".jar");
        MavenResolver.Result r = resolve(pins("org.dev:lib:2.0=" + sha256("jar lib 2.0")), "implementation(\"org.dev:lib:2.0\")", own());
        assertEquals(1, r.jars.size());
        assertTrue(r.notes.toString(), r.notes.toString().contains("[SHA-256 pinned]"));
        assertTrue(r.warnings.toString(), r.warnings.isEmpty());
    }

    @Test public void wrongPinStopsWithAChecksumFailureAndLeavesNoFileInTheCache() throws IOException {
        publish(OWN, "org.dev", "lib", "2.0");
        String wrong = sha256("something else");
        try {
            resolve(pins("org.dev:lib:2.0=" + wrong), "implementation(\"org.dev:lib:2.0\")", own());
            fail("expected a ChecksumFailure");
        } catch (ChecksumFailure failure) {
            assertEquals(ChecksumFailure.Kind.PIN_MISMATCH, failure.kind());
            assertEquals(wrong, failure.expected());
            assertEquals(sha256("jar lib 2.0"), failure.actual());
            assertEquals("org.dev:lib:2.0=" + sha256("jar lib 2.0"), failure.pinLine());
            assertTrue(failure.details().contains(wrong));
        }
        assertFalse(new File(cache, base("org.dev", "lib", "2.0") + ".jar").exists());
    }

    @Test public void pinAddedLaterIsCheckedAgainstTheCachedFile() throws IOException {
        publish(OWN, "org.dev", "lib", "2.0");
        resolve(ChecksumPins.empty(), "implementation(\"org.dev:lib:2.0\")", own());
        try {
            resolve(pins("org.dev:lib:2.0=" + sha256("not it")), "implementation(\"org.dev:lib:2.0\")", own());
            fail("expected a ChecksumFailure");
        } catch (ChecksumFailure failure) { assertEquals(ChecksumFailure.Kind.PIN_MISMATCH, failure.kind()); }
    }

    @Test public void noSha1AndNoPinIsRefusedWithTheReceivedSha256() throws IOException {
        publish(OWN, "org.dev", "lib", "2.0");
        noSha1.add(OWN + base("org.dev", "lib", "2.0") + ".jar");
        try {
            resolve(ChecksumPins.empty(), "implementation(\"org.dev:lib:2.0\")", own());
            fail("expected a ChecksumFailure");
        } catch (ChecksumFailure failure) {
            assertEquals(ChecksumFailure.Kind.SERVER_SHA1_MISSING, failure.kind());
            assertEquals(sha256("jar lib 2.0"), failure.receivedSha256());
        }
    }

    @Test public void sha1ThatDoesNotMatchIsRefused() throws IOException {
        publish(OWN, "org.dev", "lib", "2.0");
        final byte[] good = files.get(OWN + base("org.dev", "lib", "2.0") + ".jar");
        files.put(OWN + base("org.dev", "lib", "2.0") + ".jar", "tampered".getBytes(StandardCharsets.UTF_8));
        MavenResolver.Fetcher lying = new MavenResolver.Fetcher() {
            @Override public byte[] get(String url, long maxBytes) throws IOException {
                if (url.endsWith("lib-2.0.jar.sha1")) return digest("SHA-1", good).getBytes(StandardCharsets.UTF_8);
                return fetcher.get(url, maxBytes);
            }
        };
        File module = new File(project, "app"); module.mkdirs();
        Files.write(new File(module, "build.gradle").toPath(), "dependencies {\nimplementation(\"org.dev:lib:2.0\")\n}\n".getBytes(StandardCharsets.UTF_8));
        GradleDependencyParser parsed = GradleDependencyParser.parse(project, module);
        try {
            MavenResolver.forRepositories(cache, lying, Arrays.asList(own())).resolve(parsed.declarations, parsed.platforms);
            fail("expected a ChecksumFailure");
        } catch (ChecksumFailure failure) { assertEquals(ChecksumFailure.Kind.SERVER_SHA1_MISMATCH, failure.kind()); }
    }

    @Test public void repositoryThatRefusesAccessIsSkippedWhenALaterOneHasTheFile() throws IOException {
        denied.add(OWN);
        publish(JITPACK, "com.github.user", "repo", "1.0");
        MavenResolver.Result r = resolve(ChecksumPins.empty(), "implementation(\"com.github.user:repo:1.0\")", own(), MavenRepository.jitpack());
        assertEquals(1, r.jars.size());
    }

    @Test public void accessRefusalIsReportedWhenNoRepositoryHasTheFile() throws IOException {
        denied.add(OWN);
        try { resolve(ChecksumPins.empty(), "implementation(\"org.dev:lib:2.0\")", own()); fail("expected an AccessDenied"); }
        catch (MavenResolver.AccessDenied expected) { assertTrue(expected.getMessage().contains("403")); }
    }

    @Test public void accessRefusalIsOnlyAWarningWhenAnotherRepositoryAnsweredNotFound() throws IOException {
        denied.add(OWN);
        publish(GOOGLE, "org.dev", "lib", "2.0");
        MavenResolver.Result r = resolve(ChecksumPins.empty(), "implementation(\"org.dev:lib:2.0\")", MavenRepository.google(), own());
        assertEquals(1, r.jars.size());
    }

    @Test public void contentFilterKeepsOtherLibrariesAwayFromTheRepository() throws IOException {
        // the filter is set the way a user sets it: with a content block in the build file
        File settings = new File(project, "settings.gradle.kts");
        Files.write(settings.toPath(), "repositories { maven(\"https://jitpack.io\") { content { includeGroup(\"com.github.user\") } } }".getBytes(StandardCharsets.UTF_8));
        MavenRepository filtered = com.example.studiomobile.build.dependency.RepositoryDeclarations.read(project, new ArrayList<File>()).repositories.get(0);
        publish(OWN, "other.group", "thing", "1.0");
        MavenResolver.Result r = resolve(ChecksumPins.empty(), "implementation(\"other.group:thing:1.0\")", MavenRepository.google(), filtered, own());
        assertEquals(1, r.jars.size());
        for (String url : requested) assertFalse(url, url.startsWith(JITPACK));
    }

    @Test public void notFoundMarkerIsIgnoredOnceANewRepositoryIsAdded() throws IOException {
        publish(GOOGLE, "com.github.user", "repo", "1.0");
        files.put(JITPACK + base("com.github.user", "repo", "1.0") + ".jar", "jar from jitpack".getBytes(StandardCharsets.UTF_8));
        files.remove(GOOGLE + base("com.github.user", "repo", "1.0") + ".jar");   // the pom is on Google, the jar is not
        MavenResolver.Result first = resolve(ChecksumPins.empty(), "implementation(\"com.github.user:repo:1.0\")", MavenRepository.google(), MavenRepository.central());
        assertEquals(0, first.jars.size());
        MavenResolver.Result second = resolve(ChecksumPins.empty(), "implementation(\"com.github.user:repo:1.0\")", MavenRepository.google(), MavenRepository.central(), MavenRepository.jitpack());
        assertEquals(1, second.jars.size());
    }
}
