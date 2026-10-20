package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildConfigGenerator;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ProjectModules;
import com.example.studiomobile.build.ViewBindingGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Writes the sources AGP would generate before compilation: {@code BuildConfig} per module and View Binding classes per layout.
 * Runs after resource linking (the generated code refers to R) and writes into the same "generatedSources" folder that holds R.java,
 * so {@link SourceCompilationStage} and the Kotlin compiler's Java source roots pick the files up without further wiring.
 *
 * BuildConfig is generated for a module when buildFeatures.buildConfig is on (or the AGP 7 gradle.properties default is set), when its
 * own sources mention BuildConfig, or when another module imports {@code <namespace>.BuildConfig}. A BuildConfig class that the project
 * already contains is never overwritten. View Binding is generated only where viewBinding is enabled.
 */
public final class GeneratedSourcesStage implements BuildStage {
    private static final Pattern QUALIFIED_BUILD_CONFIG = Pattern.compile("([A-Za-z_][\\w.]*)\\.BuildConfig\\b");

    @Override public String getName() { return "Generated sources (BuildConfig, View Binding)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File out = context.getOutput("generatedSources");
        if (out == null) throw new IllegalStateException("Generated source directory is missing; resource linking must run first.");
        ProjectModules modules = ProjectModules.load(context.getProjectDirectory());
        List<ProjectModules.Module> android = new ArrayList<>();
        for (ProjectModules.Module module : modules.all()) if (module.isAndroid()) android.add(module);

        // One pass over all sources: which modules mention BuildConfig, which packages are imported from elsewhere, who defines their own.
        Set<String> mentioning = new HashSet<>(), referencedPackages = new HashSet<>(), defined = new HashSet<>();
        for (ProjectModules.Module module : modules.all()) scan(module, mentioning, referencedPackages, defined);

        String applicationId = BuildProjectFiles.readApplicationId(context.getProjectDirectory(), modules.app.namespace);
        int buildConfigs = 0;
        for (ProjectModules.Module module : android) {
            if (module.namespace == null) { if (logger != null) logger.onWarning("Module " + module.path + " has no namespace/package; BuildConfig and View Binding are skipped for it."); continue; }
            boolean wanted = module.buildConfigRequested || mentioning.contains(module.path) || referencedPackages.contains(module.namespace);
            if (!wanted || defined.contains(module.path)) continue;
            write(out, module.namespace.replace('.', '/') + "/BuildConfig.java", BuildConfigGenerator.generate(module, applicationId));
            buildConfigs++;
        }
        if (logger != null && buildConfigs > 0) logger.onInfo("Generated BuildConfig for " + buildConfigs + " module(s) (DEBUG=true, BUILD_TYPE=debug).");

        List<ProjectModules.Module> binding = new ArrayList<>();
        for (ProjectModules.Module module : android) if (module.viewBinding) binding.add(module);
        if (binding.isEmpty()) return;
        List<File> classpath = new ArrayList<>(BuildProjectFiles.localLibraries(context.getProjectDirectory()));
        classpath.addAll(BuildProjectFiles.jarsIn(context.getOutput("dependencyDirectory")));
        boolean hasApi = containsClass(classpath, "androidx/viewbinding/ViewBinding.class");
        boolean hasAnnotations = containsClass(classpath, "androidx/annotation/NonNull.class");
        ViewBindingGenerator generator = new ViewBindingGenerator(hasAnnotations, !hasApi);
        Map<String, String> files = generator.generate(binding);
        for (String warning : generator.getWarnings()) if (logger != null) logger.onWarning(warning);
        for (Map.Entry<String, String> file : files.entrySet()) write(out, file.getKey(), file.getValue());
        if (logger != null) logger.onInfo("Generated " + (files.size() - (hasApi || files.isEmpty() ? 0 : 1)) + " View Binding class(es) for " + binding.size() + " module(s)"
            + (hasApi ? "." : " (androidx.viewbinding.ViewBinding was not a dependency, so a stand-in interface was generated)."));
    }

    private static void scan(ProjectModules.Module module, Set<String> mentioning, Set<String> referencedPackages, Set<String> defined) throws IOException {
        List<File> roots = new ArrayList<>(module.javaRoots()); roots.addAll(module.kotlinRoots());
        for (File root : roots) {
            for (String extension : new String[] { ".java", ".kt" }) {
                for (File source : BuildProjectFiles.filesWithExtension(root, extension)) {
                    if (source.getName().equals("BuildConfig" + extension)) { defined.add(module.path); continue; }
                    if (source.length() > 4 * 1024 * 1024) continue;
                    String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
                    if (!text.contains("BuildConfig")) continue;
                    Matcher matcher = QUALIFIED_BUILD_CONFIG.matcher(text);
                    boolean qualified = false;
                    while (matcher.find()) { referencedPackages.add(matcher.group(1)); qualified = true; }
                    // A bare "BuildConfig" in a module's own code refers to the BuildConfig of its own package unless it was imported.
                    if (!qualified || !Pattern.compile("\\bimport\\s+[\\w.]+\\.BuildConfig\\b").matcher(text).find()) mentioning.add(module.path);
                }
            }
        }
    }

    private static boolean containsClass(List<File> jars, String entry) {
        for (File jar : jars) {
            if (!jar.isFile()) continue;
            try (ZipFile zip = new ZipFile(jar)) { if (zip.getEntry(entry) != null) return true; }
            catch (IOException unreadable) { /* a broken jar is reported by the stages that need it */ }
        }
        return false;
    }

    private static void write(File root, String relative, String source) throws IOException {
        File target = new File(root, relative);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        Files.write(target.toPath(), source.getBytes(StandardCharsets.UTF_8));
    }
}
