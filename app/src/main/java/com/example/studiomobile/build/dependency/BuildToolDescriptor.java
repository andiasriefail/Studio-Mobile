package com.example.studiomobile.build.dependency;

/** Describes a required build tool without embedding device-specific paths. */
public final class BuildToolDescriptor {
    private final String toolName;
    private final String version;
    private final String licenseIdentifier;
    private final boolean requiredForOfflineBuild;

    public BuildToolDescriptor(String toolName, String version, String licenseIdentifier, boolean requiredForOfflineBuild) {
        if (isBlank(toolName) || isBlank(version)) throw new IllegalArgumentException("Tool name and version are required");
        this.toolName = toolName;
        this.version = version;
        this.licenseIdentifier = licenseIdentifier == null ? "UNKNOWN" : licenseIdentifier;
        this.requiredForOfflineBuild = requiredForOfflineBuild;
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
    public String getToolName() { return toolName; }
    public String getVersion() { return version; }
    public String getLicenseIdentifier() { return licenseIdentifier; }
    public boolean isRequiredForOfflineBuild() { return requiredForOfflineBuild; }
}
