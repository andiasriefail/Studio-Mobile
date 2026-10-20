package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.dart.DartRuntime;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DartRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void reportsEverythingMissing() throws Exception {
        List<String> problems = new ArrayList<>();
        assertNull(DartRuntime.locate(temporary.newFolder(), temporary.newFolder(), problems));
        assertEquals(2, problems.size());
    }

    @Test public void buildsExplicitCommandsWithoutRelyingOnTheExecutableLocation() throws Exception {
        File files = temporary.newFolder(), nativeDirectory = temporary.newFolder();
        for (String name : new String[] {DartRuntime.AOT_RUNTIME, DartRuntime.VM}) {
            File executable = new File(nativeDirectory, name); Files.write(executable.toPath(), new byte[] {0x7f, 'E', 'L', 'F'}); assertTrue(executable.setExecutable(true));
        }
        File home = new File(files, "dart-sdk");
        for (String path : new String[] {DartRuntime.FRONTEND_SNAPSHOT, DartRuntime.PLATFORM_DILL}) {
            File file = new File(home, path); assertTrue(file.getParentFile().mkdirs()); assertTrue(file.createNewFile());
        }
        List<String> problems = new ArrayList<>();
        DartRuntime runtime = DartRuntime.locate(files, nativeDirectory, problems);
        assertNotNull(problems.toString(), runtime);
        List<String> compile = runtime.compileCommand(new File("/p/main.dart"), new File("/c/main.dart.dill"));
        assertEquals(new File(nativeDirectory, DartRuntime.AOT_RUNTIME).getAbsolutePath(), compile.get(0));
        assertTrue(compile.contains("--link-platform"));
        assertEquals("/p/main.dart", compile.get(compile.size() - 1));
        assertEquals(new File(nativeDirectory, DartRuntime.VM).getAbsolutePath(), runtime.runCommand(new File("/c/x.dill")).get(0));
    }
}
