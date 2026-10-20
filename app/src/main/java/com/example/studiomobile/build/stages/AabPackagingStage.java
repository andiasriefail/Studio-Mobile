package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.AabSigner;
import com.example.studiomobile.build.BuildConfiguration;
import com.example.studiomobile.build.BuildExecutableResolver;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds an Android App Bundle (.aab) from the outputs of the APK stages, without bundletool.
 *
 * <p>The linked resource APK is converted to protobuf format with {@code aapt2 convert}; its manifest, {@code resources.pb} and {@code res/}
 * go to {@code base/}. DEX files, native libraries, assets and the other files come from the unsigned APK. The result is
 * JAR-signed with the project's key and written to {@code <output>/bundle.aab}.
 */
public final class AabPackagingStage implements BuildStage {
    private final File tools; private final BuildProcessRunner runner;
    public AabPackagingStage(File tools, BuildProcessRunner runner) { this.tools = tools; this.runner = runner == null ? new BuildProcessRunner() : runner; }
    @Override public String getName() { return "Android App Bundle packaging (AAB)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File resourceApk = context.getOutput("resourceApk"), unsignedApk = context.getOutput("unsignedApk");
        if (resourceApk == null || !resourceApk.isFile()) throw new IllegalStateException("Linked resource APK is missing; cannot create the bundle.");
        if (unsignedApk == null || !unsignedApk.isFile()) throw new IllegalStateException("Unsigned APK is missing; cannot create the bundle.");
        File aapt2 = BuildExecutableResolver.resolve(tools, "aapt2");
        if (aapt2 == null) throw new IllegalStateException("AAPT2 executable is missing or not executable.");
        File protoApk = new File(context.getWorkingDirectory(), "resources-proto.ap_");
        List<String> command = new ArrayList<>();
        command.add(aapt2.getAbsolutePath()); command.add("convert");
        command.add("-o"); command.add(protoApk.getAbsolutePath());
        command.add("--output-format"); command.add("proto");
        command.add(resourceApk.getAbsolutePath());
        BuildProcessRunner.Result converted = runner.run(command, context.getWorkingDirectory(), 180, logger);
        if (!converted.isSuccessful() || !protoApk.isFile() || protoApk.length() == 0)
            throw new IllegalStateException("AAPT2 could not convert the resources to the bundle format. This AAPT2 version may not support 'aapt2 convert'.");

        ApkSigningStage.SigningMaterial signing = ApkSigningStage.loadSigningMaterial(tools);
        List<AabSigner.Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try (ZipFile proto = new ZipFile(protoApk); ZipFile apk = new ZipFile(unsignedApk)) {
            if (proto.getEntry("resources.pb") == null) throw new IllegalStateException("The converted resources have no resources.pb.");
            Enumeration<? extends ZipEntry> protoEntries = proto.entries();
            while (protoEntries.hasMoreElements()) {
                ZipEntry entry = protoEntries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String target;
                if (name.equals("AndroidManifest.xml")) target = "base/manifest/AndroidManifest.xml";
                else if (name.equals("resources.pb")) target = "base/resources.pb";
                else if (name.startsWith("res/")) target = "base/" + name;
                else continue;
                if (names.add(target)) entries.add(zipEntry(proto, entry, target));
            }
            Enumeration<? extends ZipEntry> apkEntries = apk.entries();
            while (apkEntries.hasMoreElements()) {
                ZipEntry entry = apkEntries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.equals("AndroidManifest.xml") || name.equals("resources.arsc") || name.startsWith("res/")) continue;   // replaced by the proto versions
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec") || lower.endsWith("manifest.mf"))) continue;
                String target;
                if (name.matches("classes\\d*\\.dex")) target = "base/dex/" + name;
                else if (name.startsWith("lib/") || name.startsWith("assets/")) target = "base/" + name;
                else target = "base/root/" + name;
                if (names.add(target)) entries.add(zipEntry(apk, entry, target));
            }
            entries.add(bytesEntry("BundleConfig.pb", bundleConfig()));
            File bundle = new File(context.getOutputDirectory(), BuildConfiguration.BUNDLE_FILE_NAME);
            if (bundle.exists() && !bundle.delete()) throw new IllegalStateException("Could not replace the previous bundle.");
            AabSigner.write(bundle, entries, signing.privateKey, signing.certificate);
            if (!bundle.isFile() || bundle.length() == 0) throw new IllegalStateException("Bundle packaging produced no output.");
            context.putOutput("bundle", bundle);
            if (logger != null) {
                logger.onInfo("Created Android App Bundle: " + bundle.getAbsolutePath());
                if (!signing.custom) logger.onWarning("The bundle is signed with the default Studio Mobile key. Google Play needs your own key.");
            }
        }
    }

    /** Minimal BundleConfig protobuf: {@code bundletool { version = "1.15.6" }}. */
    private static byte[] bundleConfig() {
        byte[] version = "1.15.6".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] inner = new byte[2 + version.length];
        inner[0] = 0x12; inner[1] = (byte) version.length;
        System.arraycopy(version, 0, inner, 2, version.length);
        byte[] outer = new byte[2 + inner.length];
        outer[0] = 0x0A; outer[1] = (byte) inner.length;
        System.arraycopy(inner, 0, outer, 2, inner.length);
        return outer;
    }

    private static AabSigner.Entry zipEntry(final ZipFile zip, final ZipEntry entry, final String name) {
        return new AabSigner.Entry() {
            @Override public String name() { return name; }
            @Override public InputStream open() throws IOException { return zip.getInputStream(entry); }
        };
    }

    private static AabSigner.Entry bytesEntry(final String name, final byte[] bytes) {
        return new AabSigner.Entry() {
            @Override public String name() { return name; }
            @Override public InputStream open() { return new java.io.ByteArrayInputStream(bytes); }
        };
    }
}
