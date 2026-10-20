package com.example.studiomobile.build.web;

import app.cash.quickjs.QuickJs;
import com.example.studiomobile.build.DeviceAbi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs Sucrase (https://github.com/alangpierce/sucrase) inside QuickJS. QuickJS is a native engine with ES2020 support that exists for
 * 32-bit and 64-bit devices, so JSX/TypeScript builds work on every phone. The Sucrase bundle ({@code build-tools/web/sucrase.js}, an
 * IIFE exposing the global {@code Sucrase}) is produced by build.yml from the versions in tools/web-tools.json.
 */
public final class QuickJsSucraseTranspiler implements JsTranspiler {
    private final QuickJs engine;

    public QuickJsSucraseTranspiler(InputStream sucraseBundle) throws IOException {
        String script = readAll(sucraseBundle);
        try {
            engine = QuickJs.create();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError missingNativeLibrary) {
            // QuickJs loads its native library when the class initializes. An Error here would otherwise crash the whole app.
            throw new IOException("The JSX engine (QuickJS) has no native library for this device (ABIs: " + DeviceAbi.supportedAbis()
                + "), so .jsx/.tsx/.ts files cannot be transformed on it: " + missingNativeLibrary, missingNativeLibrary);
        }
        try {
            engine.evaluate(script, "sucrase.js");
            Object type = engine.evaluate("typeof Sucrase === 'object' && typeof Sucrase.transform", "check.js");
            if (!"function".equals(String.valueOf(type))) throw new IOException("sucrase.js does not define Sucrase.transform.");
        } catch (RuntimeException | IOException error) {
            engine.close();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not load the JSX transpiler: " + error.getMessage(), error);
        }
    }

    @Override public String transform(String source, String fileName, List<String> transforms) throws Exception {
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < transforms.size(); i++) { if (i > 0) names.append(','); names.append(JsStringLiteral.quote(transforms.get(i))); }
        names.append(']');
        String script = "Sucrase.transform(" + JsStringLiteral.quote(source) + ", {transforms: " + names + ", jsxRuntime: 'classic', production: true, filePath: " + JsStringLiteral.quote(fileName) + "}).code";
        try {
            Object result = engine.evaluate(script, fileName);
            if (!(result instanceof String)) throw new IllegalStateException("The transpiler returned no code for " + fileName + ".");
            return (String) result;
        } catch (RuntimeException error) {
            throw new IllegalStateException(fileName + ": " + error.getMessage(), error);
        }
    }

    @Override public void close() { engine.close(); }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1 << 16]; int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
