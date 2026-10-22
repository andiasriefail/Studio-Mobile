package com.example.studiomobile.build.dependency;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parses a checksum-pinned tool catalog. The caller must pin the catalog SHA-256 in app code. */
public final class BuildToolCatalog {
    /** SHA-256 of the checked-in catalog (assets/build-tools/catalog.json). Replace only alongside a reviewed app update. */
    public static final String BUNDLED_CATALOG_SHA256 = "17c087c83157ebfc4e055e06f9bedb0daf996630382d88f101460b27692c8854";
    public static final class Component {
        public final String id, version, url, installPath, sha256, license, abi, archive;
        public final long maxBytes;
        Component(JSONObject json) throws Exception {
            id = required(json, "id"); version = required(json, "version");
            url = required(json, "url"); installPath = required(json, "installPath");
            sha256 = required(json, "sha256").toLowerCase(Locale.ROOT);
            license = required(json, "license"); abi = required(json, "abi");
            maxBytes = json.getLong("maxBytes");
            // Optional, backwards compatible with catalog schema v1: "none" (single file, default), "zip", "tar.gz" or "tar.xz" (extract after verification).
            archive = json.optString("archive", "none").trim().toLowerCase(Locale.ROOT);
            if (!("none".equals(archive) || "zip".equals(archive) || "tar.xz".equals(archive) || "tar.gz".equals(archive))) throw new IllegalArgumentException("Unsupported archive type in catalog: " + id);
            if (!url.startsWith("https://") || url.contains("@") || url.contains("#")) throw new IllegalArgumentException("Catalog component URL must be a plain HTTPS URL: " + id);
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Catalog SHA-256 is invalid: " + id);
            if (maxBytes <= 0 || maxBytes > 2L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Catalog size limit is invalid: " + id);
            if (installPath.startsWith("/") || installPath.contains("..") || installPath.contains("\\")) throw new IllegalArgumentException("Unsafe catalog install path: " + id);
            if (license.trim().isEmpty() || version.trim().isEmpty()) throw new IllegalArgumentException("Version and license metadata are required: " + id);
            if (!("any".equals(abi) || "android-platform".equals(abi) || "arm64-v8a".equals(abi)
                    || "armeabi-v7a".equals(abi) || "x86_64".equals(abi) || "x86".equals(abi)))
                throw new IllegalArgumentException("Unsupported ABI label in catalog: " + abi);
        }
        public DownloadableBuildComponent toDownloadableBuildComponent() {
            return new DownloadableBuildComponent(id, url, installPath, sha256, maxBytes);
        }
        private static String required(JSONObject json, String name) {
            String value = json.optString(name, "").trim();
            if (value.isEmpty() || "null".equals(value)) throw new IllegalArgumentException("Missing catalog field: " + name);
            return value;
        }
    }

    private final List<Component> components;
    private BuildToolCatalog(List<Component> components) { this.components = Collections.unmodifiableList(components); }
    public List<Component> getComponents() { return components; }

    public static BuildToolCatalog readVerified(InputStream input, String expectedSha256) throws Exception {
        if (input == null) throw new IllegalArgumentException("Catalog stream is required.");
        if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}"))
            throw new IllegalArgumentException("A valid catalog SHA-256 must be pinned by the application before the catalog can be trusted.");
        byte[] bytes = readBounded(input, 2 * 1024 * 1024);
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equalsIgnoreCase(expectedSha256)) throw new SecurityException("Build tool catalog checksum mismatch; refusing to trust catalog metadata.");
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"studio-mobile-build-catalog-v1".equals(root.optString("schema"))) throw new IllegalArgumentException("Unsupported build catalog schema.");
        JSONArray array = root.optJSONArray("components");
        if (array == null) throw new IllegalArgumentException("Catalog components array is missing.");
        List<Component> items = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) items.add(new Component(array.getJSONObject(index)));
        return new BuildToolCatalog(items);
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, count;
            while ((count = source.read(buffer)) != -1) {
                total += count; if (total > maxBytes) throw new IOException("Build catalog exceeds the 2 MiB safety limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
