package com.example.studiomobile.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PreviewJsxTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File assets;

    private void write(String relative, String text) throws Exception {
        File file = new File(assets, relative);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Before public void setUp() throws Exception {
        assets = temporary.newFolder("assets");
        write("app.jsx", "x"); write("App2.tsx", "x"); write("ui/index.jsx", "x"); write("ui/Button.jsx", "x");
        write("styles/main.css", "body{background:#112233;animation:f 1s}");
        write("data/text.json", "{\"title\":\"Halo\"}");
        write("plain.js", "x"); write("legacy.js", "old"); write("legacy.jsx", "new");
    }

    @Test public void jsRequestMapsToSourceAndItWinsOverAnExistingJs() {
        assertEquals("legacy.jsx", PreviewJsx.sourceFor(assets, "legacy.js").getName());
        assertEquals("App2.tsx", PreviewJsx.sourceFor(assets, "App2.js").getName());
    }

    @Test public void otherRequestsAreLeftAlone() throws Exception {
        assertNull(PreviewJsx.sourceFor(assets, "plain.js"));
        assertNull(PreviewJsx.sourceFor(assets, "app.jsx"));
        assertNull(PreviewJsx.generated(null, assets, "styles/main.css"));
        assertNull(PreviewJsx.generated(null, assets, "index.html"));
    }

    @Test public void importsAreCompleted() {
        assertEquals("./App2.js", PreviewJsx.resolve(assets, "", "./App2"));
        assertEquals("./ui/index.js", PreviewJsx.resolve(assets, "", "./ui"));
        assertEquals("./ui/Button.js", PreviewJsx.resolve(assets, "", "./ui/Button.jsx"));
        assertEquals("../styles/main.css.js", PreviewJsx.resolve(assets, "ui", "../styles/main.css"));
        assertEquals("./data/text.json.js", PreviewJsx.resolve(assets, "", "./data/text.json"));
        assertEquals("./missing", PreviewJsx.resolve(assets, "", "./missing"));
        assertEquals("../../x", PreviewJsx.resolve(assets, "ui", "../../x"));
    }

    @Test public void rewriteTouchesOnlyRelativeSpecifiers() {
        String out = PreviewJsx.rewrite(assets, "app.js",
            "import React from 'react';\nimport App from './App2';\nimport './styles/main.css';\nexport * from './ui';\nconst L = () => import('./ui/Button.jsx');\nimport 'https://cdn/x.js';");
        assertTrue(out.contains("from 'react'") && out.contains("'https://cdn/x.js'"));
        assertTrue(out.contains("from './App2.js'") && out.contains("import './styles/main.css.js'"));
        assertTrue(out.contains("export * from './ui/index.js'") && out.contains("import('./ui/Button.js')"));
    }

    @Test public void cssAndJsonBecomeModules() throws Exception {
        String css = PreviewJsx.generated(null, assets, "styles/main.css.js");
        assertNotNull(css);
        assertTrue(css.contains("createElement('style')") && css.contains("#112233"));
        assertEquals("export default {\"title\":\"Halo\"};\n", PreviewJsx.generated(null, assets, "data/text.json.js"));
    }

    @Test public void anExistingCssJsFileIsNotReplaced() throws Exception {
        write("real.css", "a{}"); write("real.css.js", "mine");
        assertNull(PreviewJsx.generated(null, assets, "real.css.js"));
    }
}
