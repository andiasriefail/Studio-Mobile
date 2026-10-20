package com.example.studiomobile.build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared paths and stage outputs for one build invocation. */
public final class BuildStageContext {
    private final BuildConfiguration configuration;
    private final File workingDirectory;
    private final Map<String, File> outputs = new LinkedHashMap<>();
    private final Map<String, List<File>> fileLists = new LinkedHashMap<>();

    public BuildStageContext(BuildConfiguration configuration, File workingDirectory) {
        if (configuration == null || workingDirectory == null) throw new IllegalArgumentException("Build configuration and working directory are required");
        this.configuration = configuration;
        this.workingDirectory = workingDirectory;
    }
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    /** Free-form values that one stage hands to another (for example a diagnostic hint); null when absent. */
    public void putAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }
    public BuildConfiguration getConfiguration() { return configuration; }
    public File getProjectDirectory() { return configuration.getProjectDirectory(); }
    public File getOutputDirectory() { return configuration.getOutputDirectory(); }
    public File getWorkingDirectory() { return workingDirectory; }
    public void putOutput(String key, File value) { outputs.put(key, value); }
    public File getOutput(String key) { return outputs.get(key); }
    /** Stores an ordered list of files (for example the extracted AAR folders) for later stages. The list is copied. */
    public void putFiles(String key, List<File> files) { fileLists.put(key, files == null ? new ArrayList<File>() : new ArrayList<>(files)); }
    /** Returns the stored list, or an empty list when no stage stored one under this key. Never null; not modifiable. */
    public List<File> getFiles(String key) {
        List<File> files = fileLists.get(key);
        return files == null ? Collections.<File>emptyList() : Collections.unmodifiableList(files);
    }
}
