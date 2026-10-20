package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.CrashRiskAnalyzer;
import com.example.studiomobile.build.ManifestPackage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Stops the build when the compiled app is certain to crash at runtime, so no APK is produced for it. */
public final class CrashRiskCheckStage implements BuildStage {
    @Override public String getName() { return "Crash check"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File classes = context.getOutput("classesDirectory");
        if (classes == null || !classes.isDirectory()) return;
        File manifest = BuildProjectFiles.manifest(context.getProjectDirectory());
        // Class names like ".MainActivity" are relative to the package (namespace), not to applicationId.
        String applicationId = ManifestPackage.effectivePackage(context.getProjectDirectory(), manifest);
        List<File> sources = sourceFiles(context.getProjectDirectory());
        List<String> problems = new ArrayList<>(CrashRiskAnalyzer.findMissingManifestComponents(manifest, classes, applicationId));
        problems.addAll(CrashRiskAnalyzer.findUndeclaredActivities(manifest, sources, applicationId));
        problems.addAll(CrashRiskAnalyzer.findMissingExported(manifest, targetSdk(context.getProjectDirectory())));
        String internetWarning = CrashRiskAnalyzer.findMissingInternetPermission(manifest, sources);
        if (internetWarning != null && logger != null) logger.onWarning(internetWarning);
        if (problems.isEmpty()) return;
        StringBuilder message = new StringBuilder("App would crash at runtime; build stopped, no APK was generated.");
        for (String problem : problems) message.append("\n- ").append(problem);
        throw new IllegalStateException(message.toString());
    }

    private static List<File> sourceFiles(File project) {
        File main = BuildProjectFiles.mainDirectory(project);
        List<File> files = new ArrayList<>();
        files.addAll(BuildProjectFiles.filesWithExtension(new File(main, "java"), ".java"));
        files.addAll(BuildProjectFiles.filesWithExtension(new File(main, "java"), ".kt"));
        files.addAll(BuildProjectFiles.filesWithExtension(new File(main, "kotlin"), ".kt"));
        return files;
    }

    private static int targetSdk(File project) {
        try { return Integer.parseInt(BuildProjectFiles.readNumericSdkValue(project, false, "0")); }
        catch (NumberFormatException notANumber) { return 0; }
    }
}
