package com.example.studiomobile.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Runs real scripts with the host's /bin/sh (the phone uses /system/bin/sh the same way). */
public class ShellScriptTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final class Lines implements BuildLogger {
        final List<String> lines = new ArrayList<>();
        @Override public void onInfo(String message) { lines.add(message); }
        @Override public void onWarning(String message) { lines.add("warning: " + message); }
        @Override public void onError(String message, Throwable cause) { lines.add("error: " + message); }
    }

    private ShellRuntime host() {
        File sh = new File("/bin/sh");
        Assume.assumeTrue(sh.canExecute());
        ShellRuntime runtime = ShellRuntime.locate(sh, new ArrayList<String>());
        assertNotNull(runtime);
        return runtime;
    }

    private File write(File folder, String name, String text) throws Exception {
        File file = new File(folder, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;   // deliberately not executable: the shell only reads it
    }

    private ShellScriptRunner runner(ShellRuntime runtime) throws Exception {
        return new ShellScriptRunner(runtime, temp.newFolder("home"), temp.newFolder("cache"));
    }

    @Test public void recognisesShellFileNames() {
        assertTrue(ShellRuntime.isShellScript("build.sh"));
        assertTrue(ShellRuntime.isShellScript("Setup.BASH"));
        assertFalse(ShellRuntime.isShellScript("main.py"));
    }

    @Test public void missingShellIsReported() {
        List<String> problems = new ArrayList<>();
        assertNull(ShellRuntime.locate(new File("/no/such/sh"), problems));
        assertEquals(1, problems.size());
    }

    @Test public void runsScriptWithoutExecutableBit() throws Exception {
        File script = write(temp.newFolder("p"), "hello.sh", "echo hello\necho \"$STUDIO_MOBILE\"\n");
        Lines log = new Lines();
        assertEquals(0, runner(host()).run(script, 30, log));
        assertEquals("hello", log.lines.get(0));
        assertEquals("1", log.lines.get(1));
    }

    @Test public void passesExitCode() throws Exception {
        File script = write(temp.newFolder("p"), "fail.sh", "exit 3\n");
        assertEquals(3, runner(host()).run(script, 30, new Lines()));
    }

    @Test public void syntaxCheckDoesNotRun() throws Exception {
        ShellRuntime runtime = host();
        File marker = new File(temp.getRoot(), "ran");
        File good = write(temp.newFolder("g"), "good.sh", "touch '" + marker + "'\n");
        File bad = write(temp.newFolder("b"), "bad.sh", "if true; then\n");
        ProcessBuilder ok = new ProcessBuilder(runtime.syntaxCheckCommand(good));
        assertEquals(0, ok.start().waitFor());
        assertFalse(marker.exists());
        assertTrue(new ProcessBuilder(runtime.syntaxCheckCommand(bad)).start().waitFor() != 0);
    }

    @Test public void hooksRunInOrderInsideProject() throws Exception {
        File project = temp.newFolder("proj");
        write(project, "scripts/pre-build.sh", "echo pre > pre.txt\necho \"$STUDIO_PROJECT_DIR\"\n");
        write(project, "scripts/post-build.sh", "echo \"$STUDIO_APK\" > post.txt\n");
        File apk = new File(temp.getRoot(), "app.apk");
        final List<String> asked = new ArrayList<>();
        BuildScriptHooks hooks = new BuildScriptHooks(runner(host()), names -> { asked.addAll(names); return true; });
        Lines log = new Lines();
        hooks.runPreBuild(project, log);
        hooks.runPostBuild(project, apk, log);
        assertTrue(new File(project, "pre.txt").isFile());
        assertEquals(apk.getAbsolutePath(), new String(Files.readAllBytes(new File(project, "post.txt").toPath()), StandardCharsets.UTF_8).trim());
        assertEquals(2, asked.size());   // asked once, listing both scripts
        assertEquals(project.getAbsolutePath(), log.lines.get(1));
    }

    @Test public void projectWithoutScriptsIsUntouched() throws Exception {
        File project = temp.newFolder("empty");
        assertTrue(BuildScriptHooks.find(project).isEmpty());
        new BuildScriptHooks(runner(host()), names -> { throw new AssertionError("must not ask"); }).runPreBuild(project, new Lines());
    }

    @Test public void failingScriptStopsTheBuild() throws Exception {
        File project = temp.newFolder("bad");
        write(project, "scripts/pre-build.sh", "echo boom\nexit 2\n");
        try {
            new BuildScriptHooks(runner(host()), names -> true).runPreBuild(project, new Lines());
            fail("expected a failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("exit code 2"));
        }
    }

    @Test public void declinedScriptsStopTheBuildAndDoNotRun() throws Exception {
        File project = temp.newFolder("declined");
        write(project, "scripts/pre-build.sh", "touch ran.txt\n");
        try {
            new BuildScriptHooks(runner(host()), names -> false).runPreBuild(project, new Lines());
            fail("expected a refusal");
        } catch (IllegalStateException expected) {
            assertFalse(new File(project, "ran.txt").exists());
        }
    }
}
