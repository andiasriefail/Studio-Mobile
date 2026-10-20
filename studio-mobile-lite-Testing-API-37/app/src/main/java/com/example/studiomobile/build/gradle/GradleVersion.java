package com.example.studiomobile.build.gradle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal comparable version for Gradle, AGP and JDK numbers such as 8.9, 8.10.2 or 17.0.12. */
public final class GradleVersion implements Comparable<GradleVersion> {
    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    private final int major, minor, patch;
    private final String original;

    private GradleVersion(int major, int minor, int patch, String original) {
        this.major = major; this.minor = minor; this.patch = patch; this.original = original;
    }

    /** Returns null when the text does not start with a numeric version. Qualifiers (-rc-1, -alpha) are ignored. */
    public static GradleVersion parse(String text) {
        if (text == null) return null;
        Matcher matcher = NUMERIC_PREFIX.matcher(text.trim());
        if (!matcher.find()) return null;
        return new GradleVersion(Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)), text.trim());
    }

    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public boolean isAtLeast(GradleVersion other) { return compareTo(other) >= 0; }

    @Override public int compareTo(GradleVersion other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }
    @Override public boolean equals(Object other) { return other instanceof GradleVersion && compareTo((GradleVersion) other) == 0; }
    @Override public int hashCode() { return major * 10007 + minor * 101 + patch; }
    @Override public String toString() { return major + "." + minor + "." + patch; }
    public String getOriginal() { return original; }
}
