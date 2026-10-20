package com.example.studiomobile.build.dependency;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Immutable, checksum-pinned metadata supplied by a trusted component catalogue. */
public final class DownloadableBuildComponent {
    private final String componentId;
    private final URI downloadUri;
    private final String relativeInstallPath;
    private final String sha256;
    private final long maximumBytes;

    public DownloadableBuildComponent(String componentId, String downloadUrl, String relativeInstallPath,
                                      String sha256, long maximumBytes) {
        this.componentId = requireText(componentId, "componentId");
        try { this.downloadUri = URI.create(requireText(downloadUrl, "downloadUrl")); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid component download URL", exception); }
        if (!"https".equalsIgnoreCase(downloadUri.getScheme()) || downloadUri.getHost() == null
                || downloadUri.getUserInfo() != null || downloadUri.getFragment() != null) {
            throw new IllegalArgumentException("Component downloads must use an HTTPS URL without user-info or fragments.");
        }
        this.relativeInstallPath = validateRelativePath(relativeInstallPath);
        String normalizedHash = requireText(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!normalizedHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256 must contain exactly 64 hexadecimal characters.");
        this.sha256 = normalizedHash;
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive.");
        this.maximumBytes = maximumBytes;
    }

    public String getComponentId() { return componentId; }
    public URI getDownloadUri() { return downloadUri; }
    public String getRelativeInstallPath() { return relativeInstallPath; }
    public String getSha256() { return sha256; }
    public long getMaximumBytes() { return maximumBytes; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank.");
        return value.trim();
    }

    private static String validateRelativePath(String path) {
        String value = requireText(path, "relativeInstallPath").replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Install path must be relative.");
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) throw new IllegalArgumentException("Install path contains an unsafe path segment.");
        }
        return value;
    }
}
