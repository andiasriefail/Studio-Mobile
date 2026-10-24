package com.example.studiomobile.build.flutter;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import com.example.studiomobile.build.BuildConfiguration;
import com.example.studiomobile.build.BuildExecutableResolver;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildPipeline;
import com.example.studiomobile.build.BuildResult;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Build route for Flutter projects. This first version makes sure the Flutter SDK is on the device and checks that its executables run,
 * and says so in the build log; it does not produce an APK yet. The APK step is added once the check shows what Android allows.
 */
public final class FlutterBuildPipeline implements BuildPipeline {
    private final Context context;
    private volatile boolean cancelled;
    private volatile FlutterSdkProvisioner provisioner;

    public FlutterBuildPipeline(Context context) {
        this.context = context.getApplicationContext();
        BuildExecutableResolver.registerNativeLibraryDirectory(new File(this.context.getApplicationInfo().nativeLibraryDir));
    }

    @Override public BuildResult build(BuildConfiguration configuration, BuildLogger logger) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("FlutterBuildPipeline.build() must not be called on the main thread; it downloads and installs toolchain components.");
        cancelled = false;
        FlutterProjectDetector.Detection project = FlutterProjectDetector.detect(configuration.getProjectDirectory());
        if (!project.isFlutter()) return failed("This folder is not a Flutter project (pubspec.yaml with the Flutter SDK dependency was not found).");
        if (project.getProblem() != null) return failed(project.getProblem());
        List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);
        if (!abis.contains("arm64-v8a")) return failed("Flutter builds need an arm64 phone. This device supports: " + abis + ".");

        logger.onInfo("[step] Checking the build tools");
        BuildToolCatalog catalog = null;
        try { catalog = BuildToolCatalog.readVerified(context.getAssets().open("build-tools/catalog.json"), BuildToolCatalog.BUNDLED_CATALOG_SHA256); }
        catch (Exception ignored) { /* no trusted catalog: the provisioner reports that nothing can be installed */ }
        File filesDirectory = context.getFilesDir();
        FlutterSdkProvisioner sdkProvisioner = new FlutterSdkProvisioner(filesDirectory, catalog, abis);
        provisioner = sdkProvisioner;
        File sdk;
        try { sdk = sdkProvisioner.install(new File(filesDirectory, "build-tools"), logger); }
        catch (IOException failure) { return cancelled ? cancelledResult() : failed(failure.getMessage()); }
        if (cancelled) return cancelledResult();
        if (sdk == null) return failed("The Flutter SDK was not downloaded, so the Flutter project cannot be built. Run the build again and allow the download.");

        logger.onInfo("[step] Checking the Flutter runtime");
        File nativeLibraries = new File(context.getApplicationInfo().nativeLibraryDir);
        FlutterRuntimeProbe.Report report = FlutterRuntimeProbe.run(nativeLibraries, sdk, new File(filesDirectory, "flutter-work"), logger);
        logger.onInfo("[flutter-check] " + report.getSummary());
        return failed(report.isRunnable()
            ? "The Flutter SDK is installed and its tools start. Building the APK is not available yet in this version; the check result is in Build Logs."
            : "The Flutter SDK is installed, but its tools do not start on this device. See Build Logs ([flutter-check] lines).");
    }

    @Override public void cancel() {
        cancelled = true;
        FlutterSdkProvisioner current = provisioner;
        if (current != null) current.cancel();
    }

    private static BuildResult cancelledResult() { return new BuildResult(BuildResult.Status.CANCELLED, null, "Flutter build cancelled."); }
    private static BuildResult failed(String message) { return new BuildResult(BuildResult.Status.FAILED, null, message); }
}
