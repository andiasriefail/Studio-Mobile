package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ManifestMergerTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private File directory;

    @Before public void setUp() throws IOException { directory = Files.createTempDirectory("manifest-merger-test").toFile(); }
    @After public void tearDown() { File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete(); }

    private static final String APP =
        "<manifest xmlns:android=\"" + ANDROID + "\" package=\"com.example.app\">"
        + "<uses-permission android:name=\"android.permission.INTERNET\"/>"
        + "<application>"
        + "<activity android:name=\".MainActivity\"/>"
        + "<meta-data android:name=\"shared.key\" android:value=\"app\"/>"
        + "</application></manifest>";

    private static final String LIBRARY =
        "<manifest xmlns:android=\"" + ANDROID + "\" xmlns:tools=\"http://schemas.android.com/tools\" package=\"com.example.lib\">"
        + "<uses-sdk android:minSdkVersion=\"21\"/>"
        + "<uses-permission android:name=\"android.permission.INTERNET\"/>"
        + "<uses-permission android:name=\"android.permission.CAMERA\"/>"
        + "<uses-permission android:name=\"${applicationId}.PRIVATE_PERMISSION\"/>"
        + "<permission android:name=\"${applicationId}.PRIVATE_PERMISSION\" android:protectionLevel=\"signature\"/>"
        + "<uses-feature android:name=\"android.hardware.camera\" android:required=\"false\"/>"
        + "<queries><package android:name=\"com.example.other\"/></queries>"
        + "<application android:label=\"ignored\" android:theme=\"@style/Ignored\">"
        + "<activity android:name=\".LibraryActivity\" tools:node=\"merge\"/>"
        + "<activity android:name=\"BareName\"/>"
        + "<activity android:name=\"com.other.Absolute\"/>"
        + "<service android:name=\".LibraryService\"/>"
        + "<receiver android:name=\".LibraryReceiver\"/>"
        + "<provider android:name=\".LibraryProvider\" android:authorities=\"${applicationId}.library\"/>"
        + "<activity android:name=\".MainActivity\"/>"
        + "<meta-data android:name=\"shared.key\" android:value=\"library\"/>"
        + "<meta-data android:name=\"library.key\" android:value=\"1\"/>"
        + "<uses-library android:name=\"org.apache.http.legacy\" android:required=\"false\"/>"
        + "</application></manifest>";

    private Document merge(String app, String... libraries) throws Exception {
        File appFile = new File(directory, "app.xml"); Files.write(appFile.toPath(), app.getBytes(StandardCharsets.UTF_8));
        java.util.List<File> libraryFiles = new java.util.ArrayList<>();
        for (int i = 0; i < libraries.length; i++) { File file = new File(directory, "lib" + i + ".xml"); Files.write(file.toPath(), libraries[i].getBytes(StandardCharsets.UTF_8)); libraryFiles.add(file); }
        File output = new File(directory, "merged.xml");
        ManifestMerger.merge(appFile, libraryFiles, "com.example.app", output);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(output);
    }
    private static int count(Document document, String tag, String name) {
        NodeList list = document.getElementsByTagName(tag); int found = 0;
        for (int i = 0; i < list.getLength(); i++) if (name.equals(((Element) list.item(i)).getAttributeNS(ANDROID, "name"))) found++;
        return found;
    }
    private static Element only(Document document, String tag, String name) {
        NodeList list = document.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) if (name.equals(((Element) list.item(i)).getAttributeNS(ANDROID, "name"))) return (Element) list.item(i);
        return null;
    }

    @Test public void copiesPermissionsFeaturesAndQueriesWithoutDuplicates() throws Exception {
        Document merged = merge(APP, LIBRARY);
        assertEquals("INTERNET is declared by the app and must not be duplicated", 1, count(merged, "uses-permission", "android.permission.INTERNET"));
        assertEquals(1, count(merged, "uses-permission", "android.permission.CAMERA"));
        assertEquals(1, count(merged, "uses-feature", "android.hardware.camera"));
        assertEquals(1, merged.getElementsByTagName("queries").getLength());
        assertEquals(1, count(merged, "package", "com.example.other"));
    }

    @Test public void replacesApplicationIdPlaceholders() throws Exception {
        Document merged = merge(APP, LIBRARY);
        assertEquals(1, count(merged, "uses-permission", "com.example.app.PRIVATE_PERMISSION"));
        assertEquals(1, count(merged, "permission", "com.example.app.PRIVATE_PERMISSION"));
        assertEquals("com.example.app.library", only(merged, "provider", "com.example.lib.LibraryProvider").getAttributeNS(ANDROID, "authorities"));
    }

    @Test public void completesRelativeComponentNamesWithTheLibraryPackage() throws Exception {
        Document merged = merge(APP, LIBRARY);
        assertEquals(1, count(merged, "activity", "com.example.lib.LibraryActivity"));
        assertEquals(1, count(merged, "activity", "com.example.lib.BareName"));
        assertEquals(1, count(merged, "activity", "com.other.Absolute"));
        assertEquals(1, count(merged, "service", "com.example.lib.LibraryService"));
        assertEquals(1, count(merged, "receiver", "com.example.lib.LibraryReceiver"));
    }

    @Test public void appElementsWinOverLibraryElements() throws Exception {
        Document merged = merge(APP, LIBRARY);
        assertEquals("the app's .MainActivity is kept as written; the library's resolves to another name", 1, count(merged, "activity", ".MainActivity"));
        assertEquals(1, count(merged, "activity", "com.example.lib.MainActivity"));
        assertEquals("app", only(merged, "meta-data", "shared.key").getAttributeNS(ANDROID, "value"));
        assertEquals(1, count(merged, "meta-data", "shared.key"));
        assertEquals(1, count(merged, "meta-data", "library.key"));
        assertEquals(1, count(merged, "uses-library", "org.apache.http.legacy"));
    }

    @Test public void ignoresLibraryApplicationAttributesAndUsesSdkAndToolsAttributes() throws Exception {
        Document merged = merge(APP, LIBRARY);
        assertEquals(0, merged.getElementsByTagName("uses-sdk").getLength());
        Element application = (Element) merged.getElementsByTagName("application").item(0);
        assertFalse(application.hasAttributeNS(ANDROID, "label"));
        assertFalse(only(merged, "activity", "com.example.lib.LibraryActivity").hasAttributeNS("http://schemas.android.com/tools", "node"));
    }

    @Test public void secondLibraryDoesNotDuplicateWhatTheFirstAlreadyAdded() throws Exception {
        Document merged = merge(APP, LIBRARY, LIBRARY);
        assertEquals(1, count(merged, "service", "com.example.lib.LibraryService"));
        assertEquals(1, count(merged, "uses-permission", "android.permission.CAMERA"));
        assertEquals(1, count(merged, "package", "com.example.other"));
    }

    @Test public void createsApplicationElementWhenTheAppHasNone() throws Exception {
        Document merged = merge("<manifest xmlns:android=\"" + ANDROID + "\" package=\"com.example.app\"/>", LIBRARY);
        assertEquals(1, merged.getElementsByTagName("application").getLength());
        assertEquals(1, count(merged, "service", "com.example.lib.LibraryService"));
    }

    @Test public void readsPackageAttribute() throws Exception {
        File file = new File(directory, "package.xml"); Files.write(file.toPath(), LIBRARY.getBytes(StandardCharsets.UTF_8));
        assertEquals("com.example.lib", ManifestMerger.readPackage(file));
        File none = new File(directory, "none.xml"); Files.write(none.toPath(), "<manifest/>".getBytes(StandardCharsets.UTF_8));
        assertNull(ManifestMerger.readPackage(none));
    }
}
