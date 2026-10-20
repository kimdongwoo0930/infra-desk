package com.infradesk.alert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 디스코드 대신 로컬 HTTP 서버로 전송한다. */
class DiscordNotifierTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile List<Integer> statuses = List.of(204);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/webhooks/1/tok", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = calls.getAndIncrement();
            int status = statuses.get(Math.min(n, statuses.size() - 1));
            byte[] body = status == 429 ? "{\"retry_after\": 0.05}".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DiscordNotifier notifier() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/tok";
        return new DiscordNotifier(() -> url, HttpClient.newHttpClient());
    }

    private static Alert alert() {
        return new Alert(Alert.Level.PROBLEM, "🔴 서버가 멈췄어요: bot", "계정 A", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void postsEmbed() {
        notifier().send(alert());
        assertEquals(1, bodies.size());
        String body = bodies.getFirst();
        assertTrue(body.contains("\"embeds\""), body);
        assertTrue(body.contains("서버가 멈췄어요: bot"), body);
        assertTrue(body.contains("\"color\":15764106"), body);
    }

    @Test
    void retriesOnceAfterRateLimit() {
        statuses = List.of(429, 204);
        notifier().send(alert());
        assertEquals(2, calls.get());
    }

    @Test
    void invalidWebhookGivesFriendlyErrorWithoutTheUrl() {
        statuses = List.of(404);
        AlertException e = assertThrows(AlertException.class, () -> notifier().send(alert()));
        assertTrue(e.getMessage().contains("유효하지 않아요"));
        assertFalse(e.getMessage().contains("tok"));
    }

    @Test
    void missingUrlFails() {
        assertThrows(AlertException.class, () -> new DiscordNotifier(() -> null).send(alert()));
    }

    @Test
    void validatesWebhookUrls() {
        assertTrue(DiscordNotifier.isWebhookUrl("https://discord.com/api/webhooks/123/abc-DEF_9"));
        assertTrue(DiscordNotifier.isWebhookUrl("https://canary.discord.com/api/webhooks/123/abc"));
        assertTrue(DiscordNotifier.isWebhookUrl("https://discordapp.com/api/webhooks/123/abc"));
        assertFalse(DiscordNotifier.isWebhookUrl("http://discord.com/api/webhooks/123/abc"));
        assertFalse(DiscordNotifier.isWebhookUrl("https://evil.example/api/webhooks/123/abc"));
        assertFalse(DiscordNotifier.isWebhookUrl("https://discord.com.evil.example/api/webhooks/1/a"));
    }
}
