package com.example.studiomobile.build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validates the project layout without creating or injecting source files. */
public final class ProjectValidator {
    public static final class ValidationReport {
        private final List<String> errors;
        private final List<String> warnings;
        ValidationReport(List<String> errors, List<String> warnings) { this.errors = errors; this.warnings = warnings; }
        public List<String> getErrors() { return Collections.unmodifiableList(errors); }
        public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }
        public boolean isValid() { return errors.isEmpty(); }
    }

    public ValidationReport validate(File projectDirectory) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (projectDirectory == null || !projectDirectory.isDirectory()) {
            errors.add("Project directory does not exist or is not accessible.");
            return new ValidationReport(errors, warnings);
        }
        File manifest = new File(projectDirectory, "src/main/AndroidManifest.xml");
        File appModuleManifest = new File(projectDirectory, "app/src/main/AndroidManifest.xml");
        File gradleSettings = new File(projectDirectory, "settings.gradle");
        File gradleSettingsKts = new File(projectDirectory, "settings.gradle.kts");
        File appBuild = new File(projectDirectory, "app/build.gradle");
        File rootBuild = new File(projectDirectory, "build.gradle");
        boolean gradleProject = gradleSettings.isFile() || gradleSettingsKts.isFile() || rootBuild.isFile();
        boolean manifestFound = manifest.isFile() || appModuleManifest.isFile();
        if (!gradleProject && !manifestFound) errors.add("No Android project structure found. Expected Gradle settings/build files or src/main/AndroidManifest.xml.");
        if (gradleProject && !appBuild.isFile() && !new File(projectDirectory, "build.gradle.kts").isFile())
            warnings.add("No app/build.gradle found at the project root. Multi-module projects may need a module selection feature.");
        if (!manifestFound) errors.add("AndroidManifest.xml is missing. Empty projects are allowed in the editor, but cannot produce an APK until the user creates a manifest.");
        if (!new File(projectDirectory, "src/main/java").exists() && !new File(projectDirectory, "app/src/main/java").exists()
                && !new File(projectDirectory, "src/main/kotlin").exists() && !new File(projectDirectory, "app/src/main/kotlin").exists())
            warnings.add("No Java/Kotlin source directory found. No source files will be generated automatically.");
        return new ValidationReport(errors, warnings);
    }
}
