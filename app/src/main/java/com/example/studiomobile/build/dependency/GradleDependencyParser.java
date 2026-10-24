package com.example.studiomobile.build.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the library declarations of one module from build.gradle / build.gradle.kts and gradle/libs.versions.toml.
 * This is NOT a Gradle interpreter: it understands the common literal forms and reports everything else in
 * {@link #warnings} instead of guessing.
 *
 * Supported: "g:a:v" strings (also with a classifier, "g:a:v:classifier", and the artifact-only form "g:a:v@aar"), version catalog aliases
 * ({@code libs.x.y}, with or without parentheses), catalog bundles ({@code libs.bundles.x}), rich catalog versions, dynamic versions and
 * ranges ({@code 1.+}, {@code [1.0,2.0)}, {@code latest.release}, resolved later by {@link MavenResolver}), and {@code platform(...)} /
 * {@code enforcedPlatform(...)} BOMs, which supply the version of declarations written without one.
 */
public final class GradleDependencyParser {
    public static final class Declaration {
        public final String group, artifact, version, configuration;
        /** The classifier ({@code g:a:1.0:sources}) and the artifact-only extension ({@code g:a:1.0@aar}); empty when the declaration has none. */
        public final String classifier, extension;
        Declaration(String group, String artifact, String version, String configuration) { this(group, artifact, version, configuration, "", ""); }
        Declaration(String group, String artifact, String version, String configuration, String classifier, String extension) {
            this.group = group; this.artifact = artifact; this.version = version; this.configuration = configuration;
            this.classifier = classifier == null ? "" : classifier; this.extension = extension == null ? "" : extension;
        }
        /** True when the declaration has no version of its own and relies on a platform/BOM. */
        public boolean isManaged() { return version.isEmpty(); }
        @Override public String toString() {
            return group + ":" + artifact + (isManaged() ? " (version from platform)" : ":" + version) + (classifier.isEmpty() ? "" : ":" + classifier) + (extension.isEmpty() ? "" : "@" + extension) + " (" + configuration + ")";
        }
    }

    /** A BOM declared with platform("g:a:v") or enforcedPlatform(...). Its dependencyManagement versions apply to the whole graph. */
    public static final class Platform {
        public final String group, artifact, version;
        public final boolean enforced;
        Platform(String group, String artifact, String version, boolean enforced) { this.group = group; this.artifact = artifact; this.version = version; this.enforced = enforced; }
        @Override public String toString() { return (enforced ? "enforcedPlatform " : "platform ") + group + ":" + artifact + ":" + version; }
    }

    public final List<Declaration> declarations = new ArrayList<>();
    public final List<Platform> platforms = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    /** The warnings of declarations that were left out of the build (each ends with "Skipped."): the first place to look when a class cannot be found. */
    public final List<String> skipped = new ArrayList<>();
    /** annotationProcessor / debugAnnotationProcessor entries: run by the on-device annotation processing step (Java sources). */
    public final List<Declaration> annotationProcessors = new ArrayList<>();
    /** kapt / ksp entries: Kotlin compiler plugins that cannot run on-device. The stage decides what to do with them. */
    public final List<Declaration> kaptProcessors = new ArrayList<>(), kspProcessors = new ArrayList<>();
    /** Project-level catalog first, module-level second; the module's entries win. */
    private final List<VersionCatalog> catalogs = new ArrayList<>();

    private static final Set<String> USED = new LinkedHashSet<>(java.util.Arrays.asList("implementation", "api", "debugImplementation", "runtimeOnly"));
    private static final Set<String> PROCESSOR = new LinkedHashSet<>(java.util.Arrays.asList("annotationProcessor", "debugAnnotationProcessor", "kapt", "kaptDebug", "ksp", "kspDebug"));
    private static final Set<String> IGNORED = new LinkedHashSet<>(java.util.Arrays.asList(
        "compileOnly", "testImplementation", "androidTestImplementation", "testRuntimeOnly", "kaptTest", "kaptAndroidTest", "kspTest", "kspAndroidTest", "testAnnotationProcessor", "androidTestAnnotationProcessor", "coreLibraryDesugaring", "lintChecks"));
    private static final Pattern STRING_DEP = Pattern.compile("\\b([A-Za-z]+)\\s*\\(?\\s*([\"'])([^\"'\\r\\n]+)\\2");
    // Accepts both implementation(libs.x.y) and the Groovy command form implementation libs.x.y (the Android Studio default).
    private static final Pattern ALIAS_DEP = Pattern.compile("\\b([A-Za-z]+)\\s*(?:\\(\\s*)?libs\\.([A-Za-z0-9_.]+)");
    private static final Pattern PLATFORM_DEP = Pattern.compile("\\b([A-Za-z]+)\\s*\\(?\\s*(platform|enforcedPlatform)\\s*\\(\\s*(?:([\"'])([^\"'\\s]+)\\3|libs\\.([A-Za-z0-9_.]+))\\s*\\)");
    private static final Pattern WRAPPED_DEP = Pattern.compile("\\b([A-Za-z]+)\\s*\\(\\s*(files|fileTree|gradleApi)\\s*\\(");

    public static GradleDependencyParser parse(File projectDirectory, File moduleDirectory) throws IOException {
        GradleDependencyParser parser = new GradleDependencyParser();
        File build = new File(moduleDirectory, "build.gradle.kts");
        if (!build.isFile()) build = new File(moduleDirectory, "build.gradle");
        if (!build.isFile()) return parser;
        for (File toml : new File[] {new File(projectDirectory, "gradle/libs.versions.toml"), new File(moduleDirectory, "gradle/libs.versions.toml")}) {
            if (!toml.isFile()) continue;
            VersionCatalog catalog = VersionCatalog.parse(new String(Files.readAllBytes(toml.toPath()), StandardCharsets.UTF_8));
            parser.catalogs.add(catalog);
            parser.warnings.addAll(catalog.warnings());
        }
        parser.readBuildFile(new String(Files.readAllBytes(build.toPath()), StandardCharsets.UTF_8));
        return parser;
    }

    void readBuildFile(String source) {
        String text = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        Set<String> seen = new LinkedHashSet<>();
        Matcher wrapped = WRAPPED_DEP.matcher(text);
        while (wrapped.find()) if (USED.contains(wrapped.group(1)))
            skip("Not supported yet: " + wrapped.group(1) + "(" + wrapped.group(2) + "(...)). Skipped.");
        readPlatforms(text);
        Matcher alias = ALIAS_DEP.matcher(text);
        while (alias.find()) {
            String configuration = alias.group(1); if (!relevant(configuration)) continue;
            String name = alias.group(2);
            if (name.startsWith("versions.") || name.startsWith("plugins.")) continue;
            if (name.startsWith("bundles.")) { addBundle(configuration, name.substring("bundles.".length()), seen); continue; }
            VersionCatalog.Library library = findLibrary(name);
            if (library == null) { skip("libs." + name + " is not defined in gradle/libs.versions.toml. Skipped."); continue; }
            addLibrary(configuration, "libs." + name, library, seen);
        }
        Matcher direct = STRING_DEP.matcher(text);
        while (direct.find()) {
            String configuration = direct.group(1); if (!relevant(configuration)) continue;
            String value = direct.group(3).trim(); if (!value.contains(":")) continue;
            if (value.indexOf('[') >= 0 || value.indexOf('(') >= 0 || value.indexOf(']') >= 0) value = value.replaceAll("\\s+", "");   // a range may be written "[1.0, 2.0)"
            else if (value.matches(".*\\s.*")) continue;   // ordinary text, not a coordinate
            add(configuration, value, seen);
        }
    }

    /** platform("g:a:v"), platform(libs.bom), enforcedPlatform(...) in a configuration that is built. */
    private void readPlatforms(String text) {
        Matcher platform = PLATFORM_DEP.matcher(text);
        while (platform.find()) {
            if (!relevant(platform.group(1))) continue;
            boolean enforced = platform.group(2).equals("enforcedPlatform");
            String label, coordinates;
            if (platform.group(4) != null) { label = platform.group(4); coordinates = label; }
            else {
                label = "libs." + platform.group(5);
                VersionCatalog.Library library = findLibrary(platform.group(5));
                if (library == null) { skip(label + " is not defined in gradle/libs.versions.toml. Skipped."); continue; }
                if (library.problem != null) { skip(label + " " + library.problem + ". Skipped."); continue; }
                coordinates = library.coordinates();
            }
            String[] p = coordinates.split(":", -1);
            if (p.length != 3 || p[2].isEmpty() || isDynamic(p[2])) { skip("platform " + label + " needs a fixed group:artifact:version (no range, +, or latest.release), because its versions have to be known. Skipped."); continue; }
            boolean duplicate = false;
            for (Platform existing : platforms) if (existing.group.equals(p[0]) && existing.artifact.equals(p[1])) duplicate = true;
            if (!duplicate) platforms.add(new Platform(p[0], p[1], p[2], enforced));
        }
    }

    private void addBundle(String configuration, String bundleAlias, Set<String> seen) {
        List<String> members = null;
        for (int i = catalogs.size() - 1; i >= 0 && members == null; i--) members = catalogs.get(i).bundle(bundleAlias);
        if (members == null) { skip("libs.bundles." + bundleAlias + " is not defined in gradle/libs.versions.toml. Skipped."); return; }
        for (String member : members) {
            VersionCatalog.Library library = findLibrary(member);
            if (library == null) { skip("Bundle libs.bundles." + bundleAlias + " lists '" + member + "', which is not defined in the catalog. Skipped."); continue; }
            addLibrary(configuration, "libs." + member + " (bundle " + bundleAlias + ")", library, seen);
        }
    }

    private VersionCatalog.Library findLibrary(String alias) {
        for (int i = catalogs.size() - 1; i >= 0; i--) { VersionCatalog.Library library = catalogs.get(i).library(alias); if (library != null) return library; }
        return null;
    }

    private void addLibrary(String configuration, String label, VersionCatalog.Library library, Set<String> seen) {
        if (library.problem != null) { skip(label + " " + library.problem + ". Skipped."); return; }
        add(configuration, library.coordinates(), seen);
    }

    /** A declaration that is left out of the build: shown as a warning and remembered in {@link #skipped}. */
    private void skip(String warning) { warnings.add(warning); skipped.add(warning); }

    private boolean relevant(String configuration) {
        if (USED.contains(configuration) || PROCESSOR.contains(configuration)) return true;
        if (IGNORED.contains(configuration)) { String note = "Ignored configuration '" + configuration + "' (not needed for assembleDebug, or not supported yet: annotation processors need kapt/ksp)."; if (!warnings.contains(note)) warnings.add(note); }
        return false;
    }

    private void add(String configuration, String coords, Set<String> seen) {
        // group:artifact[:version[:classifier]][@extension]
        String base = coords, extension = "";
        int at = base.lastIndexOf('@');
        if (at >= 0) { extension = base.substring(at + 1); base = base.substring(0, at); }
        String[] p = base.split(":", -1);
        if (p.length < 2 || p[0].isEmpty() || p[1].isEmpty()) { skip(coords + " is not a valid group:artifact[:version] notation. Skipped."); return; }
        if (p.length > 4) { skip(coords + " has too many parts (expected group:artifact:version[:classifier][@extension]). Skipped."); return; }
        String version = p.length >= 3 ? p[2] : "";
        // g:a:1.7.15!!  and  g:a:[1.7,1.8[!!1.7.25  : a strict version, optionally with a preferred one. The preferred version is used when there is one.
        int strict = version.indexOf("!!");
        if (strict >= 0) { String preferred = version.substring(strict + 2); version = preferred.isEmpty() ? version.substring(0, strict) : preferred; }
        String classifier = p.length == 4 ? p[3] : "";
        if (p.length == 4 && classifier.isEmpty()) { skip(coords + " has an empty classifier. Skipped."); return; }
        if (at >= 0 && !extension.matches("[A-Za-z0-9]+")) { skip(coords + " has an invalid @extension. Skipped."); return; }
        if (!extension.isEmpty() && !extension.equals("jar") && !extension.equals("aar")) { skip(coords + " asks for a ." + extension + " file; only jar and aar files can be added to the build. Skipped."); return; }
        if (version.contains("$")) { skip(coords + " uses a version taken from a variable, which is not read from build.gradle. Write the version out, or put it in gradle/libs.versions.toml. Skipped."); return; }
        if (MavenResolver.isDynamicVersion(version) && !MavenResolver.isSupportedDynamicVersion(version)) {
            skip(coords + " uses the version '" + version + "', which cannot be resolved here (supported: ranges like [1.0,2.0), prefixes like 1.+ and latest.release). Use a fixed version. Skipped."); return;
        }
        if (version.isEmpty() && platforms.isEmpty()) { skip(coords + " has no version and no platform(...) BOM is declared (a version { strictly(...) } block is not read: write the version in the string). Skipped."); return; }
        List<Declaration> target = declarations;
        String scope = "";
        if (PROCESSOR.contains(configuration)) {
            scope = configuration.startsWith("kapt") ? "kapt|" : configuration.startsWith("ksp") ? "ksp|" : "apt|";
            target = configuration.startsWith("kapt") ? kaptProcessors : configuration.startsWith("ksp") ? kspProcessors : annotationProcessors;
            if (version.isEmpty()) { skip(coords + " (" + configuration + ") has no version; annotation processors need an explicit version. Skipped."); return; }
        }
        if (seen.add(scope + p[0] + ":" + p[1] + ":" + classifier + "@" + extension)) target.add(new Declaration(p[0], p[1], version, configuration, classifier, extension));
    }

    private static boolean isDynamic(String version) { return version.contains("$") || MavenResolver.isDynamicVersion(version); }
}
