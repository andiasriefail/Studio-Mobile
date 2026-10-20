package com.example.studiomobile.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PythonRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void reportsMissingInterpreterAndLibrary() throws Exception {
        List<String> problems = new ArrayList<>();
        assertNull(PythonRuntime.locate(temporary.newFolder(), temporary.newFolder(), problems));
        assertEquals(2, problems.size());
    }

    @Test public void locatesInstalledRuntimeAndBuildsEnvironment() throws Exception {
        File files = temporary.newFolder(), nativeDirectory = temporary.newFolder();
        File executable = new File(nativeDirectory, PythonRuntime.EXECUTABLE_NAME);
        java.nio.file.Files.write(executable.toPath(), new byte[] {0x7f, 'E', 'L', 'F'}); assertTrue(executable.setExecutable(true));
        File library = new File(files, "python/lib/python3.14"); assertTrue(library.mkdirs());
        assertTrue(new File(library, "os.py").createNewFile());
        List<String> problems = new ArrayList<>();
        PythonRuntime runtime = PythonRuntime.locate(files, nativeDirectory, problems);
        assertNotNull(problems.toString(), runtime);
        Map<String, String> environment = runtime.environment(new File(files, "home"), new File(files, "cache"));
        assertEquals(new File(files, "python").getAbsolutePath(), environment.get("PYTHONHOME"));
        assertTrue(environment.get("LD_LIBRARY_PATH").startsWith(nativeDirectory.getAbsolutePath()));
        assertEquals(executable.getAbsolutePath(), runtime.command(new File("/x/a.py")).get(0));
    }
}
