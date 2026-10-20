package com.example.studiomobile.build.stages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.build.web.JsStringLiteral;
import com.example.studiomobile.build.web.JsTranspiler;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JsxTranspileStageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Fake implements JsTranspiler {
        final List<String> calls = new ArrayList<>(); boolean closed;
        @Override public String transform(String source, String fileName, List<String> transforms) { calls.add(fileName + " " + transforms); return "/*js*/" + source; }
        @Override public void close() { closed = true; }
    }

    private File write(File root, String path, String text) throws Exception {
        File file = new File(root, path); file.getParentFile().mkdirs(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void transformsOnlyJsxAndTypeScriptAndKeepsRelativePaths() throws Exception {
        File assets = temporary.newFolder(), output = new File(temporary.newFolder(), "out");
        write(assets, "index.html", "<html>"); write(assets, "app.jsx", "A"); write(assets, "ui/Button.tsx", "B"); write(assets, "util/math.ts", "C"); write(assets, "types.d.ts", "D"); write(assets, "plain.js", "E");
        Fake fake = new Fake();
        assertEquals(3, JsxTranspileStage.transformTree(assets, output, () -> fake, null));
        assertEquals("/*js*/A", new String(Files.readAllBytes(new File(output, "app.js").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(output, "ui/Button.js").isFile());
        assertTrue(new File(output, "util/math.js").isFile());
        assertFalse(new File(output, "types.d.js").exists());
        assertFalse(new File(output, "index.html").exists());
        assertTrue(fake.closed);
        assertTrue(fake.calls.contains("ui/Button.tsx [typescript, jsx]"));
        assertTrue(fake.calls.contains("app.jsx [jsx]"));
        assertTrue(fake.calls.contains("util/math.ts [typescript]"));
    }

    @Test public void doesNotOpenTheEngineWithoutSources() throws Exception {
        File assets = temporary.newFolder(); write(assets, "index.html", "x");
        assertEquals(0, JsxTranspileStage.transformTree(assets, new File(temporary.newFolder(), "out"), () -> { throw new AssertionError("engine must not be opened"); }, null));
    }

    @Test public void quotesJavaScriptStrings() {
        char lineSeparator = (char) 0x2028; // U+2028 is legal in a JS string only when escaped
        assertEquals("\"a\\\"b\\\\c\\n\\u2028\"", JsStringLiteral.quote("a\"b\\c\n" + lineSeparator));
    }
}
