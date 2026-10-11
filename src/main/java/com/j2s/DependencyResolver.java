package com.j2s;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Maven 依赖解析器（基于 Aether），支持 jar 和 aar。
 */
public final class DependencyResolver {

    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> repos;
    private final Path aarJarCache;
    private final Path aarResCache;

    /** 本次解析中收集到的 AAR res 目录（去重，保留插入顺序）。 */
    private final LinkedHashSet<Path> aarResDirs = new LinkedHashSet<>();

    public DependencyResolver(Path localRepo, List<String> extraRepos) throws IOException {
        this.system = new RepositorySystemSupplier().get();
        this.session = MavenRepositorySystemUtils.newSession();

        Path repo = (localRepo != null) ? localRepo
                : Paths.get(System.getProperty("user.home"), ".m2", "repository");
        Files.createDirectories(repo);

        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(repo.toFile())));

        // 注册 aar 类型 —— Aether 1.9.x 的 DefaultArtifactType 只有 4 参构造
        DefaultArtifactTypeRegistry registry = new DefaultArtifactTypeRegistry();
        registry.add(new DefaultArtifactType("jar", "jar", "", "java"));
        registry.add(new DefaultArtifactType("aar", "aar", "", "java"));
        session.setArtifactTypeRegistry(registry);

        List<RemoteRepository> list = new ArrayList<>();
        list.add(new RemoteRepository.Builder("central", "default",
                "https://repo.maven.apache.org/maven2").build());
        list.add(new RemoteRepository.Builder("google", "default",
                "https://maven.google.com").build());
        if (extraRepos != null) {
            for (String url : extraRepos) {
                list.add(new RemoteRepository.Builder(
                        "extra-" + Integer.toHexString(url.hashCode()),
                        "default", url).build());
            }
        }
        this.repos = Collections.unmodifiableList(list);

        this.aarJarCache = repo.resolve(".aar-jars");
        this.aarResCache = repo.resolve(".aar-res");
        Files.createDirectories(aarJarCache);
        Files.createDirectories(aarResCache);
    }

    public List<Path> resolve(String coord) throws Exception {
        try {
            return resolveInternal(coord, false);
        } catch (Exception first) {
            try {
                return resolveInternal(coord, true);
            } catch (Exception second) {
                throw first;
            }
        }
    }

    private List<Path> resolveInternal(String coord, boolean asAar) throws Exception {
        Artifact root;
        if (asAar) {
            String[] parts = coord.split(":");
            if (parts.length < 3) throw new IllegalArgumentException("Bad coord: " + coord);
            String g = parts[0], a = parts[1], v = parts[2];
            String cls = (parts.length > 3) ? parts[3] : "";
            root = new DefaultArtifact(g, a, cls, "aar", v);
        } else {
            root = new DefaultArtifact(coord);
        }

        CollectRequest collect = new CollectRequest();
        collect.setRoot(new Dependency(root, "compile"));
        collect.setRepositories(repos);

        DependencyRequest req = new DependencyRequest();
        req.setCollectRequest(collect);
        req.setFilter(DependencyFilterUtils.classpathFilter("compile", "runtime"));

        DependencyResult result = system.resolveDependencies(session, req);

        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (ArtifactResult ar : result.getArtifactResults()) {
            if (!ar.isResolved() || ar.getArtifact().getFile() == null) continue;
            File f = ar.getArtifact().getFile();
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".aar")) {
                Path jar = extractClassesJar(f.toPath());
                if (jar != null) out.add(jar);
                Path res = extractAarRes(f.toPath());
                if (res != null) aarResDirs.add(res);
            } else if (name.endsWith(".jar")) {
                out.add(f.toPath());
            }
        }
        return new ArrayList<>(out);
    }

    public Path resolveSingle(String coord) throws Exception {
        try {
            Artifact a = new DefaultArtifact(coord);
            ArtifactResult r = system.resolveArtifact(session,
                    new ArtifactRequest(a, repos, null));
            return r.getArtifact().getFile().toPath();
        } catch (Exception first) {
            String[] parts = coord.split(":");
            if (parts.length < 3) throw first;
            String g = parts[0], aId = parts[1], v = parts[2];
            String cls = (parts.length > 3) ? parts[3] : "";
            Artifact aar = new DefaultArtifact(g, aId, cls, "aar", v);
            ArtifactResult r = system.resolveArtifact(session,
                    new ArtifactRequest(aar, repos, null));
            return r.getArtifact().getFile().toPath();
        }
    }

    public List<Path> getAarResDirs() {
        return new ArrayList<>(aarResDirs);
    }

    // ─────────────────────────────────────────────────────────

    private Path extractClassesJar(Path aar) throws IOException {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(aar).toMillis();
        } catch (IOException e) {
            mtime = 0;
        }
        String key = aar.getFileName().toString() + "-" + mtime + ".jar";
        Path cached = aarJarCache.resolve(key);
        if (Files.exists(cached)) return cached;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(aar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("classes.jar".equals(e.getName())) {
                    Path tmp = Files.createTempFile("j2s-aar-", ".jar");
                    try (OutputStream os = Files.newOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
                    return cached;
                }
            }
        }
        return null;
    }

    private Path extractAarRes(Path aar) throws IOException {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(aar).toMillis();
        } catch (IOException e) {
            mtime = 0;
        }
        String key = aar.getFileName().toString() + "-" + mtime;
        Path cached = aarResCache.resolve(key);
        if (Files.isDirectory(cached)) return cached;

        Path tmp = aarResCache.resolve(key + ".tmp");
        deleteRecursive(tmp);
        Files.createDirectories(tmp);

        boolean hasRes = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(aar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (!name.startsWith("res/")) continue;
                if (name.contains("..")) continue;
                hasRes = true;
                Path target = tmp.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            deleteRecursive(tmp);
            throw e;
        }

        if (!hasRes) {
            deleteRecursive(tmp);
            return null;
        }

        try {
            Files.move(tmp, cached, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    private static void deleteRecursive(Path p) {
        try {
            if (!Files.exists(p)) return;
            Files.walk(p)
                    .sorted(Comparator.reverseOrder())
                    .forEach(x -> {
                        try { Files.deleteIfExists(x); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}