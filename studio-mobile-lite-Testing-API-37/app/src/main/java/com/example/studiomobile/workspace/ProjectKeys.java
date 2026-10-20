package com.example.studiomobile.workspace;

import com.example.studiomobile.build.stages.ApkSigningStage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;

/**
 * One signing key per project. Files live in {@code <files>/project-keys/<project>/}: {@code key.p12} and {@code signing.properties}.
 * A project without those files is signed with the default Studio Mobile key.
 * Before every build {@link #applyToBuild} copies the project's choice to {@code build-tools/custom-signing.properties}, which the signing stages read.
 */
public final class ProjectKeys {
    private static final String KEYSTORE_NAME = "key.p12", PROPERTIES_NAME = "signing.properties";
    private final File base;

    public ProjectKeys(File filesDir) { this.base = new File(filesDir, "project-keys"); }

    /** Everything the Keystore page shows about the key of one project. */
    public static final class Details {
        public boolean isDefault;
        public String name = "", alias = "", storePassword = "", keyPassword = "", subject = "", validUntil = "", sha256 = "", sha1 = "", base64 = "";

        public String toClipboardText() {
            return "Keystore: " + name + "\nType: PKCS12\nAlias: " + alias + "\nStore password: " + storePassword + "\nKey password: " + keyPassword
                + "\nOwner: " + subject + "\nValid until: " + validUntil + "\nSHA-256: " + sha256 + "\nSHA-1: " + sha1 + "\n\nKeystore (Base64):\n" + base64;
        }
    }

    public File dir(String project) { return new File(base, project); }
    public File keystoreFile(String project) { return new File(dir(project), KEYSTORE_NAME); }
    private File propertiesFile(String project) { return new File(dir(project), PROPERTIES_NAME); }

    /** True when the project has its own key (not the Studio Mobile default). */
    public boolean isCustom(String project) { return propertiesFile(project).isFile() && keystoreFile(project).isFile(); }

    public Properties read(String project) {
        File file = propertiesFile(project);
        if (!file.isFile()) return null;
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) { properties.load(in); return properties; } catch (IOException e) { return null; }
    }

    /** Stores a keystore file and its credentials as the key of this project. The source file is copied, never moved. */
    public void install(String project, File source, String name, String alias, String storePassword, String keyPassword) throws IOException {
        File dir = dir(project);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the key folder.");
        File target = keystoreFile(project), temporary = new File(dir, KEYSTORE_NAME + ".tmp");
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace the old keystore.");
        if (!temporary.renameTo(target)) throw new IOException("Could not store the keystore.");
        Properties properties = new Properties();
        properties.setProperty("name", name);
        properties.setProperty("alias", alias);
        properties.setProperty("storePassword", storePassword);
        properties.setProperty("keyPassword", keyPassword.isEmpty() ? storePassword : keyPassword);
        try (OutputStream out = new FileOutputStream(propertiesFile(project))) { properties.store(out, null); }
    }

    /** Creates a new key (RSA 2048, valid 30 years, so it covers Google Play's 2033 minimum) and makes it the key of this project. */
    public void generate(String project, String alias, String password, String commonName, String organization, String unit,
                         String city, String state, String country) throws Exception {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        builder.addRDN(BCStyle.CN, commonName);
        if (!unit.isEmpty()) builder.addRDN(BCStyle.OU, unit);
        if (!organization.isEmpty()) builder.addRDN(BCStyle.O, organization);
        if (!city.isEmpty()) builder.addRDN(BCStyle.L, city);
        if (!state.isEmpty()) builder.addRDN(BCStyle.ST, state);
        builder.addRDN(BCStyle.C, country.toUpperCase(Locale.ROOT));
        X500Name subject = builder.build();
        File temporary = new File(base, "generate-" + System.nanoTime() + ".p12");
        try {
            ApkSigningStage.generateKeyStore(temporary, alias, password.toCharArray(), subject, 30);
            install(project, temporary, alias, alias, password, password);
        } finally { temporary.delete(); }
    }

    /** Goes back to the default Studio Mobile key. */
    public void clear(String project) { deleteRecursively(dir(project)); }

    public void rename(String oldName, String newName) {
        File from = dir(oldName), to = dir(newName);
        if (from.isDirectory() && !to.exists()) from.renameTo(to);
    }

    /** Makes the next build use this project's key (or the default key when it has none). */
    public void applyToBuild(File tools, String project) throws IOException {
        File target = new File(tools, "custom-signing.properties");
        Properties properties = isCustom(project) ? read(project) : null;
        if (properties == null) {
            if (target.exists() && !target.delete()) throw new IOException("Could not reset the signing configuration.");
            return;
        }
        if (!tools.isDirectory() && !tools.mkdirs()) throw new IOException("Could not create the build tools folder.");
        properties.setProperty("keystore", keystoreFile(project).getAbsolutePath());
        try (OutputStream out = new FileOutputStream(target)) { properties.store(out, null); }
    }

    /** Reads the key of this project for the Keystore page. Does file I/O: call it off the UI thread. */
    public Details details(String project, File tools) throws Exception {
        Details details = new Details();
        File file; String alias, storePassword, keyPassword;
        if (isCustom(project)) {
            Properties properties = read(project);
            if (properties == null) throw new IOException("The key settings could not be read.");
            file = keystoreFile(project);
            details.name = properties.getProperty("name", properties.getProperty("alias", "key"));
            alias = properties.getProperty("alias", "");
            storePassword = properties.getProperty("storePassword", "");
            keyPassword = properties.getProperty("keyPassword", storePassword);
        } else {
            file = ApkSigningStage.ensureDebugKeyStore(tools, null);
            details.isDefault = true;
            details.name = "Studio Mobile (default)";
            alias = ApkSigningStage.DEBUG_KEY_ALIAS;
            storePassword = ApkSigningStage.DEBUG_STORE_PASSWORD;
            keyPassword = storePassword;
        }
        details.alias = alias; details.storePassword = storePassword; details.keyPassword = keyPassword;
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(file)) { store.load(in, storePassword.toCharArray()); }
        java.security.cert.Certificate certificate = store.getCertificate(alias);
        if (certificate instanceof X509Certificate) {
            X509Certificate x509 = (X509Certificate) certificate;
            details.subject = x509.getSubjectX500Principal().getName();
            details.validUntil = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(x509.getNotAfter());
            details.sha256 = fingerprint("SHA-256", x509.getEncoded());
            details.sha1 = fingerprint("SHA-1", x509.getEncoded());
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        details.base64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
        return details;
    }

    private static String fingerprint(String algorithm, byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < digest.length; i++) { if (i > 0) text.append(':'); text.append(String.format(Locale.US, "%02X", digest[i] & 0xFF)); }
        return text.toString();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
