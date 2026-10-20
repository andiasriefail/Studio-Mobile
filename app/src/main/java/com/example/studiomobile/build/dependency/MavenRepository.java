package com.example.studiomobile.build.dependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One Maven repository (an HTTPS address) and the optional {@code content { includeGroup(...) }} filter that says which libraries it may
 * be asked for. Without a filter the repository is asked for everything, in declaration order, after the repositories before it.
 */
public final class MavenRepository {
    public static final String GOOGLE_URL = "https://dl.google.com/dl/android/maven2/";
    public static final String CENTRAL_URL = "https://repo1.maven.org/maven2/";
    public static final String JITPACK_URL = "https://jitpack.io/";
    public static final String GRADLE_PLUGIN_PORTAL_URL = "https://plugins.gradle.org/m2/";
    /** Read-only since the service was shut down for new publishing; may be slow or stop answering. */
    public static final String JCENTER_URL = "https://jcenter.bintray.com/";

    private static final class Rule {
        static final int EXACT = 0, SUBGROUPS = 1, REGEX = 2;
        final int kind; final String text; final Pattern pattern;
        Rule(int kind, String text, Pattern pattern) { this.kind = kind; this.text = text; this.pattern = pattern; }
        boolean matches(String group) {
            if (kind == EXACT) return group.equals(text);
            if (kind == SUBGROUPS) return group.equals(text) || group.startsWith(text + ".");
            return pattern.matcher(group).matches();
        }
        @Override public String toString() { return kind + ":" + text; }
    }

    public final String name, url;
    /** True for Google Maven and Maven Central: files from them are checked with the server's .sha1, and no "not pinned" warning is given. */
    public final boolean trusted;
    private final List<Rule> includes = new ArrayList<>(), excludes = new ArrayList<>();

    MavenRepository(String name, String url) {
        this.name = name; this.url = normalizeUrl(url);
        this.trusted = this.url.equals(GOOGLE_URL) || this.url.equals(CENTRAL_URL);
    }

    public static MavenRepository google() { return new MavenRepository("Google", GOOGLE_URL); }
    public static MavenRepository central() { return new MavenRepository("Maven Central", CENTRAL_URL); }
    public static MavenRepository jitpack() { return new MavenRepository("JitPack", JITPACK_URL); }
    public static MavenRepository gradlePluginPortal() { return new MavenRepository("Gradle Plugin Portal", GRADLE_PLUGIN_PORTAL_URL); }
    public static MavenRepository jcenter() { return new MavenRepository("JCenter", JCENTER_URL); }
    public static MavenRepository of(String url) { return new MavenRepository(url, url); }

    static String normalizeUrl(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    // ---- content filter (kept to what Gradle's content {} block offers for groups)

    void includeGroup(String group) { includes.add(new Rule(Rule.EXACT, group, null)); }
    void includeGroupAndSubgroups(String group) { includes.add(new Rule(Rule.SUBGROUPS, group, null)); }
    void excludeGroup(String group) { excludes.add(new Rule(Rule.EXACT, group, null)); }
    void excludeGroupAndSubgroups(String group) { excludes.add(new Rule(Rule.SUBGROUPS, group, null)); }
    /** @return false when the regular expression is not valid (nothing was added) */
    boolean includeGroupByRegex(String regex) { return addRegex(includes, regex); }
    boolean excludeGroupByRegex(String regex) { return addRegex(excludes, regex); }
    private static boolean addRegex(List<Rule> into, String regex) {
        try { into.add(new Rule(Rule.REGEX, regex, Pattern.compile(regex))); return true; }
        catch (PatternSyntaxException bad) { return false; }
    }

    /** Whether this repository may be asked for a library of this group. */
    public boolean allows(String group) {
        for (Rule rule : excludes) if (rule.matches(group)) return false;
        if (includes.isEmpty()) return true;
        for (Rule rule : includes) if (rule.matches(group)) return true;
        return false;
    }

    public boolean hasFilter() { return !includes.isEmpty() || !excludes.isEmpty(); }

    /** Address plus filter: what the "not found anywhere" cache marker is tied to, so changing the repositories retries the lookup. */
    public String describe() { return url + (hasFilter() ? " include" + includes + " exclude" + excludes : ""); }

    @Override public String toString() {
        String label = name.equals(url) ? url : name + " (" + url + ")";
        return hasFilter() ? label + " [content filter]" : label;
    }

    @Override public boolean equals(Object other) { return other instanceof MavenRepository && ((MavenRepository) other).url.toLowerCase(Locale.ROOT).equals(url.toLowerCase(Locale.ROOT)); }
    @Override public int hashCode() { return url.toLowerCase(Locale.ROOT).hashCode(); }
}
