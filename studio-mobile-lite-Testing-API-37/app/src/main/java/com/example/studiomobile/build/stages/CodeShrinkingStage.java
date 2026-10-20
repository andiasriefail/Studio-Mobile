package com.example.studiomobile.build.stages;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.Origin;
import com.example.studiomobile.build.AndroidPlatformResolver;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ProguardSettings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Shrinks, obfuscates and dexes the program with the in-process R8 API.
 * Used INSTEAD of {@link DexCompilationStage} when the module enables minify (see {@link ProguardSettings}).
 * It produces the same "dexDirectory" output, so packaging, alignment and signing are unchanged.
 *
 * Keep rules come from, in order: Studio Mobile's built-in defaults (only when proguardFiles asks for getDefaultProguardFile),
 * the AAPT2-generated rules (Activities, Services, layout views...), each AAR's proguard.txt, then the project's own rule files.
 */
public final class CodeShrinkingStage implements BuildStage {
    private static final int MAX_LOGGED_WARNINGS = 20;
    private final File tools;

    public CodeShrinkingStage(File tools) { this.tools = tools; }
    @Override public String getName() { return "Code shrinking (in-process R8)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File project = context.getProjectDirectory();
        ProguardSettings settings = ProguardSettings.read(project);
        if (logger != null) for (String warning : settings.getWarnings()) logger.onWarning(warning);
        if (!settings.isMinifyEnabled())
            throw new IllegalStateException("Code shrinking was selected but minifyEnabled is not true in the module Gradle file.");
        if (!settings.getMissingRuleFiles().isEmpty())
            throw new IllegalStateException("proguardFiles lists files that do not exist: " + settings.getMissingRuleFiles());

        // Without these rules R8 would remove the components declared in the manifest and the APK would crash on launch.
        File aaptRules = context.getOutput("aaptProguardRules");
        if (aaptRules == null || !aaptRules.isFile())
            throw new IllegalStateException("AAPT2 did not produce keep rules (output 'aaptProguardRules'). Resource linking must pass --proguard.");

        File androidJar = AndroidPlatformResolver.resolve(tools, project);
        List<File> programInputs = DexCompilationStage.collectProgramInputs(tools, context);
        int minSdk = DexCompilationStage.readMinSdk(project);

        File dexDirectory = new File(context.getWorkingDirectory(), "dex");
        if (!dexDirectory.mkdirs() && !dexDirectory.isDirectory()) throw new IllegalStateException("Could not create DEX directory.");
        File mapping = new File(context.getWorkingDirectory(), "r8-mapping.txt");

        List<Path> ruleFiles = new ArrayList<>();
        ruleFiles.add(aaptRules.toPath());
        for (File aar : context.getFiles("aarDirectories")) {
            File consumerRules = new File(aar, "proguard.txt");
            if (consumerRules.isFile()) ruleFiles.add(consumerRules.toPath());
        }
        for (File rules : settings.getRuleFiles()) ruleFiles.add(rules.toPath());
        if (logger != null) logger.onInfo("R8 rule files: " + ruleFiles.size() + (settings.getBuiltInRules().isEmpty() ? "" : " + built-in defaults") + ".");

        CollectingHandler handler = new CollectingHandler(logger);
        R8Command.Builder builder = R8Command.builder(handler)
            .setMode(CompilationMode.RELEASE)
            .setMinApiLevel(minSdk)
            .setOutput(dexDirectory.toPath(), OutputMode.DexIndexed)
            .addLibraryFiles(androidJar.toPath())
            .addProguardConfigurationFiles(ruleFiles)
            .setProguardMapOutputPath(mapping.toPath());
        if (!settings.getBuiltInRules().isEmpty()) builder.addProguardConfiguration(settings.getBuiltInRules(), Origin.unknown());
        for (File input : programInputs) builder.addProgramFiles(input.toPath());

        try { R8.run(builder.build()); }
        catch (CompilationFailedException error) {
            throw new IllegalStateException("R8 failed. " + handler.errors()
                + " If it reports missing classes, add -dontwarn rules to your proguard-rules.pro.", error);
        }
        File classesDex = new File(dexDirectory, "classes.dex");
        if (!classesDex.isFile() || classesDex.length() == 0)
            throw new IllegalStateException("R8 finished but did not produce classes.dex. " + handler.errors());
        context.putOutput("dexDirectory", dexDirectory);
        context.putOutput("proguardMapping", mapping);
        keepMapping(mapping, context.getOutputDirectory(), logger);
    }

    /** Copies mapping.txt next to the APK so obfuscated stack traces can be decoded later. A failure here never fails the build. */
    private static void keepMapping(File mapping, File outputDirectory, BuildLogger logger) {
        if (!mapping.isFile() || outputDirectory == null) return;
        try {
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) throw new IOException("could not create " + outputDirectory);
            Files.copy(mapping.toPath(), new File(outputDirectory, "mapping.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (logger != null) logger.onInfo("R8 mapping saved as mapping.txt next to the APK.");
        } catch (IOException error) {
            if (logger != null) logger.onWarning("Could not save mapping.txt: " + error.getMessage());
        }
    }

    /** Collects R8 errors for the failure message and forwards a limited number of warnings to the build log. */
    private static final class CollectingHandler implements DiagnosticsHandler {
        private final BuildLogger logger;
        private final StringBuilder errors = new StringBuilder();
        private int warnings;
        CollectingHandler(BuildLogger logger) { this.logger = logger; }
        @Override public synchronized void error(Diagnostic diagnostic) {
            if (errors.length() < 4000) errors.append(diagnostic.getDiagnosticMessage()).append(' ');
        }
        @Override public synchronized void warning(Diagnostic diagnostic) {
            if (logger == null) return;
            if (++warnings <= MAX_LOGGED_WARNINGS) logger.onWarning("R8: " + diagnostic.getDiagnosticMessage());
            else if (warnings == MAX_LOGGED_WARNINGS + 1) logger.onWarning("R8: further warnings suppressed.");
        }
        synchronized String errors() { return errors.toString().trim(); }
    }
}
