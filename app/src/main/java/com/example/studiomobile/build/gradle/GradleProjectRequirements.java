package com.example.studiomobile.build.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a Gradle project asks for: Gradle version, AGP version and the minimum JDK implied by them. */
public final class GradleProjectRequirements {
    private final GradleVersion gradleVersion;   // exact version requested by the wrapper, or null
    private final GradleVersion agpVersion;      // null when it cannot be read statically
    private final GradleVersion effectiveGradle; // wrapper version, raised to the AGP minimum if needed
    private final int minimumJdk;
    private final boolean agpKnown;

    private GradleProjectRequirements(GradleVersion wrapper, GradleVersion agp, GradleVersion effective, int jdk, boolean known) {
        this.gradleVersion = wrapper; this.agpVersion = agp; this.effectiveGradle = effective; this.minimumJdk = jdk; this.agpKnown = known;
    }
    public GradleVersion getWrapperGradleVersion() { return gradleVersion; }
    public GradleVersion getAgpVersion() { return agpVersion; }
    public GradleVersion getRequiredGradleVersion() { return effectiveGradle; }
    public int getMinimumJdk() { return minimumJdk; }
    public boolean isAgpKnown() { return agpKnown; }

    private static final Pattern DISTRIBUTION = Pattern.compile("gradle-([0-9][0-9A-Za-z.\\-]*?)-(?:bin|all)\\.zip");
    private static final Pattern CATALOG_AGP = Pattern.compile("(?m)^\\s*agp\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PLUGIN_AGP = Pattern.compile("com\\.android\\.(?:application|library)['\"]?\\)?\\s*(?:version)?\\s*['\"]([0-9][^'\"]*)['\"]");
    private static final Pattern CLASSPATH_AGP = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9][^'\"\\s)]*)");

    public static GradleProjectRequirements read(File project) {
        GradleVersion wrapper = GradleVersion.parse(find(new File(project, "gradle/wrapper/gradle-wrapper.properties"), DISTRIBUTION));
        GradleVersion agp = GradleVersion.parse(readAgp(project));
        GradleCompatibilityMatrix.Requirement matrix = GradleCompatibilityMatrix.forAgp(agp);
        GradleVersion effective = wrapper;
        if (effective == null || (matrix.knownAgp && effective.compareTo(matrix.minimumGradle) < 0)) effective = matrix.knownAgp ? matrix.minimumGradle : wrapper;
        return new GradleProjectRequirements(wrapper, agp, effective, matrix.minimumJdk, matrix.knownAgp);
    }

    private static String readAgp(File project) {
        String value = find(new File(project, "gradle/libs.versions.toml"), CATALOG_AGP);
        String[] scripts = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"};
        for (String script : scripts) {
            if (value != null) break;
            File file = new File(project, script);
            value = find(file, PLUGIN_AGP);
            if (value == null) value = find(file, CLASSPATH_AGP);
        }
        return value;
    }

    private static String find(File file, Pattern pattern) {
        if (!file.isFile() || file.length() > 1024 * 1024) return null;
        try {
            Matcher matcher = pattern.matcher(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception ignored) { return null; }
    }
}
