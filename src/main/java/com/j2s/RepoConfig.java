package com.j2s;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Maven repository configuration, merged from CLI options and environment variables.
 *
 * <p>Precedence: CLI &gt; environment &gt; ~/.m2/settings.xml &gt; built-in defaults.
 *
 * <p><b>Remote repositories are empty by default.</b> J2S never assumes that a machine can reach
 * Maven Central or Google Maven; the user must configure at least one repository via
 * {@code --repo <url>} or the {@code J2S_REPOS} environment variable. If dependencies are
 * requested without any configured repository, J2S prints a reminder and resolves against the
 * local repository only.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code J2S_REPOS} - comma / whitespace separated list of repository URLs</li>
 *   <li>{@code J2S_LOCAL_REPO} (or {@code J2S_M2}) - local repository directory</li>
 *   <li>{@code J2S_OFFLINE=1} - offline mode, never touch the network</li>
 *   <li>{@code J2S_MAVEN_SETTINGS} - explicit settings.xml path</li>
 *   <li>{@code J2S_PROXY} / {@code J2S_NO_PROXY} - HTTP proxy for downloads</li>
 * </ul>
 */
public final class RepoConfig {

    public static final String ENV_REPOS = "J2S_REPOS";
    public static final String ENV_LOCAL_REPO = "J2S_LOCAL_REPO";
    public static final String ENV_M2 = "J2S_M2";
    public static final String ENV_OFFLINE = "J2S_OFFLINE";
    public static final String ENV_SETTINGS = "J2S_MAVEN_SETTINGS";
    public static final String ENV_PROXY = "J2S_PROXY";
    public static final String ENV_NO_PROXY = "J2S_NO_PROXY";

    /** Local repository (~/.m2/repository by default). */
    public final Path localRepo;
    /** Remote repositories, in resolution order, de-duplicated. */
    public final List<String> repositories;
    /** Never access the network (resolve from the local repository only). */
    public final boolean offline;
    /** Resolve transitive dependencies (default: true). */
    public final boolean transitive;
    /** settings.xml actually used, or null. */
    public final Path settingsFile;
    /** Parsed settings.xml (never null, empty when absent/unreadable). */
    public final MavenSettings settings;
    /** User exclusions ({@code g:a}), applied to every requested root. */
    public final List<String> excludes;
    /** Raw proxy spec from J2S_PROXY, or null. */
    public final String proxySpec;
    /** Raw non-proxy hosts from J2S_NO_PROXY, or null. */
    public final String nonProxyHosts;

    private RepoConfig(Path localRepo, List<String> repositories, boolean offline,
                       boolean transitive, Path settingsFile, MavenSettings settings,
                       List<String> excludes, String proxySpec, String nonProxyHosts) {
        this.localRepo = localRepo;
        this.repositories = Collections.unmodifiableList(repositories);
        this.offline = offline;
        this.transitive = transitive;
        this.settingsFile = settingsFile;
        this.settings = settings;
        this.excludes = Collections.unmodifiableList(excludes);
        this.proxySpec = proxySpec;
        this.nonProxyHosts = nonProxyHosts;
    }

    /** Create a configuration from the process environment. */
    public static RepoConfig create(Path cliLocalRepo, List<String> cliRepos, boolean cliOffline,
                                    boolean cliTransitive, List<String> cliExcludes,
                                    Path cliSettings) {
        return from(System.getenv(), cliLocalRepo, cliRepos, cliOffline, cliTransitive,
                cliExcludes, cliSettings);
    }

    /** Testable variant that takes the environment as a map. */
    static RepoConfig from(Map<String, String> env, Path cliLocalRepo, List<String> cliRepos,
                           boolean cliOffline, boolean cliTransitive, List<String> cliExcludes,
                           Path cliSettings) {
        Path settingsFile = cliSettings;
        if (settingsFile == null && isNotEmpty(env.get(ENV_SETTINGS))) {
            settingsFile = Paths.get(env.get(ENV_SETTINGS).trim());
        }
        if (settingsFile == null) {
            Path def = Paths.get(System.getProperty("user.home"), ".m2", "settings.xml");
            if (Files.isRegularFile(def)) settingsFile = def;
        }
        MavenSettings settings = MavenSettings.load(settingsFile);

        Path localRepo = cliLocalRepo;
        if (localRepo == null && settings.localRepository != null) {
            localRepo = settings.localRepository;
        }
        if (localRepo == null && isNotEmpty(env.get(ENV_LOCAL_REPO))) {
            localRepo = Paths.get(env.get(ENV_LOCAL_REPO).trim());
        }
        if (localRepo == null && isNotEmpty(env.get(ENV_M2))) {
            localRepo = Paths.get(env.get(ENV_M2).trim());
        }
        if (localRepo == null) {
            localRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        }

        List<String> repos = new ArrayList<>();
        if (cliRepos != null) repos.addAll(cliRepos);
        repos.addAll(splitList(env.get(ENV_REPOS)));
        repos = normalizeRepositories(repos);

        List<String> excludes = (cliExcludes == null)
                ? Collections.<String>emptyList() : new ArrayList<>(cliExcludes);

        boolean offline = cliOffline || truthy(env.get(ENV_OFFLINE));

        return new RepoConfig(localRepo.toAbsolutePath(), repos, offline, cliTransitive,
                settingsFile, settings, excludes,
                trimToNull(env.get(ENV_PROXY)), trimToNull(env.get(ENV_NO_PROXY)));
    }

    /** True when at least one remote repository was configured. */
    public boolean hasRemotes() {
        return !repositories.isEmpty();
    }

    /** Reminder printed when dependencies are requested without any remote repository. */
    public String reminder() {
        return "No Maven repository configured.\n"
                + "  Pass --repo <url> or set " + ENV_REPOS
                + " (comma separated), e.g.\n"
                + "    export " + ENV_REPOS + "=https://repo.maven.apache.org/maven2\n"
                + "  Only the local repository " + localRepo + " will be used.";
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("  local repository: ").append(localRepo).append('\n');
        sb.append("  offline: ").append(offline).append('\n');
        sb.append("  transitive: ").append(transitive).append('\n');
        if (settingsFile != null) sb.append("  settings: ").append(settingsFile).append('\n');
        if (repositories.isEmpty()) {
            sb.append("  repositories: (none configured)\n");
        } else {
            sb.append("  repositories:\n");
            for (String r : repositories) sb.append("    ").append(r).append('\n');
        }
        if (!excludes.isEmpty()) sb.append("  excludes: ").append(excludes).append('\n');
        return sb.toString();
    }

    /** Derive a stable Aether repository id from a URL (used for mirror matching). */
    static String repositoryId(String url) {
        try {
            URIish u = URIish.parse(url);
            if (u.host != null) return u.host;
        } catch (Exception ignored) {
        }
        return "repo-" + Integer.toHexString(url.hashCode());
    }

    /** Minimal URL splitter (avoids java.net.URI failures on malformed input). */
    private static final class URIish {
        String host;
        static URIish parse(String url) {
            URIish r = new URIish();
            String s = url;
            int scheme = s.indexOf("://");
            if (scheme >= 0) s = s.substring(scheme + 3);
            int slash = s.indexOf('/');
            if (slash >= 0) s = s.substring(0, slash);
            int at = s.lastIndexOf('@');
            if (at >= 0) s = s.substring(at + 1);
            int colon = s.lastIndexOf(':');
            if (colon >= 0 && colon > 0) s = s.substring(0, colon);
            r.host = s.isEmpty() ? null : s;
            return r;
        }
    }

    static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,\\s]+")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static List<String> normalizeRepositories(List<String> input) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String r : input) {
            if (r == null) continue;
            String s = r.trim();
            if (s.isEmpty()) continue;
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (!s.isEmpty()) seen.add(s);
        }
        return new ArrayList<>(seen);
    }

    static boolean truthy(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static boolean isNotEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
