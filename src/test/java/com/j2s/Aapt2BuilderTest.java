package com.j2s;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class Aapt2BuilderTest {

    @TempDir
    Path tmp;

    @Test
    void packApkAddsDexFilesAndJniLibs() throws Exception {
        Path base = tmp.resolve("base.apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(base))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("<manifest/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Path dex = tmp.resolve("classes.dex");
        Files.write(dex, new byte[]{0x64, 0x65, 0x78, 0x0a});

        Path jni = tmp.resolve("jni");
        Files.createDirectories(jni.resolve("arm64-v8a"));
        Files.write(jni.resolve("arm64-v8a/libfoo.so"), new byte[]{1, 2, 3});

        Path out = tmp.resolve("out.apk");
        Aapt2Builder.packApk(base, Collections.singletonList(dex), out,
                Collections.singletonList(jni));

        List<String> entries = entries(out);
        assertTrue(entries.contains("classes.dex"), "entries: " + entries);
        assertTrue(entries.contains("lib/arm64-v8a/libfoo.so"), "entries: " + entries);
        assertTrue(entries.contains("AndroidManifest.xml"), "entries: " + entries);
    }

    @Test
    void packApkWithoutJniStillWorks() throws Exception {
        Path base = tmp.resolve("base.apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(base))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.closeEntry();
        }
        Path dex = tmp.resolve("classes.dex");
        Files.write(dex, new byte[]{1});
        Path out = tmp.resolve("out.apk");
        Aapt2Builder.packApk(base, Collections.singletonList(dex), out);
        assertTrue(entries(out).contains("classes.dex"));
    }

    @Test
    void copyTreeIsRecursive() throws Exception {
        Path src = tmp.resolve("assets-src");
        Files.createDirectories(src.resolve("sub"));
        Files.write(src.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("sub/b.txt"), "b".getBytes(StandardCharsets.UTF_8));
        Path dst = tmp.resolve("assets-dst");
        Aapt2Builder.copyTree(src, dst);
        assertTrue(Files.isRegularFile(dst.resolve("a.txt")));
        assertTrue(Files.isRegularFile(dst.resolve("sub/b.txt")));
    }

    private static List<String> entries(Path zip) throws Exception {
        List<String> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) out.add(en.nextElement().getName());
        }
        return out;
    }
}
