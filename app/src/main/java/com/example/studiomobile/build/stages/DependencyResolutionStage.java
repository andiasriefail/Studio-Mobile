package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ClassFileVersionGuard;
import com.example.studiomobile.build.JavaLanguageLevel;
import com.example.studiomobile.build.ProjectModules;
import com.example.studiomobile.build.dependency.ChecksumPins;
import com.example.studiomobile.build.dependency.DownloadMemoryPolicy;
import com.example.studiomobile.build.dependency.GradleDependencyParser;
import com.example.studiomobile.build.dependency.MavenRepository;
import com.example.studiomobile.build.dependency.MavenResolver;
import com.example.studiomobile.build.dependency.RepositoryDeclarations;
import com.example.studiomobile.build.dependency.ResilientDns;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads the module's declared libraries (build.gradle(.kts) + libs.versions.toml), downloads them from Google Maven / Maven Central
 * into a persistent cache, and exposes the resulting jars to the compile and DEX stages as the "dependencyDirectory" output.
 * AAR libraries contribute their classes.jar and libs/*.jar there, and their extracted folders are stored as the "aarDirectories" file list
 * (lowest priority first: a library comes before the libraries that depend on it, so later resource/asset layers override earlier ones).
 * When the build configuration is offline only the local cache is used and the network is never touched.
 * This replaces Gradle's dependency resolution; it does not run Gradle.
 */
public final class DependencyResolutionStage implements BuildStage {
    private final File cacheRoot;
    private final MavenResolver.Fetcher fetcher; // null = choose per build: cache-only when offline, HTTPS otherwise
    public DependencyResolutionStage(File cacheRoot) { this(cacheRoot, null); }
    public DependencyResolutionStage(File cacheRoot, MavenResolver.Fetcher fetcher) { this.cacheRoot = cacheRoot; this.fetcher = fetcher; }
    @Override public String getName() { return "Dependency resolution (Maven)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File project = context.getProjectDirectory();
        // Every module contributes its libraries: the app and, in a multi-module project, each library module it depends on.
        ProjectModules modules = ProjectModules.load(project);
        List<GradleDependencyParser.Declaration> declarations = new ArrayList<>();
        List<GradleDependencyParser.Platform> platforms = new ArrayList<>();
        List<GradleDependencyParser.Declaration> processors = new ArrayList<>();
        List<String> unsupportedProcessors = new ArrayList<>();
        List<String> skippedDependencies = new ArrayList<>();
        for (ProjectModules.Module module : modules.all()) {
            GradleDependencyParser parsed = GradleDependencyParser.parse(project, module.directory);
            String prefix = modules.isMultiModule() ? module.path + ": " : "";
            for (String warning : parsed.warnings) if (logger != null) logger.onWarning(prefix + warning);
            for (String skipped : parsed.skipped) skippedDependencies.add(prefix + skipped);
            mergeDeclarations(declarations, parsed.declarations);
            for (GradleDependencyParser.Platform platform : parsed.platforms) {
                boolean known = false;
                for (GradleDependencyParser.Platform other : platforms) if (other.group.equals(platform.group) && other.artifact.equals(platform.artifact)) known = true;
                if (!known) platforms.add(platform);
            }
            mergeDeclarations(processors, parsed.annotationProcessors);
            // kapt works on Kotlin sources only, which cannot be processed here; in a module without Kotlin it is plain Java processing.
            if (!parsed.kaptProcessors.isEmpty() && !module.hasKotlinSources()) mergeDeclarations(processors, parsed.kaptProcessors);
            else for (GradleDependencyParser.Declaration d : parsed.kaptProcessors) unsupportedProcessors.add(prefix + "kapt " + d.group + ":" + d.artifact);
            for (GradleDependencyParser.Declaration d : parsed.kspProcessors) unsupportedProcessors.add(prefix + "ksp " + d.group + ":" + d.artifact);
        }
        if (!unsupportedProcessors.isEmpty()) {
            String note = "kapt/ksp are Kotlin compiler plugins and cannot run on the phone: " + String.join(", ", unsupportedProcessors)
                + ". Classes those processors would generate (Room, Hilt, Moshi codegen, ...) will be missing.";
            if (logger != null) logger.onWarning(note);
            context.putAttribute("unsupportedProcessors", note);
        }
        if (!skippedDependencies.isEmpty()) {
            // The build goes on without them. If the code needs one, the compiler will complain about a missing class: this tells where to look first.
            StringBuilder note = new StringBuilder(skippedDependencies.size() + " declared librar" + (skippedDependencies.size() == 1 ? "y was" : "ies were")
                + " left out of the build. If the compiler reports a missing class or package, check these first:");
            for (int i = 0; i < skippedDependencies.size() && i < 8; i++) note.append("\n - ").append(skippedDependencies.get(i));
            if (skippedDependencies.size() > 8) note.append("\n - ... and ").append(skippedDependencies.size() - 8).append(" more (see the warnings above)");
            if (logger != null) logger.onWarning(note.toString());
            context.putAttribute("skippedDependencies", note.toString());
        }
        if (!processors.isEmpty()) resolveProcessors(context, processors, platforms, logger);
        if (declarations.isEmpty()) { if (logger != null) logger.onInfo("No library dependencies declared; nothing to download."); return; }
        if (logger != null) { logger.onInfo("Declared libraries: " + declarations.size()); for (GradleDependencyParser.Declaration d : declarations) logger.onInfo("[maven] declared " + d); for (GradleDependencyParser.Platform p : platforms) logger.onInfo("[maven] declared " + p); }
        if (!cacheRoot.isDirectory() && !cacheRoot.mkdirs()) throw new IllegalStateException("Could not create dependency cache: " + cacheRoot);
        MavenResolver.Result result = resolve(context, declarations, platforms, logger);
        for (String warning : result.warnings) if (logger != null) logger.onWarning(warning);
        List<String> tooNew = ClassFileVersionGuard.findTooNew(result.jars, JavaLanguageLevel.REQUIRED_CLASS_MAJOR);
        if (!tooNew.isEmpty()) throw new IllegalStateException("Unsupported library (up to Java " + JavaLanguageLevel.REQUIRED + "): " + String.join(" | ", tooNew));
        File out = new File(context.getWorkingDirectory(), "dependencies");
        if (!out.mkdirs() && !out.isDirectory()) throw new IllegalStateException("Could not create " + out);
        int index = 0; List<File> jars = result.jars;
        for (File jar : jars) Files.copy(jar.toPath(), new File(out, String.format("%03d-%s", index++, displayName(jar))).toPath(), StandardCopyOption.REPLACE_EXISTING);
        List<File> aarDirectories = new ArrayList<>(result.aarDirectories);
        Collections.reverse(aarDirectories);
        context.putFiles("aarDirectories", aarDirectories);
        if (logger != null) logger.onInfo("[maven] Resolved " + jars.size() + " jar file(s) and " + aarDirectories.size() + " AAR librar" + (aarDirectories.size() == 1 ? "y" : "ies") + ": " + result.notes);
        context.putOutput("dependencyDirectory", out);
    }

    private MavenResolver.Result resolve(BuildStageContext context, List<GradleDependencyParser.Declaration> declarations,
                                         List<GradleDependencyParser.Platform> platforms, BuildLogger logger) throws Exception {
        boolean offline = context.getConfiguration().isOfflineMode();
        MavenResolver.Fetcher activeFetcher = offline ? new MavenResolver.CacheOnlyFetcher() : fetcher != null ? fetcher : new MavenResolver.HttpsFetcher();
        if (offline && logger != null) logger.onInfo("Offline mode: libraries are read from the local cache only.");
        // The project says where its libraries live (settings.gradle / build.gradle repositories { }); Google and Maven Central are always searched too.
        RepositoryDeclarations declared = RepositoryDeclarations.read(context.getProjectDirectory(), moduleDirectories(context.getProjectDirectory()));
        List<MavenRepository> repositories = declared.withDefaults();
        ChecksumPins pins = ChecksumPins.load(context.getProjectDirectory());   // a bad line here stops the build with a ChecksumFailure
        if (logger != null) {
            for (String warning : declared.warnings) logger.onWarning(warning);
            StringBuilder order = new StringBuilder();
            for (MavenRepository repository : repositories) order.append(order.length() == 0 ? "" : " , ").append(repository);
            logger.onInfo("[maven] repositories, in this order: " + order);
            if (!pins.isEmpty()) logger.onInfo("[maven] " + pins.size() + " SHA-256 pin(s) read from " + ChecksumPins.FILE);
            logger.onInfo("[download] " + DownloadMemoryPolicy.detect() + "; host names: Google DNS (8.8.8.8), then Cloudflare DNS (1.1.1.1), then the phone's own DNS, then stop");
            activeFetcher = new LoggingFetcher(activeFetcher, logger);
        }
        try {
            return MavenResolver.forRepositories(cacheRoot, activeFetcher, repositories).withPins(pins).resolve(declarations, platforms);
        } catch (ResilientDns.DnsFailedException dnsFailure) {
            // Google DNS, Cloudflare DNS and the phone's own DNS all failed: stop now. A network problem is not a missing library.
            throw new IllegalStateException("Could not resolve libraries: " + dnsFailure.getMessage(), dnsFailure);
        } catch (com.example.studiomobile.build.dependency.ChecksumFailure failure) {
            // Kept as the cause so the app can show the details in a dialog.
            throw new IllegalStateException("Could not resolve libraries: " + failure.getMessage(), failure);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not resolve libraries: " + failure.getMessage() + (offline ? "" : " (network is needed the first time; downloaded files are cached afterwards)."), failure);
        }
    }

    private static List<File> moduleDirectories(File project) throws java.io.IOException {
        List<File> directories = new ArrayList<>();
        for (ProjectModules.Module module : ProjectModules.load(project).all()) directories.add(module.directory);
        return directories;
    }

    /** Debug output for the build log: every address that is asked for and what came back. Files already in the cache never reach it. */
    private static final class LoggingFetcher implements MavenResolver.Fetcher {
        private final MavenResolver.Fetcher inner;
        private final BuildLogger logger;
        LoggingFetcher(MavenResolver.Fetcher inner, BuildLogger logger) { this.inner = inner; this.logger = logger; }

        @Override public String download(String url, File target, long maxBytes, DownloadMemoryPolicy policy) throws java.io.IOException {
            logger.onInfo("[download] GET " + url + " (chunks of " + (policy.getChunkBytes() >> 20) + " MB)");
            long start = System.currentTimeMillis();
            try {
                String sha1 = inner.download(url, target, maxBytes, policy);
                logger.onInfo("[download]   -> " + (sha1 == null ? "404, not in this repository" : "OK, " + target.length() + " bytes") + " (" + (System.currentTimeMillis() - start) + " ms)");
                return sha1;
            } catch (java.io.IOException failure) {
                logger.onInfo("[download]   -> FAILED after " + (System.currentTimeMillis() - start) + " ms: " + failure.getMessage());
                throw failure;
            }
        }

        @Override public byte[] get(String url, long maxBytes) throws java.io.IOException {
            logger.onInfo("[maven] GET " + url);
            long start = System.currentTimeMillis();
            try {
                byte[] data = inner.get(url, maxBytes);
                long elapsed = System.currentTimeMillis() - start;
                logger.onInfo("[maven]   -> " + (data == null ? "404, not in this repository" : "OK, " + data.length + " bytes") + " (" + elapsed + " ms)");
                return data;
            } catch (java.io.IOException failure) {
                logger.onInfo("[maven]   -> FAILED after " + (System.currentTimeMillis() - start) + " ms: " + failure.getMessage());
                throw failure;
            }
        }
    }

    /** annotationProcessor artifacts and their transitive jars. They run in the compiler, never reach the APK, and are stored as "processorJars". */
    private void resolveProcessors(BuildStageContext context, List<GradleDependencyParser.Declaration> processors,
                                   List<GradleDependencyParser.Platform> platforms, BuildLogger logger) throws Exception {
        if (!cacheRoot.isDirectory() && !cacheRoot.mkdirs()) throw new IllegalStateException("Could not create dependency cache: " + cacheRoot);
        if (logger != null) { logger.onInfo("Annotation processors: " + processors.size()); for (GradleDependencyParser.Declaration d : processors) logger.onInfo("[maven] processor " + d); }
        MavenResolver.Result result = resolve(context, processors, platforms, logger);
        for (String warning : result.warnings) if (logger != null) logger.onWarning(warning);
        List<File> jars = new ArrayList<>(result.jars);
        for (File aar : result.aarDirectories) jars.addAll(BuildProjectFiles.jarsIn(aar));
        context.putFiles("processorJars", jars);
        for (GradleDependencyParser.Declaration d : processors)
            if (d.group.equals("org.projectlombok") && logger != null) logger.onWarning("Lombok rewrites javac internals and cannot run on ECJ; expect missing getters/setters.");
    }

    private static void mergeDeclarations(List<GradleDependencyParser.Declaration> into, List<GradleDependencyParser.Declaration> more) {
        for (GradleDependencyParser.Declaration d : more) {
            boolean known = false;
            for (GradleDependencyParser.Declaration other : into) if (other.group.equals(d.group) && other.artifact.equals(d.artifact) && other.version.equals(d.version)
                && other.classifier.equals(d.classifier) && other.extension.equals(d.extension)) known = true;
            if (!known) into.add(d);
        }
    }

    /** classes.jar and libs/x.jar inside an extracted AAR get the AAR's name so logs and class-version errors are readable. */
    private static String displayName(File jar) {
        File parent = jar.getParentFile();
        if (parent == null) return jar.getName();
        if (jar.getName().equals("classes.jar")) return parent.getName() + ".jar";
        File grandParent = parent.getParentFile();
        if (parent.getName().equals("libs") && grandParent != null && grandParent.getName().endsWith("-aar")) return grandParent.getName() + "-libs-" + jar.getName();
        return jar.getName();
    }
}
