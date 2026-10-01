package com.infradesk.alert;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 알림을 디스코드 웹훅에 embed로 보낸다. URL은 전송할 때마다 {@code webhookUrl}에서 읽으므로
 * (설정에서 바꾸면 바로 적용된다) 로그나 오류 메시지에 절대 넣지 않는다.
 */
public class DiscordNotifier implements Notifier {

    private static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_\\-]+/?$");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(10);

    private final Supplier<String> webhookUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiscordNotifier(Supplier<String> webhookUrl, HttpClient http) {
        this.webhookUrl = webhookUrl;
        this.http = http;
    }

    public DiscordNotifier(Supplier<String> webhookUrl) {
        this(webhookUrl, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** 텍스트가 디스코드 웹훅 URL처럼 생겼는지. */
    public static boolean isWebhookUrl(String url) {
        return url != null && WEBHOOK.matcher(url.strip()).matches();
    }

    @Override
    public void send(Alert alert) {
        String url = webhookUrl.get();
        if (url == null || url.isBlank()) {
            throw new AlertException("디스코드 웹훅 URL이 설정되지 않았어요");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.strip()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(alert)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                Duration wait = retryAfter(response);
                if (wait.compareTo(MAX_RETRY_WAIT) <= 0) {
                    Thread.sleep(wait);
                    response = http.send(request, HttpResponse.BodyHandlers.ofString());
                }
            }
            int code = response.statusCode();
            if (code / 100 != 2) {
                throw new AlertException(switch (code) {
                    case 401, 403, 404 -> "디스코드 웹훅이 유효하지 않아요 (삭제됐거나 URL이 틀렸어요)";
                    case 429 -> "디스코드 전송 한도를 넘었어요. 잠시 뒤 다시 보내요";
                    default -> "디스코드 전송 실패 (HTTP " + code + ")";
                });
            }
        } catch (IOException e) {
            throw new AlertException("디스코드에 연결하지 못했어요: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AlertException("전송이 중단됐어요", e);
        }
    }

    /** 알림의 embed JSON. 테스트를 위해 package-private. */
    String payload(Alert alert) {
        int color = switch (alert.level()) {
            case PROBLEM -> 0xF08A8A;
            case RECOVERED -> 0x5FB865;
            case INFO -> 0x3B73E0;
        };
        Map<String, Object> embed = Map.of(
                "title", alert.title(),
                "description", alert.description(),
                "color", color,
                "timestamp", alert.time().toString());
        try {
            return mapper.writeValueAsString(Map.of("username", "InfraDesk", "embeds", List.of(embed)));
        } catch (IOException e) {
            throw new AlertException("알림 내용을 만들지 못했어요", e);
        }
    }

    private Duration retryAfter(HttpResponse<String> response) {
        try {
            Map<?, ?> body = mapper.readValue(response.body(), Map.class);
            Object seconds = body.get("retry_after");
            if (seconds instanceof Number n) {
                return Duration.ofMillis((long) (n.doubleValue() * 1000));
            }
        } catch (IOException | RuntimeException ignored) {
            // 헤더 / 기본값으로 넘어간다.
        }
        return response.headers().firstValue("Retry-After")
                .map(v -> Duration.ofMillis((long) (Double.parseDouble(v) * 1000)))
                .orElse(Duration.ofSeconds(1));
    }
}
