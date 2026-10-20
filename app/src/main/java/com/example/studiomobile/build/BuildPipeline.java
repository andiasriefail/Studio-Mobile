package com.example.studiomobile.build;

/** Contract for a local build pipeline implementation. */
public interface BuildPipeline {
    BuildResult build(BuildConfiguration configuration, BuildLogger logger) throws Exception;
    void cancel();
}
