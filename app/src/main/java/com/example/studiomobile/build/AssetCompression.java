package com.example.studiomobile.build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which file extensions AAPT2 must store uncompressed ({@code aapt2 link -0 <ext>}), like AGP's {@code noCompress}.
 *
 * Game assets (models, compressed textures, shaders, atlases) are read with {@code AssetManager.openFd()} or memory-mapped, which only
 * works on STORED entries; AAPT2 compresses everything outside its small built-in list of media extensions. The list used here is
 * {@link #DEFAULT_EXTENSIONS} plus whatever the module's build.gradle(.kts) declares with {@code noCompress}.
 */
public final class AssetCompression {
    /** Common game-asset extensions, stored uncompressed. AAPT2's own defaults (png, jpg, ogg, mp3, wav, ...) apply on top of these. */
    public static final List<String> DEFAULT_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
        ".glb", ".gltf", ".fbx", ".obj", ".ktx", ".ktx2", ".tga", ".webp", ".spv", ".atlas", ".skel", ".anim",
        ".ogg", ".wav", ".mp3", ".m4a", ".bank", ".pak"));
    private AssetCompression() { }

    /** Defaults plus the project's {@code noCompress} entries, each as ".ext", lowercase, without duplicates. */
    public static List<String> extensions(File project) {
        Set<String> result = new LinkedHashSet<>(DEFAULT_EXTENSIONS);
        File module = BuildProjectFiles.moduleDirectory(project);
        File buildFile = new File(module, "build.gradle");
        if (!buildFile.isFile()) buildFile = new File(module, "build.gradle.kts");
        if (buildFile.isFile()) {
            try { result.addAll(parseNoCompress(new String(Files.readAllBytes(buildFile.toPath()), StandardCharsets.UTF_8))); }
            catch (Exception ignored) { }
        }
        return new ArrayList<>(result);
    }

    /**
     * Quoted strings of every {@code noCompress} statement in a Gradle script. Handles {@code noCompress "a", "b"},
     * {@code noCompress += listOf("a", "b")}, {@code noCompress.addAll(...)}, {@code noCompress = [...]} and multi-line lists.
     * An empty string (AGP: "compress nothing") is ignored.
     */
    static List<String> parseNoCompress(String script) {
        List<String> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = script.indexOf("noCompress", from);
            if (at < 0) break;
            int i = at + "noCompress".length();
            from = i;
            int depth = 0;
            for (; i < script.length(); i++) {
                char c = script.charAt(i);
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') { depth--; if (depth <= 0) break; }
                else if (c == '\n' && depth <= 0) break;
                else if (c == '"' || c == '\'') {
                    int end = script.indexOf(c, i + 1);
                    if (end < 0) break;
                    String value = normalize(script.substring(i + 1, end));
                    if (value != null) found.add(value);
                    i = end;
                }
            }
            from = Math.max(from, i);
        }
        return found;
    }

    private static String normalize(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf(' ') >= 0) return null;
        return value.startsWith(".") ? value : "." + value;
    }
}
