package com.example.studiomobile.build.nativecode;

import com.example.studiomobile.build.BuildExecutableResolver;
import com.example.studiomobile.build.NativeToolSelfTest;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Locates an Android NDK that can run on this device, installed by the user in {@code files/build-tools/ndk}
 * (layout of an official NDK: {@code toolchains/llvm/prebuilt/<host>/bin/clang}, {@code build/cmake/android.toolchain.cmake}).
 *
 * <p>The NDK Google ships runs on desktop hosts only; an NDK built for an Android host must be provided by the user or a verified
 * catalog entry. clang, ld.lld, cmake and ninja are looked up first as {@code lib<name>.so} in the app's native library directory
 * (the only place Android 10+ lets an app execute from when it targets API 29+), then inside the NDK folder.
 */
public final class NativeToolchain {
    private static final Map<String, String> TRIPLES = new HashMap<>();
    static {
        TRIPLES.put("arm64-v8a", "aarch64-linux-android");
        TRIPLES.put("armeabi-v7a", "armv7a-linux-androideabi");
        TRIPLES.put("x86", "i686-linux-android");
        TRIPLES.put("x86_64", "x86_64-linux-android");
    }

    public final File ndkRoot, prebuilt, sysroot, clang;
    /** Null when not installed. */
    public final File linker, cmake, ninja, resourceDirectory, cmakeToolchainFile;

    private NativeToolchain(File ndkRoot, File prebuilt, File clang, File linker, File cmake, File ninja, File resourceDirectory) {
        this.ndkRoot = ndkRoot; this.prebuilt = prebuilt; this.sysroot = new File(prebuilt, "sysroot"); this.clang = clang;
        this.linker = linker; this.cmake = cmake; this.ninja = ninja; this.resourceDirectory = resourceDirectory;
        File toolchainFile = new File(ndkRoot, "build/cmake/android.toolchain.cmake");
        this.cmakeToolchainFile = toolchainFile.isFile() ? toolchainFile : null;
    }

    public boolean canUseCmake() { return cmake != null && ninja != null && cmakeToolchainFile != null; }
    public static String triple(String abi) { return TRIPLES.get(abi); }
    public static boolean is64Bit(String abi) { return abi.equals("arm64-v8a") || abi.equals("x86_64"); }

    /** @return the toolchain, or null with human-readable reasons added to {@code problems}. */
    public static NativeToolchain locate(File tools, List<String> problems) {
        int before = problems.size();
        File ndk = new File(tools, "ndk");
        File[] hosts = new File(ndk, "toolchains/llvm/prebuilt").listFiles(File::isDirectory);
        if (!ndk.isDirectory() || hosts == null || hosts.length == 0) {
            problems.add("No NDK found. Install an Android-host NDK at " + ndk.getAbsolutePath() + " (expects toolchains/llvm/prebuilt/<host>/sysroot).");
            return null;
        }
        File prebuilt = hosts[0];
        if (!new File(prebuilt, "sysroot/usr/include").isDirectory()) problems.add("NDK sysroot is incomplete: " + new File(prebuilt, "sysroot/usr/include") + " is missing.");
        File clang = firstRunnable(tools, prebuilt, "clang");
        if (clang == null) problems.add("clang is missing or not executable. Ship it as libclang.so in jniLibs, or place an executable at " + new File(prebuilt, "bin/clang") + " (blocked on Android 10+ when targetSdk >= 29).");
        else { String problem = NativeToolSelfTest.check(clang); if (problem != null) problems.add(problem); }
        File linker = firstRunnable(tools, prebuilt, "ld.lld");
        if (linker == null && clang != null && !clang.getParentFile().equals(new File(prebuilt, "bin"))) problems.add("ld.lld is missing; it cannot be found next to a relocated clang.");
        if (problems.size() > before) return null;
        File resource = null;
        File[] versions = new File(prebuilt, "lib/clang").listFiles(File::isDirectory);
        if (versions != null && versions.length > 0) resource = versions[0];
        return new NativeToolchain(ndk, prebuilt, clang, linker, firstRunnable(tools, prebuilt, "cmake"), firstRunnable(tools, prebuilt, "ninja"), resource);
    }

    private static File firstRunnable(File tools, File prebuilt, String name) {
        File found = BuildExecutableResolver.resolve(tools, name);
        if (found != null) return found;
        File inNdk = new File(prebuilt, "bin/" + name);
        return inNdk.isFile() && inNdk.canExecute() ? inNdk : null;
    }
}
