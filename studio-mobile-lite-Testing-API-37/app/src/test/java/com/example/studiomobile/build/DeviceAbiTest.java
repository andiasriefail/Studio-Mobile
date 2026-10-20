package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class DeviceAbiTest {
    @Test public void detects64BitDevices() {
        assertTrue(DeviceAbi.is64Bit(Arrays.asList("arm64-v8a", "armeabi-v7a", "armeabi")));
        assertTrue(DeviceAbi.is64Bit(Arrays.asList("x86_64", "x86")));
        assertFalse(DeviceAbi.is64Bit(Arrays.asList("armeabi-v7a", "armeabi")));
    }

    @Test public void unknownAbiListNeverBlocks() {
        assertTrue(DeviceAbi.is64Bit(Collections.<String>emptyList()));
        assertTrue(DeviceAbi.supportsArm64(null));
        assertNull(DeviceAbi.requireArm64("Dart", null));
        assertNull(DeviceAbi.requireArm64("Dart", Collections.<String>emptyList()));
    }

    @Test public void armOnlyToolsRejectX86AndExplainWhy() {
        assertNull(DeviceAbi.requireArm64("Python", Arrays.asList("arm64-v8a", "armeabi-v7a")));
        assertNull(DeviceAbi.requireArm64("Python", Arrays.asList("x86_64", "arm64-v8a", "x86")));
        assertEquals("Your device is 64-bit (x86_64, x86), but Dart is only available for arm64-v8a devices.",
            DeviceAbi.requireArm64("Dart", Arrays.asList("x86_64", "x86")));
        assertEquals("Your device is 32-bit (armeabi-v7a), but Python is only available for arm64-v8a devices.",
            DeviceAbi.requireArm64("Python", Arrays.asList("armeabi-v7a")));
    }

    @Test public void oldRequire64BitNowMeansArm64() {
        assertNull(DeviceAbi.require64Bit("Python", Arrays.asList("arm64-v8a", "armeabi-v7a")));
        assertEquals("Your device is 32-bit (armeabi-v7a, armeabi), but Python is only available for arm64-v8a devices.",
            DeviceAbi.require64Bit("Python", Arrays.asList("armeabi-v7a", "armeabi")));
        assertEquals("Your device is 64-bit (x86_64), but compiling C/C++ on the device is only available for arm64-v8a devices.",
            DeviceAbi.require64Bit("compiling C/C++ on the device", Arrays.asList("x86_64")));
    }
}
