package com.example.studiomobile.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class McpSyntaxCheckerTest {
    @Test public void mapsExtensionsToLanguages() {
        assertEquals("python", McpSyntaxChecker.languageOf("a.PY"));
        assertEquals("dart", McpSyntaxChecker.languageOf("main.dart"));
        assertEquals("javascript", McpSyntaxChecker.languageOf("x.js"));
        assertEquals("c", McpSyntaxChecker.languageOf("x.c"));
        assertEquals("c++", McpSyntaxChecker.languageOf("x.cpp"));
        assertEquals("c-header", McpSyntaxChecker.languageOf("x.h"));
        assertEquals("c++-header", McpSyntaxChecker.languageOf("x.hpp"));
        assertNull(McpSyntaxChecker.languageOf("Main.java"));
    }

    @Test public void refusesPythonDartAndNativeOnNonArm64Phones() throws Exception {
        File root = Files.createTempDirectory("syntax").toFile();
        File tools = new File(root, "files/build-tools");
        tools.mkdirs();
        File project = new File(root, "project");
        project.mkdirs();
        McpSyntaxChecker checker = new McpSyntaxChecker(tools, new File(root, "lib"), Arrays.asList("armeabi-v7a", "armeabi"));
        for (String name : new String[] { "a.py", "a.dart", "a.c", "a.cpp", "a.hpp" }) {
            File file = new File(project, name);
            Files.write(file.toPath(), "x".getBytes("UTF-8"));
            String answer = checker.check(project, file, name);
            assertTrue(name + ": " + answer, answer.contains("RESULT: NOT CHECKED"));
            assertTrue(name + ": " + answer, answer.contains("only available for arm64-v8a devices"));
        }
    }

    @Test public void javaIsNotCoveredAndSaysSo() throws Exception {
        File root = Files.createTempDirectory("syntax").toFile();
        File tools = new File(root, "files/build-tools");
        tools.mkdirs();
        File file = new File(root, "Main.java");
        Files.write(file.toPath(), "class Main {}".getBytes("UTF-8"));
        String answer = new McpSyntaxChecker(tools, null, Collections.<String>emptyList()).check(root, file, "Main.java");
        assertTrue(answer, answer.contains("RESULT: NOT CHECKED"));
        assertTrue(answer, answer.contains("only checked by the user's Build"));
    }

    @Test public void languageSupportListsAbisAndArm64Rule() {
        String text = McpSyntaxChecker.languageSupport(null, Arrays.asList("armeabi-v7a"));
        assertTrue(text, text.contains("Device ABIs: armeabi-v7a"));
        assertTrue(text, text.contains("NOT supported"));
        assertTrue(McpSyntaxChecker.languageSupport(null, Arrays.asList("arm64-v8a")).contains("This phone: supported"));
    }
}
