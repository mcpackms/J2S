package com.j2s;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AxmlEncoderTest {

    @TempDir
    Path tmp;

    @Test
    void encodesXmlIntoValidBinaryXmlChunks() throws Exception {
        Path xml = write("layout/main.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\""
                        + " android:layout_width=\"match_parent\""
                        + " android:orientation=\"vertical\">"
                        + "<TextView android:text=\"Hello &amp; bye\">inner text</TextView>"
                        + "</LinearLayout>");

        byte[] axml = AxmlEncoder.encode(xml);

        assertEquals(0x0003, u16(axml, 0), "RES_XML_TYPE");
        assertEquals(8, u16(axml, 2), "headerSize");
        assertEquals(axml.length, u32(axml, 4), "total size");

        assertEquals(0x0001, u16(axml, 8), "string pool chunk first");
        int poolSize = u32(axml, 12);
        int poolCount = u32(axml, 16);
        int stringsStart = u32(axml, 28);
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < poolCount; i++) {
            int offset = u32(axml, 8 + 28 + 4 * i);
            strings.add(readUtf16(axml, 8 + stringsStart + offset));
        }
        assertTrue(strings.contains("LinearLayout"), "strings: " + strings);
        assertTrue(strings.contains("TextView"), "strings: " + strings);
        assertTrue(strings.contains("android"), "strings: " + strings);
        assertTrue(strings.contains("http://schemas.android.com/apk/res/android"), "strings: " + strings);
        assertTrue(strings.contains("match_parent"), "strings: " + strings);
        assertTrue(strings.contains("Hello & bye"), "strings: " + strings);
        assertTrue(strings.contains("inner text"), "strings: " + strings);

        int startNs = 0, endNs = 0, startElements = 0, endElements = 0, cdata = 0;
        int pos = 8 + poolSize;
        while (pos + 8 <= axml.length) {
            int type = u16(axml, pos);
            int size = u32(axml, pos + 4);
            assertTrue(size >= 8, "bad chunk size " + size + " at " + pos);
            if (type == 0x0100) startNs++;
            else if (type == 0x0101) endNs++;
            else if (type == 0x0102) startElements++;
            else if (type == 0x0103) endElements++;
            else if (type == 0x0104) cdata++;
            pos += size;
        }
        assertEquals(axml.length, pos, "chunks exactly cover the file");
        assertEquals(1, startNs);
        assertEquals(1, endNs);
        assertEquals(2, startElements);
        assertEquals(2, endElements);
        assertEquals(1, cdata);
    }

    @Test
    void startElementCarriesAttributesWithStringValues() throws Exception {
        Path xml = write("main.xml",
                "<View xmlns:android=\"http://schemas.android.com/apk/res/android\""
                        + " android:id=\"@+id/root\" android:alpha=\"0.5\"/>");
        byte[] axml = AxmlEncoder.encode(xml);

        int pos = 8 + u32(axml, 12);
        int startElementPos = -1;
        while (pos + 8 <= axml.length) {
            int type = u16(axml, pos);
            int size = u32(axml, pos + 4);
            if (type == 0x0102) {
                startElementPos = pos;
                break;
            }
            pos += size;
        }
        assertTrue(startElementPos >= 0);
        int attributeCount = u16(axml, startElementPos + 28);
        assertEquals(2, attributeCount);
        assertEquals(20, u16(axml, startElementPos + 24), "attributeStart");
        assertEquals(20, u16(axml, startElementPos + 26), "attributeSize");
    }

    @Test
    void malformedXmlIsRejected() throws Exception {
        Path xml = write("broken.xml", "<LinearLayout><TextView></LinearLayout>");
        assertThrows(Exception.class, () -> AxmlEncoder.encode(xml));
    }

    // ── helpers ──

    private Path write(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static String readUtf16(byte[] b, int off) {
        int len = u16(b, off);
        off += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | u16(b, off);
            off += 2;
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) u16(b, off));
            off += 2;
        }
        return sb.toString();
    }
}
