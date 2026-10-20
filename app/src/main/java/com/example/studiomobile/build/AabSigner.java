package com.example.studiomobile.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/** Writes a JAR-signed (v1) zip, which is the signature format Google Play and bundletool expect on an .aab. */
public final class AabSigner {
    /** One file of the bundle; the content is opened again for each pass so large files never sit in memory. */
    public interface Entry {
        String name();
        InputStream open() throws IOException;
    }

    private AabSigner() { }

    public static void write(File target, List<Entry> entries, PrivateKey key, X509Certificate certificate) throws Exception {
        List<String> digests = new ArrayList<>();
        for (Entry entry : entries) digests.add(Base64.getEncoder().encodeToString(sha256(entry)));

        StringBuilder manifest = new StringBuilder();
        manifest.append("Manifest-Version: 1.0\r\nCreated-By: Studio Mobile\r\n\r\n");
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String section = wrap("Name: " + entries.get(i).name()) + wrap("SHA-256-Digest: " + digests.get(i)) + "\r\n";
            sections.add(section);
            manifest.append(section);
        }
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);

        StringBuilder signatureFile = new StringBuilder();
        signatureFile.append("Signature-Version: 1.0\r\nCreated-By: Studio Mobile\r\n");
        signatureFile.append(wrap("SHA-256-Digest-Manifest: " + Base64.getEncoder().encodeToString(sha256(manifestBytes)))).append("\r\n");
        for (int i = 0; i < entries.size(); i++) {
            signatureFile.append(wrap("Name: " + entries.get(i).name()));
            signatureFile.append(wrap("SHA-256-Digest: " + Base64.getEncoder().encodeToString(sha256(sections.get(i).getBytes(StandardCharsets.UTF_8))))).append("\r\n");
        }
        byte[] signatureBytes = signatureFile.toString().getBytes(StandardCharsets.UTF_8);

        String algorithm = key.getAlgorithm();
        String signatureAlgorithm, blockExtension;
        if ("EC".equalsIgnoreCase(algorithm)) { signatureAlgorithm = "SHA256withECDSA"; blockExtension = "EC"; }
        else if ("DSA".equalsIgnoreCase(algorithm)) { signatureAlgorithm = "SHA256withDSA"; blockExtension = "DSA"; }
        else { signatureAlgorithm = "SHA256withRSA"; blockExtension = "RSA"; }
        ContentSigner contentSigner = new JcaContentSignerBuilder(signatureAlgorithm).build(key);
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build()).build(contentSigner, certificate));
        generator.addCertificates(new JcaCertStore(Collections.singletonList(certificate)));
        CMSSignedData signed = generator.generate(new CMSProcessableByteArray(signatureBytes), false);
        byte[] blockBytes = signed.getEncoded();

        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            putBytes(zip, "META-INF/MANIFEST.MF", manifestBytes);
            putBytes(zip, "META-INF/BNDLTOOL.SF", signatureBytes);
            putBytes(zip, "META-INF/BNDLTOOL." + blockExtension, blockBytes);
            byte[] buffer = new byte[32768];
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                try (InputStream in = entry.open()) {
                    int count;
                    while ((count = in.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
        }
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] sha256(Entry entry) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        try (InputStream in = entry.open()) {
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static byte[] sha256(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }

    /** Manifest lines are at most 72 bytes; longer lines continue on the next line behind one space. */
    static String wrap(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 70) return line + "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int position = 0, limit = 70;
        while (position < bytes.length) {
            int length = Math.min(limit, bytes.length - position);
            // never cut inside a multi-byte UTF-8 character
            while (position + length < bytes.length && (bytes[position + length] & 0xC0) == 0x80) length--;
            if (position > 0) out.write(' ');
            out.write(bytes, position, length);
            out.write('\r'); out.write('\n');
            position += length;
            limit = 69;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
