package com.example.studiomobile.build.gradle;

import com.example.studiomobile.build.dependency.MiniJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AGP / Gradle / JDK compatibility rules, read from assets/build-tools/gradle-compat.json instead of being compiled in.
 * A rule that is not in the file is never guessed: the answer is then "unknown" (known == false) and the caller must not
 * block the build because of stale app data.
 */
public final class GradleCompatibilityMatrix {
    /** What one AGP version needs. Null bounds mean "not restricted / not known". */
    public static final class Requirement {
        public final GradleVersion minimumGradle;
        public final GradleVersion maximumGradleExclusive;
        public final int minimumJdk;
        public final boolean knownAgp;
        Requirement(GradleVersion min, GradleVersion maxBefore, int jdk, boolean known) {
            this.minimumGradle = min; this.maximumGradleExclusive = maxBefore; this.minimumJdk = jdk; this.knownAgp = known;
        }
    }

    private static final class Row {
        final int major, minor; final GradleVersion min, maxBefore; final int jdk;
        Row(int major, int minor, GradleVersion min, GradleVersion maxBefore, int jdk) { this.major = major; this.minor = minor; this.min = min; this.maxBefore = maxBefore; this.jdk = jdk; }
    }
    private static final class GradleJdk {
        final GradleVersion from; final int maxJdk;
        GradleJdk(GradleVersion from, int maxJdk) { this.from = from; this.maxJdk = maxJdk; }
    }

    private final List<Row> exact = new ArrayList<>();
    private final List<Row> majorDefaults = new ArrayList<>();
    private final List<GradleJdk> gradleJdk = new ArrayList<>();
    private int unknownMinJdk = 8;

    private GradleCompatibilityMatrix() { }

    /** A matrix without any rule: every answer is "unknown". Used when the data file is missing. */
    public static GradleCompatibilityMatrix empty() { return new GradleCompatibilityMatrix(); }

    @SuppressWarnings("unchecked")
    public static GradleCompatibilityMatrix parse(String json) {
        GradleCompatibilityMatrix matrix = new GradleCompatibilityMatrix();
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(json);
        Object unknown = root.get("unknownMinJdk");
        if (unknown != null) matrix.unknownMinJdk = Integer.parseInt(unknown.toString());
        for (Object item : list(root.get("agp"))) {
            Map<String, Object> row = (Map<String, Object>) item;
            GradleVersion series = GradleVersion.parse(text(row, "series"));
            if (series == null) throw new IllegalArgumentException("Bad AGP series in gradle-compat.json: " + row);
            matrix.exact.add(new Row(series.getMajor(), series.getMinor(), GradleVersion.parse(text(row, "minGradle")),
                GradleVersion.parse(text(row, "maxGradleBefore")), Integer.parseInt(text(row, "minJdk"))));
        }
        for (Object item : list(root.get("majorDefaults"))) {
            Map<String, Object> row = (Map<String, Object>) item;
            matrix.majorDefaults.add(new Row(Integer.parseInt(text(row, "major")), -1, GradleVersion.parse(text(row, "minGradle")),
                GradleVersion.parse(text(row, "maxGradleBefore")), Integer.parseInt(text(row, "minJdk"))));
        }
        for (Object item : list(root.get("gradleJdk"))) {
            Map<String, Object> row = (Map<String, Object>) item;
            matrix.gradleJdk.add(new GradleJdk(GradleVersion.parse(text(row, "from")), Integer.parseInt(text(row, "maxJdk"))));
        }
        // Newest line first, whatever order the file lists them in.
        java.util.Collections.sort(matrix.gradleJdk, new java.util.Comparator<GradleJdk>() {
            @Override public int compare(GradleJdk a, GradleJdk b) { return b.from.compareTo(a.from); }
        });
        return matrix;
    }

    private static List<?> list(Object value) { return value instanceof List ? (List<?>) value : new ArrayList<Object>(); }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : value.toString(); }

    /** Rules for this AGP. Exact series first, then the major default (known == false), otherwise no restriction. */
    public Requirement forAgp(GradleVersion agp) {
        if (agp == null) return new Requirement(null, null, 8, false);
        for (Row row : exact) if (row.major == agp.getMajor() && row.minor == agp.getMinor()) return new Requirement(row.min, row.maxBefore, row.jdk, true);
        for (Row row : majorDefaults) if (row.major == agp.getMajor()) return new Requirement(row.min, row.maxBefore, row.jdk, false);
        return new Requirement(null, null, unknownMinJdk, false);
    }

    /** Newest JDK major this Gradle can run on; Integer.MAX_VALUE when the file has no rule for it (never blocks). */
    public int maximumJdk(GradleVersion gradle) {
        if (gradle == null) return Integer.MAX_VALUE;
        for (GradleJdk row : gradleJdk) if (gradle.compareTo(row.from) >= 0) return row.maxJdk;
        return Integer.MAX_VALUE;
    }
}
