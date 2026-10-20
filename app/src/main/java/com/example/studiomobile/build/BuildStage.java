package com.example.studiomobile.build;

/** One independently testable stage in the on-device APK build pipeline. */
public interface BuildStage {
    String getName();
    void execute(BuildStageContext context, BuildLogger logger) throws Exception;
}
