package com.example.studiomobile.build.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The planner must follow the project (wrapper, AGP, Java level) and never pick a Gradle outside what the AGP supports. */
public class GradleToolchainPlannerTest {
    /** Same file the app ships, so the test fails when the data and the rules drift apart. */
    private static GradleCompatibilityMatrix matrix() throws Exception {
        File file = new File("src/main/assets/build-tools/gradle-compat.json");
        return GradleCompatibilityMatrix.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static File project(String... pathsAndText) throws Exception {
        File root = Files.createTempDirectory("planner").toFile();
        for (int i = 0; i < pathsAndText.length; i += 2) {
            File file = new File(root, pathsAndText[i]);
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), pathsAndText[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private static Set<GradleVersion> gradles(String... versions) {
        Set<GradleVersion> set = new HashSet<>();
        for (String version : versions) set.add(GradleVersion.parse(version));
        return set;
    }

    private static final List<Integer> ALL_JDKS = Arrays.asList(11, 17, 21, 25, 27);

    private static final String CATALOG_813 = "[versions]\nagp = \"8.13.1\"\n";

    @Test public void agp813WithJava25PicksGradle95AndJdk25() throws Exception {   // the Java25Test project when it does need Gradle
        File dir = project("gradle/libs.versions.toml", CATALOG_813,
            "app/build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_25\n targetCompatibility = JavaVersion.VERSION_25 } }");
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(dir, matrix()), matrix(), gradles("8.13", "9.5.0", "9.8.1"), ALL_JDKS, false);
        assertTrue(plan.error, plan.isUsable());
        assertEquals(GradleVersion.parse("9.5.0"), plan.gradle);   // 9.8.1 is outside the AGP 8.13 window, 8.13 cannot run JDK 25
        assertEquals(25, plan.jdk);
    }

    @Test public void agp813NeverFallsBackToGradle98() throws Exception {   // the failure in the build log
        File dir = project("gradle/libs.versions.toml", CATALOG_813);
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(dir, matrix()), matrix(), gradles("9.8.1"), ALL_JDKS, false);
        assertFalse(plan.isUsable());
        assertTrue(plan.error, plan.error.contains("below 9.6"));
    }

    @Test public void wrapperVersionIsFollowedExactly() throws Exception {
        File dir = project("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip",
            "build.gradle", "plugins { id 'com.android.application' version '9.4.1' apply false }",
            "app/build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_21 } }");
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(dir, matrix()), matrix(), gradles("8.13", "9.5.0", "9.8.1"), ALL_JDKS, false);
        assertTrue(plan.error, plan.isUsable());
        assertEquals(GradleVersion.parse("9.8.1"), plan.gradle);
        assertEquals(21, plan.jdk);
        assertTrue(plan.warnings.isEmpty());
    }

    @Test public void wrapperBelowAgpMinimumIsRaised() throws Exception {
        File dir = project("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=gradle-8.9-bin.zip", "gradle/libs.versions.toml", CATALOG_813);
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(dir, matrix()), matrix(), gradles("8.13", "9.8.1"), ALL_JDKS, false);
        assertEquals(GradleVersion.parse("8.13"), plan.gradle);
        assertEquals(17, plan.jdk);
    }

    @Test public void wrapperFetchesItsOwnGradleWhenTheCatalogLacksIt() throws Exception {
        File dir = project("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=gradle-8.9-bin.zip",
            "build.gradle", "plugins { id 'com.android.application' version '8.7.0' apply false }");
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(dir, matrix()), matrix(), gradles("9.8.1"), ALL_JDKS, true);
        assertTrue(plan.error, plan.isUsable());
        assertTrue(plan.viaWrapper);
        assertEquals(GradleVersion.parse("8.9"), plan.gradle);
    }

    @Test public void missingJdkIsReportedNotSubstituted() throws Exception {
        File dir = project("gradle/libs.versions.toml", CATALOG_813, "app/build.gradle", "android { compileOptions { sourceCompatibility = JavaVersion.VERSION_25 } }");
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(dir, matrix()), matrix(), gradles("8.13", "9.5.0"), Arrays.asList(17, 21), false);
        assertFalse(plan.isUsable());
        assertNotNull(plan.error);
    }

    @Test public void unknownAgpIsNotBlocked() throws Exception {
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(GradleProjectRequirements.read(project("settings.gradle", "include ':app'"), matrix()), matrix(),
            gradles("9.8.1"), Collections.singletonList(21), false);
        assertTrue(plan.error, plan.isUsable());
    }

    @Test public void newMatrixRowNeedsNoCodeChange() throws Exception {
        GradleCompatibilityMatrix custom = GradleCompatibilityMatrix.parse("{\"agp\":[{\"series\":\"10.0\",\"minGradle\":\"10.1\",\"minJdk\":21}],\"gradleJdk\":[{\"from\":\"0.0\",\"maxJdk\":30}]}");
        File dir = project("build.gradle", "plugins { id 'com.android.application' version '10.0.2' apply false }");
        GradleProjectRequirements need = GradleProjectRequirements.read(dir, custom);
        assertEquals(GradleVersion.parse("10.1"), need.getRequiredGradleVersion());
        assertEquals(21, need.getMinimumJdk());
    }
}
