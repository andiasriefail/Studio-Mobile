package com.example.studiomobile.dart;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dart 3.13.x built for Android arm64 (see the build-dart job in .github/workflows/build.yml).
 *
 * <p>Android 10+ only executes files from the native library directory, and the Dart launcher looks for its SDK next to its own
 * executable. So the SDK layout cannot be used as is. Instead two executables ship in the APK, and every path is passed explicitly:
 * <ul>
 * <li>{@code libdartaotruntime.so} (bin/dartaotruntime) runs the AOT-compiled front end {@code frontend_server_aot.dart.snapshot} to
 *     compile a script to a kernel (.dill) file;</li>
 * <li>{@code libdartvm.so} (bin/dartvm) runs that .dill.</li>
 * </ul>
 * The snapshot and the platform dill are downloaded once (SHA-256 pinned) into {@code files/dart-sdk}.
 */
public final class DartRuntime {
    public static final String AOT_RUNTIME = "libdartaotruntime.so", VM = "libdartvm.so";
    public static final String FRONTEND_SNAPSHOT = "bin/snapshots/frontend_server_aot.dart.snapshot";
    public static final String PLATFORM_DILL = "lib/_internal/vm_platform_strong.dill";
    public final File aotRuntime, vm, home, frontendSnapshot, platformDill;

    private DartRuntime(File aotRuntime, File vm, File home) {
        this.aotRuntime = aotRuntime; this.vm = vm; this.home = home;
        this.frontendSnapshot = new File(home, FRONTEND_SNAPSHOT); this.platformDill = new File(home, PLATFORM_DILL);
    }

    /** True when both executables are part of this APK (it was built after a successful Dart SDK build). */
    public static boolean isBundled(File nativeLibraryDirectory) { return usable(new File(nativeLibraryDirectory, AOT_RUNTIME)) && usable(new File(nativeLibraryDirectory, VM)); }
    public static boolean isSdkInstalled(File home) { return new File(home, FRONTEND_SNAPSHOT).isFile() && new File(home, PLATFORM_DILL).isFile(); }

    /** @return the runtime, or null with human-readable reasons added to {@code problems}. */
    public static DartRuntime locate(File filesDirectory, File nativeLibraryDirectory, List<String> problems) {
        int before = problems.size();
        if (!isBundled(nativeLibraryDirectory)) problems.add("The Dart executables (" + AOT_RUNTIME + ", " + VM + ") are not part of this build of Studio Mobile. Build the Dart SDK once (build_dart) and rebuild the app.");
        File home = new File(filesDirectory, "dart-sdk");
        if (!isSdkInstalled(home)) problems.add("The Dart SDK files are not installed at " + home + ".");
        return problems.size() > before ? null : new DartRuntime(new File(nativeLibraryDirectory, AOT_RUNTIME), new File(nativeLibraryDirectory, VM), home);
    }

    /** Kernel compile: aotruntime frontend_server_aot.snapshot ... script.dart. */
    public List<String> compileCommand(File script, File outputDill) {
        List<String> command = new ArrayList<>();
        command.add(aotRuntime.getAbsolutePath()); command.add(frontendSnapshot.getAbsolutePath());
        command.add("--sdk-root=" + new File(home, "lib/_internal").getAbsolutePath() + File.separator);
        command.add("--platform=" + platformDill.getAbsolutePath());
        command.add("--target=vm");
        command.add("--link-platform");
        command.add("--output-dill=" + outputDill.getAbsolutePath());
        command.add(script.getAbsolutePath());
        return command;
    }

    public List<String> runCommand(File dill) {
        List<String> command = new ArrayList<>();
        command.add(vm.getAbsolutePath()); command.add(dill.getAbsolutePath());
        return command;
    }

    public Map<String, String> environment(File dataDirectory, File cacheDirectory) {
        Map<String, String> environment = new HashMap<>();
        environment.put("HOME", dataDirectory.getAbsolutePath());
        environment.put("TMPDIR", cacheDirectory.getAbsolutePath());
        environment.put("PUB_CACHE", new File(dataDirectory, "pub-cache").getAbsolutePath());
        return environment;
    }

    private static boolean usable(File file) { return file.isFile() && file.length() > 0 && file.canExecute(); }
}
