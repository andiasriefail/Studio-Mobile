package com.example.studiomobile.build.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.build.dependency.MiniJson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.Test;

/** The names the app looks for must be the names the CI step writes, and a JDK without launchers must never reach the planner. */
public class JdkLauncherSetTest {
    private static JdkLauncherSet set(String... names) { return JdkLauncherSet.of(Arrays.asList(names)); }

    @Test public void fileNameIsTheNameTheCiScriptWrites() throws Exception {
        assertEquals("libjava21exe.so", JdkLauncherSet.fileName("java", 21));
        assertEquals("libjavac17exe.so", JdkLauncherSet.fileName("javac", 17));
        String script = new String(Files.readAllBytes(new File("../tools/resolve_gradle_jdk.py").toPath()), StandardCharsets.UTF_8);
        assertTrue(script.contains("\"lib%s%dexe.so\""));
    }

    @Test @SuppressWarnings("unchecked") public void toolListEqualsGradleJdkJson() throws Exception {
        String json = new String(Files.readAllBytes(new File("../tools/gradle-jdk.json").toPath()), StandardCharsets.UTF_8);
        Map<String, Object> jdk = (Map<String, Object>) ((Map<String, Object>) MiniJson.parse(json)).get("jdk");
        assertEquals(Arrays.asList(JdkLauncherSet.TOOLS), (List<Object>) jdk.get("launcherTools"));
    }

    @Test public void oldPlainNameIsNotALauncher() {
        assertTrue(set("libjavaexe.so", "libaapt2.so", "libpythonexe.so").isEmpty());
    }

    @Test public void javacIsNotMistakenForJava() {
        JdkLauncherSet launchers = set("libjavac21exe.so");
        assertTrue(launchers.majorsWithJava().isEmpty());
        assertEquals(new TreeSet<>(Arrays.asList("java", "jar", "jmod", "jlink")), launchers.missingTools(21));
    }

    @Test public void majorsAreReadPerJdkAndNeverSubstituted() {
        JdkLauncherSet launchers = set("libjava17exe.so", "libjava21exe.so", "libjavac21exe.so", "libjmod21exe.so", "libjlink21exe.so", "libjar21exe.so", "libjava25exe.so");
        assertEquals(new TreeSet<>(Arrays.asList(17, 21, 25)), launchers.majorsWithJava());
        assertEquals(new TreeSet<>(Collections.singletonList(21)), launchers.majorsForImageBuilds());
        assertTrue(launchers.missingTools(21).isEmpty());
        assertFalse(launchers.missingTools(17).isEmpty());
    }

    @Test public void restrictKeepsOnlyJdksWithLaunchers() {
        JdkLauncherSet launchers = set("libjava17exe.so", "libjava25exe.so");
        assertEquals(new TreeSet<>(Arrays.asList(17, 25)), JdkLauncherSet.restrict(Arrays.asList(11, 17, 21, 25, 27), launchers.majorsWithJava()));
    }

    @Test public void plannerFollowsTheProjectInsideTheLauncherBackedMajors() throws Exception {
        File file = new File("src/main/assets/build-tools/gradle-compat.json");
        GradleCompatibilityMatrix matrix = GradleCompatibilityMatrix.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        File dir = Files.createTempDirectory("launcher").toFile();
        File toml = new File(dir, "gradle/libs.versions.toml"); toml.getParentFile().mkdirs();
        Files.write(toml.toPath(), "[versions]\nagp = \"8.13.1\"\n".getBytes(StandardCharsets.UTF_8));
        GradleProjectRequirements need = GradleProjectRequirements.read(dir, matrix);
        List<Integer> installable = Arrays.asList(11, 17, 21, 25, 27);
        JdkLauncherSet launchers = set("libjava21exe.so", "libjava25exe.so");
        GradleToolchainPlanner.Plan plan = GradleToolchainPlanner.plan(need, matrix, Collections.singleton(GradleVersion.parse("9.5.0")),
            JdkLauncherSet.restrict(installable, launchers.majorsWithJava()), false);
        assertTrue(plan.error, plan.isUsable());
        assertEquals(21, plan.jdk);   // AGP 8.13 needs 17+; 17 has no launcher, so the next JDK that has one - not a hard-coded 21 (see next test)
        JdkLauncherSet only25 = set("libjava25exe.so");
        GradleToolchainPlanner.Plan plan25 = GradleToolchainPlanner.plan(need, matrix, Collections.singleton(GradleVersion.parse("9.5.0")),
            JdkLauncherSet.restrict(installable, only25.majorsWithJava()), false);
        assertEquals(25, plan25.jdk);
    }
}
