package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.DeviceAbi;
import com.example.studiomobile.build.nativecode.NativeProject;
import com.example.studiomobile.build.nativecode.NativeToolchain;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs first. When the project has C/C++ sources that must be compiled on the device but the device is 32-bit (the on-device NDK and
 * clang exist for 64-bit only), the build stops right away with that reason, instead of failing minutes later with a misleading
 * "No NDK found". Projects whose libraries are already prebuilt in src/main/jniLibs are not affected.
 */
public final class NativePreflightStage implements BuildStage {
    private final File tools;
    private final List<String> deviceAbis;

    public NativePreflightStage(File tools) { this(tools, DeviceAbi.supportedAbis()); }
    public NativePreflightStage(File tools, List<String> deviceAbis) { this.tools = tools; this.deviceAbis = deviceAbis; }

    @Override public String getName() { return "Device check for native code"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        NativeProject project = NativeProject.detect(context.getProjectDirectory());
        if (project == null) return;
        String unsupported = DeviceAbi.require64Bit("compiling C/C++ on the device", deviceAbis);
        if (unsupported == null) return;
        if (NativeToolchain.locate(tools, new ArrayList<String>()) != null) return; // someone installed a working toolchain by hand
        if (NativeBuildStage.uncoveredByPrebuilt(project) == null) return;           // every .so is already in jniLibs
        throw new IllegalStateException(unsupported
            + "\nThis project has C/C++ sources and src/main/jniLibs does not contain every library."
            + "\nFix: build the .so files on GitHub Actions (see the native-build.yml template) and put them in src/main/jniLibs/<abi>/.");
    }
}
