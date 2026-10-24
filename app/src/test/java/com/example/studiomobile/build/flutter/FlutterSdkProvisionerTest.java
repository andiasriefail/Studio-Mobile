package com.example.studiomobile.build.flutter;

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

public class FlutterSdkProvisionerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File write(File root, String path) throws Exception {
        File file = new File(root, path); file.getParentFile().mkdirs(); Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void everyFileOfTheArchiveIsKept() {
        assertTrue(FlutterSdkProvisioner.isNeeded("bin/flutter"));
        assertTrue(FlutterSdkProvisioner.isNeeded("packages/flutter/lib/widgets.dart"));
        assertFalse(FlutterSdkProvisioner.isNeeded(""));
    }

    @Test public void layoutNeedsEveryRequiredFileAndNamesTheMissingOne() throws Exception {
        File sdk = temporary.newFolder();
        assertNotNull(FlutterSdkProvisioner.verifyLayout(sdk));
        for (String relative : FlutterSdkProvisioner.REQUIRED_FILES) write(sdk, relative);
        assertNull(FlutterSdkProvisioner.verifyLayout(sdk));
        assertTrue(new File(sdk, "bin/internal/engine.version").delete());
        assertTrue(FlutterSdkProvisioner.verifyLayout(sdk).contains("engine.version"));
    }

    @Test public void missingBundledExecutableStopsTheBuild() throws Exception {
        File nativeLibraries = temporary.newFolder();
        try { FlutterSdkProvisioner.bundled(nativeLibraries, FlutterSdkProvisioner.DART_LIBRARY); fail("a missing Flutter executable must not be ignored"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains(FlutterSdkProvisioner.DART_LIBRARY)); }
        write(nativeLibraries, FlutterSdkProvisioner.DART_LIBRARY);
        assertEquals(FlutterSdkProvisioner.DART_LIBRARY, FlutterSdkProvisioner.bundled(nativeLibraries, FlutterSdkProvisioner.DART_LIBRARY).getName());
    }

    @Test public void installWithoutACatalogEntryExplainsWhy() throws Exception {
        FlutterSdkProvisioner provisioner = new FlutterSdkProvisioner(temporary.newFolder(), null, java.util.Collections.singletonList("arm64-v8a"));
        try { provisioner.install(temporary.newFolder(), new com.example.studiomobile.build.BuildLogger() {
            @Override public void onInfo(String message) { }
            @Override public void onWarning(String message) { }
            @Override public void onError(String message, Throwable cause) { }
        }); fail("a catalog without a flutter-sdk entry must stop the build"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("flutter-sdk-")); }
    }
}
