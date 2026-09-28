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
            {"name": "InfraDesk 1.0.0 Beta (빌드 7)",
             "html_url": "https://github.com/o/r/releases/tag/beta",
             "body": "> 빌드 #7 · 커밋 [`abc1234`](https://github.com/o/r/commit/abc1234)",
             "assets": [
               {"name": "InfraDesk-beta-macOS.dmg", "browser_download_url": "https://dl/mac.dmg"},
               {"name": "InfraDesk-beta-windows.zip", "browser_download_url": "https://dl/win.zip"}]}
            """;

    @Test
    void parsesBuildCommitAndAssetForThisOs() throws Exception {
        var json = new ObjectMapper().readTree(RELEASE);
        UpdateService.Release mac = UpdateService.parse(json, "InfraDesk-beta-macOS.dmg");
        assertEquals(7, mac.build());
        assertEquals("abc1234", mac.commit());
        assertEquals("https://dl/mac.dmg", mac.downloadUrl());
        assertEquals("https://dl/win.zip", UpdateService.parse(json, "InfraDesk-beta-windows.zip").downloadUrl());
    }

    @Test
    void fetchesFromTheApiAndReportsMissingRelease() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/beta", ex -> {
            byte[] body = RELEASE.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            UpdateService svc = new UpdateService(HttpClient.newHttpClient(), URI.create(base + "/beta"),
                    "InfraDesk-beta-macOS.dmg", null);
            assertEquals(7, svc.latest().build());
            UpdateService none = new UpdateService(HttpClient.newHttpClient(), URI.create(base + "/missing"),
                    "InfraDesk-beta-macOS.dmg", null);
            IllegalStateException e = assertThrows(IllegalStateException.class, none::latest);
            assertTrue(e.getMessage().contains("베타가 없어요"));
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
