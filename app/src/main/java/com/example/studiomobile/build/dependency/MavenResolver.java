package com.example.studiomobile.build.dependency;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Resolves Maven coordinates (with transitive dependencies) from Google Maven and Maven Central, downloads the jar/aar,
 * verifies it against the .sha1 published next to it, and leaves compile-ready jars in a persistent cache.
 *
 * Each .aar is extracted once into the cache (SecureZipExtractor, folder {@code <artifact>-<version>-aar}). Its classes.jar and libs/*.jar
 * are returned in {@link Result#jars}; the extracted folder is returned in {@link Result#aarDirectories} so later stages can use its
 * res/, assets/, jni/ and AndroidManifest.xml. Conflicts are resolved "newest version wins". Kotlin stdlib artifacts are
 * skipped because the toolchain already supplies kotlin-stdlib.jar (two copies would be a duplicate-class error in D8).
 *
 * Memory: jars and AARs are never held in memory as a whole. They are streamed to disk in chunks sized from the phone's RAM
 * ({@link DownloadMemoryPolicy}: 4 GB -> 150 MB limit, 6 GB -> 250 MB, 8 GB -> 350 MB), hashed on the way, and an interrupted
 * download resumes from the bytes already on disk. Host names are resolved by Google DNS, then Cloudflare DNS ({@link ResilientDns});
 * if both fail the build stops. Only small files (POM, .module, .sha1) are read into memory.
 */
public final class MavenResolver {
    /** The repository answered 401 or 403: it needs a login or does not allow this file. The resolver moves on to the next repository. */
    public static final class AccessDenied extends IOException { public AccessDenied(String message) { super(message); } }

    public interface Fetcher {
        /** @return bytes, or null when the file does not exist (HTTP 404). */
        byte[] get(String url, long maxBytes) throws IOException;

        /**
         * Writes the file to {@code target} (an existing partial file may be resumed or replaced) without holding it all in memory when the
         * fetcher can stream. This default is for fetchers that only know {@link #get}: it holds the file in memory, so it is capped by the policy's memory limit.
         *
         * @return the lowercase hex SHA-1 of the bytes now in {@code target}, or null when the file does not exist (HTTP 404)
         */
        default String download(String url, File target, long maxBytes, DownloadMemoryPolicy policy) throws IOException {
            byte[] data = get(url, policy.wholeFileLimit(maxBytes));
            if (data == null) return null;
            Files.write(target.toPath(), data);
            return sha1(data);
        }
    }

    public static final String GOOGLE = "https://dl.google.com/dl/android/maven2/";
    public static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final long MAX_ARTIFACT = 120L * 1024 * 1024, MAX_SMALL = 8L * 1024 * 1024, MAX_AAR_EXTRACTED = 512L * 1024 * 1024;
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList(
        "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib-jdk7", "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains.kotlin:kotlin-stdlib-common", "org.jetbrains.kotlin:kotlin-stdlib-js"));

    public static final class Result {
        public final List<File> jars = new ArrayList<>();
        /** Extracted AAR folders in resolution order (a library comes before the libraries it depends on). */
        public final List<File> aarDirectories = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    private static final class Pom {
        String group, artifact, version, packaging = "jar";
        final Map<String, String> properties = new LinkedHashMap<>();
        final Map<String, String> managed = new LinkedHashMap<>();
        final List<String[]> deps = new ArrayList<>(); // {group, artifact, version-or-null}
    }
    private static final class DepNode { String ga; String version; List<String[]> deps = new ArrayList<>(); boolean hasArtifact; String packaging = "jar"; }

    private final File cache; private final Fetcher fetcher; private final List<MavenRepository> repos; private final DownloadMemoryPolicy policy;
    /** Tied to the repository list: a "not found anywhere" marker only counts while the same repositories are searched. */
    private final String repoFingerprint;
    /** Markers written before repositories could be declared are empty. They stay valid while only Google and Maven Central are searched, as they were then. */
    private final boolean emptyMarkersApply;
    private ChecksumPins pins = ChecksumPins.empty();
    private final Map<String, DepNode> nodes = new LinkedHashMap<>();
    private final Result result = new Result();
    /** group:artifact -> version supplied by platform(...) BOMs. */
    private final Map<String, String> platformManaged = new LinkedHashMap<>();
    /** Modules whose version is fixed by enforcedPlatform(...). */
    private final Set<String> enforcedModules = new HashSet<>();
    /** group:artifact -> {classifier, extension} of the declaration that asked for it (empty strings when not given). */
    private final Map<String, String[]> variants = new LinkedHashMap<>();
    /** Modules declared with @ext: only that file is fetched; the module's POM and its dependencies are not read (the same as Gradle). */
    private final Set<String> artifactOnly = new HashSet<>();
    /** group:artifact -> every version the repositories list for it, kept for this resolution. */
    private final Map<String, List<String>> versionLists = new HashMap<>();
    /** Like Gradle, a saved list of versions is trusted for 24 hours before the repository is asked again. */
    private static final long VERSION_LIST_TTL_MS = 24L * 60 * 60 * 1000;

    public MavenResolver(File cacheDirectory, Fetcher fetcher, List<String> repositories) {
        this(cacheDirectory, fetcher, repositories, DownloadMemoryPolicy.detect());
    }

    public MavenResolver(File cacheDirectory, Fetcher fetcher, List<String> repositories, DownloadMemoryPolicy policy) {
        this(cacheDirectory, fetcher, policy, toRepositories(repositories));
    }

    private MavenResolver(File cacheDirectory, Fetcher fetcher, DownloadMemoryPolicy policy, List<MavenRepository> repositories) {
        this.cache = cacheDirectory; this.fetcher = fetcher; this.repos = repositories; this.policy = policy;
        this.repoFingerprint = fingerprintOf(repositories);
        this.emptyMarkersApply = repositories.size() == 2 && repositories.contains(MavenRepository.google()) && repositories.contains(MavenRepository.central())
            && !repositories.get(0).hasFilter() && !repositories.get(1).hasFilter();
    }

    /** Repositories with their own content filters (JitPack, a developer's own server, ...), searched in the order given. */
    public static MavenResolver forRepositories(File cacheDirectory, Fetcher fetcher, List<MavenRepository> repositories) {
        return new MavenResolver(cacheDirectory, fetcher, DownloadMemoryPolicy.detect(), new ArrayList<>(repositories));
    }

    /** SHA-256 values the user pinned (gradle/checksums.properties). A pinned file that does not match is never used. */
    public MavenResolver withPins(ChecksumPins pinned) { this.pins = pinned == null ? ChecksumPins.empty() : pinned; return this; }

    private static List<MavenRepository> toRepositories(List<String> urls) {
        List<MavenRepository> out = new ArrayList<>();
        for (String url : urls) out.add(MavenRepository.of(url));
        return out;
    }

    private static String fingerprintOf(List<MavenRepository> repositories) {
        StringBuilder all = new StringBuilder();
        for (MavenRepository repository : repositories) all.append(repository.describe()).append('\n');
        try { return hex(MessageDigest.getInstance("SHA-1").digest(all.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-1 unavailable", e); }
    }

    public Result resolve(List<GradleDependencyParser.Declaration> roots) throws IOException {
        return resolve(roots, Collections.<GradleDependencyParser.Platform>emptyList());
    }

    /**
     * Like {@link #resolve(List)}, with BOMs: a declaration without a version takes it from the platforms, and every module that
     * a platform manages is held to at least the platform's version (exactly that version for enforcedPlatform).
     */
    public Result resolve(List<GradleDependencyParser.Declaration> roots, List<GradleDependencyParser.Platform> platforms) throws IOException {
        loadPlatforms(platforms);
        Map<String, String> best = new LinkedHashMap<>();
        for (GradleDependencyParser.Declaration d : roots) {
            String ga = d.group + ":" + d.artifact;
            String declared = d.version == null || d.version.trim().isEmpty() ? null : d.version.trim();
            if (declared != null && isDynamicVersion(declared)) declared = resolveDynamic(d.group, d.artifact, declared);   // 1.+, [1.0,2.0), latest.release -> one real version
            rememberVariant(ga, d);
            String version = constrain(ga, declared == null ? null : cleanVersion(declared));
            if (version == null) { result.warnings.add(ga + " has no version and no platform manages it; skipped."); continue; }
            best.merge(ga, version, (a, b) -> compare(a, b) >= 0 ? a : b);
        }
        List<String> order = null;
        for (int pass = 0; pass < 12; pass++) {
            boolean[] changed = {false}; List<String> visitedOrder = new ArrayList<>(); Set<String> visited = new HashSet<>();
            for (String ga : new ArrayList<>(best.keySet())) walk(ga, best, visited, visitedOrder, changed, 0);
            order = visitedOrder;
            if (!changed[0]) break;
            if (pass == 11) throw new IOException("Dependency versions did not settle after 12 passes.");
        }
        for (String ga : order) {
            DepNode node = nodes.get(ga + ":" + best.get(ga));
            if (node == null || !node.hasArtifact) continue;
            if (materialize(node)) result.notes.add(ga + ":" + node.version + (pins.get(ga + ":" + node.version) != null ? " [SHA-256 pinned]" : ""));
        }
        return result;
    }

    /** Remembers the classifier / @extension a declaration asked for. The first declaration of a module decides. */
    private void rememberVariant(String ga, GradleDependencyParser.Declaration d) {
        String classifier = d.classifier == null ? "" : d.classifier, extension = d.extension == null ? "" : d.extension;
        if (classifier.isEmpty() && extension.isEmpty()) return;
        String[] known = variants.get(ga);
        if (known == null) { variants.put(ga, new String[] {classifier, extension}); if (!extension.isEmpty()) artifactOnly.add(ga); }
        else if (!known[0].equals(classifier) || !known[1].equals(extension))
            result.warnings.add(ga + " is declared more than once with a different classifier or @extension; only the first one is used.");
    }

    /** Reads each BOM's dependencyManagement (parents and imported BOMs included) into {@link #platformManaged}. */
    private void loadPlatforms(List<GradleDependencyParser.Platform> platforms) throws IOException {
        for (GradleDependencyParser.Platform platform : platforms) {
            String coordinates = platform.group + ":" + platform.artifact + ":" + platform.version;
            Pom bom = readPom(platform.group, platform.artifact, platform.version, 0);
            if (bom == null) throw new IOException("BOM " + coordinates + " was not found in Google Maven or Maven Central.");
            int count = 0;
            for (Map.Entry<String, String> entry : bom.managed.entrySet()) {
                String version = entry.getValue();
                if (version == null || version.isEmpty() || version.contains("${")) continue;
                version = cleanVersion(version);
                String ga = entry.getKey();
                if (platform.enforced) { platformManaged.put(ga, version); enforcedModules.add(ga); }
                else if (!enforcedModules.contains(ga)) platformManaged.merge(ga, version, (a, b) -> compare(a, b) >= 0 ? a : b);
                count++;
            }
            result.notes.add((platform.enforced ? "enforcedPlatform " : "platform ") + coordinates + " manages " + count + " version(s)");
        }
    }

    /** The version to use for ga: the platform's for enforced modules, otherwise the higher of the requested and the managed one. */
    private String constrain(String ga, String requested) {
        String managed = platformManaged.get(ga);
        if (managed == null) return requested;
        if (requested == null || enforcedModules.contains(ga)) return managed;
        return compare(requested, managed) >= 0 ? requested : managed;
    }

    private void walk(String ga, Map<String, String> best, Set<String> visited, List<String> order, boolean[] changed, int depth) throws IOException {
        if (SKIPPED.contains(ga) || depth > 40 || !visited.add(ga)) return;
        DepNode node = node(ga, best.get(ga));
        order.add(ga);
        for (String[] dep : node.deps) {
            String child = dep[0] + ":" + dep[1];
            if (SKIPPED.contains(child)) continue;
            String wanted = constrain(child, dep[2] == null ? null : cleanVersion(dep[2]));
            String current = best.get(child);
            if (wanted == null && current == null) { result.warnings.add(child + " (needed by " + ga + ") has no version in its POM; skipped."); continue; }
            if (current == null || (wanted != null && compare(wanted, current) > 0)) { best.put(child, wanted); changed[0] = true; }
            walk(child, best, visited, order, changed, depth + 1);
        }
    }

    /** Loads (and caches) the dependency list of ga:version, following Gradle Module Metadata redirects used by Kotlin/KMP libraries. */
    private DepNode node(String ga, String version) throws IOException {
        String key = ga + ":" + version; DepNode existing = nodes.get(key); if (existing != null) return existing;
        String[] p = ga.split(":"); DepNode node = new DepNode(); node.ga = ga; node.version = version; nodes.put(key, node);
        if (artifactOnly.contains(ga)) {   // g:a:v@ext: just that file, no POM and no transitive dependencies (as Gradle does for artifact-only notation)
            node.hasArtifact = true; node.packaging = variants.get(ga)[1];
            result.notes.add(ga + ":" + version + "@" + node.packaging + " is an artifact-only dependency: its own dependencies are not added");
            return node;
        }
        String redirect = moduleRedirect(p[0], p[1], version);
        if (redirect != null) { node.deps.add(redirect.split(":")); node.hasArtifact = false; result.notes.add(ga + ":" + version + " -> " + redirect); return node; }
        Pom pom = readPom(p[0], p[1], version, 0);
        if (pom == null) throw new IOException("POM not found for " + key + " in Google Maven or Maven Central.");
        node.packaging = pom.packaging; node.deps = pom.deps;
        node.hasArtifact = !("pom".equals(pom.packaging));
        return node;
    }

    // ---------- Gradle Module Metadata (only the "available-at" redirect to the JVM/Android artifact) ----------
    @SuppressWarnings("unchecked")
    private String moduleRedirect(String g, String a, String v) throws IOException {
        byte[] data = fetchVerified(path(g, a, v, a + "-" + v + ".module"), true);
        if (data == null) return null;
        try {
            Object root = MiniJson.parse(new String(data, StandardCharsets.UTF_8));
            Object variants = ((Map<String, Object>) root).get("variants");
            if (!(variants instanceof List)) return null;
            int bestScore = -1; String target = null;
            for (Object o : (List<Object>) variants) {
                Map<String, Object> variant = (Map<String, Object>) o;
                Object at = variant.get("available-at"); if (!(at instanceof Map)) continue;
                Map<String, Object> attrs = variant.get("attributes") instanceof Map ? (Map<String, Object>) variant.get("attributes") : Collections.emptyMap();
                String platform = String.valueOf(attrs.get("org.jetbrains.kotlin.platform.type")), usage = String.valueOf(attrs.get("org.gradle.usage")), env = String.valueOf(attrs.get("org.gradle.jvm.environment"));
                int score = 0;
                if ("androidJvm".equals(platform) || "android".equals(env)) score = 3; else if ("jvm".equals(platform) || "standard-jvm".equals(env)) score = 2; else continue;
                if (usage.contains("runtime")) score += 1;
                if (score > bestScore) {
                    Map<String, Object> m = (Map<String, Object>) at;
                    bestScore = score; target = m.get("group") + ":" + m.get("module") + ":" + m.get("version");
                }
            }
            return target;
        } catch (RuntimeException badJson) { result.warnings.add("Could not read module metadata of " + g + ":" + a + ":" + v + "; using its POM."); return null; }
    }

    // ---------- POM ----------
    private Pom readPom(String g, String a, String v, int depth) throws IOException {
        byte[] data = fetchVerified(path(g, a, v, a + "-" + v + ".pom"), false);
        if (data == null) return null;
        Pom pom = new Pom();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } // no XXE
            catch (javax.xml.parsers.ParserConfigurationException unsupported) { /* Android's parser does not resolve external entities anyway */ }
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
            Element project = doc.getDocumentElement();
            pom.group = text(project, "groupId"); pom.artifact = text(project, "artifactId"); pom.version = text(project, "version");
            String packaging = text(project, "packaging"); if (packaging != null) pom.packaging = packaging;
            Element parent = child(project, "parent");
            if (parent != null && depth < 6) {
                String pg = text(parent, "groupId"), pa = text(parent, "artifactId"), pv = text(parent, "version");
                if (pom.group == null) pom.group = pg; if (pom.version == null) pom.version = pv;
                Pom parentPom = readPom(pg, pa, pv, depth + 1);
                if (parentPom != null) { pom.properties.putAll(parentPom.properties); pom.managed.putAll(parentPom.managed); }
            }
            pom.properties.put("project.version", pom.version == null ? v : pom.version); pom.properties.put("project.groupId", pom.group == null ? g : pom.group);
            pom.properties.put("version", pom.properties.get("project.version")); pom.properties.put("pom.version", pom.properties.get("project.version"));
            Element props = child(project, "properties");
            if (props != null) for (Node n = props.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) pom.properties.put(n.getNodeName(), n.getTextContent().trim());
            Element mgmt = child(project, "dependencyManagement");
            Element mgmtDeps = mgmt == null ? null : child(mgmt, "dependencies");
            List<String[]> imports = new ArrayList<>();
            if (mgmtDeps != null) for (Element d : children(mgmtDeps, "dependency")) {
                String mv = text(d, "version"), scope = text(d, "scope");
                if ("import".equals(scope)) { imports.add(new String[] {interpolate(text(d, "groupId"), pom.properties), interpolate(text(d, "artifactId"), pom.properties), interpolate(mv, pom.properties)}); continue; }
                if (mv != null) pom.managed.put(text(d, "groupId") + ":" + text(d, "artifactId"), interpolate(mv, pom.properties));
            }
            // Maven semantics: an imported BOM only adds modules that this POM (and its parents) do not already manage; the first import wins.
            for (String[] bom : imports) {
                String label = bom[0] + ":" + bom[1] + ":" + bom[2];
                if (bom[2] == null || bom[2].contains("${") || depth >= 6) { result.warnings.add("BOM import " + label + " in " + g + ":" + a + ":" + v + " cannot be followed (unknown version or nested too deeply); versions may be missing."); continue; }
                Pom imported = readPom(bom[0], bom[1], bom[2], depth + 1);
                if (imported == null) { result.warnings.add("BOM import " + label + " in " + g + ":" + a + ":" + v + " was not found; versions may be missing."); continue; }
                for (Map.Entry<String, String> managed : imported.managed.entrySet()) pom.managed.putIfAbsent(managed.getKey(), managed.getValue());
            }
            Element deps = child(project, "dependencies");
            if (deps != null) for (Element d : children(deps, "dependency")) {
                String scope = text(d, "scope"), type = text(d, "type"), optional = text(d, "optional");
                if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope) || "true".equals(optional)) continue;
                if (type != null && !"jar".equals(type) && !"aar".equals(type) && !"bundle".equals(type)) continue;
                String dg = interpolate(text(d, "groupId"), pom.properties), da = interpolate(text(d, "artifactId"), pom.properties);
                String dv = text(d, "version"); dv = dv == null ? pom.managed.get(dg + ":" + da) : interpolate(dv, pom.properties);
                pom.deps.add(new String[] {dg, da, dv});
            }
            return pom;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Could not parse POM of " + g + ":" + a + ":" + v + ": " + e.getMessage(), e); }
    }

    // ---------- artifact files ----------
    /** Downloads (or reads from the cache) the artifact and registers its jar(s) / extracted AAR folder in the result. */
    private boolean materialize(DepNode node) throws IOException {
        String[] p = node.ga.split(":");
        String[] variant = variants.get(node.ga);
        String classifier = variant == null ? "" : variant[0], wantedExtension = variant == null ? "" : variant[1];
        String suffix = classifier.isEmpty() ? "" : "-" + classifier;
        String ext = !wantedExtension.isEmpty() ? wantedExtension : "aar".equals(node.packaging) ? "aar" : "jar";
        String name = p[1] + "-" + node.version + suffix + "." + ext;
        if (!fetchArtifact(path(p[0], p[1], node.version, name), true, MAX_ARTIFACT)) {
            if ("jar".equals(ext) && wantedExtension.isEmpty()) { // Some POMs declare no packaging but ship an AAR.
                String aarName = p[1] + "-" + node.version + suffix + ".aar";
                if (fetchArtifact(path(p[0], p[1], node.version, aarName), true, MAX_ARTIFACT)) { node.packaging = "aar"; fromAar(p, node, aarName, suffix); return true; }
            }
            result.warnings.add(node.ga + ":" + node.version + (classifier.isEmpty() ? "" : " (classifier " + classifier + ")") + " has no " + ext + " file; skipped"
                + (classifier.isEmpty() && wantedExtension.isEmpty() ? " (metadata-only artifact)." : "."));
            return false;
        }
        if ("aar".equals(ext)) fromAar(p, node, name, suffix); else result.jars.add(cachedFile(p[0], p[1], node.version, name));
        return true;
    }

    /** Extracts the AAR once (atomically, via a temporary folder) and registers classes.jar, libs/*.jar and the folder itself. */
    private void fromAar(String[] p, DepNode node, String aarName, String suffix) throws IOException {
        File aar = cachedFile(p[0], p[1], node.version, aarName);
        File directory = new File(aar.getParentFile(), p[1] + "-" + node.version + suffix + "-aar");
        File marker = new File(directory, ".extracted");
        if (!marker.isFile()) {
            File temporary = new File(aar.getParentFile(), directory.getName() + ".tmp");
            deleteRecursively(temporary); deleteRecursively(directory);
            SecureZipExtractor.extract(aar, temporary, false, MAX_AAR_EXTRACTED);
            Files.write(new File(temporary, ".extracted").toPath(), new byte[0]);
            if (!temporary.renameTo(directory)) throw new IOException("Could not finish extracting " + aar.getName() + " into " + directory);
        }
        File classes = new File(directory, "classes.jar");
        boolean hasCode = false;
        if (classes.isFile() && classes.length() > 0) { result.jars.add(classes); hasCode = true; }
        File[] libraries = new File(directory, "libs").listFiles((dir, fileName) -> fileName.endsWith(".jar"));
        if (libraries != null) { Arrays.sort(libraries); for (File library : libraries) if (library.isFile() && library.length() > 0) { result.jars.add(library); hasCode = true; } }
        if (!hasCode) result.notes.add(node.ga + ":" + node.version + " is an AAR without classes (resources only)");
        result.aarDirectories.add(directory);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private File cachedFile(String g, String a, String v, String name) { return new File(new File(cache, g.replace('.', '/') + "/" + a + "/" + v), name); }
    private static String path(String g, String a, String v, String name) { return g.replace('.', '/') + "/" + a + "/" + v + "/" + name; }

    private byte[] fetchVerified(String relative, boolean optional) throws IOException { return fetchVerified(relative, optional, MAX_SMALL); }

    /**
     * Small files only (POM, .module): they are held in memory, capped by the memory limit.
     * Cache hit -> bytes from disk (it was verified when written). Otherwise fetch from each repo and require a matching .sha1.
     */
    private byte[] fetchVerified(String relative, boolean optional, long maxBytes) throws IOException {
        File local = new File(cache, relative), missing = new File(cache, relative + ".missing");
        if (local.isFile() && local.length() > 0) return Files.readAllBytes(local.toPath());
        if (optional && isMissingMarker(missing)) return null;
        String group = groupOf(relative);
        IOException denied = null; boolean notFound = false;
        for (MavenRepository repo : repos) {
            if (!repo.allows(group)) continue;
            byte[] data, sidecar;
            try {
                data = fetcher.get(repo.url + relative, policy.wholeFileLimit(maxBytes));
                if (data == null) { notFound = true; continue; }
                sidecar = fetcher.get(repo.url + relative + ".sha1", 1024);
            } catch (AccessDenied refused) { if (denied == null) denied = refused; noteDenied(repo); continue; }   // this repository does not let us in: try the next one
            String coordinate = coordinateOf(relative), fileName = fileNameOf(relative);
            if (sidecar == null) throw ChecksumFailure.serverMissing(coordinate, fileName, repo.url, sha256(data));
            String expected = new String(sidecar, StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            String actual = sha1(data);
            if (!actual.equals(expected)) throw ChecksumFailure.serverMismatch(coordinate, fileName, repo.url, expected, actual, sha256(data));
            if (!local.getParentFile().isDirectory() && !local.getParentFile().mkdirs()) throw new IOException("Could not create " + local.getParentFile());
            // Never expose a partially-written dependency as a valid cache hit if Android kills the app mid-write.
            File temporary = File.createTempFile(local.getName(), ".partial", local.getParentFile());
            try {
                Files.write(temporary.toPath(), data);
                moveIntoPlace(temporary, local);
            } finally {
                if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
            }
            return data;
        }
        // A repository that said "no access" is not the same as "the file does not exist": it is never cached as missing, and when every
        // repository asked refused, that is the error to show. (Some servers answer 403 for files they simply do not have.)
        if (denied != null && !notFound) throw denied;
        if (!optional) return null;
        if (denied == null) writeMissingMarker(missing);
        return null;
    }

    /**
     * Jars and AARs: streamed to a ".download" file next to the target in chunks (never held in memory as a whole), hashed on the way,
     * checked against the published .sha1 and only then moved into place. A download that was interrupted keeps its ".download" file and resumes
     * from there next time; one that fails the checksum is deleted.
     *
     * @return true when the file is now in the cache, false when no repository has it
     */
    private boolean fetchArtifact(String relative, boolean optional, long maxBytes) throws IOException {
        File local = new File(cache, relative), missing = new File(cache, relative + ".missing");
        String coordinate = coordinateOf(relative), fileName = fileNameOf(relative);
        String pin = pins.get(coordinate);
        if (local.isFile() && local.length() > 0) {
            if (pin == null) return true;   // verified when it was written
            String actual = sha256(local);   // a pin added after the file was cached still has to hold
            if (!actual.equals(pin)) { local.delete(); throw ChecksumFailure.pinMismatch(coordinate, fileName, "the local cache", pin, actual); }
            return true;
        }
        if (optional && isMissingMarker(missing)) return false;
        File parent = local.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        File partial = new File(parent, local.getName() + ".download");
        String group = groupOf(relative);
        IOException denied = null; boolean notFound = false;
        for (MavenRepository repo : repos) {
            if (!repo.allows(group)) continue;
            String actualSha1;
            try { actualSha1 = fetcher.download(repo.url + relative, partial, maxBytes, policy); }
            catch (AccessDenied refused) { partial.delete(); if (denied == null) denied = refused; noteDenied(repo); continue; }
            if (actualSha1 == null) { partial.delete(); notFound = true; continue; }
            if (pin != null) {
                // The pin comes from outside the server, so it decides on its own; the server's .sha1 is not needed.
                String actual = sha256(partial);
                if (!actual.equals(pin)) { partial.delete(); throw ChecksumFailure.pinMismatch(coordinate, fileName, repo.url, pin, actual); }
            } else {
                byte[] sidecar;
                try { sidecar = fetcher.get(repo.url + relative + ".sha1", 1024); } catch (AccessDenied refused) { sidecar = null; }
                if (sidecar == null) { String received = sha256(partial); partial.delete(); throw ChecksumFailure.serverMissing(coordinate, fileName, repo.url, received); }
                String expected = new String(sidecar, StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (!actualSha1.equals(expected)) { String received = sha256(partial); partial.delete(); throw ChecksumFailure.serverMismatch(coordinate, fileName, repo.url, expected, actualSha1, received); }
                if (!repo.trusted) {
                    String received = sha256(partial);
                    result.warnings.add(coordinate + " (" + fileName + ") came from " + repo.url + " and is not pinned: only the server's own SHA-1 was checked, which does not prove the file is the one you expect. SHA-256: " + received
                        + ". If it equals the SHA-256 on the release page, pin it by adding this line to " + ChecksumPins.FILE + ": " + coordinate + "=" + received);
                }
            }
            moveIntoPlace(partial, local);
            return true;
        }
        if (denied != null && !notFound) throw denied;
        if (!optional) return false;
        if (denied == null) writeMissingMarker(missing);
        return false;
    }

    // ---------- dynamic versions: 1.+, [1.0,2.0), latest.release ----------
    //
    // Same rules as Gradle: the versions a repository has are read from its maven-metadata.xml, every allowed repository is asked and the
    // highest matching version wins, a saved list is trusted for 24 hours, and the version that was chosen is written to the build log.

    /** True for a version that is not one fixed version: a range, a prefix such as 1.+ (or just +), or latest.release. */
    public static boolean isDynamicVersion(String version) {
        if (version == null) return false;
        String v = version.trim();
        return v.startsWith("[") || v.startsWith("(") || v.startsWith("]") || v.contains(",") || v.endsWith("+") || v.startsWith("latest.");
    }

    /** Whether the dynamic version can be resolved here. latest.integration (SNAPSHOTs) and other statuses cannot. */
    public static boolean isSupportedDynamicVersion(String version) {
        String v = version.trim();
        if (v.startsWith("latest.")) return v.equals("latest.release");
        if (v.startsWith("[") || v.startsWith("(") || v.startsWith("]")) { try { new VersionRange(v); return true; } catch (IllegalArgumentException invalid) { return false; } }
        return !v.contains(",");
    }

    /** Whether the concrete version is accepted by the dynamic version. */
    public static boolean versionMatches(String spec, String version) {
        String v = spec.trim();
        if (v.equals("+")) return true;
        if (v.endsWith("+")) return version.startsWith(v.substring(0, v.length() - 1));
        if (v.equals("latest.release")) return !version.toUpperCase(Locale.ROOT).endsWith("SNAPSHOT");
        if (v.startsWith("latest.")) throw new IllegalArgumentException("only latest.release is supported");
        return new VersionRange(v).contains(version);
    }

    /** A Maven-style range: [1.0,2.0)  (1.0,2.0]  [1.5]  [1.0,)  (,2.0). */
    private static final class VersionRange {
        final String low, high; final boolean lowInclusive, highInclusive, exact;
        VersionRange(String spec) {
            String s = spec.trim();
            // ( and ] both start an exclusive lower bound, ) and [ both end an exclusive upper bound: (1.2, 1.5] == ]1.2, 1.5]  and  [1.1, 2.0) == [1.1, 2.0[
            if (s.length() < 3 || !(s.startsWith("[") || s.startsWith("(") || s.startsWith("]")) || !(s.endsWith("]") || s.endsWith(")") || s.endsWith("[")))
                throw new IllegalArgumentException("not a version range: " + spec);
            lowInclusive = s.startsWith("["); highInclusive = s.endsWith("]");
            String inner = s.substring(1, s.length() - 1);
            if (inner.contains("[") || inner.contains("]") || inner.contains("(") || inner.contains(")")) throw new IllegalArgumentException("only one range is supported: " + spec);
            String[] parts = inner.split(",", -1);
            if (parts.length == 1) {
                if (!lowInclusive || !highInclusive || parts[0].trim().isEmpty()) throw new IllegalArgumentException("not a version range: " + spec);
                exact = true; low = high = parts[0].trim();
            } else if (parts.length == 2) {
                exact = false; low = parts[0].trim(); high = parts[1].trim();
                if (low.isEmpty() && high.isEmpty()) throw new IllegalArgumentException("empty range: " + spec);
            } else throw new IllegalArgumentException("only one range is supported: " + spec);
        }
        boolean contains(String version) {
            if (exact) return compare(version, low) == 0;
            if (!low.isEmpty()) { int c = compare(version, low); if (c < 0 || (c == 0 && !lowInclusive)) return false; }
            if (!high.isEmpty()) {
                int c = compare(version, high);
                if (c > 0 || (c == 0 && !highInclusive)) return false;
                // An exclusive upper bound acts as a prefix exclude: [1.0,2.0) also leaves out 2.0-rc1, 2.0-dev1 ... (Gradle docs)
                if (!highInclusive && c < 0 && version.startsWith(high)) return false;
            }
            return true;
        }
    }

    private static boolean looksPreRelease(String version) {
        return version.toLowerCase(Locale.ROOT).matches(".*[-._](alpha|beta|rc|cr|m\\d+|dev|preview|eap|snapshot)[-._\\d]*.*|.*[-._](alpha|beta|rc|cr|dev|preview|eap|snapshot)$");
    }

    /** Picks the highest listed version that the dynamic version accepts. */
    private String resolveDynamic(String g, String a, String spec) throws IOException {
        String ga = g + ":" + a;
        if (!isSupportedDynamicVersion(spec))
            throw new IOException(ga + ":" + spec + " cannot be resolved here (supported: ranges like [1.0,2.0), prefixes like 1.+ and latest.release). Use a fixed version.");
        List<String> available = availableVersions(g, a);
        if (available.isEmpty())
            throw new IOException("No list of versions was found for " + ga + " in the repositories, so '" + spec + "' cannot be resolved. Use a fixed version.");
        String best = null; int matching = 0;
        for (String version : available) {
            if (!versionMatches(spec, version)) continue;
            matching++;
            if (best == null || compare(version, best) > 0) best = version;
        }
        if (best == null) throw new IOException("No version of " + ga + " matches '" + spec + "'. Available: " + available);
        result.notes.add(ga + ":" + spec + " -> " + best + " (highest of " + matching + " matching version" + (matching == 1 ? "" : "s") + "; the list is saved for 24 hours)");
        if (looksPreRelease(best))
            result.warnings.add(ga + ":" + spec + " resolved to " + best + ", which is a pre-release. Gradle chooses it too, because a dynamic version accepts pre-releases. Use a fixed version for repeatable builds.");
        return best;
    }

    /** Every version of g:a that the repositories allowed for this group list, in the order found (all repositories are asked, as Gradle does for dynamic versions). */
    private List<String> availableVersions(String g, String a) throws IOException {
        String key = g + ":" + a;
        List<String> known = versionLists.get(key);
        if (known != null) return known;
        Set<String> all = new LinkedHashSet<>();
        for (MavenRepository repo : repos) {
            if (!repo.allows(g)) continue;
            List<String> listed = readVersionList(repo, g, a);
            if (listed != null) all.addAll(listed);
        }
        List<String> versions = new ArrayList<>(all);
        versionLists.put(key, versions);
        return versions;
    }

    /** The versions one repository lists for g:a, or null when it has no list. Saved for 24 hours; a stale saved list is used when the repository cannot be reached. */
    private List<String> readVersionList(MavenRepository repo, String g, String a) throws IOException {
        String relative = g.replace('.', '/') + "/" + a + "/maven-metadata.xml";
        File saved = new File(new File(cache, ".version-lists/" + sha1Hex(repo.url)), relative);
        boolean fresh = saved.isFile() && saved.length() > 0 && System.currentTimeMillis() - saved.lastModified() < VERSION_LIST_TTL_MS;
        if (fresh) { List<String> list = parseVersionList(Files.readAllBytes(saved.toPath()), a); if (list != null) return list; }
        byte[] data; List<String> list = null;
        try {
            data = fetcher.get(repo.url + relative, policy.wholeFileLimit(MAX_SMALL));
            if (data != null) {
                byte[] sidecar = null;
                try { sidecar = fetcher.get(repo.url + relative + ".sha1", 1024); } catch (AccessDenied noSidecar) { /* the list is only used to choose a version; the file itself is verified later */ }
                if (sidecar != null) {
                    String expected = new String(sidecar, StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(Locale.ROOT), actual = sha1(data);
                    if (!actual.equals(expected)) throw ChecksumFailure.serverMismatch(g + ":" + a, "maven-metadata.xml", repo.url, expected, actual, sha256(data));
                }
                list = parseVersionList(data, a);
            } else {   // Google's repository documents a per-group index instead
                byte[] index = fetcher.get(repo.url + g.replace('.', '/') + "/group-index.xml", policy.wholeFileLimit(MAX_SMALL));
                if (index != null) list = parseGroupIndex(index, a);
            }
        } catch (AccessDenied refused) {
            noteDenied(repo); return null;
        } catch (ChecksumFailure integrity) {
            throw integrity;
        } catch (IOException unreachable) {
            if (saved.isFile() && saved.length() > 0) {   // offline or the repository is down: an old list is better than none
                List<String> old = parseVersionList(Files.readAllBytes(saved.toPath()), a);
                if (old != null) { result.warnings.add("Could not refresh the version list of " + g + ":" + a + " from " + repo.url + " (" + unreachable.getMessage() + "); the saved list is used."); return old; }
            }
            throw unreachable;
        }
        if (list == null) return null;
        File parent = saved.getParentFile();
        if (parent.isDirectory() || parent.mkdirs()) {
            File temporary = File.createTempFile(saved.getName(), ".partial", parent);
            try {
                Files.write(temporary.toPath(), list.isEmpty() ? new byte[0] : toMetadataXml(g, a, list).getBytes(StandardCharsets.UTF_8));
                moveIntoPlace(temporary, saved);
                //noinspection ResultOfMethodCallIgnored
                saved.setLastModified(System.currentTimeMillis());
            } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
        }
        return list;
    }

    /** The list is saved in maven-metadata.xml form whichever file it came from, so one reader serves the cache. */
    private static String toMetadataXml(String g, String a, List<String> versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>").append(g).append("</groupId><artifactId>").append(a).append("</artifactId><versioning><versions>");
        for (String version : versions) xml.append("<version>").append(version.replace("&", "&amp;").replace("<", "&lt;")).append("</version>");
        return xml.append("</versions></versioning></metadata>").toString();
    }

    /** maven-metadata.xml: metadata / versioning / versions / version*. Null when the file cannot be read. */
    private static List<String> parseVersionList(byte[] data, String artifact) {
        try {
            Element root = parseXml(data);
            Element versioning = child(root, "versioning");
            Element versions = versioning == null ? null : child(versioning, "versions");
            if (versions == null) return null;
            List<String> list = new ArrayList<>();
            for (Element version : children(versions, "version")) { String t = version.getTextContent().trim(); if (!t.isEmpty()) list.add(t); }
            return list;
        } catch (Exception unreadable) { return null; }
    }

    /** Google's group-index.xml: the root is named after the group, a child after the artifact, with a comma separated versions attribute. */
    private static List<String> parseGroupIndex(byte[] data, String artifact) {
        try {
            Element root = parseXml(data);
            Element entry = child(root, artifact);
            if (entry == null || !entry.hasAttribute("versions")) return null;
            List<String> list = new ArrayList<>();
            for (String version : entry.getAttribute("versions").split(",")) if (!version.trim().isEmpty()) list.add(version.trim());
            return list;
        } catch (Exception unreadable) { return null; }
    }

    private static Element parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }   // no XXE
        catch (javax.xml.parsers.ParserConfigurationException unsupported) { /* Android's parser does not resolve external entities anyway */ }
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data)).getDocumentElement();
    }

    private static String sha1Hex(String text) {
        try { return hex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-1 unavailable", e); }
    }

    // ---------- repository helpers ----------
    private final Set<String> deniedReported = new HashSet<>();
    private void noteDenied(MavenRepository repo) {
        if (deniedReported.add(repo.url)) result.warnings.add(repo + " refused access (HTTP 401/403) for some files. Those files were looked up in the other repositories instead. Logins are not supported, so only public files can be downloaded from it.");
    }

    /** Group of a "group/with/slashes/artifact/version/file" path. */
    private static String groupOf(String relative) {
        String[] parts = relative.split("/");
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++) group.append(i == 0 ? "" : ".").append(parts[i]);
        return group.toString();
    }
    private static String coordinateOf(String relative) {
        String[] parts = relative.split("/");
        int n = parts.length;
        if (n < 4) return null;
        String coordinate = groupOf(relative) + ":" + parts[n - 3] + ":" + parts[n - 2];
        // A classifier file (artifact-1.0-sources.jar) is its own coordinate, so a SHA-256 pinned for the main file is never compared with it.
        String prefix = parts[n - 3] + "-" + parts[n - 2] + "-", file = parts[n - 1];
        int dot = file.lastIndexOf('.');
        if (file.startsWith(prefix) && dot > prefix.length()) coordinate += ":" + file.substring(prefix.length(), dot);
        return coordinate;
    }
    private static String fileNameOf(String relative) { return relative.substring(relative.lastIndexOf('/') + 1); }

    private boolean isMissingMarker(File marker) throws IOException {
        if (!marker.isFile()) return false;
        if (marker.length() == 0) return emptyMarkersApply;
        return new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).equals(repoFingerprint);
    }
    private void writeMissingMarker(File marker) throws IOException {
        if (marker.getParentFile().isDirectory() || marker.getParentFile().mkdirs()) Files.write(marker.toPath(), repoFingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] data) throws IOException {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }
    /** SHA-256 of a file, read in small pieces (a jar is never held in memory). */
    private static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static void moveIntoPlace(File temporary, File local) throws IOException {
        try {
            Files.move(temporary.toPath(), local.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Files.move(temporary.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------- helpers ----------
    static String cleanVersion(String v) {
        v = v.trim();
        if (v.startsWith("[") || v.startsWith("(")) {
            for (String part : v.replaceAll("[\\[\\]()]", "").split(",")) if (!part.trim().isEmpty()) return part.trim();
        }
        return v;
    }
    private static String interpolate(String value, Map<String, String> props) {
        if (value == null) return null;
        for (int i = 0; i < 8 && value.contains("${"); i++) {
            int s = value.indexOf("${"), e = value.indexOf('}', s); if (e < 0) break;
            String repl = props.get(value.substring(s + 2, e)); if (repl == null) break;
            value = value.substring(0, s) + repl + value.substring(e + 1);
        }
        return value;
    }
    private static Element child(Element parent, String name) { for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element && n.getNodeName().equals(name)) return (Element) n; return null; }
    private static List<Element> children(Element parent, String name) { List<Element> out = new ArrayList<>(); for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element && n.getNodeName().equals(name)) out.add((Element) n); return out; }
    private static String text(Element parent, String name) { Element e = child(parent, name); if (e == null) return null; String t = e.getTextContent().trim(); return t.isEmpty() ? null : t; }
    private static String sha1(byte[] data) throws IOException {
        try { return hex(MessageDigest.getInstance("SHA-1").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-1 unavailable", e); }
    }

    /**
     * Gradle's version ordering (docs.gradle.org, "Declaring Versions and Ranges"): a version is split at . - _ + and between digits and
     * letters (1a1 is 1.a.1). Numbers compare as numbers and are higher than text (1.a < 1.1); text compares alphabetically and
     * case-sensitively, except that dev is lower than any other text, and rc < snapshot < final < ga < release < sp are higher than any other
     * text. A version with an extra number is higher (1.1 < 1.1.0), one with extra text is lower (1.1.a < 1.1).
     */
    static int compare(String x, String y) {
        List<String> a = versionParts(x), b = versionParts(y);
        int common = Math.min(a.size(), b.size());
        for (int i = 0; i < common; i++) { int c = comparePart(a.get(i), b.get(i)); if (c != 0) return c; }
        if (a.size() == b.size()) return 0;
        boolean firstIsLonger = a.size() > b.size();
        String extra = (firstIsLonger ? a : b).get(common);
        int longerIs = isNumber(extra) ? 1 : -1;
        return firstIsLonger ? longerIs : -longerIs;
    }

    private static final List<String> HIGH_TEXT = Arrays.asList("rc", "snapshot", "final", "ga", "release", "sp");

    private static List<String> versionParts(String version) {
        List<String> parts = new ArrayList<>();
        for (String piece : version.trim().split("[._+\\-]")) {
            if (piece.isEmpty()) continue;
            for (String part : piece.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")) if (!part.isEmpty()) parts.add(part);
        }
        return parts;
    }
    private static boolean isNumber(String part) { for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return false; return !part.isEmpty(); }
    private static int comparePart(String a, String b) {
        boolean na = isNumber(a), nb = isNumber(b);
        if (na && nb) return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        if (na) return 1;
        if (nb) return -1;
        int ra = textRank(a), rb = textRank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        return ra == -1 ? a.compareTo(b) : 0;   // other text: alphabetical, case-sensitive
    }
    /** dev is below all other text (-2), ordinary text is -1, and the special words are above it in the order of {@link #HIGH_TEXT}. */
    private static int textRank(String part) {
        String lower = part.toLowerCase(Locale.ROOT);
        if (lower.equals("dev")) return -2;
        int index = HIGH_TEXT.indexOf(lower);
        return index < 0 ? -1 : index;
    }

    /**
     * Fetcher for offline builds: never touches the network. Everything already in the cache is served by MavenResolver before the
     * fetcher is asked, so any call here means the file is not cached. It throws (instead of returning null = "404") so that no
     * ".missing" marker is written and the cache is not poisoned for the next online build.
     */
    public static final class CacheOnlyFetcher implements Fetcher {
        @Override public byte[] get(String url, long maxBytes) throws IOException {
            throw new IOException("Offline mode: " + url + " is not in the local dependency cache. Run one online build first so it is downloaded and cached.");
        }
    }

    /**
     * HTTPS-only fetcher for any repository. 404 -> null; 401/403 -> {@link AccessDenied}. Follows at most 3 redirects, only to HTTPS.
     * Host names are resolved by {@link ResilientDns} (Google DNS, then Cloudflare DNS); when both fail the lookup throws and the build stops.
     * {@link #download} streams to disk in chunks and resumes an interrupted file with an HTTP Range request.
     */
    public static final class HttpsFetcher implements Fetcher {
        private static final int MAX_RESUMES = 3;
        private final ResilientDns dns;

        public HttpsFetcher() { this(ResilientDns.standard()); }
        HttpsFetcher(ResilientDns dns) { this.dns = dns; }

        /** The body stopped coming in the middle of a file: worth resuming, unlike a refusal from the server. */
        private static final class Interrupted extends IOException { Interrupted(String m, Throwable c) { super(m, c); } }
        /** The server or the size limit said no: resuming would not help. */
        private static final class Refused extends IOException { Refused(String m) { super(m); } }
        /** HTTP 416: the saved part does not fit the file on the server. */
        private static final class RangeRejected extends IOException { RangeRejected(String m) { super(m); } }

        @Override public byte[] get(String url, long maxBytes) throws IOException {
            HttpsConnector.Response response = open(url, null);
            if (response == null) return null;
            try {
                if (response.contentLength() > maxBytes) throw new IOException("File too large: " + url);
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; long total = 0; int n;
                InputStream in = response.body();
                while ((n = in.read(buf)) != -1) { total += n; if (total > maxBytes) throw new IOException("File too large: " + url); out.write(buf, 0, n); }
                return out.toByteArray();
            } finally { response.close(); }
        }

        @Override public String download(String url, File target, long maxBytes, DownloadMemoryPolicy policy) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt <= MAX_RESUMES; attempt++) {
                try { return downloadOnce(url, target, maxBytes, policy); }
                catch (Interrupted interrupted) { last = interrupted; }   // the partial file stays; the next round continues after its last byte
            }
            throw new IOException("Download kept breaking off: " + url + " (" + last.getMessage() + "). It will resume from the saved part next time.", last);
        }

        private String downloadOnce(String url, File target, long maxBytes, DownloadMemoryPolicy policy) throws IOException {
            MessageDigest digest = newSha1();
            long have = target.isFile() ? hashExisting(target, digest) : 0;
            Map<String, String> range = new LinkedHashMap<>();
            if (have > 0) range.put("Range", "bytes=" + have + "-");
            HttpsConnector.Response c;
            try { c = open(url, range); }
            catch (RangeRejected staleRange) { target.delete(); return downloadOnce(url, target, maxBytes, policy); }   // start over without a Range
            if (c == null) { target.delete(); return null; }
            try {
                int status = c.status();
                boolean resumed = status == 206 && have > 0;
                if (!resumed) { digest.reset(); have = 0; }   // the server sent the whole file (or we had nothing): start the partial file over
                long length = c.contentLength();
                if (length >= 0 && have + length > maxBytes) { target.delete(); throw new Refused("File too large: " + url); }
                byte[] chunk = new byte[policy.getChunkBytes()];
                try (InputStream in = c.body();
                     OutputStream out = Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE, resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    long total = have;
                    while (true) {
                        // One chunk in memory at a time. If the connection breaks halfway through a chunk, what did arrive is still saved,
                        // so a resume loses nothing.
                        int filled = 0; IOException broken = null;
                        try { int n; while (filled < chunk.length && (n = in.read(chunk, filled, chunk.length - filled)) != -1) filled += n; }
                        catch (IOException failure) { broken = failure; }
                        if (filled > 0) {
                            total += filled;
                            if (total > maxBytes) { target.delete(); throw new Refused("File too large: " + url); }
                            out.write(chunk, 0, filled);
                            digest.update(chunk, 0, filled);
                        }
                        if (broken != null) throw broken;
                        if (filled < chunk.length) break;      // short chunk = end of file
                    }
                    if (length >= 0 && total != have + length) throw new Interrupted("received " + (total - have) + " of " + length + " bytes", null);
                } catch (IOException failure) {
                    if (failure instanceof Interrupted || failure instanceof Refused) throw failure;
                    throw new Interrupted(String.valueOf(failure.getMessage()), failure);
                }
                return hex(digest.digest());
            } finally { c.close(); }
        }

        /** Feeds the part already on disk into the digest (a small buffer, not the file) and returns its length. */
        private static long hashExisting(File file, MessageDigest digest) throws IOException {
            long total = 0; byte[] buf = new byte[65536]; int n;
            try (InputStream in = new FileInputStream(file)) { while ((n = in.read(buf)) != -1) { digest.update(buf, 0, n); total += n; } }
            return total;
        }

        /** Response with status 200 or 206, or null for 404. Redirects (HTTPS only, at most 3) are followed by hand. */
        private HttpsConnector.Response open(String url, Map<String, String> headers) throws IOException {
            String current = url;
            for (int hop = 0; hop <= 3; hop++) {
                if (!current.startsWith("https://")) throw new IOException("Refusing non-HTTPS URL: " + current);
                HttpsConnector.Response response = HttpsConnector.get(dns, new URL(current), 15000, readTimeoutFor(current), headers);   // Google DNS, then Cloudflare DNS; both failing throws and stops
                boolean keep = false;
                try {
                    int status = response.status();
                    if (status == 404) return null;
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String loc = response.header("Location"); if (loc == null) throw new IOException("Redirect without Location: " + current);
                        current = new URL(new URL(current), loc).toString(); continue;
                    }
                    if (status == 416) throw new RangeRejected("HTTP 416 for " + current);
                    if (status == 401 || status == 403) throw new AccessDenied("HTTP " + status + " for " + current + " (the repository needs a login or does not allow access; logins are not supported)");
                    if (status != 200 && status != 206) throw new Refused("HTTP " + status + " for " + current);
                    keep = true;
                    return response;
                } finally { if (!keep) response.close(); }
            }
            throw new IOException("Too many redirects for " + url);
        }

        /** JitPack builds a library the first time somebody asks for it, so its first answer can take much longer than a normal download. */
        private static int readTimeoutFor(String url) { return url.startsWith("https://jitpack.io/") ? 120000 : 30000; }

        private static MessageDigest newSha1() throws IOException {
            try { return MessageDigest.getInstance("SHA-1"); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-1 unavailable", e); }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }
}
