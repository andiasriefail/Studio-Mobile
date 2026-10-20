package com.example.studiomobile.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ABI checks for languages and tools that only exist as arm64-v8a binaries (the on-device NDK/clang, Python, Dart).
 * Call {@link #requireArm64} before doing any download or build work so the person gets the real reason immediately, instead of a
 * misleading follow-up such as "No NDK found".
 *
 * <p>JavaScript (Rhino) and Java (ECJ) run inside the app process and work on every ABI, so they need no check.
 */
public final class DeviceAbi {
    private static final List<String> SIXTY_FOUR_BIT = Arrays.asList("arm64-v8a", "x86_64", "riscv64");
    private DeviceAbi() { }

    /** {@code Build.SUPPORTED_ABIS}, or an empty list when it cannot be read (plain JVM unit tests). Empty means "unknown", never "32-bit". */
    public static List<String> supportedAbis() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            return abis == null ? Collections.<String>emptyList() : new ArrayList<>(Arrays.asList(abis));
        } catch (Throwable unavailable) {
            return Collections.emptyList();
        }
    }

    /** True when the device can run 64-bit code. An unknown (empty) ABI list is treated as 64-bit so a failed lookup never blocks a build. */
    public static boolean is64Bit(List<String> abis) {
        if (abis == null || abis.isEmpty()) return true;
        for (String abi : abis) if (SIXTY_FOUR_BIT.contains(abi)) return true;
        return false;
    }

    /** True when the device can run arm64-v8a code. An unknown (empty) ABI list is treated as arm64 so a failed lookup never blocks a build. */
    public static boolean supportsArm64(List<String> abis) {
        if (abis == null || abis.isEmpty()) return true;
        return abis.contains("arm64-v8a");
    }

    /**
     * For tools that are only shipped as arm64-v8a binaries (Python, Dart, the on-device NDK). A 64-bit x86_64 device is not enough,
     * unless it translates arm64 and lists arm64-v8a as a supported ABI. 32-bit ARM (armeabi-v7a) is never enough.
     * @return null when {@code feature} can run on this device, else a one-line explanation for the person.
     */
    public static String requireArm64(String feature, List<String> abis) {
        if (supportsArm64(abis)) return null;
        String kind = is64Bit(abis) ? "64-bit" : "32-bit";
        return "Your device is " + kind + " (" + String.join(", ", abis) + "), but " + feature + " is only available for arm64-v8a devices.";
    }

    /**
     * Kept so older call sites keep compiling. Every tool that used to call this (Python, Dart, on-device C/C++) ships only for arm64-v8a,
     * so it now answers exactly like {@link #requireArm64}. A plain 64-bit check let x86_64 devices through to a confusing failure.
     * @deprecated use {@link #requireArm64}.
     */
    @Deprecated
    public static String require64Bit(String feature, List<String> abis) {
        return requireArm64(feature, abis);
    }
}
