package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ManifestPackage;
import com.example.studiomobile.build.ProjectModules;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-module support for resources and manifests.
 *
 * Every Android library module of the project (found through settings.gradle and the app's {@code project(":x")} dependencies) is
 * staged as a folder that looks like an extracted AAR (AndroidManifest.xml with a package, res/, assets/, jni/). It is appended to the
 * "aarDirectories" list, after the Maven AARs and in dependency order, so the existing stages handle it like any other library:
 * AAPT2 compiles its resources, the manifest is merged, its package gets an R class, assets and native libraries are packaged,
 * and the app still wins every resource conflict.
 *
 * Library sources are not compiled here: {@link SourceCompilationStage} compiles all modules together, after linking, because the
 * libraries' R classes are produced by the app's single link step.
 */
public final class ProjectModulesStage implements BuildStage {
    @Override public String getName() { return "Project modules (multi-module)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        ProjectModules modules = ProjectModules.load(context.getProjectDirectory());
        for (String warning : modules.warnings) if (logger != null) logger.onWarning(warning);
        if (!modules.isMultiModule()) { if (logger != null) logger.onInfo("Single-module project (" + modules.app + ")."); return; }
        if (logger != null) { logger.onInfo("Application module: " + modules.app); for (ProjectModules.Module library : modules.libraries) logger.onInfo("  library module: " + library); }

        File root = new File(context.getWorkingDirectory(), "module-libraries");
        List<File> directories = new ArrayList<>(context.getFiles("aarDirectories"));
        int staged = 0;
        for (ProjectModules.Module library : modules.androidLibraries()) {
            if (library.namespace == null)
                throw new IllegalStateException("Library module " + library.path + " declares neither namespace nor a manifest package; the resources of a module need a package.");
            File directory = new File(root, library.path.substring(1).replace(':', '-') + "-module");
            if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Could not create " + directory);
            stageManifest(library, directory);
            for (File resources : library.resourceDirectories()) copyTree(resources, new File(directory, "res"));
            copyTree(new File(library.mainDirectory(), "assets"), new File(directory, "assets"));
            copyTree(new File(library.mainDirectory(), "jniLibs"), new File(directory, "jni"));
            directories.add(directory);
            staged++;
        }
        context.putFiles("aarDirectories", directories);
        if (logger != null) logger.onInfo("Staged " + staged + " Android library module(s) for resource merging.");
    }

    private static void stageManifest(ProjectModules.Module library, File directory) throws IOException {
        File target = new File(directory, "AndroidManifest.xml");
        File manifest = library.manifest();
        if (!manifest.isFile()) {
            String minimal = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + library.namespace + "\" />\n";
            Files.write(target.toPath(), minimal.getBytes(StandardCharsets.UTF_8));
            return;
        }
        File prepared = ManifestPackage.withPackage(manifest, library.namespace, directory);
        if (!prepared.equals(target)) Files.copy(prepared.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(File source, File target) throws IOException {
        File[] children = source.listFiles();
        if (children == null) return;
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create " + target);
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) copyTree(child, destination);
            else Files.copy(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
