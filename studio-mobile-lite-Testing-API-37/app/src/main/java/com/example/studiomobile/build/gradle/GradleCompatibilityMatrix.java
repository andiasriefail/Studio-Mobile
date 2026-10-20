package com.example.studiomobile.build.gradle;

/**
 * Minimum Gradle and JDK versions required by each Android Gradle Plugin (AGP) series.
 * Values follow the AGP release notes "compatibility" table. Versions newer than the table
 * produce a warning instead of a failure so a newer AGP is never blocked by stale app data.
 */
public final class GradleCompatibilityMatrix {
    public static final class Requirement {
        public final GradleVersion minimumGradle; public final int minimumJdk; public final boolean knownAgp;
        Requirement(String gradle, int jdk, boolean known) { this.minimumGradle = GradleVersion.parse(gradle); this.minimumJdk = jdk; this.knownAgp = known; }
    }
    // {agp major.minor, minimum Gradle, minimum JDK}
    private static final String[][] TABLE = {
        {"7.0", "7.0.2", "11"}, {"7.1", "7.2", "11"}, {"7.2", "7.3.3", "11"}, {"7.3", "7.4", "11"}, {"7.4", "7.5", "11"},
        {"8.0", "8.0", "17"}, {"8.1", "8.0", "17"}, {"8.2", "8.2", "17"}, {"8.3", "8.4", "17"}, {"8.4", "8.6", "17"},
        {"8.5", "8.7", "17"}, {"8.6", "8.7", "17"}, {"8.7", "8.9", "17"}, {"8.8", "8.10.2", "17"}, {"8.9", "8.11.1", "17"},
        {"8.10", "8.11.1", "17"}, {"8.11", "8.13", "17"}, {"8.12", "8.13", "17"}, {"8.13", "8.13", "17"}
    };
    private GradleCompatibilityMatrix() { }

    public static Requirement forAgp(GradleVersion agp) {
        if (agp == null) return new Requirement("0.0", 8, false);
        for (String[] row : TABLE) {
            GradleVersion key = GradleVersion.parse(row[0]);
            if (key.getMajor() == agp.getMajor() && key.getMinor() == agp.getMinor()) return new Requirement(row[1], Integer.parseInt(row[2]), true);
        }
        // Unknown series: assume the newest known requirement for >= 8, otherwise unknown.
        if (agp.getMajor() >= 8) return new Requirement("8.13", 17, false);
        return new Requirement("0.0", 8, false);
    }

    /** Highest JDK major version a Gradle release can run on (Gradle compatibility matrix). */
    public static int maximumJdk(GradleVersion gradle) {
        if (gradle == null) return 21;
        if (gradle.compareTo(GradleVersion.parse("8.14")) >= 0) return 24;
        if (gradle.compareTo(GradleVersion.parse("8.10")) >= 0) return 23;
        if (gradle.compareTo(GradleVersion.parse("8.8")) >= 0) return 22;
        if (gradle.compareTo(GradleVersion.parse("8.5")) >= 0) return 21;
        if (gradle.compareTo(GradleVersion.parse("8.3")) >= 0) return 20;
        if (gradle.compareTo(GradleVersion.parse("7.6")) >= 0) return 19;
        return 18;
    }
}
