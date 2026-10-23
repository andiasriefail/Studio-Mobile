package com.example.studiomobile.build.flutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FlutterProjectDetectorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File write(File root, String path, String text) throws Exception {
        File file = new File(root, path); file.getParentFile().mkdirs(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void flutterProjectWithAndroidFolderIsBuildable() throws Exception {
        File project = temporary.newFolder();
        write(project, "pubspec.yaml", "name: demo\ndependencies:\n  flutter:\n    sdk: flutter\n  http: ^1.0.0\nflutter:\n  uses-material-design: true\n");
        write(project, "android/build.gradle", "");
        FlutterProjectDetector.Detection detection = FlutterProjectDetector.detect(project);
        assertTrue(detection.isFlutter());
        assertTrue(detection.isBuildable());
        assertNull(detection.getProblem());
        assertEquals(new File(project, "android"), detection.getAndroidDirectory());
    }

    @Test public void flutterProjectWithoutAndroidFolderIsReportedNotBuilt() throws Exception {
        File project = temporary.newFolder();
        write(project, "pubspec.yaml", "name: demo\ndependencies:\n  flutter:\n    sdk: flutter\n");
        FlutterProjectDetector.Detection detection = FlutterProjectDetector.detect(project);
        assertTrue(detection.isFlutter());
        assertFalse(detection.isBuildable());
        assertTrue(detection.getProblem().contains("android/"));
    }

    @Test public void plainDartProjectIsNotFlutter() throws Exception {
        File project = temporary.newFolder();
        write(project, "pubspec.yaml", "name: dartonly\ndependencies:\n  args: ^2.0.0\n");
        write(project, "android/build.gradle", "");
        assertFalse(FlutterProjectDetector.detect(project).isFlutter());
    }

    @Test public void inlineDependencyIsRecognised() throws Exception {
        File project = temporary.newFolder();
        write(project, "pubspec.yaml", "name: x\ndependencies:\n  flutter: {sdk: flutter}\n");
        write(project, "android/settings.gradle.kts", "");
        assertTrue(FlutterProjectDetector.detect(project).isBuildable());
    }

    @Test public void commentedOutDependencyIsIgnored() throws Exception {
        File project = temporary.newFolder();
        write(project, "pubspec.yaml", "name: x\n# flutter:\n#   sdk: flutter\ndependencies:\n  args: ^2.0.0\n");
        write(project, "android/build.gradle", "");
        assertFalse(FlutterProjectDetector.detect(project).isFlutter());
    }

    @Test public void missingPubspecOrFolderIsNotFlutter() throws Exception {
        assertFalse(FlutterProjectDetector.detect(temporary.newFolder()).isFlutter());
        assertFalse(FlutterProjectDetector.detect(null).isFlutter());
    }
}
