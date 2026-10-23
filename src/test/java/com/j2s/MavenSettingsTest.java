package com.j2s;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MavenSettingsTest {

    @TempDir
    Path tmp;

    private MavenSettings load(String xml) throws Exception {
        Path file = tmp.resolve("settings.xml");
        Files.write(file, xml.getBytes(StandardCharsets.UTF_8));
        return MavenSettings.load(file);
    }

    @Test
    void parsesLocalRepositoryMirrorProxyAndServer() throws Exception {
        MavenSettings s = load("<settings>"
                + "<localRepository>" + tmp.resolve("m2") + "</localRepository>"
                + "<mirrors><mirror><id>aliyun</id>"
                + "<url>https://maven.aliyun.com/repository/public/</url>"
                + "<mirrorOf>*</mirrorOf></mirror></mirrors>"
                + "<proxies><proxy><id>corp</id><active>true</active>"
                + "<protocol>http</protocol><host>proxy.local</host><port>3128</port>"
                + "<username>u</username><password>secret</password>"
                + "<nonProxyHosts>localhost|*.internal</nonProxyHosts></proxy></proxies>"
                + "<servers><server><id>aliyun</id><username>su</username>"
                + "<password>sp</password></server></servers>"
                + "</settings>");

        assertEquals(tmp.resolve("m2"), s.localRepository);

        MavenSettings.Mirror mirror = s.mirrorFor("central",
                "https://repo.maven.apache.org/maven2");
        assertNotNull(mirror);
        assertEquals("https://maven.aliyun.com/repository/public", mirror.url);

        assertEquals(1, s.activeProxies().size());
        MavenSettings.ProxySpec proxy = s.activeProxies().get(0);
        assertEquals("proxy.local", proxy.host);
        assertEquals(3128, proxy.port);
        assertEquals("u", proxy.username);
        assertEquals("localhost|*.internal", proxy.nonProxyHosts);

        assertEquals("su", s.server("aliyun").username);
        assertEquals("sp", s.server("aliyun").password);
    }

    @Test
    void mirrorExclusionAndExternalPatternsWork() {
        assertTrue(MavenSettings.mirrorMatches("*", "central", "https://repo.maven.apache.org/maven2"));
        assertFalse(MavenSettings.mirrorMatches("*,!central", "central", "https://x"));
        assertTrue(MavenSettings.mirrorMatches("external:*", "central", "https://x"));
        assertFalse(MavenSettings.mirrorMatches("external:*", "local", "file:///tmp/repo"));
        assertTrue(MavenSettings.mirrorMatches("repo*", "repo-1", "https://x"));
    }

    @Test
    void missingFileYieldsEmptySettings() {
        MavenSettings s = MavenSettings.load(tmp.resolve("absent.xml"));
        assertSame(MavenSettings.EMPTY, s);
    }
}
