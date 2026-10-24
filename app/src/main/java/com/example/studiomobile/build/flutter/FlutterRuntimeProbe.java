package com.example.studiomobile.build.flutter;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts the bundled Flutter executables once ({@code --version}) and looks at what the unpacked SDK contains. Whether Android lets
 * these binaries run, and whether {@code dart} finds its SDK from the native library directory, cannot be known without trying on a
 * phone, so the result is written to the build log in plain words instead of failing later with an unclear error.
 */
public final class FlutterRuntimeProbe {
    private static final String TAG = "[flutter-check] ";
    private static final long TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LINES = 12;

    private FlutterRuntimeProbe() { }

    public static final class Report {
        private final boolean runnable;
        private final String summary;
        Report(boolean runnable, String summary) { this.runnable = runnable; this.summary = summary; }
        /** True when {@code dart --version} and {@code gen_snapshot --version} both ran and exited with 0. */
        public boolean isRunnable() { return runnable; }
        public String getSummary() { return summary; }
    }

    private static final class Collector implements BuildLogger {
        final List<String> lines = new ArrayList<>();
        @Override public void onInfo(String message) { if (lines.size() < MAX_OUTPUT_LINES) lines.add(message); }
        @Override public void onWarning(String message) { onInfo(message); }
        @Override public void onError(String message, Throwable cause) { /* the exit code is reported separately */ }
    }

    public static Report run(File nativeLibraryDirectory, File sdkRoot, File workDirectory, BuildLogger logger) {
        File dart, genSnapshot, impellerc;
        try {
            dart = FlutterSdkProvisioner.bundled(nativeLibraryDirectory, FlutterSdkProvisioner.DART_LIBRARY);
            genSnapshot = FlutterSdkProvisioner.bundled(nativeLibraryDirectory, FlutterSdkProvisioner.GEN_SNAPSHOT_LIBRARY);
            impellerc = FlutterSdkProvisioner.bundled(nativeLibraryDirectory, FlutterSdkProvisioner.IMPELLERC_LIBRARY);
        } catch (IOException missing) {
            logger.onInfo(TAG + missing.getMessage());
            return new Report(false, missing.getMessage());
        }
        logger.onInfo(TAG + "Bundled executables: " + dart.getName() + ", " + genSnapshot.getName() + ", " + impellerc.getName());

        Map<String, String> environment = new LinkedHashMap<>();
        File home = new File(workDirectory, "home"), temp = new File(workDirectory, "tmp"), pubCache = new File(workDirectory, "pub-cache");
        home.mkdirs(); temp.mkdirs(); pubCache.mkdirs();
        environment.put("FLUTTER_ROOT", sdkRoot.getAbsolutePath());
        environment.put("HOME", home.getAbsolutePath());
        environment.put("TMPDIR", temp.getAbsolutePath());
        environment.put("PUB_CACHE", pubCache.getAbsolutePath());
        environment.put("FLUTTER_SUPPRESS_ANALYTICS", "true");
        environment.put("CI", "true");

        logSdkFacts(sdkRoot, logger);

        boolean dartRuns = execute("dart --version", Arrays.asList(dart.getAbsolutePath(), "--version"), sdkRoot, environment, logger);
        execute("dart pub --version", Arrays.asList(dart.getAbsolutePath(), "pub", "--version"), sdkRoot, environment, logger);
        boolean snapshotRuns = execute("gen_snapshot --version", Arrays.asList(genSnapshot.getAbsolutePath(), "--version"), sdkRoot, environment, logger);

        boolean runnable = dartRuns && snapshotRuns;
        String summary = runnable
            ? "dart and gen_snapshot start on this device. Check the 'dart pub --version' line above: it shows whether dart finds its SDK from the native library folder."
            : "These executables did not start on this device (" + (dartRuns ? "" : "dart ") + (snapshotRuns ? "" : "gen_snapshot") + "). The lines above show the exact error.";
        return new Report(runnable, summary);
    }

    private static boolean execute(String label, List<String> command, File workingDirectory, Map<String, String> environment, BuildLogger logger) {
        Collector collector = new Collector();
        try {
            BuildProcessRunner.Result result = new BuildProcessRunner().run(command, workingDirectory, TIMEOUT_SECONDS, collector, environment);
            logger.onInfo(TAG + label + " -> " + (result.isTimedOut() ? "timed out" : "exit code " + result.getExitCode()));
            for (String line : collector.lines) logger.onInfo(TAG + "    " + line);
            return result.isSuccessful();
        } catch (IOException failure) {
            logger.onInfo(TAG + label + " -> could not be started: " + failure.getMessage());
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.onInfo(TAG + label + " -> interrupted");
            return false;
        }
    }

    private static void logSdkFacts(File sdkRoot, BuildLogger logger) {
        String[] paths = {
            "bin/flutter", "bin/cache/flutter_tools.snapshot", "bin/cache/flutter_tools.stamp",
            "packages/flutter_tools/.dart_tool/package_config.json",
            "bin/cache/dart-sdk/bin/dart", "bin/cache/dart-sdk/bin/dartaotruntime",
            "bin/cache/dart-sdk/bin/snapshots/dartdev_aot.dart.snapshot",
            "bin/cache/artifacts/engine/linux-arm64/impellerc", "bin/cache/artifacts/engine/linux-arm64/const_finder.dart.snapshot"
        };
        for (String relative : paths) logger.onInfo(TAG + (new File(sdkRoot, relative).exists() ? "present  " : "absent   ") + relative);
        File launcher = new File(sdkRoot, "bin/flutter");
        if (launcher.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(launcher.toPath(), StandardCharsets.UTF_8)) {
                logger.onInfo(TAG + "bin/flutter starts with: " + reader.readLine());
            } catch (IOException ignored) { /* informational only */ }
        }
    }
}
