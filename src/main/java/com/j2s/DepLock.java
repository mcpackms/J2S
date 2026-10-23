package com.j2s;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A tiny dependency lock file: the resolved artifact set plus the SHA-256 of every artifact file.
 *
 * <p>With {@code --dep-lock <file>}:
 * <ul>
 *   <li>file missing -&gt; write it after resolution;</li>
 *   <li>file present -&gt; verify that the resolved artifacts are exactly the locked ones;</li>
 *   <li>file present + {@code --update-dep-lock} -&gt; overwrite it.</li>
 * </ul>
 * This is intentionally not a full dependency-graph lock (no POM hashes); it catches version
 * drift and changed artifact contents, which is enough for reproducible offline builds.
 */
public final class DepLock {

    private static final String VERSION = "1";

    private DepLock() {}

    public static void writeOrVerify(Path file, boolean update, DepResources deps, RepoConfig cfg)
            throws Exception {
        if (Files.isRegularFile(file) && !update) {
            verify(file, deps);
        } else {
            write(file, deps, cfg);
        }
    }

    static void write(Path file, DepResources deps, RepoConfig cfg) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# J2S dependency lock file (generated, do not edit by hand)\n");
        sb.append("lock.version=").append(VERSION).append('\n');
        sb.append("repo.count=").append(cfg.repositories.size()).append('\n');
        for (int i = 0; i < cfg.repositories.size(); i++) {
            sb.append("repo.").append(i).append('=').append(cfg.repositories.get(i)).append('\n');
        }
        sb.append("artifact.count=").append(deps.artifacts.size()).append('\n');
        int i = 0;
        for (DepResources.ResolvedArtifact a : deps.artifacts) {
            sb.append("artifact.").append(i).append('=').append(a.coord).append('\n');
            sb.append("artifact.").append(i).append(".sha256=")
                    .append(sha256(a.file)).append('\n');
            i++;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Dep lock: wrote " + deps.artifacts.size()
                + " artifact(s) to " + file);
    }

    static void verify(Path file, DepResources deps) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        String version = p.getProperty("lock.version");
        if (version != null && !VERSION.equals(version)) {
            throw new Exception("Unsupported dependency lock format " + version
                    + " in " + file + " (expected " + VERSION + ")");
        }

        Map<String, String> expected = new LinkedHashMap<>();
        int count = intProp(p, "artifact.count");
        for (int i = 0; i < count; i++) {
            String coord = p.getProperty("artifact." + i);
            String sha = p.getProperty("artifact." + i + ".sha256");
            if (coord != null && sha != null) expected.put(coord, sha);
        }

        Map<String, String> actual = new LinkedHashMap<>();
        for (DepResources.ResolvedArtifact a : deps.artifacts) {
            actual.put(a.coord, sha256(a.file));
        }

        StringBuilder problems = new StringBuilder();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) {
                problems.append("  missing: ").append(e.getKey()).append('\n');
            } else if (!got.equals(e.getValue())) {
                problems.append("  changed: ").append(e.getKey())
                        .append("\n    locked:   ").append(e.getValue())
                        .append("\n    resolved: ").append(got).append('\n');
            }
        }
        for (String coord : actual.keySet()) {
            if (!expected.containsKey(coord)) {
                problems.append("  new: ").append(coord).append('\n');
            }
        }
        if (problems.length() > 0) {
            throw new Exception("Dependency lock verification failed for " + file + ":\n"
                    + problems
                    + "Re-run with --update-dep-lock to accept the new resolution.");
        }
        System.out.println("Dep lock: verified " + actual.size() + " artifact(s) against " + file);
    }

    private static int intProp(Properties p, String key) {
        try {
            return Integer.parseInt(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
