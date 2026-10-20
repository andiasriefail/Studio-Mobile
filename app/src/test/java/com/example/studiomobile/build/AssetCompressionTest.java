package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AssetCompressionTest {
    private File project;

    @Before public void setUp() throws IOException { project = Files.createTempDirectory("asset-compression-test").toFile(); }
    @After public void tearDown() { delete(project); }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private void write(String path, String content) throws IOException {
        File file = new File(project, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void groovyCommandForm() {
        assertEquals(Arrays.asList(".tflite", ".dat"), AssetCompression.parseNoCompress("androidResources {\n  noCompress 'tflite', \"dat\"\n}\n"));
    }
    @Test public void kotlinPlusAssignList() {
        assertEquals(Arrays.asList(".tflite", ".lite"), AssetCompression.parseNoCompress("androidResources { noCompress += listOf(\"tflite\", \".LITE\") }"));
    }
    @Test public void multiLineListAndAddAll() {
        assertEquals(Arrays.asList(".a", ".b", ".c"), AssetCompression.parseNoCompress("noCompress = [\n 'a',\n 'b'\n]\naaptOptions.noCompress.addAll(\"c\")"));
    }
    @Test public void emptyAndInvalidEntriesAreIgnored() {
        assertTrue(AssetCompression.parseNoCompress("noCompress ''\nnoCompress 'a/b'").isEmpty());
    }
    @Test public void noBuildFileGivesDefaultsOnly() {
        assertEquals(AssetCompression.DEFAULT_EXTENSIONS, AssetCompression.extensions(project));
    }
    @Test public void buildFileEntriesAreAddedWithoutDuplicates() throws IOException {
        write("app/build.gradle", "android {\n  androidResources {\n    noCompress 'glb', 'custom'\n  }\n}\n");
        List<String> extensions = AssetCompression.extensions(project);
        assertTrue(extensions.contains(".custom"));
        assertEquals(1, java.util.Collections.frequency(extensions, ".glb"));
        assertEquals(AssetCompression.DEFAULT_EXTENSIONS.size() + 1, extensions.size());
    }
}
