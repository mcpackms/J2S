package com.j2s;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Everything produced by Maven dependency resolution:
 * <ul>
 *   <li>classpath jars (plain jars, AAR {@code classes.jar} and AAR {@code libs/*.jar})</li>
 *   <li>AAR payloads (resources, assets, JNI libs, AndroidManifest.xml)</li>
 *   <li>the exact resolved artifact set (for the lock file)</li>
 * </ul>
 */
public final class DepResources {

    /** One resolved AAR and the parts extracted from it. */
    public static final class Aar {
        public final String label;       // group:artifact:version
        public final Path root;          // extraction root
        public final Path classesJar;    // may be null
        public final List<Path> libJars; // AAR libs/*.jar
        public final Path res;           // AAR res/ directory, may be null
        public final Path assets;        // AAR assets/ directory, may be null
        public final Path jni;           // AAR jni/ directory, may be null
        public final Path manifest;      // AAR AndroidManifest.xml, may be null

        Aar(String label, Path root, Path classesJar, List<Path> libJars,
            Path res, Path assets, Path jni, Path manifest) {
            this.label = label;
            this.root = root;
            this.classesJar = classesJar;
            this.libJars = libJars;
            this.res = res;
            this.assets = assets;
            this.jni = jni;
            this.manifest = manifest;
        }
    }

    /** One artifact present in the resolved graph, used for lock files. */
    public static final class ResolvedArtifact {
        /** group:artifact:version:extension[:classifier] */
        public final String coord;
        public final Path file;

        public ResolvedArtifact(String coord, Path file) {
            this.coord = coord;
            this.file = file;
        }
    }

    public final List<Path> jars;
    public final List<Aar> aars;
    public final List<ResolvedArtifact> artifacts;

    public static final DepResources EMPTY = new DepResources(
            Collections.<Path>emptyList(), Collections.<Aar>emptyList(),
            Collections.<ResolvedArtifact>emptyList());

    public DepResources(List<Path> jars, List<Aar> aars, List<ResolvedArtifact> artifacts) {
        this.jars = Collections.unmodifiableList(new ArrayList<>(jars));
        this.aars = Collections.unmodifiableList(new ArrayList<>(aars));
        this.artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts));
    }

    public boolean isEmpty() {
        return jars.isEmpty() && aars.isEmpty();
    }

    public List<Path> resDirs() {
        List<Path> out = new ArrayList<>();
        for (Aar a : aars) if (a.res != null) out.add(a.res);
        return out;
    }

    public List<Path> assetsDirs() {
        List<Path> out = new ArrayList<>();
        for (Aar a : aars) if (a.assets != null) out.add(a.assets);
        return out;
    }

    public List<Path> jniDirs() {
        List<Path> out = new ArrayList<>();
        for (Aar a : aars) if (a.jni != null) out.add(a.jni);
        return out;
    }

    public List<Path> aarManifests() {
        List<Path> out = new ArrayList<>();
        for (Aar a : aars) if (a.manifest != null) out.add(a.manifest);
        return out;
    }

    public boolean hasAarResources() {
        for (Aar a : aars) {
            if (a.res != null || a.assets != null || a.manifest != null) return true;
        }
        return false;
    }

    public String summary() {
        int resFiles = countFiles(resDirs());
        int assetFiles = countFiles(assetsDirs());
        int jniFiles = countFiles(jniDirs());
        return jars.size() + " jar(s), " + aars.size() + " aar(s), "
                + resFiles + " res file(s), " + assetFiles + " asset file(s), "
                + jniFiles + " jni lib(s)";
    }

    /**
     * Extract all AAR resources into a single {@code dexres.zip}.
     *
     * <p>Layout:
     * <pre>
     *   res/...                              merged resources (later AAR wins on conflict)
     *   assets/...                           merged assets (later AAR wins on conflict)
     *   aar/&lt;group_artifact_version&gt;/res/...       per-AAR resources, nothing lost
     *   aar/&lt;group_artifact_version&gt;/assets/...
     *   aar/&lt;group_artifact_version&gt;/jni/...
     *   aar/&lt;group_artifact_version&gt;/AndroidManifest.xml
     *   axml/res/...                         every res XML compiled to Android binary XML
     *   axml/aar/&lt;group_artifact_version&gt;/res/...
     * </pre>
     *
     * @return false when there is nothing to pack
     */
    public boolean writeDexResZip(Path target) throws IOException {
        if (!hasAarResources()) return false;

        Map<String, Path> merged = new LinkedHashMap<>();
        Map<String, Path> perAar = new LinkedHashMap<>();
        for (Aar aar : aars) {
            String prefix = "aar/" + safeName(aar.label);
            if (aar.res != null) {
                addTree(merged, "res", aar.res);
                addTree(perAar, prefix + "/res", aar.res);
            }
            if (aar.assets != null) {
                addTree(merged, "assets", aar.assets);
                addTree(perAar, prefix + "/assets", aar.assets);
            }
            if (aar.jni != null) addTree(perAar, prefix + "/jni", aar.jni);
            if (aar.manifest != null && Files.isRegularFile(aar.manifest)) {
                perAar.put(prefix + "/AndroidManifest.xml", aar.manifest);
            }
        }
        Map<String, byte[]> axml = new LinkedHashMap<>();
        int axmlFailures = 0;
        for (Map.Entry<String, Path> e : merged.entrySet()) {
            if (!isXmlPath(e.getKey())) continue;
            byte[] encoded = encodeAxml(e.getKey(), e.getValue());
            if (encoded != null) axml.put("axml/" + e.getKey(), encoded);
            else axmlFailures++;
        }
        for (Map.Entry<String, Path> e : perAar.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("aar/") || !key.contains("/res/") || !isXmlPath(key)) continue;
            byte[] encoded = encodeAxml(key, e.getValue());
            if (encoded != null) axml.put("axml/" + key, encoded);
            else axmlFailures++;
        }
        if (axmlFailures > 0) {
            System.err.println("Warning: " + axmlFailures
                    + " resource XML file(s) could not be encoded as AXML");
        }

        if (merged.isEmpty() && perAar.isEmpty()) return false;

        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (OutputStream os = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Map.Entry<String, Path> e : merged.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                Files.copy(e.getValue(), zip);
                zip.closeEntry();
            }
            for (Map.Entry<String, Path> e : perAar.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                Files.copy(e.getValue(), zip);
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : axml.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        System.out.println("dexres.zip: " + target + "  ("
                + merged.size() + " merged file(s), " + axml.size() + " AXML file(s), "
                + aars.size() + " AAR(s))");
        return true;
    }

    private static boolean isXmlPath(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".xml");
    }

    private static byte[] encodeAxml(String path, Path source) {
        try {
            return AxmlEncoder.encode(source);
        } catch (Exception e) {
            System.err.println("Warning: cannot encode AXML for " + path + " (" + e + ")");
            return null;
        }
    }

    private static void addTree(Map<String, Path> out, String prefix, Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        Collections.sort(files);
        for (Path f : files) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            out.put(prefix + "/" + rel, f); // LinkedHashMap: later entries overwrite => last wins
        }
    }

    private static String safeName(String label) {
        return label.replace(':', '_').replace('/', '_');
    }

    private static int countFiles(List<Path> dirs) {
        int n = 0;
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                n += (int) s.filter(Files::isRegularFile).count();
            } catch (IOException ignored) {
            }
        }
        return n;
    }
}
