package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.web.JsTranspiler;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Web content (React in a WebView) lives in {@code src/main/assets}; browsers cannot run JSX or TypeScript. This stage converts every
 * {@code .jsx}, {@code .tsx} and {@code .ts} file there into a {@code .js} file at the same relative path, in a generated folder that
 * {@link ResourceLinkingStage} merges on top of the app's assets. The source files are left untouched. When the project has no such
 * files nothing happens and the JavaScript engine is never loaded.
 */
public final class JsxTranspileStage implements BuildStage {
    public static final String OUTPUT_KEY = "generatedAssets";

    public interface TranspilerFactory { JsTranspiler open() throws IOException; }

    private final TranspilerFactory factory;

    public JsxTranspileStage(TranspilerFactory factory) { this.factory = factory; }

    @Override public String getName() { return "JSX/TypeScript transform"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File assets = new File(BuildProjectFiles.mainDirectory(context.getProjectDirectory()), "assets");
        File output = new File(context.getWorkingDirectory(), "web-assets");
        if (transformTree(assets, output, factory, logger) > 0) context.putOutput(OUTPUT_KEY, output);
    }

    /** @return the number of files written; 0 when the folder has nothing to transform (then {@code output} is not created). */
    static int transformTree(File assets, File output, TranspilerFactory factory, BuildLogger logger) throws Exception {
        List<File> sources = new ArrayList<>();
        collect(assets, sources);
        if (sources.isEmpty()) return 0;
        deleteTree(output);
        int written = 0;
        try (JsTranspiler transpiler = factory.open()) {
            for (File source : sources) {
                String relative = assets.toPath().relativize(source.toPath()).toString().replace(File.separatorChar, '/');
                String javascript = transpiler.transform(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8), relative, transformsFor(source.getName()));
                File target = new File(output, relative.substring(0, relative.lastIndexOf('.')) + ".js");
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Could not create " + target.getParentFile());
                Files.write(target.toPath(), javascript.getBytes(StandardCharsets.UTF_8));
                written++;
            }
        }
        if (logger != null) logger.onInfo("Transformed " + written + " JSX/TypeScript file" + (written == 1 ? "" : "s") + " to JavaScript.");
        return written;
    }

    static List<String> transformsFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tsx")) return Arrays.asList("typescript", "jsx");
        if (lower.endsWith(".ts")) return Collections.singletonList("typescript");
        return Collections.singletonList("jsx");
    }

    private static boolean isSource(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".jsx") || lower.endsWith(".tsx") || lower.endsWith(".ts")) && !lower.endsWith(".d.ts");
    }

    private static void collect(File directory, List<File> sources) {
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) { if (child.isDirectory()) collect(child, sources); else if (isSource(child.getName())) sources.add(child); }
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
