package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Compiles the res/ folder of every AAR library and then the app's own resources with the device-ABI AAPT2 executable. */
public final class ResourceCompilationStage implements BuildStage {
    private final File toolDirectory; private final BuildProcessRunner processRunner; private final File libraryCache;
    public ResourceCompilationStage(File toolDirectory, BuildProcessRunner processRunner) { this(toolDirectory, processRunner, null); }
    public ResourceCompilationStage(File toolDirectory, BuildProcessRunner processRunner, File libraryCache) {
        if (toolDirectory == null || processRunner == null) throw new IllegalArgumentException("Tool directory and process runner are required.");
        this.toolDirectory = toolDirectory; this.processRunner = processRunner; this.libraryCache = libraryCache;
    }
    @Override public String getName() { return "Resource compilation (AAPT2 compile)"; }
    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File resourceDirectory = new File(BuildProjectFiles.mainDirectory(context.getProjectDirectory()), "res");
        File compiledResources = new File(context.getWorkingDirectory(), "compiled-resources.zip");
        File aapt2 = com.example.studiomobile.build.BuildExecutableResolver.resolve(toolDirectory, "aapt2");
        if (aapt2 == null) throw new IllegalStateException("AAPT2 executable is missing or not executable. Checked build-tools/aapt2, bin/aapt2, and native/aapt2.");
        context.putFiles("libraryCompiledResources", compileLibraryResources(aapt2, context, logger));
        if (!resourceDirectory.isDirectory()) { if (logger != null) logger.onInfo("No res directory found; linking will use the manifest only."); context.putOutput("compiledResources", null); return; }
        if (!compiledResources.getParentFile().isDirectory() && !compiledResources.getParentFile().mkdirs()) throw new IllegalStateException("Could not create resource compilation directory.");
        BuildProcessRunner.Result result = processRunner.run(Arrays.asList(aapt2.getAbsolutePath(), "compile", "--dir", resourceDirectory.getAbsolutePath(), "-o", compiledResources.getAbsolutePath()), context.getWorkingDirectory(), 180, logger);
        if (!result.isSuccessful()) throw new IllegalStateException("AAPT2 resource compilation failed.");
        if (!compiledResources.isFile() || compiledResources.length() == 0) throw new IllegalStateException("AAPT2 returned success but produced no compiled resource archive.");
        context.putOutput("compiledResources", compiledResources);
    }

    /** One compiled archive per AAR that has resource files, in the order of the "aarDirectories" list (lowest priority first). */
    private List<File> compileLibraryResources(File aapt2, BuildStageContext context, BuildLogger logger) throws Exception {
        List<File> compiled = new ArrayList<>();
        File output = new File(context.getWorkingDirectory(), "library-resources");
        for (File aar : context.getFiles("aarDirectories")) {
            File resources = new File(aar, "res");
            if (!containsFiles(resources)) continue;
            File cached = libraryCache == null ? null : cacheFile(aapt2, aar);
            if (cached != null && cached.isFile() && cached.length() > 0) {
                if (logger != null) logger.onInfo("Library resources from the compile cache: " + aar.getName());
                compiled.add(cached);
                continue;
            }
            if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Could not create " + output);
            File archive = new File(output, String.format("%03d-%s.zip", compiled.size(), aar.getName()));
            if (logger != null) logger.onInfo("Compiling library resources: " + aar.getName());
            BuildProcessRunner.Result result = processRunner.run(Arrays.asList(aapt2.getAbsolutePath(), "compile", "--dir", resources.getAbsolutePath(), "-o", archive.getAbsolutePath()), context.getWorkingDirectory(), 180, logger);
            if (!result.isSuccessful()) throw new IllegalStateException("AAPT2 could not compile the resources of library " + aar.getName() + ".");
            if (!archive.isFile() || archive.length() == 0) throw new IllegalStateException("AAPT2 returned success but produced no compiled resources for library " + aar.getName() + ".");
            compiled.add(cached != null && store(archive, cached) ? cached : archive);
        }
        return compiled;
    }

    private File cacheFile(File aapt2, File aar) {
        String key = aar.getAbsolutePath() + "|" + aapt2.length() + "|" + aapt2.lastModified() + "|" + new File(aar, ".extracted").lastModified();
        return new File(libraryCache, aar.getName() + "-" + Integer.toHexString(key.hashCode()) + ".zip");
    }

    private boolean store(File archive, File cached) {
        File temporary = new File(cached.getParentFile(), cached.getName() + ".tmp");
        try {
            if (!cached.getParentFile().isDirectory() && !cached.getParentFile().mkdirs()) return false;
            java.nio.file.Files.copy(archive.toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (temporary.renameTo(cached)) return true;
        } catch (java.io.IOException ignored) { }
        temporary.delete();
        return false;
    }

    private static boolean containsFiles(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return false;
        for (File child : children) if (child.isFile() || containsFiles(child)) return true;
        return false;
    }
}
