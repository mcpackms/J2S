package com.j2s;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a tiny {@code file://} Maven repository for offline resolver tests.
 *
 * <p>Every artifact gets a POM and either a jar or an AAR with resources, assets, JNI libs and an
 * AndroidManifest.xml, so the tests never touch the network.
 */
final class TestRepo {

    final Path root;

    TestRepo(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    String url() {
        return root.toUri().toString();
    }

    /** Install a plain jar with the given dependencies ({@code g:a:v[:scope]}). */
    void jar(String g, String a, String v, String... deps) throws IOException {
        List<String[]> parsed = parse(deps);
        writePom(g, a, v, null, parsed, null, null);
        writeJar(artifactFile(g, a, v, "jar"), g + ":" + a + ":" + v);
    }

    /** Install an AAR (POM packaging=aar) with classes.jar, res/, assets/, jni/ and a manifest. */
    void aar(String g, String a, String v, String... deps) throws IOException {
        List<String[]> parsed = parse(deps);
        writePom(g, a, v, "aar", parsed, null, null);
        writeAar(artifactFile(g, a, v, "aar"), g + ":" + a + ":" + v);
    }

    /** Install a BOM: packaging=pom with explicit dependencyManagement entries. */
    void bom(String g, String a, String v, String... managed) throws IOException {
        writePom(g, a, v, "pom", null, parse(managed), null);
    }

    /** Install a BOM that imports another BOM through a {@code scope=import} entry. */
    void bomImport(String g, String a, String v, String... imported) throws IOException {
        writePom(g, a, v, "pom", null, null, parse(imported));
    }

    Path artifactFile(String g, String a, String v, String ext) {
        return root.resolve(g.replace('.', '/')).resolve(a).resolve(v)
                .resolve(a + "-" + v + "." + ext);
    }

    private static List<String[]> parse(String[] specs) {
        List<String[]> out = new ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":");
            String[] d = new String[]{"", "", "", null};
            if (parts.length > 0) d[0] = parts[0];
            if (parts.length > 1) d[1] = parts[1];
            if (parts.length > 2) d[2] = parts[2];
            if (parts.length > 3) d[3] = parts[3];
            out.add(d);
        }
        return out;
    }

    private void writePom(String g, String a, String v, String packaging,
                          List<String[]> deps, List<String[]> managed,
                          List<String[]> imports) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n");
        sb.append("  <groupId>").append(g).append("</groupId>\n");
        sb.append("  <artifactId>").append(a).append("</artifactId>\n");
        sb.append("  <version>").append(v).append("</version>\n");
        if (packaging != null) sb.append("  <packaging>").append(packaging).append("</packaging>\n");
        if (deps != null && !deps.isEmpty()) {
            sb.append("  <dependencies>\n");
            for (String[] d : deps) {
                sb.append("    <dependency><groupId>").append(d[0])
                        .append("</groupId><artifactId>").append(d[1]).append("</artifactId>");
                if (d[2] != null && !d[2].isEmpty()) {
                    sb.append("<version>").append(d[2]).append("</version>");
                }
                if (d[3] != null && !d[3].isEmpty()) {
                    sb.append("<scope>").append(d[3]).append("</scope>");
                }
                sb.append("</dependency>\n");
            }
            sb.append("  </dependencies>\n");
        }
        boolean hasManagement = (managed != null && !managed.isEmpty())
                || (imports != null && !imports.isEmpty());
        if (hasManagement) {
            sb.append("  <dependencyManagement><dependencies>\n");
            if (managed != null) {
                for (String[] m : managed) {
                    sb.append("    <dependency><groupId>").append(m[0])
                            .append("</groupId><artifactId>").append(m[1])
                            .append("</artifactId><version>").append(m[2])
                            .append("</version></dependency>\n");
                }
            }
            if (imports != null) {
                for (String[] im : imports) {
                    sb.append("    <dependency><groupId>").append(im[0])
                            .append("</groupId><artifactId>").append(im[1])
                            .append("</artifactId><version>").append(im[2])
                            .append("</version><type>pom</type><scope>import</scope></dependency>\n");
                }
            }
            sb.append("  </dependencies></dependencyManagement>\n");
        }
        sb.append("</project>\n");
        write(artifactFile(g, a, v, "pom"), sb.toString());
    }

    private void writeJar(Path target, String marker) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream os = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("marker/" + marker.replace(':', '_') + ".txt"));
            zip.write(marker.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private byte[] nestedJar(String marker) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("classes/" + marker.replace(':', '_') + ".class"));
            zip.write(marker.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    private void writeAar(Path target, String marker) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream os = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            putBytes(zip, "classes.jar", nestedJar(marker));
            putText(zip, "res/values/strings.xml",
                    "<resources><string name=\"marker\">" + marker + "</string></resources>");
            putText(zip, "res/layout/main.xml",
                    "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"/>");
            putText(zip, "assets/a.txt", "asset from " + marker);
            putText(zip, "AndroidManifest.xml",
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"test.marker\">"
                            + "<uses-sdk android:minSdkVersion=\"19\"/>"
                            + "<uses-permission android:name=\"android.permission.INTERNET\"/>"
                            + "<application><activity android:name=\".MarkerActivity\"/></application>"
                            + "</manifest>");
            putBytes(zip, "jni/armeabi-v7a/libmarker.so", new byte[]{1, 2, 3, 4});
        }
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        putBytes(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
