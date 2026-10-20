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
 * Supported: "g:a:v" strings, version catalog aliases ({@code libs.x.y}, with or without parentheses), catalog bundles
 * ({@code libs.bundles.x}), rich catalog versions, and {@code platform(...)} / {@code enforcedPlatform(...)} BOMs, which supply
 * the version of declarations written without one.
 */
public final class GradleDependencyParser {
    public static final class Declaration {
        public final String group, artifact, version, configuration;
        Declaration(String group, String artifact, String version, String configuration) { this.group = group; this.artifact = artifact; this.version = version; this.configuration = configuration; }
        /** True when the declaration has no version of its own and relies on a platform/BOM. */
        public boolean isManaged() { return version.isEmpty(); }
        @Override public String toString() { return group + ":" + artifact + (isManaged() ? " (version from platform)" : ":" + version) + " (" + configuration + ")"; }
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
    private static final Pattern STRING_DEP = Pattern.compile("\\b([A-Za-z]+)\\s*\\(?\\s*([\"'])([^\"'\\s]+)\\2");
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
            warnings.add("Not supported yet: " + wrapped.group(1) + "(" + wrapped.group(2) + "(...)). Skipped.");
        readPlatforms(text);
        Matcher alias = ALIAS_DEP.matcher(text);
        while (alias.find()) {
            String configuration = alias.group(1); if (!relevant(configuration)) continue;
            String name = alias.group(2);
            if (name.startsWith("versions.") || name.startsWith("plugins.")) continue;
            if (name.startsWith("bundles.")) { addBundle(configuration, name.substring("bundles.".length()), seen); continue; }
            VersionCatalog.Library library = findLibrary(name);
            if (library == null) { warnings.add("libs." + name + " is not defined in gradle/libs.versions.toml. Skipped."); continue; }
            addLibrary(configuration, "libs." + name, library, seen);
        }
        Matcher direct = STRING_DEP.matcher(text);
        while (direct.find()) {
            String configuration = direct.group(1); if (!relevant(configuration)) continue;
            String value = direct.group(3); if (!value.contains(":")) continue;
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
                if (library == null) { warnings.add(label + " is not defined in gradle/libs.versions.toml. Skipped."); continue; }
                if (library.problem != null) { warnings.add(label + " " + library.problem + ". Skipped."); continue; }
                coordinates = library.coordinates();
            }
            String[] p = coordinates.split(":", -1);
            if (p.length != 3 || p[2].isEmpty() || isDynamic(p[2])) { warnings.add("platform " + label + " needs a fixed group:artifact:version. Skipped."); continue; }
            boolean duplicate = false;
            for (Platform existing : platforms) if (existing.group.equals(p[0]) && existing.artifact.equals(p[1])) duplicate = true;
            if (!duplicate) platforms.add(new Platform(p[0], p[1], p[2], enforced));
        }
    }

    private void addBundle(String configuration, String bundleAlias, Set<String> seen) {
        List<String> members = null;
        for (int i = catalogs.size() - 1; i >= 0 && members == null; i--) members = catalogs.get(i).bundle(bundleAlias);
        if (members == null) { warnings.add("libs.bundles." + bundleAlias + " is not defined in gradle/libs.versions.toml. Skipped."); return; }
        for (String member : members) {
            VersionCatalog.Library library = findLibrary(member);
            if (library == null) { warnings.add("Bundle libs.bundles." + bundleAlias + " lists '" + member + "', which is not defined in the catalog. Skipped."); continue; }
            addLibrary(configuration, "libs." + member + " (bundle " + bundleAlias + ")", library, seen);
        }
    }

    private VersionCatalog.Library findLibrary(String alias) {
        for (int i = catalogs.size() - 1; i >= 0; i--) { VersionCatalog.Library library = catalogs.get(i).library(alias); if (library != null) return library; }
        return null;
    }

    private void addLibrary(String configuration, String label, VersionCatalog.Library library, Set<String> seen) {
        if (library.problem != null) { warnings.add(label + " " + library.problem + ". Skipped."); return; }
        add(configuration, library.coordinates(), seen);
    }

    private boolean relevant(String configuration) {
        if (USED.contains(configuration) || PROCESSOR.contains(configuration)) return true;
        if (IGNORED.contains(configuration)) { String note = "Ignored configuration '" + configuration + "' (not needed for assembleDebug, or not supported yet: annotation processors need kapt/ksp)."; if (!warnings.contains(note)) warnings.add(note); }
        return false;
    }

    private void add(String configuration, String coords, Set<String> seen) {
        String[] p = coords.split(":", -1);
        if (p.length < 2 || p[0].isEmpty() || p[1].isEmpty()) { warnings.add(coords + " is not a valid group:artifact[:version] notation. Skipped."); return; }
        if (p.length > 3) { warnings.add(coords + " uses a classifier, which is not supported. Skipped."); return; }
        String version = p.length == 3 ? p[2] : "";
        if (!version.isEmpty() && isDynamic(version)) { warnings.add(coords + " uses a dynamic or interpolated version, which is not supported. Skipped."); return; }
        if (version.isEmpty() && platforms.isEmpty()) { warnings.add(coords + " has no version and no platform(...) BOM is declared. Skipped."); return; }
        List<Declaration> target = declarations;
        String scope = "";
        if (PROCESSOR.contains(configuration)) {
            scope = configuration.startsWith("kapt") ? "kapt|" : configuration.startsWith("ksp") ? "ksp|" : "apt|";
            target = configuration.startsWith("kapt") ? kaptProcessors : configuration.startsWith("ksp") ? kspProcessors : annotationProcessors;
            if (version.isEmpty()) { warnings.add(coords + " (" + configuration + ") has no version; annotation processors need an explicit version. Skipped."); return; }
        }
        if (seen.add(scope + p[0] + ":" + p[1])) target.add(new Declaration(p[0], p[1], version, configuration));
    }

    private static boolean isDynamic(String version) { return version.contains("$") || version.endsWith("+") || version.startsWith("latest"); }
}
