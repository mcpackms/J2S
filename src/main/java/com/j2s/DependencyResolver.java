package com.j2s;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.ProxySelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.graph.transformer.JavaDependencyContextRefiner;
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver;
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector;
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Maven dependency resolver built on Aether.
 *
 * <p>Supports:
 * <ul>
 *   <li>transitive resolution (compile + runtime scopes, optional/test/provided filtered)</li>
 *   <li>BOMs: a coordinate whose POM has {@code <packaging>pom</packaging>} (or an explicit
 *       {@code g:a:v@pom}) imports its {@code dependencyManagement}; versions may then be omitted
 *       from subsequent {@code -D} coordinates. Nested BOM imports are followed recursively.</li>
 *   <li>AARs: {@code classes.jar}, {@code libs/*.jar}, {@code res/}, {@code assets/},
 *       {@code jni/} and {@code AndroidManifest.xml} are extracted to a content-addressed cache
 *       under {@code <localRepo>/.j2s-aar/<sha256>/}.</li>
 *   <li>mirrors / proxies / credentials from {@code settings.xml}, plus a {@code J2S_PROXY}
 *       fallback.</li>
 *   <li>offline mode and a configurable set of remote repositories (empty by default).</li>
 * </ul>
 */
public final class DependencyResolver {

    private static final String POM = "pom";
    private static final String JAR = "jar";
    private static final String AAR = "aar";

    private final RepoConfig cfg;
    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> repos;
    private final Path aarCacheRoot;

    public DependencyResolver(RepoConfig cfg) throws Exception {
        this.cfg = cfg;
        this.repos = Collections.unmodifiableList(buildRepositories(cfg));
        this.system = new RepositorySystemSupplier().get();

        DefaultRepositorySystemSession s = MavenRepositorySystemUtils.newSession();
        // Maven's model builder activates POM profiles (e.g. java.version based ones), so the
        // session must expose the JVM properties; without them descriptor reading fails and
        // transitive dependencies silently disappear.
        Map<String, String> systemProperties = new HashMap<>();
        for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
            systemProperties.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        s.setSystemProperties(systemProperties);
        s.setUserProperties(new HashMap<String, String>());
        Files.createDirectories(cfg.localRepo);
        s.setLocalRepositoryManager(system.newLocalRepositoryManager(
                s, new LocalRepository(cfg.localRepo.toFile())));
        s.setOffline(cfg.offline);
        s.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_WARN);
        s.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_DAILY);
        s.setArtifactTypeRegistry(artifactTypes());
        // AndroidX POMs publish Gradle strict versions as ranges like [2.5.1]; the default
        // NearestVersionSelector treats them as Maven hard ranges and aborts with
        // "Could not resolve version conflict". Relax singleton ranges to soft requirements.
        s.setDependencyGraphTransformer(new ChainedDependencyGraphTransformer(
                new ConflictResolver(new RelaxedVersionSelector(),
                        new JavaScopeSelector(), new SimpleOptionalitySelector(),
                        new JavaScopeDeriver()),
                new JavaDependencyContextRefiner()));
        ProxySelector proxySelector = buildProxySelector(cfg);
        if (proxySelector != null) s.setProxySelector(proxySelector);
        this.session = s;
        this.aarCacheRoot = chooseAarCacheRoot(cfg.localRepo);
        Files.createDirectories(this.aarCacheRoot);
    }

    public RepoConfig config() {
        return cfg;
    }

    // ───────────────────────────────────────────────────────── resolve

    /** Resolve the given Maven coordinates into jars + AAR payloads. */
    public DepResources resolve(List<String> coords) throws Exception {
        if (coords == null || coords.isEmpty()) return DepResources.EMPTY;

        List<Coord> parsed = new ArrayList<>();
        for (String raw : coords) parsed.add(Coord.parse(raw));

        LinkedHashMap<String, Dependency> managed = new LinkedHashMap<>();
        Set<String> bomVisited = new LinkedHashSet<>();
        List<Coord> roots = new ArrayList<>();
        List<Coord> versionless = new ArrayList<>();

        // Pass 1: explicit BOMs and coordinates that already have a version.
        for (Coord c : parsed) {
            if (POM.equals(c.extension)) {
                collectBom(c, managed, bomVisited);
            } else if (c.version == null) {
                versionless.add(c);
            } else {
                String ext = c.extension != null ? c.extension : detectExtension(c);
                if (POM.equals(ext)) {
                    collectBom(c.withExtension(POM), managed, bomVisited);
                } else {
                    roots.add(c.withExtension(ext));
                }
            }
        }

        // Pass 2: version-less coordinates, filled from the BOMs collected above.
        for (Coord c : versionless) {
            String v = managedVersion(managed, c);
            if (v == null) {
                throw new Exception("Dependency " + c.groupId + ":" + c.artifactId
                        + " has no version and no BOM manages it.\n"
                        + "  Pass g:a:v, or add a BOM such as g:a:v (packaging=pom) before it.");
            }
            Coord withVersion = c.withVersion(v);
            String ext = withVersion.extension != null
                    ? withVersion.extension
                    : detectExtension(withVersion);
            if (POM.equals(ext)) {
                collectBom(withVersion.withExtension(POM), managed, bomVisited);
            } else {
                roots.add(withVersion.withExtension(ext));
            }
        }

        if (roots.isEmpty()) {
            System.out.println("Warning: only BOM(s) were requested; no dependency is added to the build.");
            return DepResources.EMPTY;
        }

        List<ArtifactResult> results = resolveRoots(roots, managed);
        return processResults(results);
    }

    /** Resolve a single artifact file (used for the Compose compiler plugin). */
    public Path resolveSingle(String coord) throws Exception {
        Coord c = Coord.parse(coord);
        if (c.version == null) {
            throw new Exception("Dependency " + coord + " needs an explicit version");
        }
        String[] extOrder = c.extension != null
                ? new String[]{c.extension}
                : new String[]{JAR, AAR};
        for (String ext : extOrder) {
            try {
                Artifact a = new DefaultArtifact(c.groupId, c.artifactId, c.classifier, ext, c.version);
                ArtifactResult r = system.resolveArtifact(session, new ArtifactRequest(a, repos, null));
                if (r.isResolved() && r.getArtifact().getFile() != null) {
                    return r.getArtifact().getFile().toPath();
                }
            } catch (Exception ignored) {
                // try the next packaging
            }
        }
        throw new Exception("Cannot resolve " + coord + " from " + reposDescription());
    }

    // ───────────────────────────────────────────────────────── resolution

    private List<ArtifactResult> resolveRoots(List<Coord> roots,
                                              Map<String, Dependency> managed) throws Exception {
        if (!cfg.transitive) {
            List<ArtifactResult> out = new ArrayList<>();
            for (Coord c : roots) {
                try {
                    ArtifactResult r = system.resolveArtifact(session,
                            new ArtifactRequest(artifact(c), repos, null));
                    if (r.isResolved()) out.add(r);
                } catch (Exception e) {
                    throw explain("Cannot resolve " + c, e);
                }
            }
            return out;
        }

        CollectRequest collect = new CollectRequest();
        collect.setRepositories(repos);
        List<Dependency> rootDeps = new ArrayList<>();
        for (Coord c : roots) {
            rootDeps.add(new Dependency(artifact(c), "compile", Boolean.FALSE, userExclusions()));
        }
        collect.setDependencies(rootDeps);
        if (!managed.isEmpty()) {
            collect.setManagedDependencies(new ArrayList<>(managed.values()));
        }

        DependencyRequest req = new DependencyRequest();
        req.setCollectRequest(collect);
        req.setFilter(DependencyFilterUtils.classpathFilter("compile", "runtime"));
        try {
            DependencyResult result = system.resolveDependencies(session, req);
            return result.getArtifactResults();
        } catch (DependencyResolutionException e) {
            throw explain("Dependency resolution failed", unwrap(e));
        }
    }

    private DepResources processResults(List<ArtifactResult> results) {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        List<DepResources.Aar> aars = new ArrayList<>();
        List<DepResources.ResolvedArtifact> artifacts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ArtifactResult ar : results) {
            if (!ar.isResolved()) continue;
            Artifact a = ar.getArtifact();
            File f = a.getFile();
            if (f == null) continue;
            Path file = f.toPath();
            String coord = artifactCoord(a);
            if (seen.add(coord)) artifacts.add(new DepResources.ResolvedArtifact(coord, file));

            String ext = a.getExtension() == null ? "" : a.getExtension().toLowerCase(Locale.ROOT);
            String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (POM.equals(ext)) continue;

            if (AAR.equals(ext) || fileName.endsWith(".aar")) {
                try {
                    DepResources.Aar aar = processAar(a, file);
                    aars.add(aar);
                    if (aar.classesJar != null) jars.add(aar.classesJar);
                    jars.addAll(aar.libJars);
                } catch (Exception e) {
                    System.err.println("Warning: cannot extract AAR " + coord + " (" + e + ")");
                }
            } else if (ext.isEmpty() || JAR.equals(ext) || fileName.endsWith(".jar")) {
                jars.add(file);
            }
        }

        System.out.println("Resolved " + artifacts.size() + " artifact(s): "
                + jars.size() + " jar(s), " + aars.size() + " aar(s)");
        return new DepResources(new ArrayList<>(jars), aars, artifacts);
    }

    // ───────────────────────────────────────────────────────── BOMs

    private void collectBom(Coord bom, Map<String, Dependency> managed, Set<String> visited)
            throws Exception {
        String key = bom.groupId + ":" + bom.artifactId + ":" + bom.version
                + (bom.classifier.isEmpty() ? "" : ":" + bom.classifier);
        if (!visited.add(key)) return;

        Artifact pomArtifact = new DefaultArtifact(bom.groupId, bom.artifactId,
                bom.classifier, POM, bom.version);
        ArtifactDescriptorResult descriptor;
        try {
            descriptor = system.readArtifactDescriptor(session,
                    new ArtifactDescriptorRequest(pomArtifact, repos, null));
        } catch (Exception e) {
            throw explain("Cannot read BOM " + bom, e);
        }

        int added = 0;
        for (Dependency d : descriptor.getManagedDependencies()) {
            String depKey = managedKey(d.getArtifact());
            if (!managed.containsKey(depKey)) {
                managed.put(depKey, d);
                added++;
            }
        }

        // Follow import-scope BOMs explicitly; this keeps nested BOMs working even if the
        // repository system does not expand them for us.
        Path pomFile = resolvePomFile(bom);
        if (pomFile != null) {
            Map<String, String> props = readPomProperties(pomFile);
            putProjectProperties(props, bom);
            for (Coord imported : readImportedBoms(pomFile, props, bom)) {
                collectBom(imported, managed, visited);
            }
        }

        System.out.println("BOM " + bom + ": " + descriptor.getManagedDependencies().size()
                + " managed dep(s), " + added + " new");
    }

    /** Read the BOM's own {@code <properties>} for import-scope version interpolation. */
    private static Map<String, String> readPomProperties(Path pom) {
        Map<String, String> props = new HashMap<>();
        try {
            Document doc = parseXml(pom);
            Element project = doc.getDocumentElement();
            Element properties = firstChild(project, "properties");
            if (properties != null) {
                NodeList kids = properties.getChildNodes();
                for (int i = 0; i < kids.getLength(); i++) {
                    Node n = kids.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    props.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
        } catch (Exception ignored) {
        }
        return props;
    }

    private List<Coord> readImportedBoms(Path pom, Map<String, String> props, Coord self) {
        List<Coord> out = new ArrayList<>();
        try {
            Document doc = parseXml(pom);
            Element project = doc.getDocumentElement();
            Element depMgmt = firstChild(project, "dependencyManagement");
            if (depMgmt == null) return out;
            Element dependencies = firstChild(depMgmt, "dependencies");
            if (dependencies == null) return out;
            for (Element dep : children(dependencies, "dependency")) {
                String scope = interpolate(text(child(dep, "scope")), props);
                if (!"import".equals(scope)) continue;
                String g = interpolate(text(child(dep, "groupId")), props);
                String a = interpolate(text(child(dep, "artifactId")), props);
                String v = interpolate(text(child(dep, "version")), props);
                String classifier = interpolate(text(child(dep, "classifier")), props);
                if (g == null || a == null || v == null) {
                    System.err.println("Warning: cannot expand BOM import in " + self
                            + " (unresolved property)");
                    continue;
                }
                out.add(new Coord(g, a, v, classifier == null ? "" : classifier, POM));
            }
        } catch (Exception e) {
            System.err.println("Warning: cannot parse BOM " + pom + " (" + e + ")");
        }
        return out;
    }

    private String managedVersion(Map<String, Dependency> managed, Coord c) {
        Dependency d = managed.get(managedKey(c));
        if (d == null) return null;
        return d.getArtifact().getVersion();
    }

    private static String managedKey(Artifact a) {
        String classifier = a.getClassifier() == null ? "" : a.getClassifier();
        return a.getGroupId() + ":" + a.getArtifactId()
                + (classifier.isEmpty() ? "" : ":" + classifier);
    }

    private static String managedKey(Coord c) {
        return c.groupId + ":" + c.artifactId
                + (c.classifier.isEmpty() ? "" : ":" + c.classifier);
    }

    // ───────────────────────────────────────────────────────── packaging detection

    /** Read the POM's {@code <packaging>} to decide between jar / aar / pom (BOM). */
    private String detectExtension(Coord c) {
        try {
            Path pom = resolvePomFile(c);
            if (pom == null) return JAR;
            Document doc = parseXml(pom);
            Element project = doc.getDocumentElement();
            String packaging = text(firstChild(project, "packaging"));
            if (packaging == null || packaging.trim().isEmpty()) packaging = JAR;
            packaging = packaging.trim().toLowerCase(Locale.ROOT);
            if (POM.equals(packaging) || AAR.equals(packaging)) return packaging;
            return JAR;
        } catch (Exception e) {
            return JAR;
        }
    }

    private Path resolvePomFile(Coord c) {
        try {
            Artifact pomArtifact = new DefaultArtifact(c.groupId, c.artifactId,
                    c.classifier, POM, c.version);
            ArtifactResult r = system.resolveArtifact(session,
                    new ArtifactRequest(pomArtifact, repos, null));
            if (r.isResolved() && r.getArtifact().getFile() != null) {
                return r.getArtifact().getFile().toPath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ───────────────────────────────────────────────────────── AAR extraction

    private DepResources.Aar processAar(Artifact artifact, Path file) throws Exception {
        String sha = DepLock.sha256(file);
        Path root = aarCacheRoot.resolve(sha);
        Path marker = root.resolve(".complete");
        if (!Files.isRegularFile(marker)) {
            Path tmp = aarCacheRoot.resolve(sha + ".tmp-" + token());
            Utils.delete(tmp);
            Files.createDirectories(aarCacheRoot);
            try {
                extractAar(file, tmp);
                Files.write(tmp.resolve(".complete"), new byte[0]);
                moveIntoPlace(tmp, root);
            } catch (Exception e) {
                Utils.delete(tmp);
                throw e;
            }
        }

        String label = artifact.getGroupId() + ":" + artifact.getArtifactId()
                + ":" + artifact.getVersion();
        return new DepResources.Aar(label, root,
                existingFile(root.resolve("classes.jar")),
                listJars(root.resolve("libs")),
                existingDir(root.resolve("res")),
                existingDir(root.resolve("assets")),
                existingDir(root.resolve("jni")),
                existingFile(root.resolve("AndroidManifest.xml")));
    }

    private void moveIntoPlace(Path tmp, Path root) throws IOException {
        if (Files.exists(root)) {
            Utils.delete(tmp);
            return;
        }
        Files.createDirectories(root.getParent());
        try {
            Files.move(tmp, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, root);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            Utils.delete(tmp);
        }
    }

    private void extractAar(Path aar, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(aar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!isWantedAarEntry(name)) continue;
                Path out = safeResolve(target, name);
                if (out == null) {
                    System.err.println("Warning: skipping suspicious AAR entry " + name
                            + " in " + aar.getFileName());
                    continue;
                }
                Path parent = out.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isWantedAarEntry(String name) {
        return name.equals("classes.jar")
                || name.equals("AndroidManifest.xml")
                || name.equals("R.txt")
                || name.startsWith("res/")
                || name.startsWith("assets/")
                || name.startsWith("jni/")
                || (name.startsWith("libs/") && name.endsWith(".jar"));
    }

    private static Path safeResolve(Path root, String name) {
        try {
            Path out = root.resolve(name).normalize();
            if (!out.startsWith(root)) return null;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path existingFile(Path p) {
        return Files.isRegularFile(p) ? p : null;
    }

    private static Path existingDir(Path p) {
        return Files.isDirectory(p) ? p : null;
    }

    private static List<Path> listJars(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Path chooseAarCacheRoot(Path localRepo) {
        Path candidate = localRepo.resolve(".j2s-aar");
        try {
            Files.createDirectories(candidate);
            if (Files.isWritable(candidate)) return candidate;
        } catch (Exception ignored) {
        }
        Path fallback = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "j2s-aar");
        try {
            Files.createDirectories(fallback);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    // ───────────────────────────────────────────────────────── session helpers

    private static DefaultArtifactTypeRegistry artifactTypes() {
        DefaultArtifactTypeRegistry registry = new DefaultArtifactTypeRegistry();
        registry.add(new DefaultArtifactType(POM, POM, "", "java"));
        registry.add(new DefaultArtifactType(JAR, JAR, "", "java"));
        registry.add(new DefaultArtifactType(AAR, AAR, "", "java"));
        registry.add(new DefaultArtifactType("test-jar", JAR, "tests", "java"));
        return registry;
    }

    private static List<RemoteRepository> buildRepositories(RepoConfig cfg) {
        List<RemoteRepository> out = new ArrayList<>();
        for (String url : cfg.repositories) {
            String id = RepoConfig.repositoryId(url);
            MavenSettings.Mirror mirror = cfg.settings.mirrorFor(id, url);
            String targetUrl = mirror != null ? mirror.url : url;
            String targetId = mirror != null ? mirror.id : id;
            RemoteRepository.Builder b = new RemoteRepository.Builder(targetId, "default", targetUrl);
            MavenSettings.Server server = cfg.settings.server(targetId);
            if (server == null) server = cfg.settings.server(id);
            if (server != null && server.username != null) {
                b.setAuthentication(new AuthenticationBuilder()
                        .addUsername(server.username)
                        .addPassword(server.password == null ? "" : server.password)
                        .build());
            }
            out.add(b.build());
        }
        return out;
    }

    private static ProxySelector buildProxySelector(RepoConfig cfg) {
        DefaultProxySelector selector = new DefaultProxySelector();
        boolean any = false;
        for (MavenSettings.ProxySpec p : cfg.settings.activeProxies()) {
            selector.add(new Proxy(p.protocol, p.host, p.port, authentication(p.username, p.password)),
                    p.nonProxyHosts);
            any = true;
        }
        if (!any && cfg.proxySpec != null) {
            ParsedProxy pp = ParsedProxy.parse(cfg.proxySpec);
            if (pp != null) {
                selector.add(new Proxy(Proxy.TYPE_HTTP, pp.host, pp.port,
                        authentication(pp.username, pp.password)), cfg.nonProxyHosts);
                selector.add(new Proxy(Proxy.TYPE_HTTPS, pp.host, pp.port,
                        authentication(pp.username, pp.password)), cfg.nonProxyHosts);
                any = true;
            } else {
                System.err.println("Warning: cannot parse " + RepoConfig.ENV_PROXY + "="
                        + cfg.proxySpec + " (expected [http://][user:pass@]host:port)");
            }
        }
        return any ? selector : null;
    }

    private static org.eclipse.aether.repository.Authentication authentication(String user,
                                                                              String password) {
        if (user == null) return null;
        return new AuthenticationBuilder()
                .addUsername(user)
                .addPassword(password == null ? "" : password)
                .build();
    }

    private List<Exclusion> userExclusions() {
        List<Exclusion> out = new ArrayList<>();
        for (String e : cfg.excludes) {
            String[] parts = e.split(":");
            if (parts.length >= 2) {
                out.add(new Exclusion(parts[0].trim(), parts[1].trim(), "*", "*"));
            }
        }
        return out;
    }

    private static Artifact artifact(Coord c) {
        return new DefaultArtifact(c.groupId, c.artifactId, c.classifier,
                c.extension == null ? JAR : c.extension, c.version);
    }

    private static String artifactCoord(Artifact a) {
        String classifier = a.getClassifier() == null ? "" : a.getClassifier();
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion()
                + ":" + a.getExtension()
                + (classifier.isEmpty() ? "" : ":" + classifier);
    }

    private String reposDescription() {
        if (repos.isEmpty()) return "(no remote repository configured; local repository only: "
                + cfg.localRepo + ")";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repos.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(repos.get(i).getUrl());
        }
        return sb.toString();
    }

    /** Extract the real reason(s) from Aether's collection/artifact results. */
    private static Exception unwrap(DependencyResolutionException e) {
        DependencyResult result = e.getResult();
        List<Exception> candidates = new ArrayList<>();
        if (result != null) {
            if (result.getCollectExceptions() != null) {
                candidates.addAll(result.getCollectExceptions());
            }
            if (result.getArtifactResults() != null) {
                for (ArtifactResult ar : result.getArtifactResults()) {
                    if (ar.getExceptions() != null) candidates.addAll(ar.getExceptions());
                }
            }
        }
        Exception best = null;
        String bestMessage = null;
        for (Exception ex : candidates) {
            String m = deepestMessage(ex);
            if (m == null || m.isEmpty()) continue;
            if (bestMessage == null || m.length() > bestMessage.length()) {
                bestMessage = m;
                best = ex;
            }
        }
        if (best != null) return new Exception(bestMessage, best);
        return e;
    }

    private static String deepestMessage(Throwable t) {
        String message = null;
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) message = cur.getMessage();
            cur = cur.getCause();
        }
        return message;
    }

    private Exception explain(String message, Exception cause) {
        StringBuilder sb = new StringBuilder(message);
        if (cause != null && cause.getMessage() != null) {
            sb.append(": ").append(cause.getMessage());
        }
        if (cfg.offline) {
            sb.append("\n  Offline mode is enabled (--offline / ")
                    .append(RepoConfig.ENV_OFFLINE).append("=1).");
        }
        if (!cfg.hasRemotes()) {
            sb.append("\n  ").append(cfg.reminder().replace("\n", "\n  "));
        } else {
            sb.append("\n  Repositories: ").append(reposDescription());
        }
        return new Exception(sb.toString());
    }

    // ───────────────────────────────────────────────────────── coordinates

    /** Parsed Maven coordinate: {@code group:artifact[:version][:classifier][@jar|aar|pom]}. */
    static final class Coord {
        final String groupId;
        final String artifactId;
        final String version;     // may be null when a BOM manages it
        final String classifier;  // never null, may be empty
        final String extension;   // null when it must be detected

        Coord(String groupId, String artifactId, String version, String classifier, String extension) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.classifier = classifier == null ? "" : classifier;
            this.extension = extension;
        }

        static Coord parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                throw new IllegalArgumentException("Empty Maven coordinate");
            }
            String s = raw.trim();
            String ext = null;
            int at = s.lastIndexOf('@');
            if (at > 0) {
                ext = s.substring(at + 1).trim().toLowerCase(Locale.ROOT);
                s = s.substring(0, at).trim();
                if (!ext.equals(JAR) && !ext.equals(AAR) && !ext.equals(POM)) {
                    throw new IllegalArgumentException("Unsupported artifact extension @" + ext
                            + " in coordinate " + raw + " (use @jar, @aar or @pom)");
                }
            }
            String[] parts = s.split(":", -1);
            if (parts.length < 2 || parts.length > 4) {
                throw new IllegalArgumentException("Bad Maven coordinate: " + raw
                        + " (expected g:a[:v][:classifier][@jar|aar|pom])");
            }
            String g = parts[0].trim();
            String a = parts[1].trim();
            String v = parts.length >= 3 ? emptyToNull(parts[2].trim()) : null;
            String classifier = parts.length == 4 ? parts[3].trim() : "";
            if (g.isEmpty() || a.isEmpty()) {
                throw new IllegalArgumentException("Bad Maven coordinate: " + raw
                        + " (groupId and artifactId are required)");
            }
            return new Coord(g, a, v, classifier, ext);
        }

        Coord withVersion(String v) {
            return new Coord(groupId, artifactId, v, classifier, extension);
        }

        Coord withExtension(String ext) {
            return new Coord(groupId, artifactId, version, classifier, ext);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(groupId).append(':').append(artifactId);
            if (version != null) sb.append(':').append(version);
            if (!classifier.isEmpty()) sb.append(':').append(classifier);
            if (extension != null) sb.append('@').append(extension);
            return sb.toString();
        }

        private static String emptyToNull(String s) {
            return s == null || s.isEmpty() ? null : s;
        }
    }

    // ───────────────────────────────────────────────────────── proxy parsing

    static final class ParsedProxy {
        final String host;
        final int port;
        final String username;
        final String password;

        ParsedProxy(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        static ParsedProxy parse(String spec) {
            try {
                String s = spec.trim();
                if (!s.contains("://")) s = "http://" + s;
                URI uri = new URI(s);
                String host = uri.getHost();
                if (host == null) return null;
                int port = uri.getPort() > 0 ? uri.getPort() : 8080;
                String user = null;
                String pass = null;
                String userInfo = uri.getUserInfo();
                if (userInfo != null) {
                    int colon = userInfo.indexOf(':');
                    if (colon >= 0) {
                        user = userInfo.substring(0, colon);
                        pass = userInfo.substring(colon + 1);
                    } else {
                        user = userInfo;
                    }
                }
                return new ParsedProxy(host, port, user, pass);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ───────────────────────────────────────────────────────── XML helpers

    private static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
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

    private static Element child(Element parent, String name) {
        return firstChild(parent, name);
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

    private static String text(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    private static void putProjectProperties(Map<String, String> props, Coord c) {
        props.put("project.groupId", c.groupId);
        props.put("project.artifactId", c.artifactId);
        if (c.version != null) props.put("project.version", c.version);
        props.put("pom.groupId", c.groupId);
        props.put("pom.artifactId", c.artifactId);
        if (c.version != null) props.put("pom.version", c.version);
    }

    static String interpolate(String s, Map<String, String> props) {
        if (s == null) return null;
        if (s.indexOf("${") < 0) return s;
        String out = s;
        for (int round = 0; round < 10; round++) {
            Matcher m = PROPERTY.matcher(out);
            StringBuffer sb = new StringBuffer();
            boolean replaced = false;
            while (m.find()) {
                String value = props.get(m.group(1).trim());
                if (value == null) return null;
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
                replaced = true;
            }
            m.appendTail(sb);
            out = sb.toString();
            if (!replaced || out.indexOf("${") < 0) break;
        }
        return out;
    }

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private static String token() {
        return Long.toHexString(System.nanoTime());
    }
}
