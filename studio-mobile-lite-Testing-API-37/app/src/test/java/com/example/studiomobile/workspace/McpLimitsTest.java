package com.example.studiomobile.workspace;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class McpLimitsTest {
    @Test public void promptStatesWhichLanguagesNeedArm64() {
        String text = McpLimits.instructions(false);
        assertTrue(text.contains("LANGUAGE SUPPORT BY DEVICE"));
        assertTrue(text.contains("Python (CPython), Dart and C/C++ compiled on the phone (NDK clang) exist ONLY for arm64-v8a phones"));
        assertTrue(text.contains("armeabi-v7a"));
    }

    @Test public void promptExplainsCheckSyntax() {
        String text = McpLimits.instructions(true);
        assertTrue(text.contains("check_syntax"));
        assertTrue(text.contains("RESULT: NOT CHECKED"));
        assertTrue(text.contains("it never runs the code"));
    }
}
