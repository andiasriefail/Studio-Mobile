package com.example.studiomobile.workspace;

import android.content.Context;
import com.example.studiomobile.build.web.JsStringLiteral;
import com.example.studiomobile.build.web.JsTranspiler;
import com.example.studiomobile.build.web.QuickJsSucraseTranspiler;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the preview show React/TypeScript web content the way the app is meant to look, including its colors, effects and text.
 * <ul>
 * <li>x.js is made from x.jsx / x.tsx / x.ts (as JsxTranspileStage does for the build); it wins over a hand-written x.js.</li>
 * <li>Relative imports in those files are completed so the WebView can load them: {@code './App'} and {@code './ui'} (folder with an index file)
 *     get their .js, {@code './App.jsx'} becomes {@code './App.js'}.</li>
 * <li>{@code import './style.css'} injects the stylesheet into the page (colors, animations, effects); {@code import data from './data.json'} gives the parsed text.</li>
 * </ul>
 * Requests for the .jsx/.tsx/.ts file itself, and for every other file, are left alone.
 */
final class PreviewJsx {
    private PreviewJsx() { }

    private static final String[] SOURCE_EXTENSIONS = {".jsx", ".tsx", ".ts"};
    private static final Pattern RELATIVE_IMPORT = Pattern.compile("(\\bfrom\\s*|\\bimport\\s*\\(\\s*|\\bimport\\s+)([\"'])(\\.{1,2}/[^\"'\\r\\n]+)\\2");
    private static final Map<String, String[]> CACHE = new HashMap<>();   // key -> {stamp, code}; guarded by PreviewJsx.class
    private static JsTranspiler engine;                                      // QuickJS is single-threaded; guarded by PreviewJsx.class

    /**
     * @return JavaScript the preview must produce for {@code relative} (a path inside assets/), or null when the file is served as it is.
     * Never throws for a bad source: that becomes a script reporting it through console.error, which the preview shows as a tip.
     */
    static String generated(Context context, File assets, String relative) throws IOException {
        File source = sourceFor(assets, relative);
        if (source != null) {
            if (!inside(assets, source)) return null;
            String code = javascript(context, source, relative);
            return code.startsWith("console.error(") ? code : rewrite(assets, relative, code);
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if ((lower.endsWith(".css.js") || lower.endsWith(".json.js")) && !new File(assets, relative).isFile()) {
            File data = new File(assets, relative.substring(0, relative.length() - 3));
            if (data.isFile() && inside(assets, data)) return wrapper(data, relative.substring(0, relative.length() - 3));
        }
        return null;
    }

    /** @return the .jsx/.tsx/.ts file that the build would turn into {@code relativeJs}, or null (also when relativeJs is not a .js path). */
    static File sourceFor(File assets, String relativeJs) {
        String lower = relativeJs.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".js") || lower.endsWith(".d.js")) return null;
        String stem = relativeJs.substring(0, relativeJs.length() - 3);
        for (String extension : SOURCE_EXTENSIONS) {
            File candidate = new File(assets, stem + extension);
            if (candidate.isFile() && !candidate.getName().toLowerCase(Locale.ROOT).endsWith(".d.ts")) return candidate;
        }
        return null;
    }

    /** Completes the relative imports of a transformed file; {@code relativeFile} is its own path inside assets/. */
    static String rewrite(File assets, String relativeFile, String code) {
        String dir = relativeFile.contains("/") ? relativeFile.substring(0, relativeFile.lastIndexOf('/')) : "";
        Matcher match = RELATIVE_IMPORT.matcher(code);
        StringBuffer out = new StringBuffer();
        while (match.find())
            match.appendReplacement(out, Matcher.quoteReplacement(match.group(1) + match.group(2) + resolve(assets, dir, match.group(3)) + match.group(2)));
        match.appendTail(out);
        return out.toString();
    }

    static String resolve(File assets, String dir, String spec) {
        String path = normalize(dir.isEmpty() ? spec : dir + "/" + spec);
        if (path == null) return spec;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css") || lower.endsWith(".json")) return new File(assets, path).isFile() ? spec + ".js" : spec;
        if (lower.endsWith(".jsx") || lower.endsWith(".tsx") || lower.endsWith(".ts"))
            return new File(assets, path).isFile() ? spec.substring(0, spec.lastIndexOf('.')) + ".js" : spec;
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return spec;
        if (exists(assets, path + ".js")) return spec + ".js";
        if (new File(assets, path).isDirectory() && exists(assets, path + "/index.js")) return spec.endsWith("/") ? spec + "index.js" : spec + "/index.js";
        return spec;
    }

    private static boolean exists(File assets, String relativeJs) { return new File(assets, relativeJs).isFile() || sourceFor(assets, relativeJs) != null; }

    /** Collapses "." and ".."; null when the path would leave assets/. */
    private static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) return null; parts.remove(parts.size() - 1); } else parts.add(part);
        }
        return String.join("/", parts);
    }

    private static boolean inside(File assets, File file) {
        try { return file.getCanonicalPath().startsWith(assets.getCanonicalPath() + File.separator); } catch (IOException unreadable) { return false; }
    }

    /** A module for {@code import './x.css'} (adds the stylesheet to the page) or {@code import data from './x.json'}. */
    static String wrapper(File data, String relativeData) throws IOException {
        String text = new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (relativeData.toLowerCase(Locale.ROOT).endsWith(".css"))
            return "const css = " + JsStringLiteral.quote(text) + ";\n"
                + "const style = document.createElement('style');\n"
                + "style.setAttribute('data-source', " + JsStringLiteral.quote(relativeData) + ");\n"
                + "style.textContent = css;\n"
                + "(document.head || document.documentElement).appendChild(style);\n"
                + "export default css;\n";
        return "export default " + text.trim() + ";\n";
    }

    /** JavaScript for a .jsx/.tsx/.ts file. Never throws: a failure becomes a script that reports it through console.error. */
    static synchronized String javascript(Context context, File source, String relative) {
        String stamp = source.lastModified() + ":" + source.length();
        String[] cached = CACHE.get(source.getPath());
        if (cached != null && cached[0].equals(stamp)) return cached[1];
        try {
            if (engine == null) {
                InputStream bundle;
                try { bundle = context.getAssets().open("build-tools/web/sucrase.js"); }
                catch (IOException missing) { throw new IOException("The JSX transpiler (build-tools/web/sucrase.js) is missing from this build of Studio Mobile.", missing); }
                engine = new QuickJsSucraseTranspiler(bundle);
            }
            String name = source.getName().toLowerCase(Locale.ROOT);
            List<String> transforms = name.endsWith(".tsx") ? Arrays.asList("typescript", "jsx")
                : name.endsWith(".ts") ? java.util.Collections.singletonList("typescript") : java.util.Collections.singletonList("jsx");
            String code = engine.transform(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8), relative, transforms);
            CACHE.put(source.getPath(), new String[] {stamp, code});
            return code;
        } catch (Throwable failure) {
            return "console.error(" + JsStringLiteral.quote("JSX/TypeScript: " + (failure.getMessage() == null ? failure.toString() : failure.getMessage())) + ");";
        }
    }

    /** Frees the JavaScript engine (it is created again on the next request). */
    static synchronized void release() {
        if (engine != null) { try { engine.close(); } catch (RuntimeException ignored) { } engine = null; }
        CACHE.clear();
    }
}
