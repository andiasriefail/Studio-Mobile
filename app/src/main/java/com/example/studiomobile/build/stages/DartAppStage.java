package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.DeviceAbi;
import com.example.studiomobile.dart.DartRuntime;
import com.example.studiomobile.dart.DartScriptRunner;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs a Dart program into the app. When the project has {@code src/main/dart/main.dart}:
 * <ul>
 *   <li>the program is compiled with the Dart front end to a self-contained kernel file, stored in the APK as {@code assets/dart/main.dill};</li>
 *   <li>the Dart VM ({@code libdartvm.so}, the one that ships inside Studio Mobile) is added as {@code lib/arm64-v8a/libdartvm.so};</li>
 *   <li>the class {@code studio.dart.StudioDart} is generated, so the app's Java/Kotlin code can start the program, read what it prints and
 *       send it lines (see {@link com.example.studiomobile.build.DartHelperSource}).</li>
 * </ul>
 * Runs before the Java/Kotlin compilation, because the app's code may call the generated class. Projects without Dart files are not affected.
 * Only the Dart core libraries are available: there is no package download, and no Flutter.
 */
public final class DartAppStage implements BuildStage {
    public static final String NATIVE_OUTPUT_KEY = "dartNativeLibsDirectory";
    public static final String PACKAGE_OUTPUT_KEY = "dartPackageDirectory";
    private static final long COMPILE_TIMEOUT_SECONDS = 600;
    private static final String ABI = "arm64-v8a";

    /** Downloads and installs the Dart SDK files once. Returns false when that failed (the reason was sent to the logger). */
    public interface SdkInstaller { boolean install(BuildLogger logger) throws Exception; }

    private final File filesDirectory, nativeLibraryDirectory, cacheDirectory;
    private final SdkInstaller installer;

    public DartAppStage(File filesDirectory, File nativeLibraryDirectory, File cacheDirectory, SdkInstaller installer) {
        this.filesDirectory = filesDirectory; this.nativeLibraryDirectory = nativeLibraryDirectory; this.cacheDirectory = cacheDirectory; this.installer = installer;
    }

    @Override public String getName() { return "Dart compilation"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File dartDirectory = new File(BuildProjectFiles.mainDirectory(context.getProjectDirectory()), "dart");
        List<File> sources = BuildProjectFiles.filesWithExtension(dartDirectory, ".dart");
        if (sources.isEmpty()) return;
        File entry = new File(dartDirectory, "main.dart");
        if (!entry.isFile()) throw new IllegalStateException("src/main/dart has Dart files but no main.dart. The program starts from src/main/dart/main.dart.");

        String unsupported = DeviceAbi.requireArm64("Dart inside the app", DeviceAbi.supportedAbis());
        if (unsupported != null) throw new IllegalStateException(unsupported);
        rejectUnextractedLibraries(context.getProjectDirectory());
        File generated = context.getOutput("generatedSources");
        if (generated == null) throw new IllegalStateException("Generated source directory is missing; resource linking must run first.");

        File home = new File(filesDirectory, "dart-sdk");
        if (!DartRuntime.isBundled(nativeLibraryDirectory))
            throw new IllegalStateException("This build of Studio Mobile does not contain the Dart runtime, so Dart cannot be packed into an app.");
        if (!DartRuntime.isSdkInstalled(home)) {
            logger.onInfo("Installing the Dart SDK files (once)…");
            if (installer == null || !installer.install(logger)) throw new IllegalStateException("The Dart SDK files could not be installed. See the lines above.");
        }
        List<String> problems = new ArrayList<>();
        DartRuntime runtime = DartRuntime.locate(filesDirectory, nativeLibraryDirectory, problems);
        if (runtime == null) throw new IllegalStateException(String.join("\n", problems));

        File dill = new File(context.getWorkingDirectory(), "dart-package/assets/dart/main.dill");
        DartScriptRunner runner = new DartScriptRunner(runtime, new File(filesDirectory, "dart-home"), cacheDirectory);
        logger.onInfo("Compiling " + sources.size() + " Dart file(s), starting from main.dart…");
        int code = runner.compile(entry, dill, COMPILE_TIMEOUT_SECONDS, logger);
        if (code == -1) throw new IllegalStateException("Dart compilation timed out after " + COMPILE_TIMEOUT_SECONDS + " seconds.");
        if (code != 0) throw new IllegalStateException("Dart compilation failed. Fix the errors listed above.");

        File vmOutput = new File(context.getWorkingDirectory(), "dart-native/" + ABI + "/" + DartRuntime.VM);
        copy(new File(nativeLibraryDirectory, DartRuntime.VM), vmOutput);
        File helper = new File(generated, "studio/dart/StudioDart.java");
        File helperParent = helper.getParentFile();
        if (!helperParent.isDirectory() && !helperParent.mkdirs()) throw new IOException("Could not create " + helperParent);
        Files.write(helper.toPath(), com.example.studiomobile.build.DartHelperSource.SOURCE.getBytes(StandardCharsets.UTF_8));

        context.putOutput(PACKAGE_OUTPUT_KEY, new File(context.getWorkingDirectory(), "dart-package"));
        context.putOutput(NATIVE_OUTPUT_KEY, new File(context.getWorkingDirectory(), "dart-native"));
        logger.onInfo("Dart program packed: assets/dart/main.dill (" + dill.length() / 1024 + " KB) and lib/" + ABI + "/" + DartRuntime.VM + ".");
    }

    /** The VM is started from the app's native library folder, which only exists when Android unpacks the libraries. */
    private static void rejectUnextractedLibraries(File project) throws IOException {
        File manifest = BuildProjectFiles.manifest(project);
        if (!manifest.isFile()) return;
        String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        if (text.replace(" ", "").contains("extractNativeLibs=\"false\""))
            throw new IllegalStateException("AndroidManifest.xml sets android:extractNativeLibs=\"false\". Dart needs the libraries unpacked: set it to \"true\" or remove it.");
    }

    private static void copy(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }
}
