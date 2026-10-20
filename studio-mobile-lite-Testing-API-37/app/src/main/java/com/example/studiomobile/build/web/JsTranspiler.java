package com.example.studiomobile.build.web;

import java.util.List;

/** Turns JSX / TypeScript source into plain JavaScript. Closed after the build stage is finished. */
public interface JsTranspiler extends AutoCloseable {
    /**
     * @param transforms Sucrase transform names, for example ["jsx"], ["typescript"] or ["typescript", "jsx"]
     * @throws Exception with a message that names the problem in the source (syntax error and so on)
     */
    String transform(String source, String fileName, List<String> transforms) throws Exception;

    @Override void close();
}
