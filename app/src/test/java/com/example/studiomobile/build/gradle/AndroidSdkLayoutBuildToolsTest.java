package com.example.studiomobile.build.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AndroidSdkLayoutBuildToolsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final BuildLogger QUIET = new BuildLogger() {
        @Override public void onInfo(String message) { }
        @Override public void onWarning(String message) { }
        @Override public void onError(String message, Throwable cause) { }
    };

    private static final String LICENSE_FAILURE = "Failed to install the following Android SDK packages as some licences have not been accepted.\n"
        + "     build-tools;36.0.0 Android SDK Build-Tools 36\n";
    private static final String CORRUPTED_FAILURE = "> Installed Build Tools revision 36.0.0 is corrupted. Remove and install again using the SDK Manager.\n";

    private void assertComplete(File dir) {
        for (String name : new String[] {"aapt", "aapt2", "aidl", "d8", "zipalign", "apksigner", "lib/d8.jar", "lib/apksigner.jar"}) {
            assertTrue(name + " must exist", new File(dir, name).exists());
        }
        assertTrue(new File(dir, "package.xml").isFile());
    }

    @Test public void licenseFailureRegistersAndFillsBuildTools() {
        File files = temp.getRoot();
        assertEquals(1, AndroidSdkLayout.provideMissing(files, LICENSE_FAILURE, QUIET));
        assertComplete(new File(files, "android-sdk/build-tools/36.0.0"));
    }

    @Test public void corruptedFolderWithOnlyPackageXmlIsRepaired() throws Exception {
        File files = temp.getRoot();
        File dir = new File(files, "android-sdk/build-tools/36.0.0");
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "package.xml").toPath(), "<x/>".getBytes("UTF-8"));
        assertFalse(new File(dir, "aapt").exists());
        assertEquals(1, AndroidSdkLayout.provideMissing(files, CORRUPTED_FAILURE, QUIET));
        assertComplete(dir);
    }

    @Test public void completeFolderIsLeftAloneSoTheBuildIsNotRetriedForever() {
        File files = temp.getRoot();
        AndroidSdkLayout.provideMissing(files, LICENSE_FAILURE, QUIET);
        assertEquals(0, AndroidSdkLayout.provideMissing(files, CORRUPTED_FAILURE, QUIET));
    }

    @Test public void missingAaptMessageIsRecognised() {
        File files = temp.getRoot();
        String output = "Build-tool 36.0.0 is missing AAPT at /x/android-sdk/build-tools/36.0.0/aapt\n";
        assertEquals(1, AndroidSdkLayout.provideMissing(files, output, QUIET));
        assertComplete(new File(files, "android-sdk/build-tools/36.0.0"));
    }

    @Test public void existingToolsAreNotOverwritten() throws Exception {
        File files = temp.getRoot();
        File dir = new File(files, "android-sdk/build-tools/36.0.0");
        assertTrue(dir.mkdirs());
        File aapt = new File(dir, "aapt");
        Files.write(aapt.toPath(), "real".getBytes("UTF-8"));
        AndroidSdkLayout.provideMissing(files, CORRUPTED_FAILURE, QUIET);
        assertEquals("real", new String(Files.readAllBytes(aapt.toPath()), "UTF-8"));
    }

    @Test public void coreForSystemModulesIsBuiltFromAndroidJarForApi34AndUp() throws Exception {
        File files = temp.getRoot();
        File platform = new File(files, "build-tools/platforms/android-37");
        assertTrue(platform.mkdirs());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(new File(platform, "android.jar")))) {
            for (String name : new String[] {"java/lang/Object.class", "android/app/Activity.class", "resources.arsc"}) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[] {1, 2, 3}); zip.closeEntry();
            }
        }
        assertTrue(AndroidSdkLayout.ensure(files, QUIET) != null);
        File core = new File(files, "android-sdk/platforms/android-37/core-for-system-modules.jar");
        assertTrue(core.isFile());
        try (ZipFile zip = new ZipFile(core)) {
            assertTrue(zip.getEntry("java/lang/Object.class") != null);
            assertFalse(zip.getEntry("android/app/Activity.class") != null);
        }
    }
}
