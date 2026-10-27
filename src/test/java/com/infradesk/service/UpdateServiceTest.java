package com.infradesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    @TempDir
    Path dir;

    private static final String RELEASE = """
            {"name": "InfraDesk 1.0.0", "tag_name": "v1.0.0", "prerelease": false, "draft": false,
             "html_url": "https://github.com/o/r/releases/tag/v1.0.0",
             "body": "> 빌드 #31 · 커밋 [`def5678`](https://github.com/o/r/commit/def5678)",
             "assets": [
               {"name": "InfraDesk-macOS.dmg", "browser_download_url": "https://dl/mac.dmg"},
               {"name": "InfraDesk-macOS.zip", "browser_download_url": "https://dl/mac.zip"},
               {"name": "InfraDesk-windows.zip", "browser_download_url": "https://dl/win.zip"},
               {"name": "SHA256SUMS.txt", "browser_download_url": "https://dl/sums"}]}
            """;

    @Test
    void parsesVersionCommitAndAssetForThisOs() throws Exception {
        var json = new ObjectMapper().readTree(RELEASE);
        UpdateService.Release mac = UpdateService.parse(json, "InfraDesk-macOS.dmg", "InfraDesk-macOS.zip");
        assertEquals("1.0.0", mac.version(), "버전은 태그에서 읽는다");
        assertEquals("InfraDesk 1.0.0", mac.label(), "이름에 Beta가 붙지 않는다");
        assertEquals(31, mac.build());
        assertEquals("def5678", mac.commit());
        assertEquals("https://dl/mac.dmg", mac.downloadUrl());
        assertEquals("https://dl/mac.zip", mac.updateUrl());
        assertEquals("https://dl/sums", mac.checksumsUrl());
        UpdateService.Release win = UpdateService.parse(json, "InfraDesk-windows.zip", "InfraDesk-windows.zip");
        assertEquals("https://dl/win.zip", win.downloadUrl());
        assertEquals("https://dl/win.zip", win.updateUrl());
    }

    @Test
    void aReleaseWithoutUpdateAssetsCannotSelfInstall() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"name": "InfraDesk 1.0.0", "tag_name": "v1.0.0", "html_url": "https://p", "body": "",
                 "assets": [{"name": "InfraDesk-macOS.dmg", "browser_download_url": "https://dl/mac.dmg"}]}
                """);
        UpdateService.Release r = UpdateService.parse(json, "InfraDesk-macOS.dmg", "InfraDesk-macOS.zip");
        assertEquals(null, r.updateUrl());
        assertEquals(null, r.checksumsUrl());
        assertEquals(0, r.build(), "본문에 빌드 번호가 없으면 0");
    }

    @Test
    void thingsThatAreNotReleasesAreRejected() throws Exception {
        var mapper = new ObjectMapper();
        for (String bad : new String[] {
                RELEASE.replace("\"prerelease\": false", "\"prerelease\": true"),
                RELEASE.replace("\"draft\": false", "\"draft\": true"),
                RELEASE.replace("v1.0.0", "beta"),
                RELEASE.replace("v1.0.0", "beta-12")}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> UpdateService.parse(mapper.readTree(bad), "a", "b"));
            assertTrue(e.getMessage().contains("릴리스가 없어요"), "옛 베타 릴리스나 프리릴리스는 정식 릴리스가 아니다");
        }
    }

    @Test
    void versionsAreComparedByNumberNotByText() {
        assertTrue(UpdateService.isNewer("1.0.1", "1.0.0"));
        assertTrue(UpdateService.isNewer("1.0.10", "1.0.9"), "10은 9보다 크다");
        assertTrue(UpdateService.isNewer("1.1.0", "1.0.9"));
        assertTrue(UpdateService.isNewer("2.0.0", "1.9.9"));
        assertFalse(UpdateService.isNewer("1.0.0", "1.0.0"), "같으면 새 버전이 아니다");
        assertFalse(UpdateService.isNewer("1.0.0", "1.0.1"), "낮은 버전으로는 알리지 않는다");
        assertFalse(UpdateService.isNewer("", "1.0.0"), "모르는 버전은 비교하지 않는다");
        assertFalse(UpdateService.isNewer("1.0.0", "dev"));
        assertFalse(UpdateService.isNewer(null, "1.0.0"));
    }

    @Test
    void labelFallsBackWhenTheVersionIsUnknown() {
        assertEquals("새 버전", new UpdateService.Release(1, "", "p", "d", null, null).label());
    }

    @Test
    void fetchesFromTheApiAndReportsMissingRelease() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", ex -> {
            byte[] body = RELEASE.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/broken", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            UpdateService svc = new UpdateService(HttpClient.newHttpClient(), URI.create(base + "/latest"),
                    "InfraDesk-macOS.dmg", "InfraDesk-macOS.zip", null);
            assertEquals("1.0.0", svc.latest().version());
            UpdateService none = new UpdateService(HttpClient.newHttpClient(), URI.create(base + "/missing"),
                    "InfraDesk-macOS.dmg", null);
            IllegalStateException e = assertThrows(IllegalStateException.class, none::latest);
            assertTrue(e.getMessage().contains("릴리스가 없어요"), "릴리스가 아직 없으면(404) 그렇게 알린다");
            UpdateService broken = new UpdateService(HttpClient.newHttpClient(), URI.create(base + "/broken"),
                    "InfraDesk-macOS.dmg", null);
            assertTrue(assertThrows(IllegalStateException.class, broken::latest).getMessage().contains("HTTP 500"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void autoCheckDefaultsOnAndPersists() {
        Path file = dir.resolve("app-settings.json");
        UpdateService svc = new UpdateService(HttpClient.newHttpClient(), URI.create("http://x"), "a", file);
        assertTrue(svc.autoCheck());
        svc.setAutoCheck(false);
        assertFalse(new UpdateService(HttpClient.newHttpClient(), URI.create("http://x"), "a", file).autoCheck());
    }
}
