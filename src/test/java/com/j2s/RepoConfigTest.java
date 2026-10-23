package com.j2s;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepoConfigTest {

    @TempDir
    Path tmp;

    @Test
    void repositoriesAreEmptyByDefault() {
        RepoConfig cfg = RepoConfig.from(Collections.<String, String>emptyMap(),
                tmp.resolve("m2"), Collections.<String>emptyList(), false, true,
                Collections.<String>emptyList(), null);
        assertTrue(cfg.repositories.isEmpty());
        assertFalse(cfg.hasRemotes());
        assertTrue(cfg.reminder().contains("No Maven repository configured"));
    }

    @Test
    void envRepositoriesAreMergedAndDeduplicated() {
        Map<String, String> env = new HashMap<>();
        env.put(RepoConfig.ENV_REPOS,
                "https://a.example/repo/, https://b.example/repo\nhttps://a.example/repo");
        RepoConfig cfg = RepoConfig.from(env, tmp.resolve("m2"),
                Collections.singletonList("https://a.example/repo"), false, true,
                Collections.<String>emptyList(), null);
        assertEquals(Arrays.asList("https://a.example/repo", "https://b.example/repo"),
                cfg.repositories);
    }

    @Test
    void cliLocalRepositoryWinsOverEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put(RepoConfig.ENV_LOCAL_REPO, "/env/repo");
        RepoConfig cfg = RepoConfig.from(env, tmp.resolve("cli-m2"),
                Collections.<String>emptyList(), false, true,
                Collections.<String>emptyList(), null);
        assertEquals(tmp.resolve("cli-m2").toAbsolutePath(), cfg.localRepo);
    }

    @Test
    void localRepositoryCanComeFromEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put(RepoConfig.ENV_M2, "/env/repo");
        RepoConfig cfg = RepoConfig.from(env, null, Collections.<String>emptyList(),
                false, true, Collections.<String>emptyList(), null);
        assertEquals(java.nio.file.Paths.get("/env/repo").toAbsolutePath(), cfg.localRepo);
    }

    @Test
    void settingsLocalRepositoryIsUsedWhenNothingElseIsGiven() throws Exception {
        Path settings = tmp.resolve("settings.xml");
        Files.write(settings, ("<settings><localRepository>" + tmp.resolve("from-settings")
                + "</localRepository></settings>").getBytes(StandardCharsets.UTF_8));
        RepoConfig cfg = RepoConfig.from(Collections.<String, String>emptyMap(), null,
                Collections.<String>emptyList(), false, true,
                Collections.<String>emptyList(), settings);
        assertEquals(tmp.resolve("from-settings"), cfg.localRepo);
    }

    @Test
    void offlineAndExcludesAreReadFromEnvironmentAndCli() {
        Map<String, String> env = new HashMap<>();
        env.put(RepoConfig.ENV_OFFLINE, "1");
        List<String> excludes = Collections.singletonList("g:excluded");
        RepoConfig cfg = RepoConfig.from(env, tmp.resolve("m2"),
                Collections.<String>emptyList(), false, true, excludes, null);
        assertTrue(cfg.offline);
        assertEquals(excludes, cfg.excludes);
    }

    @Test
    void proxyIsReadFromEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put(RepoConfig.ENV_PROXY, "http://user:pass@proxy.local:3128");
        RepoConfig cfg = RepoConfig.from(env, tmp.resolve("m2"),
                Collections.<String>emptyList(), false, true,
                Collections.<String>emptyList(), null);
        assertEquals("http://user:pass@proxy.local:3128", cfg.proxySpec);
    }
}
