package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ManifestMerger;
import com.example.studiomobile.build.ManifestPackage;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Links the manifest and compiled resources into a resource APK and generates R.java.
 * With AAR libraries: their manifests are merged into the app manifest first, their compiled resources are passed to AAPT2 before the app's
 * (so the app wins conflicts), their packages go to --extra-packages so each library gets its R class, and their assets are merged into one -A folder.
 */
public final class ResourceLinkingStage implements BuildStage {
    private final File toolDirectory; private final BuildProcessRunner runner;
    public ResourceLinkingStage(File toolDirectory, BuildProcessRunner runner) { this.toolDirectory=toolDirectory; this.runner=runner; }
    @Override public String getName() { return "Resource linking (AAPT2 link)"; }
    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File manifest = BuildProjectFiles.manifest(context.getProjectDirectory());
        if (!manifest.isFile()) throw new IllegalStateException("AndroidManifest.xml is required to build an APK: " + manifest);
        // AGP-style manifests have no package attribute (the namespace lives in build.gradle); AAPT2 needs one.
        File appManifest = ManifestPackage.withPackage(manifest, context.getProjectDirectory(), new File(context.getWorkingDirectory(), "package-manifest"));
        manifest = mergeLibraryManifests(appManifest, context, logger);
        File androidJar = com.example.studiomobile.build.AndroidPlatformResolver.resolve(toolDirectory, context.getProjectDirectory());
        File aapt2 = com.example.studiomobile.build.BuildExecutableResolver.resolve(toolDirectory, "aapt2");
        if (aapt2 == null) throw new IllegalStateException("AAPT2 executable is missing or not executable. Checked build-tools/aapt2, bin/aapt2, and native/aapt2.");
        File resourceApk = new File(context.getWorkingDirectory(), "resources.ap_");
        File generatedSources = new File(context.getWorkingDirectory(), "generated-sources");
        if (!generatedSources.mkdirs() && !generatedSources.isDirectory()) throw new IllegalStateException("Could not create generated source directory.");
        String jarProblem = validateJar(androidJar);
        if (jarProblem != null) throw new IllegalStateException("android.jar is not usable by AAPT2: " + jarProblem + " (" + androidJar.getAbsolutePath() + "). Re-import that platform.");
        BuildProcessRunner.Result result = link(aapt2, androidJar, manifest, appManifest, resourceApk, generatedSources, context, logger);
        if (!result.isSuccessful()) diagnoseIncludeFailure(aapt2, androidJar, context, logger);
        if (!result.isSuccessful() || !resourceApk.isFile() || resourceApk.length()==0) throw new IllegalStateException("AAPT2 resource linking failed or produced no resources.ap_. If the log says 'failed to load include path', the bundled AAPT2 cannot read " + androidJar.getParentFile().getName() + "/android.jar: use a newer AAPT2 or re-import that platform.");
        context.putOutput("resourceApk", resourceApk); context.putOutput("generatedSources", generatedSources);
        File aaptRules = new File(context.getWorkingDirectory(), "aapt-proguard-rules.txt");
        if (aaptRules.isFile()) context.putOutput("aaptProguardRules", aaptRules);
    }
    private BuildProcessRunner.Result link(File aapt2, File androidJar, File manifest, File appManifest, File resourceApk, File generatedSources, BuildStageContext context, BuildLogger logger) throws Exception {
        List<String> command = new ArrayList<>(); command.add(aapt2.getAbsolutePath()); command.add("link"); command.add("-o"); command.add(resourceApk.getAbsolutePath());
        command.add("--manifest"); command.add(manifest.getAbsolutePath()); command.add("-I"); command.add(androidJar.getAbsolutePath());
        command.add("--java"); command.add(generatedSources.getAbsolutePath());
        command.add("--auto-add-overlay");
        String extraPackages = libraryPackages(appManifest, context);
        if (!extraPackages.isEmpty()) { command.add("--extra-packages"); command.add(extraPackages); if (logger != null) logger.onInfo("Generating R classes for library packages: " + extraPackages); }
        File assets = mergeAssets(context, logger);
        if (assets != null) { command.add("-A"); command.add(assets.getAbsolutePath()); if (logger != null) logger.onInfo("Packaging assets from " + assets.getAbsolutePath()); }
        // Game assets (.glb, .ktx, .spv, ...) must stay STORED so AssetManager.openFd()/mmap work; same as AGP's noCompress.
        for (String extension : com.example.studiomobile.build.AssetCompression.extensions(context.getProjectDirectory())) { command.add("-0"); command.add(extension); }
        // Like AGP: R stays in the namespace package, the installed package becomes applicationId when the two differ.
        String applicationId = BuildProjectFiles.readApplicationId(context.getProjectDirectory(), null);
        boolean injectedPackage = !appManifest.equals(BuildProjectFiles.manifest(context.getProjectDirectory()));
        if (injectedPackage && applicationId != null && !applicationId.equals(ManifestMerger.readPackage(appManifest))) {
            command.add("--rename-manifest-package"); command.add(applicationId);
            if (logger != null) logger.onInfo("Package " + ManifestMerger.readPackage(appManifest) + " is installed as applicationId " + applicationId + ".");
        }
        command.add("--min-sdk-version"); command.add(BuildProjectFiles.readNumericSdkValue(context.getProjectDirectory(), true, "23"));
        command.add("--target-sdk-version"); command.add(BuildProjectFiles.readNumericSdkValue(context.getProjectDirectory(), false, "36"));
        // Later -R archives override earlier ones (--auto-add-overlay): libraries first, the app last, so the app wins conflicts.
        for (File libraryResources : context.getFiles("libraryCompiledResources")) { command.add("-R"); command.add(libraryResources.getAbsolutePath()); }
        File compiledResources = context.getOutput("compiledResources"); if (compiledResources != null && compiledResources.isFile()) { command.add("-R"); command.add(compiledResources.getAbsolutePath()); }
        // Keep rules for manifest components and layout classes; used by the R8 stage when minify is on.
        command.add("--proguard"); command.add(new File(context.getWorkingDirectory(), "aapt-proguard-rules.txt").getAbsolutePath());
        return runner.run(command, context.getWorkingDirectory(), 180, logger);
    }
    /** Returns null when the jar is a readable ZIP holding what AAPT2 needs to load it as an include path, else the reason. */
    private static String validateJar(File jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            if (zip.getEntry("AndroidManifest.xml") == null) return "missing AndroidManifest.xml";
            if (zip.getEntry("resources.arsc") == null) return "missing resources.arsc";
            return null;
        } catch (java.io.IOException exception) { return "not a readable ZIP/JAR (" + exception.getMessage() + ")"; }
    }

    /** Returns the app manifest unchanged, or a merged copy in the working directory when AAR libraries contribute manifests. */
    private static File mergeLibraryManifests(File appManifest, BuildStageContext context, BuildLogger logger) throws IOException {
        List<File> libraryManifests = new ArrayList<>();
        for (File aar : context.getFiles("aarDirectories")) { File manifest = new File(aar, "AndroidManifest.xml"); if (manifest.isFile()) libraryManifests.add(manifest); }
        if (libraryManifests.isEmpty()) return appManifest;
        String applicationId = BuildProjectFiles.readApplicationId(context.getProjectDirectory(), ManifestMerger.readPackage(appManifest));
        File merged = new File(new File(context.getWorkingDirectory(), "merged-manifest"), "AndroidManifest.xml");
        int added = ManifestMerger.merge(appManifest, libraryManifests, applicationId, merged);
        if (logger != null) logger.onInfo("Merged " + added + " manifest element(s) from " + libraryManifests.size() + " AAR librar" + (libraryManifests.size() == 1 ? "y" : "ies") + " (applicationId " + applicationId + ").");
        return merged;
    }

    /** Colon-separated package names of the AAR libraries (the app's own package excluded) for --extra-packages. */
    private static String libraryPackages(File appManifest, BuildStageContext context) throws IOException {
        String appPackage = ManifestMerger.readPackage(appManifest);
        Set<String> packages = new LinkedHashSet<>();
        for (File aar : context.getFiles("aarDirectories")) {
            File manifest = new File(aar, "AndroidManifest.xml");
            String name = manifest.isFile() ? ManifestMerger.readPackage(manifest) : null;
            if (name != null && !name.equals(appPackage)) packages.add(name);
        }
        return String.join(":", packages);
    }

    /** The app's assets folder, or a merged copy (libraries first, app last, so the app wins) when an AAR has assets; null when there are none. */
    private static File mergeAssets(BuildStageContext context, BuildLogger logger) throws IOException {
        File appAssets = new File(BuildProjectFiles.mainDirectory(context.getProjectDirectory()), "assets");
        List<File> libraryAssets = new ArrayList<>();
        for (File aar : context.getFiles("aarDirectories")) { File assets = new File(aar, "assets"); if (assets.isDirectory()) libraryAssets.add(assets); }
        File generated = context.getOutput(JsxTranspileStage.OUTPUT_KEY); // .js made from the app's .jsx/.tsx/.ts, applied last so it wins
        if (generated != null && !generated.isDirectory()) generated = null;
        if (libraryAssets.isEmpty() && generated == null) return appAssets.isDirectory() ? appAssets : null;
        File merged = new File(context.getWorkingDirectory(), "merged-assets");
        if (!merged.isDirectory() && !merged.mkdirs()) throw new IOException("Could not create " + merged);
        for (File assets : libraryAssets) copyTree(assets, merged);
        if (appAssets.isDirectory()) copyTree(appAssets, merged);
        if (generated != null) copyTree(generated, merged);
        if (logger != null) logger.onInfo("Merged assets of " + libraryAssets.size() + " AAR librar" + (libraryAssets.size() == 1 ? "y" : "ies") + (appAssets.isDirectory() ? " and the app" : "") + (generated != null ? " plus the generated JavaScript." : "."));
        return merged;
    }

    private static void copyTree(File source, File target) throws IOException {
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) { if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Could not create " + destination); copyTree(child, destination); }
            else Files.copy(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Prints, in the on-screen log, why AAPT2 may reject this android.jar: jar facts, AAPT2 version, and AAPT2's own parse error from "dump packagename". */
    private void diagnoseIncludeFailure(File aapt2, File androidJar, BuildStageContext context, BuildLogger logger) {
        if (logger == null) return;
        try {
            logger.onInfo("--- Diagnosis ---");
            logger.onInfo("android.jar: " + androidJar.getAbsolutePath() + " (" + androidJar.length() + " bytes)");
            logger.onInfo("aapt2: " + aapt2.getAbsolutePath() + " (" + aapt2.length() + " bytes)");
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(androidJar)) {
                for (String name : new String[] {"AndroidManifest.xml", "resources.arsc"}) {
                    java.util.zip.ZipEntry entry = zip.getEntry(name);
                    logger.onInfo(name + ": " + (entry == null ? "MISSING" : entry.getSize() + " bytes, compressed " + entry.getCompressedSize() + ", method " + (entry.getMethod() == java.util.zip.ZipEntry.STORED ? "STORED" : entry.getMethod() == java.util.zip.ZipEntry.DEFLATED ? "DEFLATED" : String.valueOf(entry.getMethod()))));
                }
            }
            logger.onInfo("aapt2 version:");
            runner.run(java.util.Arrays.asList(aapt2.getAbsolutePath(), "version"), context.getWorkingDirectory(), 30, logger);
            logger.onInfo("aapt2 dump packagename (shows AAPT2's own parse error):");
            runner.run(java.util.Arrays.asList(aapt2.getAbsolutePath(), "dump", "packagename", androidJar.getAbsolutePath()), context.getWorkingDirectory(), 60, logger);
            logger.onInfo("--- End diagnosis ---");
        } catch (Exception exception) { logger.onWarning("Diagnosis failed: " + exception); }
    }
}
