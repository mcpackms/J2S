package com.j2s;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ManifestMergerTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String XMLNS =
            "xmlns:android=\"http://schemas.android.com/apk/res/android\"";

    @TempDir
    Path tmp;

    @Test
    void mergesSdkPermissionsFeaturesAndApplicationComponents() throws Exception {
        Path user = write("user.xml",
                "<manifest " + XMLNS + " package=\"com.example\">"
                        + "<uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"33\"/>"
                        + "<uses-permission android:name=\"android.permission.INTERNET\"/>"
                        + "<application android:label=\"App\">"
                        + "<activity android:name=\".Main\"/>"
                        + "</application></manifest>");
        Path aar1 = write("aar1.xml",
                "<manifest " + XMLNS + " package=\"lib.one\">"
                        + "<uses-sdk android:minSdkVersion=\"19\"/>"
                        + "<uses-permission android:name=\"android.permission.CAMERA\"/>"
                        + "<uses-feature android:name=\"android.hardware.camera\"/>"
                        + "<application>"
                        + "<activity android:name=\".LibActivity\"/>"
                        + "<meta-data android:name=\"lib.flag\" android:value=\"1\"/>"
                        + "</application></manifest>");
        Path aar2 = write("aar2.xml",
                "<manifest " + XMLNS + " package=\"lib.two\">"
                        + "<uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\"/>"
                        + "<uses-permission android:name=\"android.permission.INTERNET\"/>"
                        + "<application><service android:name=\".LibService\"/></application>"
                        + "</manifest>");

        Path out = tmp.resolve("gen/AndroidManifest.xml");
        Path merged = ManifestMerger.merge(user, Arrays.asList(aar1, aar2), out);

        assertEquals(out.toAbsolutePath().normalize(), merged.toAbsolutePath().normalize());
        Document doc = parse(out);
        Element root = doc.getDocumentElement();

        Element sdk = first(root, "uses-sdk");
        assertNotNull(sdk);
        assertEquals("24", sdk.getAttributeNS(ANDROID, "minSdkVersion"));
        assertEquals("34", sdk.getAttributeNS(ANDROID, "targetSdkVersion"));

        assertEquals(1, countNamed(root, "uses-permission", "android.permission.INTERNET"));
        assertEquals(1, countNamed(root, "uses-permission", "android.permission.CAMERA"));
        assertEquals(1, countNamed(root, "uses-feature", "android.hardware.camera"));

        Element app = first(root, "application");
        assertEquals("App", app.getAttributeNS(ANDROID, "label"), "user application attrs kept");
        assertNotNull(findNamed(app, "activity", ".Main"));
        assertNotNull(findNamed(app, "activity", ".LibActivity"));
        assertNotNull(findNamed(app, "service", ".LibService"));
        assertNotNull(findNamed(app, "meta-data", "lib.flag"));
    }

    @Test
    void noAarManifestsReturnsUserManifestUntouched() throws Exception {
        Path user = write("user.xml",
                "<manifest " + XMLNS + " package=\"com.example\"><application/></manifest>");
        Path out = tmp.resolve("gen/merged.xml");
        Path merged = ManifestMerger.merge(user, java.util.Collections.<Path>emptyList(), out);
        assertEquals(user, merged);
        assertFalse(Files.exists(out));
    }

    @Test
    void missingSdkInUserManifestIsAdded() throws Exception {
        Path user = write("user.xml",
                "<manifest " + XMLNS + " package=\"com.example\"><application/></manifest>");
        Path aar = write("aar.xml",
                "<manifest " + XMLNS + " package=\"lib\"><uses-sdk android:minSdkVersion=\"26\"/>"
                        + "</manifest>");
        Path out = tmp.resolve("gen/merged.xml");
        ManifestMerger.merge(user, Arrays.asList(aar), out);
        Element sdk = first(parse(out).getDocumentElement(), "uses-sdk");
        assertNotNull(sdk);
        assertEquals("26", sdk.getAttributeNS(ANDROID, "minSdkVersion"));
    }

    // ── helpers ──

    private Path write(String name, String xml) throws Exception {
        Path p = tmp.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, xml.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(file.toFile());
    }

    private static Element first(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static int countNamed(Element parent, String tag, String name) {
        int count = 0;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !tag.equals(n.getNodeName())) continue;
            Element e = (Element) n;
            String v = e.getAttributeNS(ANDROID, "name");
            if (v == null || v.isEmpty()) v = e.getAttribute("android:name");
            if (name.equals(v)) count++;
        }
        return count;
    }

    private static Element findNamed(Element parent, String tag, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !tag.equals(n.getNodeName())) continue;
            Element e = (Element) n;
            String v = e.getAttributeNS(ANDROID, "name");
            if (v == null || v.isEmpty()) v = e.getAttribute("android:name");
            if (name.equals(v)) return e;
        }
        return null;
    }
}
