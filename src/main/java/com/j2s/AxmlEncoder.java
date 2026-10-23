package com.j2s;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a plain text Android resource XML file into Android binary XML (AXML).
 *
 * <p>The output uses the standard {@code ResXMLTree} chunk layout
 * ({@code RES_XML_TYPE} + string pool + start/end namespace, start/end element and CDATA
 * chunks). Element names, attribute names, namespaces and attribute values are preserved as
 * strings; the optional resource-id map chunk is intentionally omitted because mapping
 * {@code android:*} attribute names to framework resource ids requires a framework resource
 * table. This is fine for consumers that read AXML by namespace + name (and for the dexres.zip
 * bundle); it is not a replacement for {@code aapt2} when building an APK.
 *
 * <p>The encoder is pure Java: no aapt2 / android.jar is required.
 */
public final class AxmlEncoder {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
    private static final int RES_XML_END_NAMESPACE_TYPE = 0x0101;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_CDATA_TYPE = 0x0104;

    private static final int TYPE_STRING = 0x03;
    private static final int NO_INDEX = 0xFFFFFFFF;
    private static final int NO_COMMENT = 0xFFFFFFFF;
    private static final int NO_LINE = 0xFFFFFFFF;
    private static final int ATTR_START = 20;
    private static final int ATTR_SIZE = 20;

    private static final String XMLNS_URI = "http://www.w3.org/2000/xmlns/";

    private AxmlEncoder() {}

    /** Encode a text XML file into AXML bytes. */
    public static byte[] encode(Path xmlFile) throws Exception {
        Document doc = parse(xmlFile);
        Element root = doc.getDocumentElement();
        if (root == null) throw new IllegalArgumentException("empty XML document: " + xmlFile);

        Encoder enc = new Encoder();
        enc.collectNamespaces(root);

        // Namespace chunks need their prefix/uri strings interned first.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<int[]> namespaces = enc.namespaces();
        for (int[] ns : namespaces) {
            writeNamespace(body, RES_XML_START_NAMESPACE_TYPE, ns[0], ns[1]);
        }
        enc.writeElement(body, root);
        for (int i = namespaces.size() - 1; i >= 0; i--) {
            int[] ns = namespaces.get(i);
            writeNamespace(body, RES_XML_END_NAMESPACE_TYPE, ns[0], ns[1]);
        }

        byte[] pool = enc.poolBytes();
        int total = 8 + pool.length + body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        u16(out, RES_XML_TYPE);
        u16(out, 8);
        u32(out, total);
        out.write(pool);
        body.writeTo(out);
        return out.toByteArray();
    }

    // ───────────────────────────────────────────────────────── DOM traversal

    private static final class Encoder {
        private final StringPool pool = new StringPool();
        /** prefix -> namespace uri, in first-seen order ("" = default namespace). */
        private final Map<String, String> nsMap = new LinkedHashMap<>();
        /** uri -> index of the {@code uri} string (or {@code prefix} for the pair). */
        private final Map<String, Integer> nsUriIndex = new LinkedHashMap<>();
        private final Map<String, Integer> nsPrefixIndex = new LinkedHashMap<>();

        void collectNamespaces(Element root) {
            walkNamespaces(root);
            for (Map.Entry<String, String> e : nsMap.entrySet()) {
                nsPrefixIndex.put(e.getKey(), pool.intern(e.getKey()));
                nsUriIndex.put(e.getValue(), pool.intern(e.getValue()));
            }
        }

        private void walkNamespaces(Element el) {
            addNamespace(el.getPrefix(), el.getNamespaceURI());
            NamedNodeMap attrs = el.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if (isNamespaceDeclaration(a)) continue;
                addNamespace(a.getPrefix(), a.getNamespaceURI());
            }
            NodeList kids = el.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) walkNamespaces((Element) n);
            }
        }

        private void addNamespace(String prefix, String uri) {
            if (uri == null || uri.isEmpty() || XMLNS_URI.equals(uri)) return;
            String p = prefix == null ? "" : prefix;
            if (!nsMap.containsKey(p)) nsMap.put(p, uri);
        }

        List<int[]> namespaces() {
            List<int[]> out = new ArrayList<>();
            for (Map.Entry<String, String> e : nsMap.entrySet()) {
                out.add(new int[]{nsPrefixIndex.get(e.getKey()), nsUriIndex.get(e.getValue())});
            }
            return out;
        }

        void writeElement(ByteArrayOutputStream out, Element el) {
            int ns = nsIndex(el.getNamespaceURI());
            int name = pool.intern(localName(el));

            List<int[]> attrs = new ArrayList<>();
            NamedNodeMap map = el.getAttributes();
            for (int i = 0; i < map.getLength(); i++) {
                Attr a = (Attr) map.item(i);
                if (isNamespaceDeclaration(a)) continue;
                int aNs = nsIndex(a.getNamespaceURI());
                int aName = pool.intern(localName(a));
                String value = a.getValue() == null ? "" : a.getValue();
                int aValue = pool.intern(value);
                attrs.add(new int[]{aNs, aName, aValue});
            }

            writeStartElement(out, ns, name, attrs);

            NodeList kids = el.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    writeElement(out, (Element) n);
                } else if (n.getNodeType() == Node.TEXT_NODE
                        || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                    String text = n.getNodeValue();
                    if (text != null && !text.trim().isEmpty()) {
                        writeCData(out, pool.intern(text));
                    }
                }
            }
            writeEndElement(out, ns, name);
        }

        private int nsIndex(String uri) {
            if (uri == null || uri.isEmpty()) return NO_INDEX;
            Integer idx = nsUriIndex.get(uri);
            return idx == null ? NO_INDEX : idx;
        }

        byte[] poolBytes() {
            return pool.toBytes();
        }
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null && !local.isEmpty()) return local;
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static boolean isNamespaceDeclaration(Attr a) {
        String name = a.getName();
        return XMLNS_URI.equals(a.getNamespaceURI())
                || "xmlns".equals(name)
                || name.startsWith("xmlns:");
    }

    // ───────────────────────────────────────────────────────── chunks

    private static void writeNamespace(ByteArrayOutputStream out, int type,
                                       int prefixIdx, int uriIdx) {
        int size = 24;
        chunkHeader(out, type, size, size);
        u32(out, NO_LINE);
        u32(out, NO_COMMENT);
        u32(out, prefixIdx);
        u32(out, uriIdx);
    }

    private static void writeStartElement(ByteArrayOutputStream out, int ns, int name,
                                          List<int[]> attrs) {
        int size = 36 + attrs.size() * ATTR_SIZE;
        chunkHeader(out, RES_XML_START_ELEMENT_TYPE, 36, size);
        u32(out, NO_LINE);
        u32(out, NO_COMMENT);
        u32(out, ns);
        u32(out, name);
        u16(out, ATTR_START);
        u16(out, ATTR_SIZE);
        u16(out, attrs.size());
        u16(out, 0); // idIndex
        u16(out, 0); // classIndex
        u16(out, 0); // styleIndex
        for (int[] attr : attrs) {
            u32(out, attr[0]);
            u32(out, attr[1]);
            u32(out, attr[2]);   // rawValue (string index)
            u16(out, 8);         // Res_value size
            out.write(0);        // res0
            out.write(TYPE_STRING);
            u32(out, attr[2]);   // typedValue.data = string index
        }
    }

    private static void writeEndElement(ByteArrayOutputStream out, int ns, int name) {
        int size = 24;
        chunkHeader(out, RES_XML_END_ELEMENT_TYPE, size, size);
        u32(out, NO_LINE);
        u32(out, NO_COMMENT);
        u32(out, ns);
        u32(out, name);
    }

    private static void writeCData(ByteArrayOutputStream out, int data) {
        int size = 28;
        chunkHeader(out, RES_XML_CDATA_TYPE, size, size);
        u32(out, NO_LINE);
        u32(out, NO_COMMENT);
        u32(out, data);
        u16(out, 8);      // Res_value size
        out.write(0);     // res0
        out.write(TYPE_STRING);
        u32(out, data);   // typedValue.data = string index
    }

    private static void chunkHeader(ByteArrayOutputStream out, int type, int headerSize, int size) {
        u16(out, type);
        u16(out, headerSize);
        u32(out, size);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    // ───────────────────────────────────────────────────────── string pool

    private static final class StringPool {
        private final Map<String, Integer> indices = new LinkedHashMap<>();

        int intern(String s) {
            String key = s == null ? "" : s;
            Integer idx = indices.get(key);
            if (idx != null) return idx;
            int next = indices.size();
            indices.put(key, next);
            return next;
        }

        byte[] toBytes() {
            List<String> strings = new ArrayList<>(indices.keySet());
            int count = strings.size();
            int stringsStart = 28 + 4 * count;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int[] offsets = new int[count];
            for (int i = 0; i < count; i++) {
                offsets[i] = data.size();
                writeUtf16(data, strings.get(i));
            }
            while ((data.size() & 3) != 0) data.write(0);
            int size = stringsStart + data.size();

            ByteArrayOutputStream out = new ByteArrayOutputStream(size);
            u16(out, RES_STRING_POOL_TYPE);
            u16(out, 28);
            u32(out, size);
            u32(out, count);
            u32(out, 0);            // styleCount
            u32(out, 0);            // flags: UTF-16
            u32(out, stringsStart);
            u32(out, 0);            // stylesStart
            for (int offset : offsets) u32(out, offset);
            byte[] bytes = data.toByteArray();
            out.write(bytes, 0, bytes.length);
            return out.toByteArray();
        }

        private static void writeUtf16(ByteArrayOutputStream out, String s) {
            int len = s.length();
            if (len > 0x7fff) {
                u16(out, 0x8000 | (len >> 16));
                u16(out, len & 0xffff);
            } else {
                u16(out, len);
            }
            for (int i = 0; i < len; i++) {
                u16(out, s.charAt(i));
            }
            u16(out, 0);
        }
    }

    // ───────────────────────────────────────────────────────── parsing

    private static Document parse(Path xmlFile) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(xmlFile.toFile());
    }
}
