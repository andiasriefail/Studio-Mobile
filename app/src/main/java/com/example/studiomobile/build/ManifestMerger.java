package com.example.studiomobile.build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Deliberately small manifest merger for AAR libraries. It copies uses-permission, permission, uses-feature, queries and the
 * application-level activity/service/receiver/provider/meta-data/uses-library elements of each library manifest into the app manifest.
 * An element is dropped when the app (or an earlier library) already has one with the same android:name, so the app always wins.
 * ${applicationId} is replaced and relative component names (".Foo" or "Foo") get the library package.
 * Not supported on purpose: tools:node / tools:replace, attribute merging, and the other parts of the full AGP merger.
 */
public final class ManifestMerger {
    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String TOOLS_NS = "http://schemas.android.com/tools";
    private static final Set<String> COMPONENTS = new HashSet<>(Arrays.asList("activity", "service", "receiver", "provider"));
    private static final Set<String> TOP_LEVEL = new HashSet<>(Arrays.asList("uses-permission", "permission", "uses-feature"));
    private ManifestMerger() { }

    /** Writes the merged manifest to {@code output}; returns how many elements were copied from the libraries. */
    public static int merge(File appManifest, List<File> libraryManifests, String applicationId, File output) throws IOException {
        Document app = parse(appManifest);
        Element root = app.getDocumentElement();
        Set<String> known = new HashSet<>();
        for (Element child : children(root)) {
            if (TOP_LEVEL.contains(child.getNodeName())) known.add(key(child));
            else if (child.getNodeName().equals("queries")) for (Element query : children(child)) known.add("queries|" + canonical(query));
            else if (child.getNodeName().equals("application")) for (Element item : children(child)) known.add(key(item));
        }
        int added = 0;
        for (File libraryManifest : libraryManifests) {
            Element library = parse(libraryManifest).getDocumentElement();
            String libraryPackage = emptyToNull(library.getAttribute("package"));
            for (Element child : children(library)) {
                String tag = child.getNodeName();
                if (TOP_LEVEL.contains(tag)) {
                    Element copy = prepare(app, child, libraryPackage, applicationId);
                    if (key(copy).endsWith("|") || !known.add(key(copy))) continue;
                    insertBeforeApplication(root, copy); added++;
                } else if (tag.equals("queries")) {
                    for (Element query : children(child)) {
                        Element copy = prepare(app, query, libraryPackage, applicationId);
                        if (!known.add("queries|" + canonical(copy))) continue;
                        Element queries = firstChild(root, "queries");
                        if (queries == null) { queries = app.createElement("queries"); insertBeforeApplication(root, queries); }
                        queries.appendChild(copy); added++;
                    }
                } else if (tag.equals("application")) {
                    for (Element item : children(child)) {
                        String itemTag = item.getNodeName();
                        if (!COMPONENTS.contains(itemTag) && !itemTag.equals("meta-data") && !itemTag.equals("uses-library")) continue;
                        Element copy = prepare(app, item, libraryPackage, applicationId);
                        if (key(copy).endsWith("|") || !known.add(key(copy))) continue;
                        Element application = firstChild(root, "application");
                        if (application == null) { application = app.createElement("application"); root.appendChild(application); }
                        application.appendChild(copy); added++;
                    }
                }
            }
        }
        write(app, output);
        return added;
    }

    /** The package attribute of a manifest, or null when it has none. */
    public static String readPackage(File manifest) throws IOException { return emptyToNull(parse(manifest).getDocumentElement().getAttribute("package")); }

    private static Element prepare(Document target, Element source, String libraryPackage, String applicationId) {
        Element copy = (Element) target.importNode(source, true);
        rewrite(copy, applicationId);
        if (COMPONENTS.contains(copy.getNodeName()) && libraryPackage != null) {
            Attr name = copy.getAttributeNodeNS(ANDROID_NS, "name");
            if (name != null) {
                String value = name.getValue();
                if (value.startsWith(".")) name.setValue(libraryPackage + value);
                else if (!value.isEmpty() && value.indexOf('.') < 0) name.setValue(libraryPackage + "." + value);
            }
        }
        return copy;
    }

    /** Replaces ${applicationId} and drops tools:* attributes (their namespace is not declared in the app manifest). */
    private static void rewrite(Element element, String applicationId) {
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> toRemove = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (TOOLS_NS.equals(attribute.getNamespaceURI())) { toRemove.add(attribute); continue; }
            if (applicationId != null && attribute.getValue().contains("${applicationId}")) attribute.setValue(attribute.getValue().replace("${applicationId}", applicationId));
        }
        for (Attr attribute : toRemove) element.removeAttributeNode(attribute);
        for (Element child : children(element)) rewrite(child, applicationId);
    }

    /** Identity of an element: its tag plus android:name (uses-feature may use glEsVersion). Ends with "|" when it has no usable name. */
    private static String key(Element element) {
        String name = element.getAttributeNS(ANDROID_NS, "name");
        if (name.isEmpty()) name = element.getAttributeNS(ANDROID_NS, "glEsVersion");
        return element.getNodeName() + "|" + name;
    }

    /** Tag, sorted attributes and children as one string; used to detect identical queries entries. */
    private static String canonical(Element element) {
        TreeSet<String> attributes = new TreeSet<>();
        NamedNodeMap map = element.getAttributes();
        for (int i = 0; i < map.getLength(); i++) { Attr attribute = (Attr) map.item(i); if (!"http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI())) attributes.add(attribute.getLocalName() + "=" + attribute.getValue()); }
        StringBuilder text = new StringBuilder("<").append(element.getNodeName()).append(' ').append(attributes).append('>');
        for (Element child : children(element)) text.append(canonical(child));
        return text.append("</>").toString();
    }

    private static void insertBeforeApplication(Element root, Element copy) {
        Element application = firstChild(root, "application");
        if (application == null) root.appendChild(copy); else root.insertBefore(copy, application);
    }
    private static Element firstChild(Element parent, String tag) { for (Element child : children(parent)) if (child.getNodeName().equals(tag)) return child; return null; }
    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element) result.add((Element) node);
        return result;
    }
    private static String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }

    private static Document parse(File file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (ParserConfigurationException unsupported) { /* Android's parser does not resolve external entities anyway */ }
            return factory.newDocumentBuilder().parse(file);
        } catch (Exception failure) { throw new IOException("Could not read manifest " + file + ": " + failure.getMessage(), failure); }
    }

    private static void write(Document document, File output) throws IOException {
        File parent = output.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(document), new StreamResult(output));
        } catch (Exception failure) { throw new IOException("Could not write merged manifest " + output + ": " + failure.getMessage(), failure); }
    }
}
