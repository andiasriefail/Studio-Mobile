package com.example.studiomobile.build.stages;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

/** Signs and verifies APKs through apksig APIs; no keytool/apksigner process is launched. */
public final class ApkSigningStage implements BuildStage {
    public static final String DEBUG_KEYSTORE_FILE = "studio-mobile-debug.p12";
    public static final String DEBUG_KEY_ALIAS = "studio-mobile-debug";
    public static final String DEBUG_STORE_PASSWORD = "studio-mobile-local-debug";
    private static final char[] STORE_PASSWORD = DEBUG_STORE_PASSWORD.toCharArray();
    private static final String KEY_ALIAS = DEBUG_KEY_ALIAS;
    private final File tools;
    private final com.example.studiomobile.build.process.BuildProcessRunner processRunner;

    public ApkSigningStage(File tools) { this(tools, new com.example.studiomobile.build.process.BuildProcessRunner()); }
    public ApkSigningStage(File tools, com.example.studiomobile.build.process.BuildProcessRunner processRunner) {
        this.tools = tools;
        this.processRunner = processRunner == null ? new com.example.studiomobile.build.process.BuildProcessRunner() : processRunner;
    }
    @Override public String getName() { return "APK signing and verification (in-process apksig)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File aligned = context.getOutput("alignedApk");
        if (aligned == null || !aligned.isFile() || aligned.length() == 0) throw new IllegalStateException("Aligned APK is missing.");
        File keyStoreFile = new File(tools, DEBUG_KEYSTORE_FILE);
        File parent = keyStoreFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Could not create signing key directory.");
        File signingStoreFile = keyStoreFile;
        char[] signingStorePassword = STORE_PASSWORD, signingKeyPassword = STORE_PASSWORD;
        String signingAlias = KEY_ALIAS;
        boolean customKey = false;
        File customConfig = new File(tools, "custom-signing.properties");
        if (customConfig.isFile()) {
            java.util.Properties custom = new java.util.Properties();
            try (FileInputStream configInput = new FileInputStream(customConfig)) { custom.load(configInput); }
            signingStoreFile = new File(custom.getProperty("keystore", ""));
            if (!signingStoreFile.isFile()) throw new IllegalStateException("The selected signing keystore file is missing. Choose another key in Keystore.");
            signingAlias = custom.getProperty("alias", "");
            signingStorePassword = custom.getProperty("storePassword", "").toCharArray();
            signingKeyPassword = custom.getProperty("keyPassword", custom.getProperty("storePassword", "")).toCharArray();
            customKey = true;
            if (logger != null) logger.onInfo("Signing with imported key: " + custom.getProperty("name", signingAlias));
        } else if (!keyStoreFile.isFile()) createDebugKeyStore(keyStoreFile, logger);
        KeyMaterial key = loadKey(signingStoreFile, signingStorePassword, signingKeyPassword, signingAlias);

        File signed = new File(context.getOutputDirectory(), "app-debug.apk").getAbsoluteFile();
        File outputParent = signed.getParentFile();
        if (outputParent != null && !outputParent.isDirectory() && !outputParent.mkdirs()) throw new IllegalStateException("Could not create APK output directory.");
        File temporary = new File(outputParent, signed.getName() + ".tmp");
        if (temporary.exists() && !temporary.delete()) throw new IllegalStateException("Could not clear temporary signed APK.");
        String primaryFailure = null;
        try {
            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(customKey ? "Studio Mobile Custom" : "Studio Mobile Debug", key.privateKey, Collections.singletonList(key.certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setInputApk(aligned)
                    .setOutputApk(temporary)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build().sign();
            if (!temporary.isFile() || temporary.length() == 0) throw new IllegalStateException("apksig returned without producing a signed APK.");
            ApkVerifier.Result verification = new ApkVerifier.Builder(temporary).build().verify();
            if (!verification.isVerified()) throw new IllegalStateException("APK signature verification failed: " + verification.getErrors());
        } catch (Exception | LinkageError apiFailure) {
            primaryFailure = apiFailure.getClass().getName() + ": " + String.valueOf(apiFailure.getMessage());
            if (temporary.exists() && !temporary.delete()) throw new IllegalStateException("In-process apksig failed and temporary output could not be cleared. Original failure: " + primaryFailure, apiFailure);
            File apksigner = findExecutable(new File(tools, "apksigner"));
            if (apksigner == null) throw new IllegalStateException("In-process apksig failed and no executable apksigner fallback is installed. Original failure: " + primaryFailure, apiFailure);
            com.example.studiomobile.build.process.BuildProcessRunner.Result signResult =
                    processRunner.run(
                    java.util.Arrays.asList(apksigner.getAbsolutePath(), "sign", "--ks", signingStoreFile.getAbsolutePath(),
                            "--ks-pass", "pass:" + new String(signingStorePassword), "--key-pass", "pass:" + new String(signingKeyPassword),
                            "--ks-key-alias", signingAlias, "--out", temporary.getAbsolutePath(), aligned.getAbsolutePath()),
                    context.getWorkingDirectory(), 120, logger);
            if (!signResult.isSuccessful() || !temporary.isFile() || temporary.length() == 0)
                throw new IllegalStateException("apksigner fallback failed. In-process error: " + primaryFailure);
            com.example.studiomobile.build.process.BuildProcessRunner.Result verifyResult =
                    processRunner.run(
                    java.util.Arrays.asList(apksigner.getAbsolutePath(), "verify", "--verbose", temporary.getAbsolutePath()),
                    context.getWorkingDirectory(), 60, logger);
            if (!verifyResult.isSuccessful()) throw new IllegalStateException("apksigner fallback produced an APK that failed verification.");
            if (logger != null) logger.onWarning("Signing used executable apksigner fallback because in-process apksig failed: " + primaryFailure);
        }
        if (signed.exists() && !signed.delete()) throw new IllegalStateException("Could not replace previous APK output.");
        if (!temporary.renameTo(signed)) {
            try (FileInputStream input = new FileInputStream(temporary); FileOutputStream output = new FileOutputStream(signed)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            if (!temporary.delete()) temporary.deleteOnExit();
        }
        context.putOutput("signedApk", signed);
        if (logger != null) logger.onInfo("Signed debug APK verified in-process: " + signed.getAbsolutePath());
    }

    private File findExecutable(File preferred) {
        return com.example.studiomobile.build.BuildExecutableResolver.resolve(tools, "apksigner");
    }

    /** Creates the default Studio Mobile debug keystore if it does not exist yet, so Keystore works before the first build. */
    public static File ensureDebugKeyStore(File tools, BuildLogger logger) throws Exception {
        File file = new File(tools, DEBUG_KEYSTORE_FILE);
        if (file.isFile()) return file;
        if (!tools.isDirectory() && !tools.mkdirs()) throw new IllegalStateException("Could not create signing key directory.");
        createDebugKeyStore(file, logger);
        return file;
    }

    private static void createDebugKeyStore(File file, BuildLogger logger) throws Exception {
        // Do not use the "BC" provider: Android ships its own stripped-down "BC" provider that has no SHA256withRSA,
        // and it shadows the bundled BouncyCastle. BouncyCastle is only used here to build the certificate; signing and
        // certificate conversion go through the platform providers (Conscrypt).
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000);
        X500Name subject = new X500Name("CN=Studio Mobile Debug,O=Studio Mobile,C=ID");
        BigInteger serial = new BigInteger(160, new java.security.SecureRandom()).abs().add(BigInteger.ONE);
        JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X509CertificateHolder holder = certificateBuilder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
        certificate.verify(pair.getPublic());
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, STORE_PASSWORD);
        store.setKeyEntry(KEY_ALIAS, pair.getPrivate(), STORE_PASSWORD, new java.security.cert.Certificate[]{certificate});
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { store.store(output, STORE_PASSWORD); output.getFD().sync(); }
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace debug keystore.");
        if (!temporary.renameTo(file)) throw new IllegalStateException("Could not commit debug keystore file.");
        if (logger != null) logger.onWarning("Created a local debug signing key. Do not use this key for production publishing.");
    }

    /** Key and certificate used to sign a build (custom project key when configured, otherwise the default debug key). */
    public static final class SigningMaterial {
        public final PrivateKey privateKey; public final X509Certificate certificate; public final boolean custom;
        SigningMaterial(PrivateKey privateKey, X509Certificate certificate, boolean custom) { this.privateKey = privateKey; this.certificate = certificate; this.custom = custom; }
    }

    /** Loads the key the next build signs with. Reads {@code custom-signing.properties} (written per project before each build). */
    public static SigningMaterial loadSigningMaterial(File tools) throws Exception {
        File customConfig = new File(tools, "custom-signing.properties");
        File storeFile; char[] storePassword, keyPassword; String alias; boolean custom = false;
        if (customConfig.isFile()) {
            java.util.Properties custom1 = new java.util.Properties();
            try (FileInputStream configInput = new FileInputStream(customConfig)) { custom1.load(configInput); }
            storeFile = new File(custom1.getProperty("keystore", ""));
            if (!storeFile.isFile()) throw new IllegalStateException("The signing keystore file of this project is missing. Open Keystore and choose a key.");
            alias = custom1.getProperty("alias", "");
            storePassword = custom1.getProperty("storePassword", "").toCharArray();
            keyPassword = custom1.getProperty("keyPassword", custom1.getProperty("storePassword", "")).toCharArray();
            custom = true;
        } else {
            storeFile = ensureDebugKeyStore(tools, null);
            alias = KEY_ALIAS; storePassword = STORE_PASSWORD; keyPassword = STORE_PASSWORD;
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(storeFile)) { store.load(input, storePassword); }
        PrivateKey privateKey = (PrivateKey) store.getKey(alias, keyPassword);
        X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
        if (privateKey == null || certificate == null) throw new IllegalStateException("The signing keystore does not contain the expected key and certificate.");
        return new SigningMaterial(privateKey, certificate, custom);
    }

    /** Creates a PKCS12 keystore with one RSA 2048 key; the key password equals the store password. */
    public static void generateKeyStore(File file, String alias, char[] password, org.bouncycastle.asn1.x500.X500Name subject, int validityYears) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis() + validityYears * 365L * 24 * 60 * 60 * 1000);
        BigInteger serial = new BigInteger(160, new java.security.SecureRandom()).abs().add(BigInteger.ONE);
        JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
        certificate.verify(pair.getPublic());
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password);
        store.setKeyEntry(alias, pair.getPrivate(), password, new java.security.cert.Certificate[]{certificate});
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Could not create the keystore directory.");
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { store.store(output, password); output.getFD().sync(); }
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace the keystore file.");
        if (!temporary.renameTo(file)) throw new IllegalStateException("Could not commit the keystore file.");
    }

    private KeyMaterial loadKey(File file, char[] storePassword, char[] keyPassword, String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(file)) { store.load(input, storePassword); }
        PrivateKey privateKey = (PrivateKey) store.getKey(alias, keyPassword);
        X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
        if (privateKey == null || certificate == null) throw new IllegalStateException("The signing keystore does not contain the expected key and certificate.");
        return new KeyMaterial(privateKey, certificate);
    }

    private static final class KeyMaterial {
        final PrivateKey privateKey; final X509Certificate certificate;
        KeyMaterial(PrivateKey privateKey, X509Certificate certificate) { this.privateKey = privateKey; this.certificate = certificate; }
    }
}
