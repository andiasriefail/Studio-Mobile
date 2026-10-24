package com.example.studiomobile.build.dependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The libraries and bundles of a gradle/libs.versions.toml, ready to be looked up by the alias used in build.gradle
 * ({@code libs.androidx.core.ktx}, {@code libs.bundles.compose}).
 *
 * Understands: {@code module = "g:a"}, {@code group} + {@code name}, "g:a:v" and "g:a" strings, {@code version = "1.0"},
 * {@code version.ref}, rich versions ({@code strictly}, {@code require}, {@code prefer}) and {@code [bundles]}.
 * A library without a version is allowed: its version has to come from a platform/BOM.
 * Ranges ("[1.0,2.0)") and dynamic versions ("1.+", "latest.release") are passed on as written: the dependency resolver picks the real
 * version from the repository's version list and writes the choice to the build log. Versions that come from a variable ("$x") are reported.
 */
public final class VersionCatalog {
    /** One library entry. {@code version} is empty when the entry has none; {@code problem} is non-null when it cannot be used. */
    public static final class Library {
        public final String group, artifact, version, problem;
        Library(String group, String artifact, String version, String problem) { this.group = group; this.artifact = artifact; this.version = version; this.problem = problem; }
        public String coordinates() { return version.isEmpty() ? group + ":" + artifact : group + ":" + artifact + ":" + version; }
    }

    private final Map<String, Library> libraries = new LinkedHashMap<>();
    private final Map<String, List<String>> bundles = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private VersionCatalog() { }

    public static VersionCatalog empty() { return new VersionCatalog(); }

    /** Parses the catalog text. Never throws: a malformed file gives an empty catalog and a warning. */
    public static VersionCatalog parse(String toml) {
        VersionCatalog catalog = new VersionCatalog();
        Map<String, Object> root;
        try { root = TomlReader.parse(toml); }
        catch (IllegalArgumentException malformed) { catalog.warnings.add("gradle/libs.versions.toml could not be read: " + malformed.getMessage()); return catalog; }
        Map<String, Object> versions = table(root.get("versions"));
        for (Map.Entry<String, Object> entry : table(root.get("libraries")).entrySet()) {
            Library library = readLibrary(entry.getKey(), entry.getValue(), versions, catalog.warnings);
            if (library != null) catalog.libraries.put(normalize(entry.getKey()), library);
        }
        for (Map.Entry<String, Object> entry : table(root.get("bundles")).entrySet()) {
            if (!(entry.getValue() instanceof List)) { catalog.warnings.add("Bundle '" + entry.getKey() + "' must be a list of library aliases."); continue; }
            List<String> members = new ArrayList<>();
            for (Object member : (List<?>) entry.getValue()) if (member instanceof String) members.add(normalize((String) member));
            catalog.bundles.put(normalize(entry.getKey()), members);
        }
        return catalog;
    }

    /** Alias as written in build.gradle ({@code androidx.core.ktx}); null when unknown. */
    public Library library(String alias) { return libraries.get(normalize(alias)); }

    /** Aliases of the libraries in a bundle ({@code compose.ui} for {@code libs.bundles.compose.ui}); null when unknown. */
    public List<String> bundle(String alias) { List<String> members = bundles.get(normalize(alias)); return members == null ? null : Collections.unmodifiableList(members); }

    /** Problems found while reading the file itself (not usage problems). */
    public List<String> warnings() { return Collections.unmodifiableList(warnings); }

    /** Gradle treats '-', '_' and '.' in aliases as the same separator: androidx-core-ktx == androidx.core.ktx. */
    public static String normalize(String alias) { return alias.trim().toLowerCase(Locale.ROOT).replace('-', '.').replace('_', '.'); }

    // ---------------------------------------------------------------- libraries

    private static Library readLibrary(String alias, Object spec, Map<String, Object> versions, List<String> warnings) {
        String group = null, artifact = null;
        Object versionSpec = null;
        if (spec instanceof String) {
            String[] parts = ((String) spec).split(":", -1);
            if (parts.length == 2 || parts.length == 3) {
                group = parts[0]; artifact = parts[1];
                if (parts.length == 3) versionSpec = parts[2];
            } else if (parts.length > 3) {
                return new Library(parts[0], parts[1], "", "uses a classifier or type (" + spec + "), which is not supported");
            }
        } else if (spec instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) spec;
            Object module = map.get("module");
            if (module instanceof String && ((String) module).split(":", -1).length == 2) {
                String[] parts = ((String) module).split(":", -1);
                group = parts[0]; artifact = parts[1];
            } else if (map.get("group") instanceof String && map.get("name") instanceof String) {
                group = (String) map.get("group"); artifact = (String) map.get("name");
            }
            versionSpec = map.get("version");
        }
        if (group == null || artifact == null || group.isEmpty() || artifact.isEmpty()) {
            warnings.add("Library '" + alias + "' in the version catalog is not understood (expected module = \"group:name\" or group + name).");
            return null;
        }
        Resolved resolved = resolveVersion(versionSpec, versions, 0);
        return new Library(group, artifact, resolved.version, resolved.problem);
    }

    // ---------------------------------------------------------------- versions

    private static final class Resolved {
        final String version, problem;
        Resolved(String version, String problem) { this.version = version; this.problem = problem; }
    }

    private static Resolved ok(String version) { return new Resolved(version, null); }
    private static Resolved problem(String problem) { return new Resolved("", problem); }

    /** Turns a version value (text, {@code ref}, or a rich version table) into one concrete version. */
    private static Resolved resolveVersion(Object spec, Map<String, Object> versions, int depth) {
        if (spec == null) return ok("");
        if (spec instanceof String) return concrete((String) spec);
        if (!(spec instanceof Map)) return problem("has an unsupported version value");
        Map<?, ?> map = (Map<?, ?>) spec;
        Object ref = map.get("ref");
        if (ref instanceof String) {
            if (depth >= 5 || !versions.containsKey(ref)) return problem("refers to version '" + ref + "', which is not defined in [versions]");
            return resolveVersion(versions.get(ref), versions, depth + 1);
        }
        String strictly = text(map.get("strictly")), require = text(map.get("require")), prefer = text(map.get("prefer"));
        // A fixed strictly/require wins. A range goes on to the resolver, which takes the highest version inside it; a fixed 'prefer' next to a range is used as it is.
        for (String bound : new String[] {strictly, require}) {
            if (bound == null) continue;
            if (!isRange(bound)) return concrete(bound);
            if (prefer != null && !isRange(prefer)) return concrete(prefer);
            return concrete(bound);
        }
        if (prefer != null) return concrete(prefer);
        return problem("has a version table without ref, strictly, require or prefer");
    }

    private static Resolved concrete(String version) {
        String v = version.trim();
        if (v.isEmpty()) return ok("");
        if (v.contains("$")) return problem("uses the version '" + v + "', which is built from a variable and cannot be read");
        if (MavenResolver.isDynamicVersion(v) && !MavenResolver.isSupportedDynamicVersion(v))
            return problem("uses the version '" + v + "', which cannot be resolved here (supported: ranges like [1.0,2.0), prefixes like 1.+ and latest.release)");
        return ok(v);
    }

    private static boolean isRange(String v) { String t = v.trim(); return t.startsWith("[") || t.startsWith("(") || t.startsWith("]") || t.contains(","); }
    private static String text(Object value) { return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Object value) { return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap(); }
}
