package com.example.studiomobile.build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Finds problems that compile fine but are certain to crash the app, so the build can stop before an APK exists.
 * Only decidable cases are reported (no guessing): warnings that are not crashes (unused import, deprecation...) never stop a build.
 */
public final class CrashRiskAnalyzer {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String[] COMPONENT_TAGS = {"activity", "service", "receiver", "provider"};
    private static final int EXPORTED_REQUIRED_FROM_SDK = 31;
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)");
    /** Only top-level classes (declared at the start of a line), so nested classes are never mistaken for manifest entries. */
    private static final Pattern TOP_LEVEL_CLASS = Pattern.compile("(?m)^(?:(?:public|final|abstract|open|internal|private)\\s+)*class\\s+([A-Za-z_]\\w*)");
    private static final Pattern ACTIVITY_SUPERCLASS = Pattern.compile("(?:\\bextends\\s+|:\\s*)[\\w.]*Activity\\b");
    private static final Pattern JAVA_INTENT = Pattern.compile("new\\s+Intent\\s*\\(\\s*(?:[^,()]|\\(\\))+?\\s*,\\s*([A-Za-z_][\\w.]*)\\s*\\.\\s*class\\s*\\)");
    private static final Pattern KOTLIN_INTENT = Pattern.compile("\\bIntent\\s*\\(\\s*(?:[^,()]|\\(\\))+?\\s*,\\s*([A-Za-z_][\\w.]*)\\s*::\\s*class\\s*\\.\\s*java\\s*\\)");
    private static final Pattern NETWORK_USE = Pattern.compile("\\bHttpURLConnection\\b|\\bHttpsURLConnection\\b|\\.openConnection\\s*\\(|\\bnew\\s+Socket\\s*\\(");
    private static final String INTERNET_PERMISSION = "android.permission.INTERNET";
    private CrashRiskAnalyzer() { }

    /**
     * Compiler diagnostics that describe a guaranteed runtime crash. ECJ reports "Null pointer access: The variable x can only be
     * null at this location" only when the value is definitely null (the "Potential null pointer access" form is not matched).
     * Returns the full diagnostic blocks; empty when there is nothing fatal.
     */
    public static List<String> findFatalDiagnostics(String diagnostics) {
        List<String> fatal = new ArrayList<>();
        if (diagnostics == null || diagnostics.isEmpty()) return fatal;
        for (String block : diagnostics.split("-{10,}")) {
            if (block.contains("Null pointer access:") || block.contains("can only be null at this location")) fatal.add(block.trim());
        }
        return fatal;
    }

    /**
     * Components declared in the manifest under the app's own package whose class was not compiled. Android would throw
     * ClassNotFoundException the moment the component starts (for the launcher activity: the app closes right after opening).
     */
    public static List<String> findMissingManifestComponents(File manifest, File classesDirectory, String appPackage) throws Exception {
        List<String> missing = new ArrayList<>();
        if (manifest == null || !manifest.isFile() || classesDirectory == null) return missing;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Android's built-in XML parser rejects some of these features; they are hardening only, so ignore when unsupported.
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl");
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(manifest);
        Element root = document.getDocumentElement();
        String pkg = root.getAttribute("package");
        if (pkg == null || pkg.isEmpty()) pkg = appPackage;
        if (pkg == null || pkg.isEmpty()) return missing;
        for (String tag : COMPONENT_TAGS) {
            NodeList nodes = document.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element)) continue;
                String name = ((Element) node).getAttributeNS(ANDROID_NS, "name");
                if (name == null || name.isEmpty() || name.contains("$")) continue;
                String className = resolve(pkg, name);
                // Classes from libraries live in JARs, not in the app's class directory: only the app's own package is checked.
                if (!className.startsWith(pkg + ".")) continue;
                File classFile = new File(classesDirectory, className.replace('.', File.separatorChar) + ".class");
                if (!classFile.isFile()) missing.add("<" + tag + " android:name=\"" + name + "\"> refers to class " + className + ", which does not exist in the compiled code");
            }
        }
        return missing;
    }

    /**
     * Activities the code starts with {@code new Intent(context, Name.class)} that the manifest never declares. Android throws
     * ActivityNotFoundException the moment the Intent is started. Only activity classes found in the app's own sources are checked.
     */
    public static List<String> findUndeclaredActivities(File manifest, List<File> sources, String appPackage) throws Exception {
        List<String> missing = new ArrayList<>();
        if (manifest == null || !manifest.isFile() || sources == null || sources.isEmpty()) return missing;
        Document document = parseManifest(manifest);
        String pkg = manifestPackage(document, appPackage);
        if (pkg == null) return missing;
        Set<String> declared = new HashSet<>();
        for (String tag : new String[] {"activity", "activity-alias"}) {
            NodeList nodes = document.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element)) continue;
                Element element = (Element) node;
                addResolved(declared, pkg, element.getAttributeNS(ANDROID_NS, "name"));
                addResolved(declared, pkg, element.getAttributeNS(ANDROID_NS, "targetActivity"));
            }
        }
        Map<File, String> texts = new HashMap<>();
        Map<String, String> activityPackages = new HashMap<>();   // full class name -> package
        for (File source : sources) {
            String text = readSource(source);
            if (text == null) continue;
            texts.put(source, text);
            String filePackage = findPackage(text);
            Matcher matcher = TOP_LEVEL_CLASS.matcher(text);
            while (matcher.find()) {
                int headerEnd = text.indexOf('{', matcher.end());
                String header = text.substring(matcher.end(), headerEnd < 0 ? Math.min(text.length(), matcher.end() + 400) : Math.min(headerEnd, matcher.end() + 400));
                if (ACTIVITY_SUPERCLASS.matcher(header).find()) {
                    String simple = matcher.group(1);
                    activityPackages.put(filePackage.isEmpty() ? simple : filePackage + "." + simple, filePackage);
                }
            }
        }
        Set<String> reported = new HashSet<>();
        for (Map.Entry<File, String> entry : texts.entrySet()) {
            String text = entry.getValue();
            String filePackage = findPackage(text);
            for (Pattern pattern : new Pattern[] {JAVA_INTENT, KOTLIN_INTENT}) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    String target = findActivityClass(matcher.group(1), filePackage, text, activityPackages);
                    if (target != null && !declared.contains(target) && reported.add(target))
                        missing.add("Activity " + target + " is started with an Intent but is not declared in AndroidManifest.xml (add <activity android:name=\"" + target + "\" />)");
                }
            }
        }
        return missing;
    }

    /**
     * Components with an intent-filter and no android:exported. From targetSdkVersion 31 Android refuses to install such an APK.
     */
    public static List<String> findMissingExported(File manifest, int targetSdk) throws Exception {
        List<String> problems = new ArrayList<>();
        if (manifest == null || !manifest.isFile() || targetSdk < EXPORTED_REQUIRED_FROM_SDK) return problems;
        Document document = parseManifest(manifest);
        for (String tag : new String[] {"activity", "activity-alias", "service", "receiver", "provider"}) {
            NodeList nodes = document.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element)) continue;
                Element element = (Element) node;
                if (element.hasAttributeNS(ANDROID_NS, "exported") || !hasIntentFilter(element)) continue;
                problems.add("<" + tag + " android:name=\"" + element.getAttributeNS(ANDROID_NS, "name") + "\"> has an intent-filter but no android:exported;"
                    + " targetSdkVersion " + targetSdk + " requires it and the APK could not be installed");
            }
        }
        return problems;
    }

    /**
     * A warning, never a build stop: the sources use network classes but the manifest has no INTERNET permission, which makes the
     * first request fail with a SecurityException (unless a library adds the permission). Returns null when nothing looks wrong.
     */
    public static String findMissingInternetPermission(File manifest, List<File> sources) throws Exception {
        if (manifest == null || !manifest.isFile() || sources == null) return null;
        Document document = parseManifest(manifest);
        NodeList permissions = document.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            Node node = permissions.item(i);
            if (node instanceof Element && INTERNET_PERMISSION.equals(((Element) node).getAttributeNS(ANDROID_NS, "name"))) return null;
        }
        for (File source : sources) {
            String text = readSource(source);
            if (text != null && NETWORK_USE.matcher(text).find())
                return source.getName() + " uses network classes but AndroidManifest.xml has no <uses-permission android:name=\"" + INTERNET_PERMISSION
                    + "\" />; the request would fail at runtime unless a library adds the permission";
        }
        return null;
    }

    private static boolean hasIntentFilter(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element && "intent-filter".equals(child.getNodeName())) return true;
        return false;
    }

    /** The class the Intent refers to when it is an activity of the app: same package, an explicit import, or the only one with that name. */
    private static String findActivityClass(String reference, String filePackage, String text, Map<String, String> activityPackages) {
        if (reference.contains(".")) return activityPackages.containsKey(reference) ? reference : null;
        String sameFile = filePackage.isEmpty() ? reference : filePackage + "." + reference;
        if (activityPackages.containsKey(sameFile)) return sameFile;
        Matcher imports = IMPORT_DECLARATION.matcher(text);
        while (imports.find()) if (imports.group(1).endsWith("." + reference) && activityPackages.containsKey(imports.group(1))) return imports.group(1);
        String only = null;
        for (String candidate : activityPackages.keySet()) {
            if (candidate.equals(reference) || candidate.endsWith("." + reference)) { if (only != null) return null; only = candidate; }
        }
        return only;
    }

    private static String findPackage(String text) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void addResolved(Set<String> names, String pkg, String name) {
        if (name != null && !name.isEmpty()) names.add(resolve(pkg, name));
    }

    private static String manifestPackage(Document document, String appPackage) {
        String pkg = document.getDocumentElement().getAttribute("package");
        if (pkg == null || pkg.isEmpty()) pkg = appPackage;
        return pkg == null || pkg.isEmpty() ? null : pkg;
    }

    private static Document parseManifest(File manifest) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl");
        return factory.newDocumentBuilder().parse(manifest);
    }

    /** Source text without comments, so commented-out code is not counted. Null when the file cannot be read. */
    private static String readSource(File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(?<!:)//.*$", "");
        } catch (Exception unreadable) { return null; }
    }

    static String resolve(String pkg, String name) {
        if (name.startsWith(".")) return pkg + name;
        if (!name.contains(".")) return pkg + "." + name;
        return name;
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature) {
        try { factory.setFeature(feature, true); } catch (Exception | LinkageError ignored) { }
    }
}
