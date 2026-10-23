package com.j2s;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal parser for Maven's {@code settings.xml}: local repository, mirrors, proxies and servers.
 *
 * <p>Only the parts that matter for artifact download are implemented. Encrypted passwords
 * (values starting with <code>{</code>) are not supported; a warning is printed and the entry is
 * ignored.
 */
public final class MavenSettings {

    public static final class Mirror {
        public final String id;
        public final String url;
        public final String mirrorOf;

        Mirror(String id, String url, String mirrorOf) {
            this.id = id;
            this.url = url;
            this.mirrorOf = mirrorOf;
        }
    }

    public static final class ProxySpec {
        public final String id;
        public final boolean active;
        public final String protocol;
        public final String host;
        public final int port;
        public final String username;
        public final String password;
        public final String nonProxyHosts;

        ProxySpec(String id, boolean active, String protocol, String host, int port,
                  String username, String password, String nonProxyHosts) {
            this.id = id;
            this.active = active;
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.nonProxyHosts = nonProxyHosts;
        }
    }

    public static final class Server {
        public final String id;
        public final String username;
        public final String password;

        Server(String id, String username, String password) {
            this.id = id;
            this.username = username;
            this.password = password;
        }
    }

    public final Path localRepository;
    public final List<Mirror> mirrors;
    public final List<ProxySpec> proxies;
    public final Map<String, Server> servers;

    public static final MavenSettings EMPTY = new MavenSettings(null,
            Collections.<Mirror>emptyList(), Collections.<ProxySpec>emptyList(),
            Collections.<String, Server>emptyMap());

    private MavenSettings(Path localRepository, List<Mirror> mirrors,
                          List<ProxySpec> proxies, Map<String, Server> servers) {
        this.localRepository = localRepository;
        this.mirrors = mirrors;
        this.proxies = proxies;
        this.servers = servers;
    }

    /** Parse a settings file; returns {@link #EMPTY} when absent or unreadable. */
    public static MavenSettings load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return EMPTY;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(file.toFile());
            Element root = doc.getDocumentElement();

            Path localRepo = null;
            String local = text(child(root, "localRepository"));
            if (local != null && !local.isEmpty()) {
                local = interpolateUserHome(local);
                localRepo = java.nio.file.Paths.get(local).toAbsolutePath();
            }

            List<Mirror> mirrors = new ArrayList<>();
            for (Element group : children(root, "mirrors")) {
                for (Element m : children(group, "mirror")) {
                    String id = text(child(m, "id"));
                    String url = text(child(m, "url"));
                    String mirrorOf = text(child(m, "mirrorOf"));
                    if (id == null || url == null || mirrorOf == null) continue;
                    mirrors.add(new Mirror(id.trim(), stripTrailingSlash(url.trim()), mirrorOf.trim()));
                }
            }

            List<ProxySpec> proxies = new ArrayList<>();
            for (Element group : children(root, "proxies")) {
                for (Element p : children(group, "proxy")) {
                    String id = text(child(p, "id"));
                    String active = text(child(p, "active"));
                    String protocol = text(child(p, "protocol"));
                    String host = text(child(p, "host"));
                    String port = text(child(p, "port"));
                    if (host == null || protocol == null) continue;
                    int portNum = 8080;
                    try {
                        if (port != null) portNum = Integer.parseInt(port.trim());
                    } catch (NumberFormatException ignored) {
                    }
                    String username = text(child(p, "username"));
                    String password = text(child(p, "password"));
                    if (isEncrypted(password)) {
                        warnEncrypted("proxy", id);
                        password = null;
                    }
                    proxies.add(new ProxySpec(id == null ? protocol : id.trim(),
                            active == null || active.trim().isEmpty() || Boolean.parseBoolean(active.trim()),
                            protocol.trim(), host.trim(), portNum, username, password,
                            text(child(p, "nonProxyHosts"))));
                }
            }

            Map<String, Server> servers = new LinkedHashMap<>();
            for (Element group : children(root, "servers")) {
                for (Element s : children(group, "server")) {
                    String id = text(child(s, "id"));
                    if (id == null) continue;
                    String username = text(child(s, "username"));
                    String password = text(child(s, "password"));
                    if (isEncrypted(password)) {
                        warnEncrypted("server", id);
                        password = null;
                    }
                    servers.put(id.trim(), new Server(id.trim(), username, password));
                }
            }

            return new MavenSettings(localRepo,
                    Collections.unmodifiableList(mirrors),
                    Collections.unmodifiableList(proxies),
                    Collections.unmodifiableMap(servers));
        } catch (Exception e) {
            System.err.println("Warning: cannot parse Maven settings " + file + " (" + e + "); ignoring it");
            return EMPTY;
        }
    }

    /** First mirror matching the given repository, or null. */
    public Mirror mirrorFor(String repoId, String repoUrl) {
        for (Mirror m : mirrors) {
            if (mirrorMatches(m.mirrorOf, repoId, repoUrl)) return m;
        }
        return null;
    }

    /** Credentials for a repository / mirror id, or null. */
    public Server server(String id) {
        return id == null ? null : servers.get(id);
    }

    /** Active proxies (all protocols); the resolver installs them into the Aether session. */
    public List<ProxySpec> activeProxies() {
        List<ProxySpec> out = new ArrayList<>();
        for (ProxySpec p : proxies) {
            if (p.active) out.add(p);
        }
        return out;
    }

    /** Maven-style mirrorOf matching: comma separated patterns, {@code *}, {@code !exclusion}. */
    static boolean mirrorMatches(String mirrorOf, String repoId, String repoUrl) {
        if (mirrorOf == null) return false;
        boolean matched = false;
        for (String raw : mirrorOf.split(",")) {
            String pat = raw.trim();
            if (pat.isEmpty()) continue;
            boolean negative = pat.startsWith("!");
            if (negative) pat = pat.substring(1);
            if (patternMatches(pat, repoId, repoUrl)) {
                if (negative) return false;
                matched = true;
            }
        }
        return matched;
    }

    private static boolean patternMatches(String pat, String repoId, String repoUrl) {
        if (pat.equals("*")) return true;
        if (pat.equals("external:*") || pat.startsWith("external:")) {
            return !isLocal(repoUrl);
        }
        if (pat.indexOf('*') >= 0) {
            String regex = Pattern.quote(pat).replace("*", "\\E.*\\Q");
            return (repoId != null && repoId.matches(regex)) || (repoUrl != null && repoUrl.matches(regex));
        }
        return pat.equals(repoId);
    }

    static boolean isLocal(String url) {
        if (url == null) return true;
        String u = url.toLowerCase();
        return u.startsWith("file:") || u.contains("//localhost") || u.contains("//127.0.0.1")
                || u.contains("//[::1]");
    }

    static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String interpolateUserHome(String path) {
        String home = System.getProperty("user.home");
        return path.replace("${user.home}", home).replace("~", home);
    }

    private static boolean isEncrypted(String s) {
        return s != null && s.trim().startsWith("{");
    }

    private static void warnEncrypted(String kind, String id) {
        System.err.println("Warning: encrypted password in settings.xml (" + kind + " "
                + id + ") is not supported; ignoring credentials");
    }

    private static Element child(Element parent, String name) {
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

    private static String text(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }
}
