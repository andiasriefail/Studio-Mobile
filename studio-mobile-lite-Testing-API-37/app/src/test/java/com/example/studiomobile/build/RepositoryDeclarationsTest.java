package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.build.dependency.MavenRepository;
import com.example.studiomobile.build.dependency.RepositoryDeclarations;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RepositoryDeclarationsTest {
    private File project;

    @Before public void setUp() throws IOException { project = Files.createTempDirectory("repos-test").toFile(); }
    @After public void tearDown() { delete(project); }
    private static void delete(File f) { File[] c = f.listFiles(); if (c != null) for (File x : c) delete(x); f.delete(); }

    private RepositoryDeclarations read(String fileName, String text) throws IOException {
        Files.write(new File(project, fileName).toPath(), text.getBytes(StandardCharsets.UTF_8));
        return RepositoryDeclarations.read(project, Collections.<File>emptyList());
    }
    private static List<String> urls(List<MavenRepository> repositories) {
        List<String> out = new ArrayList<>();
        for (MavenRepository r : repositories) out.add(r.url);
        return out;
    }

    @Test public void kotlinSettingsWithJitpackAndOwnServer() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts",
            "dependencyResolutionManagement {\n  repositories {\n    google()\n    mavenCentral()\n"
            + "    maven(\"https://jitpack.io\")\n    maven { url = uri(\"https://repo.example.com/releases\") }\n  }\n}\n");
        assertEquals(java.util.Arrays.asList("https://dl.google.com/dl/android/maven2/", "https://repo1.maven.org/maven2/",
            "https://jitpack.io/", "https://repo.example.com/releases/"), urls(r.repositories));
        assertTrue(r.warnings.isEmpty());
    }

    @Test public void groovyForms() throws IOException {
        RepositoryDeclarations r = read("build.gradle",
            "allprojects {\n repositories {\n  maven { url 'https://jitpack.io' }\n  maven { url = \"https://a.example.com/m\" }\n  maven { setUrl(\"https://b.example.com/m/\") }\n }\n}\n");
        assertEquals(java.util.Arrays.asList("https://jitpack.io/", "https://a.example.com/m/", "https://b.example.com/m/"), urls(r.repositories));
    }

    @Test public void namedArgumentAndUriInArguments() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts", "repositories {\n maven(url = \"https://a.example.com/x\")\n maven(url = uri(\"https://b.example.com/x\"))\n maven(name = \"n\", url = \"https://c.example.com/x\")\n}\n");
        assertEquals(java.util.Arrays.asList("https://a.example.com/x/", "https://b.example.com/x/", "https://c.example.com/x/"), urls(r.repositories));
    }

    @Test public void commentsDoNotCutTheAddressAndCommentedRepositoriesAreIgnored() throws IOException {
        RepositoryDeclarations r = read("settings.gradle",
            "repositories {\n  // maven { url 'https://old.example.com' }\n  /* maven { url 'https://older.example.com' } */\n  maven { url 'https://jitpack.io' } // the real one\n}\n");
        assertEquals(Collections.singletonList("https://jitpack.io/"), urls(r.repositories));
    }

    @Test public void contentFilterIsRead() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts",
            "repositories {\n maven(\"https://jitpack.io\") { content { includeGroup(\"com.github.user\"); includeGroupAndSubgroups(\"com.github.team\") } }\n}\n");
        MavenRepository jitpack = r.repositories.get(0);
        assertTrue(jitpack.allows("com.github.user"));
        assertTrue(jitpack.allows("com.github.team.sub"));
        assertFalse(jitpack.allows("androidx.core"));
        assertFalse(jitpack.allows("com.github.users"));
    }

    @Test public void exclusiveContentFilterApplies() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts",
            "repositories {\n exclusiveContent {\n  forRepository { maven(\"https://jitpack.io\") }\n  filter { includeGroup(\"com.github.user\") }\n }\n}\n");
        assertEquals(1, r.repositories.size());
        assertTrue(r.repositories.get(0).allows("com.github.user"));
        assertFalse(r.repositories.get(0).allows("other"));
    }

    @Test public void httpVariablesAndCredentialsAreReported() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts",
            "repositories {\n maven(\"http://insecure.example.com/m\")\n maven(url = uri(repoUrl))\n maven { url = uri(\"https://private.example.com/m\"); credentials { username = \"u\" } }\n flatDir { dirs(\"libs\") }\n mavenLocal()\n}\n");
        assertEquals(Collections.singletonList("https://private.example.com/m/"), urls(r.repositories));
        String warnings = r.warnings.toString();
        assertTrue(warnings, warnings.contains("not HTTPS"));
        assertTrue(warnings, warnings.contains("not a plain text string"));
        assertTrue(warnings, warnings.contains("credentials"));
        assertTrue(warnings, warnings.contains("flatDir"));
        assertTrue(warnings, warnings.contains("mavenLocal"));
    }

    @Test public void pluginPortalAndJcenterAreKnownButJcenterIsFlagged() throws IOException {
        RepositoryDeclarations r = read("settings.gradle", "pluginManagement { repositories { gradlePluginPortal() } }\nrepositories { jcenter() }\n");
        assertEquals(java.util.Arrays.asList("https://plugins.gradle.org/m2/", "https://jcenter.bintray.com/"), urls(r.repositories));
        assertTrue(r.warnings.toString().contains("jcenter"));
    }

    @Test public void defaultsAreAddedAfterDeclaredOnes() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts", "repositories { maven(\"https://jitpack.io\") }\n");
        assertEquals(java.util.Arrays.asList("https://jitpack.io/", "https://dl.google.com/dl/android/maven2/", "https://repo1.maven.org/maven2/"), urls(r.withDefaults()));
        RepositoryDeclarations none = RepositoryDeclarations.read(Files.createTempDirectory("empty").toFile(), Collections.<File>emptyList());
        assertEquals(java.util.Arrays.asList("https://dl.google.com/dl/android/maven2/", "https://repo1.maven.org/maven2/"), urls(none.withDefaults()));
    }

    @Test public void sameAddressIsKeptOnce() throws IOException {
        RepositoryDeclarations r = read("settings.gradle.kts", "repositories { google(); mavenCentral(); mavenCentral() }\n");
        assertEquals(2, r.repositories.size());
        assertEquals(2, r.withDefaults().size());
    }
}
