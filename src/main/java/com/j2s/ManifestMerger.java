package com.j2s;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative AndroidManifest merge for AAR dependencies.
 *
 * <p>This is <b>not</b> a full Gradle manifest merger. It merges the parts that matter for
 * standalone APK builds and that are safe to merge without a full merger:
 * <ul>
 *   <li>{@code uses-sdk}: min/target SDK raised to the maximum of user + AARs</li>
 *   <li>{@code uses-permission} / {@code uses-permission-sdk-23}: union by name</li>
 *   <li>{@code uses-feature}: union by name</li>
 *   <li>{@code application} children: activity / service / provider / receiver / meta-data /
 *       uses-library union by {@code android:name}</li>
 * </ul>
 * {@code application} attributes, {@code package}, {@code queries}, placeholders and
 * {@code tools:} directives are intentionally left untouched.
 */
public final class ManifestMerger {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private ManifestMerger() {}

    /**
     * Merge AAR manifests into {@code userManifest} and write the result to {@code outFile}.
     *
     * @return {@code outFile}, or {@code userManifest} when there is nothing to merge
     */
    public static Path merge(Path userManifest, List<Path> aarManifests, Path outFile)
            throws Exception {
        if (aarManifests == null || aarManifests.isEmpty()) return userManifest;

        Document user = parse(userManifest);
        Element root = user.getDocumentElement();

        int minSdk = -1;
        int targetSdk = -1;
        Element userSdk = firstChild(root, "uses-sdk");
        if (userSdk != null) {
            minSdk = attrInt(userSdk, "minSdkVersion");
            targetSdk = attrInt(userSdk, "targetSdkVersion");
        }

        Set<String> permissions = names(root, "uses-permission");
        permissions.addAll(names(root, "uses-permission-sdk-23"));
        Set<String> features = names(root, "uses-feature");

        Element app = firstChild(root, "application");
        Set<String> components = new LinkedHashSet<>();
        if (app != null) components.addAll(componentKeys(app));

        int mergedPermissions = 0;
        int mergedFeatures = 0;
        int mergedComponents = 0;
        int mergedSdk = 0;
        List<Path> used = new ArrayList<>();

        for (Path aarManifest : aarManifests) {
            if (aarManifest == null || !Files.isRegularFile(aarManifest)) continue;
            Document doc;
            try {
                doc = parse(aarManifest);
            } catch (Exception e) {
                System.err.println("Warning: cannot parse AAR manifest " + aarManifest + ": " + e);
                continue;
            }
            used.add(aarManifest);
            Element aarRoot = doc.getDocumentElement();

            Element sdk = firstChild(aarRoot, "uses-sdk");
            if (sdk != null) {
                int aMin = attrInt(sdk, "minSdkVersion");
                int aTarget = attrInt(sdk, "targetSdkVersion");
                if (aMin > minSdk) {
                    minSdk = aMin;
                    mergedSdk++;
                }
                if (aTarget > targetSdk) {
                    targetSdk = aTarget;
                    mergedSdk++;
                }
            }

            for (String tag : new String[]{"uses-permission", "uses-permission-sdk-23"}) {
                for (Element perm : children(aarRoot, tag)) {
                    String name = attr(perm, "name");
                    if (name != null && permissions.add(name)) {
                        insertBeforeApplication(root, user.importNode(perm, true));
                        mergedPermissions++;
                    }
                }
            }

            for (Element feature : children(aarRoot, "uses-feature")) {
                String name = attr(feature, "name");
                if (name != null && features.add(name)) {
                    insertBeforeApplication(root, user.importNode(feature, true));
                    mergedFeatures++;
                }
            }

            Element aarApp = firstChild(aarRoot, "application");
            if (aarApp != null && app != null) {
                NodeList kids = aarApp.getChildNodes();
                for (int i = 0; i < kids.getLength(); i++) {
                    Node n = kids.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element el = (Element) n;
                    if (!isMergeableComponent(el.getNodeName())) continue;
                    String key = componentKey(el);
                    if (key != null && components.add(key)) {
                        app.appendChild(user.importNode(el, true));
                        mergedComponents++;
                    }
                }
            }
        }

        if (minSdk >= 0 || targetSdk >= 0) {
            if (userSdk == null) {
                userSdk = user.createElement("uses-sdk");
                insertBeforeApplication(root, userSdk, false);
            }
            if (minSdk >= 0) setAndroidAttr(userSdk, "minSdkVersion", String.valueOf(minSdk));
            if (targetSdk >= 0) setAndroidAttr(userSdk, "targetSdkVersion", String.valueOf(targetSdk));
        }

        Path parent = outFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.transform(new DOMSource(user), new StreamResult(outFile.toFile()));

        System.out.println("Manifest merge: " + used.size() + " AAR manifest(s), +"
                + mergedPermissions + " permission(s), +" + mergedFeatures + " feature(s), +"
                + mergedComponents + " component(s)"
                + (mergedSdk > 0 ? ", uses-sdk raised" : ""));
        return outFile;
    }

    private static boolean isMergeableComponent(String tag) {
        return tag.equals("activity") || tag.equals("service") || tag.equals("provider")
                || tag.equals("receiver") || tag.equals("meta-data")
                || tag.equals("uses-library") || tag.equals("activity-alias");
    }

    private static String componentKey(Element el) {
        String name = attr(el, "name");
        if (name == null) return null;
        return el.getNodeName() + ":" + name;
    }

    private static Set<String> componentKeys(Element app) {
        Set<String> out = new LinkedHashSet<>();
        NodeList kids = app.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if (!isMergeableComponent(el.getNodeName())) continue;
            String key = componentKey(el);
            if (key != null) out.add(key);
        }
        return out;
    }

    private static void insertBeforeApplication(Element root, Node node) {
        insertBeforeApplication(root, node, true);
    }

    private static void insertBeforeApplication(Element root, Node node, boolean beforeApp) {
        Element app = beforeApp ? firstChild(root, "application") : null;
        if (app != null) root.insertBefore(node, app);
        else root.appendChild(node);
    }

    private static Set<String> names(Element parent, String tag) {
        Set<String> out = new LinkedHashSet<>();
        for (Element e : children(parent, tag)) {
            String name = attr(e, "name");
            if (name != null) out.add(name);
        }
        return out;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return f.newDocumentBuilder().parse(file.toFile());
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttributeNS(ANDROID_NS, name);
        if (v == null || v.isEmpty()) v = e.getAttribute("android:" + name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static int attrInt(Element e, String name) {
        String v = attr(e, name);
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static void setAndroidAttr(Element e, String name, String value) {
        e.setAttributeNS(ANDROID_NS, "android:" + name, value);
    }
}
