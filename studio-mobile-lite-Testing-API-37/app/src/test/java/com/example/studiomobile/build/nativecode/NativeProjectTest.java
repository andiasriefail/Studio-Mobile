package com.example.studiomobile.build.nativecode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;

public class NativeProjectTest {
    private static void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static File androidProject(String gradle) throws Exception {
        File root = Files.createTempDirectory("native-project").toFile();
        write(new File(root, "settings.gradle"), "include ':app'");
        write(new File(root, "app/build.gradle"), gradle);
        write(new File(root, "app/src/main/AndroidManifest.xml"), "<manifest/>");
        return root;
    }

    @Test public void detectsCMakeProjectWithAbiFiltersAndPlatform() throws Exception {
        File root = androidProject("android { defaultConfig { minSdk 24\n ndk { abiFilters 'arm64-v8a', 'x86_64' } } }");
        write(new File(root, "app/src/main/cpp/CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22.1)\nproject(\"app\" C)\nadd_library(native-lib SHARED native-lib.c)\nfind_library(log-lib log)\ntarget_link_libraries(native-lib ${log-lib})\n");
        write(new File(root, "app/src/main/cpp/native-lib.c"), "#include <jni.h>\n");
        NativeProject project = NativeProject.detect(root);
        assertNotNull(project);
        assertTrue(project.problems.isEmpty());
        assertEquals(Arrays.asList("arm64-v8a", "x86_64"), project.abis);
        assertEquals(24, project.platform);
        assertTrue(project.libraryNames().contains("native-lib"));
        assertEquals(Arrays.asList("log"), project.script.targets.get("native-lib").libraries);
    }

    @Test public void projectWithoutNativeCodeIsIgnored() throws Exception {
        assertNull(NativeProject.detect(androidProject("android { }")));
    }

    @Test public void nativeSourcesWithoutCMakeListsAreReported() throws Exception {
        File root = androidProject("android { }");
        write(new File(root, "app/src/main/cpp/native-lib.c"), "int x;");
        assertTrue(NativeProject.detect(root).problems.get(0).contains("CMakeLists.txt"));
    }

    @Test public void platformIsNeverBelowTheNdkMinimum() throws Exception {
        File root = androidProject("android { defaultConfig { minSdk 16 } }");
        write(new File(root, "app/src/main/cpp/CMakeLists.txt"), "add_library(a SHARED a.c)");
        assertEquals(NativeProject.LOWEST_PLATFORM, NativeProject.detect(root).platform);
    }

    @Test public void unsupportedCMakeFeaturesAreListedNotGuessed() {
        CMakeScriptReader reader = CMakeScriptReader.parse("add_library(a SHARED a.c)\nif(ANDROID)\nendif()\nfile(GLOB S *.c)\nadd_library(b STATIC b.c)");
        String text = reader.unsupported.toString();
        assertTrue(text.contains("if()") && text.contains("file()") && text.contains("STATIC"));
        assertEquals(Arrays.asList("a"), reader.libraryNames());
    }

    @Test public void definitionsOptionsAndIncludesAreCollected() {
        CMakeScriptReader reader = CMakeScriptReader.parse("add_library(a SHARED a.c)\ntarget_compile_definitions(a PRIVATE FOO=1)\ntarget_include_directories(a PRIVATE include)\nadd_compile_options(-Wextra)\n");
        CMakeScriptReader.Target target = reader.targets.get("a");
        assertEquals(Arrays.asList("-DFOO=1"), target.compileOptions);
        assertEquals(Arrays.asList("include"), target.includeDirectories);
        assertEquals(Arrays.asList("-Wextra"), reader.globalCompileOptions);
    }
}
