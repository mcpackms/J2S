package com.j2s;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline resolver tests: every artifact comes from a {@code file://} repository built by
 * {@link TestRepo}, so no network access is required.
 */
class DependencyResolverTest {

    @TempDir
    Path tmp;

    private TestRepo repo;
    private Path settings;

    @BeforeEach
    void setUp() throws Exception {
        repo = new TestRepo(tmp.resolve("repo"));
        settings = tmp.resolve("settings.xml");
        Files.write(settings, "<settings/>".getBytes(StandardCharsets.UTF_8));
    }

    private RepoConfig config(List<String> repos, boolean offline, boolean transitive,
                              List<String> excludes) {
        return RepoConfig.from(Collections.<String, String>emptyMap(), tmp.resolve("m2"),
                repos, offline, transitive, excludes, settings);
    }

    private DependencyResolver resolver(RepoConfig cfg) throws Exception {
        return new DependencyResolver(cfg);
    }

    private static List<String> coords(DepResources deps) {
        List<String> out = new ArrayList<>();
        for (DepResources.ResolvedArtifact a : deps.artifacts) out.add(a.coord);
        return out;
    }

    @Test
    void resolvesTransitiveDependenciesAndFiltersTestScope() throws Exception {
        repo.jar("t", "liba", "1.0", "t:libb:1.0", "t:libc:1.0:test");
        repo.jar("t", "libb", "1.0");
        repo.jar("t", "libc", "1.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Collections.singletonList("t:liba:1.0"));

        List<String> coords = coords(deps);
        assertTrue(coords.contains("t:liba:1.0:jar"), "root jar: " + coords);
        assertTrue(coords.contains("t:libb:1.0:jar"), "transitive jar: " + coords);
        assertFalse(coords.contains("t:libc:1.0:jar"), "test scope must be filtered: " + coords);
        assertEquals(2, deps.jars.size());
    }

    @Test
    void noTransitiveResolvesOnlyRoot() throws Exception {
        repo.jar("t", "liba", "1.0", "t:libb:1.0");
        repo.jar("t", "libb", "1.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, false, emptyList()))
                .resolve(Collections.singletonList("t:liba:1.0"));

        List<String> coords = coords(deps);
        assertEquals(1, coords.size(), "coords: " + coords);
        assertTrue(coords.contains("t:liba:1.0:jar"));
    }

    @Test
    void userExclusionIsApplied() throws Exception {
        repo.jar("t", "liba", "1.0", "t:libb:1.0");
        repo.jar("t", "libb", "1.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, true,
                Collections.singletonList("t:libb")))
                .resolve(Collections.singletonList("t:liba:1.0"));

        List<String> coords = coords(deps);
        assertFalse(coords.contains("t:libb:1.0:jar"), "coords: " + coords);
        assertEquals(1, deps.jars.size());
    }

    @Test
    void bomManagesVersionOfDependencyWithoutVersion() throws Exception {
        repo.bom("t", "bom", "1.0", "t:liba:2.5");
        repo.jar("t", "liba", "2.5");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Arrays.asList("t:bom:1.0", "t:liba"));

        List<String> coords = coords(deps);
        assertTrue(coords.contains("t:liba:2.5:jar"), "coords: " + coords);
        assertEquals(1, deps.jars.size());
    }

    @Test
    void nestedBomImportIsFollowed() throws Exception {
        repo.bomImport("t", "bom-a", "1.0", "t:bom-b:1.0");
        repo.bom("t", "bom-b", "1.0", "t:liba:3.1");
        repo.jar("t", "liba", "3.1");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Arrays.asList("t:bom-a:1.0", "t:liba"));

        List<String> coords = coords(deps);
        assertTrue(coords.contains("t:liba:3.1:jar"), "coords: " + coords);
    }

    @Test
    void androidXStyleSingletonRangesAreRelaxedInsteadOfFailing() throws Exception {
        // AndroidX POMs publish Gradle strict versions as ranges: <version>[1.0]</version>.
        // The default Aether selector treats them as incompatible hard Maven ranges.
        repo.jar("t", "liba", "1.0", "t:libx:[1.0]");
        repo.jar("t", "libb", "1.0", "t:libx:[2.0]");
        repo.jar("t", "libx", "1.0");
        repo.jar("t", "libx", "2.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Arrays.asList("t:liba:1.0", "t:libb:1.0"));

        int libx = 0;
        for (DepResources.ResolvedArtifact a : deps.artifacts) {
            if (a.coord.startsWith("t:libx:")) libx++;
        }
        assertEquals(1, libx, "exactly one libx version must be selected: " + coords(deps));
    }

    @Test
    void versionlessWithoutBomFailsWithHelpfulMessage() throws Exception {
        repo.jar("t", "liba", "1.0");

        Exception e = assertThrows(Exception.class, () ->
                resolver(config(singleton(repo.url()), false, true, emptyList()))
                        .resolve(Collections.singletonList("t:liba")));
        assertTrue(e.getMessage().contains("no version and no BOM"), e.getMessage());
    }

    @Test
    void aarIsExtractedCompletely() throws Exception {
        repo.aar("t", "libd", "1.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Collections.singletonList("t:libd:1.0"));

        assertEquals(1, deps.aars.size());
        DepResources.Aar aar = deps.aars.get(0);
        assertEquals("t:libd:1.0", aar.label);
        assertNotNull(aar.classesJar);
        assertTrue(Files.isRegularFile(aar.classesJar));
        assertNotNull(aar.res);
        assertTrue(Files.isRegularFile(aar.res.resolve("values/strings.xml")));
        assertNotNull(aar.assets);
        assertTrue(Files.isRegularFile(aar.assets.resolve("a.txt")));
        assertNotNull(aar.jni);
        assertTrue(Files.isRegularFile(aar.jni.resolve("armeabi-v7a/libmarker.so")));
        assertNotNull(aar.manifest);
        assertTrue(Files.isRegularFile(aar.manifest));
        assertTrue(deps.hasAarResources());
        assertTrue(deps.resDirs().contains(aar.res));
        assertTrue(deps.jars.contains(aar.classesJar));
        assertTrue(deps.summary().contains("1 aar(s)"), deps.summary());
    }

    @Test
    void dexResZipContainsMergedAndPerAarLayout() throws Exception {
        repo.aar("t", "libd", "1.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Collections.singletonList("t:libd:1.0"));

        Path zipFile = tmp.resolve("dexres.zip");
        assertTrue(deps.writeDexResZip(zipFile));
        List<String> entries = zipEntries(zipFile);
        assertTrue(entries.contains("res/values/strings.xml"), "entries: " + entries);
        assertTrue(entries.contains("assets/a.txt"), "entries: " + entries);
        assertTrue(entries.contains("aar/t_libd_1.0/res/values/strings.xml"), "entries: " + entries);
        assertTrue(entries.contains("aar/t_libd_1.0/AndroidManifest.xml"), "entries: " + entries);
        assertTrue(entries.contains("aar/t_libd_1.0/jni/armeabi-v7a/libmarker.so"), "entries: " + entries);

        // resource XML files are also compiled into Android binary XML
        assertTrue(entries.contains("axml/res/values/strings.xml"), "entries: " + entries);
        assertTrue(entries.contains("axml/res/layout/main.xml"), "entries: " + entries);
        assertTrue(entries.contains("axml/aar/t_libd_1.0/res/layout/main.xml"), "entries: " + entries);
        byte[] axml = readEntry(zipFile, "axml/res/layout/main.xml");
        assertEquals(0x0003, axml[0] & 0xff, "AXML RES_XML_TYPE low byte");
        assertEquals(0x00, axml[1] & 0xff, "AXML RES_XML_TYPE high byte");
    }

    @Test
    void lockFileIsWrittenAndVerified() throws Exception {
        repo.jar("t", "liba", "1.0");

        DepResources deps = resolver(config(singleton(repo.url()), false, true, emptyList()))
                .resolve(Collections.singletonList("t:liba:1.0"));

        Path lock = tmp.resolve("deps.lock");
        RepoConfig cfg = config(singleton(repo.url()), false, true, emptyList());
        DepLock.write(lock, deps, cfg);
        assertTrue(Files.isRegularFile(lock));
        DepLock.verify(lock, deps);

        Path tampered = tmp.resolve("tampered.jar");
        Files.write(tampered, "different content".getBytes(StandardCharsets.UTF_8));
        DepResources changed = new DepResources(Collections.<Path>emptyList(),
                Collections.<DepResources.Aar>emptyList(),
                Collections.singletonList(new DepResources.ResolvedArtifact(
                        "t:liba:1.0:jar", tampered)));
        Exception e = assertThrows(Exception.class, () -> DepLock.verify(lock, changed));
        assertTrue(e.getMessage().contains("changed"), e.getMessage());
    }

    @Test
    void missingArtifactWithoutRepositoriesExplainsConfiguration() throws Exception {
        repo.jar("t", "liba", "1.0");

        Exception e = assertThrows(Exception.class, () ->
                resolver(config(emptyList(), false, true, emptyList()))
                        .resolve(Collections.singletonList("t:liba:1.0")));
        assertTrue(e.getMessage().contains("No Maven repository configured"), e.getMessage());
    }

    @Test
    void offlineMissingArtifactFailsAndSaysSo() throws Exception {
        repo.jar("t", "liba", "1.0");

        Exception e = assertThrows(Exception.class, () ->
                resolver(config(singleton(repo.url()), true, true, emptyList()))
                        .resolve(Collections.singletonList("t:liba:1.0")));
        assertTrue(e.getMessage().contains("Offline"), e.getMessage());
    }

    // ── helpers ──

    private static List<String> singleton(String s) {
        return Collections.singletonList(s);
    }

    private static List<String> emptyList() {
        return Collections.emptyList();
    }

    private static byte[] readEntry(Path zip, String name) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.zip.ZipEntry e = zf.getEntry(name);
            assertNotNull(e, "missing zip entry " + name);
            java.io.InputStream in = zf.getInputStream(e);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static List<String> zipEntries(Path zip) throws Exception {
        List<String> out = new ArrayList<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) out.add(en.nextElement().getName());
        }
        return out;
    }
}
