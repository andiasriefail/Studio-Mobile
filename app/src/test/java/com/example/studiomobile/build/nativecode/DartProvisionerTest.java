package com.example.studiomobile.build.nativecode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DartProvisionerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File write(File root, String path) throws Exception {
        File file = new File(root, path); file.getParentFile().mkdirs(); Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void keepsLibrariesAndSnapshotsButNotTheExecutables() {
        assertTrue(DartProvisioner.isNeeded("lib/core/core.dart"));
        assertTrue(DartProvisioner.isNeeded("bin/snapshots/frontend_server_aot.dart.snapshot"));
        assertTrue(DartProvisioner.isNeeded("version"));
        assertFalse(DartProvisioner.isNeeded("bin/dart"));
        assertFalse(DartProvisioner.isNeeded("bin/dartaotruntime"));
        assertFalse(DartProvisioner.isNeeded("bin/dartvm"));
        assertFalse(DartProvisioner.isNeeded("include/dart_api.h"));
        assertFalse(DartProvisioner.isNeeded(""));
    }

    @Test public void layoutNeedsCoreLibraryAndCompilerSnapshot() throws Exception {
        File sdk = temporary.newFolder();
        assertNotNull(DartProvisioner.verifyLayout(sdk));
        write(sdk, "lib/core/core.dart");
        assertNotNull(DartProvisioner.verifyLayout(sdk));
        write(sdk, "bin/snapshots/frontend_server_aot.dart.snapshot");
        assertNull(DartProvisioner.verifyLayout(sdk));
    }

    @Test public void missingBundledRuntimeStopsTheBuild() throws Exception {
        File nativeLibraries = temporary.newFolder();
        try { DartProvisioner.runtime(nativeLibraries); fail("a missing Dart runtime must not be ignored"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains(DartProvisioner.RUNTIME_LIBRARY)); }
        write(nativeLibraries, DartProvisioner.RUNTIME_LIBRARY);
        assertEquals(DartProvisioner.RUNTIME_LIBRARY, DartProvisioner.runtime(nativeLibraries).getName());
    }
}
