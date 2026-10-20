package com.example.studiomobile.build;

import java.io.File;
import java.util.Objects;

/** Immutable paths and options for one local build. */
public final class BuildConfiguration {
    private final File projectDirectory;
    private final File outputDirectory;
    private final boolean offlineMode;
    private final boolean bundleRequested;

    public BuildConfiguration(File projectDirectory, File outputDirectory, boolean offlineMode) { this(projectDirectory, outputDirectory, offlineMode, false); }

    /** @param bundleRequested also produce an Android App Bundle ({@link #BUNDLE_FILE_NAME}) in the output directory */
    public BuildConfiguration(File projectDirectory, File outputDirectory, boolean offlineMode, boolean bundleRequested) {
        this.bundleRequested = bundleRequested;
        this.projectDirectory = requireDirectory(projectDirectory, "projectDirectory");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
        this.offlineMode = offlineMode;
    }

    private static File requireDirectory(File value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isDirectory()) throw new IllegalArgumentException(name + " must be an existing directory");
        return value;
    }

    public File getProjectDirectory() { return projectDirectory; }
    public File getOutputDirectory() { return outputDirectory; }
    public boolean isOfflineMode() { return offlineMode; }
    public boolean isBundleRequested() { return bundleRequested; }
    public static final String BUNDLE_FILE_NAME = "bundle.aab";
}
