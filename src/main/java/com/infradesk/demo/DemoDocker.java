package com.infradesk.demo;

import com.infradesk.ssh.DockerCommands;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 데모 모드용 가짜 Docker: 호스트별 컨테이너가 있고, 시작/정지/재시작에 따라 상태가 바뀐다. */
final class DemoDocker {

    private record Box(String id, String name, String image, String ports, boolean running, String since) {
    }

    private static final Map<String, Map<String, Box>> HOSTS = new ConcurrentHashMap<>();
    private static final Pattern ACTION = Pattern.compile("\\$D (start|stop|restart) ([0-9a-f]{12,64})$");
    private static final Pattern LOGS = Pattern.compile("\\$D logs --tail (\\d+) --timestamps ([0-9a-f]{12,64})");

    private DemoDocker() {
    }

    // ── 데모 로그 ────────────────────────────────────────────────
    // 컨테이너마다 정보·경고·오류가 섞인 줄들. 수준은 줄 안의 글자(warn, [error], HTTP 404/502)로
    // 화면(LogLine)이 짐작하므로, 여기서는 수준을 따로 적지 않고 실제 로그처럼만 쓴다.

    private static final String[] NGINX_LOG = {
            "\"GET /health HTTP/1.1\" 200 2 \"-\" \"uptime-check\"",
            "\"POST /api/webhook HTTP/1.1\" 200 18 \"-\" \"Discord-Webhook\"",
            "\"GET /favicon.ico HTTP/1.1\" 404 153 \"-\" \"Mozilla/5.0\"",
            "\"GET /api/rank?guild=8812 HTTP/1.1\" 200 911 \"-\" \"DiscordBot\"",
            "[error] 29#29: *104 upstream prematurely closed connection while reading response header from upstream",
            "\"POST /api/webhook HTTP/1.1\" 502 157 \"-\" \"Discord-Webhook\"",
            "\"POST /api/webhook HTTP/1.1\" 200 18 \"-\" \"Discord-Webhook\"",
            "\"GET /health HTTP/1.1\" 200 2 \"-\" \"uptime-check\"",
            "\"GET /wp-login.php HTTP/1.1\" 404 153 \"-\" \"python-requests\"",
            "\"GET /api/rank?guild=1204 HTTP/1.1\" 200 870 \"-\" \"DiscordBot\"",
            "\"GET /api/rank?guild=8812 HTTP/1.1\" 200 905 \"-\" \"DiscordBot\"",
            "\"GET /health HTTP/1.1\" 200 2 \"-\" \"uptime-check\""};
    private static final String[] REDIS_LOG = {
            "1:M * 100 changes in 300 seconds. Saving...",
            "1:M * Background saving started by pid 21",
            "21:C * DB saved on disk",
            "1:M * Background saving terminated with success",
            "1:M # WARNING overcommit_memory is set to 0! Background save may fail under low memory condition",
            "1:M * Ready to accept connections"};
    private static final String[] MYSQL_LOG = {
            "[Note] [MY-010931] [Server] /usr/sbin/mysqld: ready for connections. Version: '8.4.0'",
            "[Note] [MY-013172] [Server] Received SHUTDOWN from user root.",
            "[Warning] [MY-010055] [Server] IP address '10.0.0.9' could not be resolved: Name or service not known",
            "[Note] [MY-011953] [Server] Event Scheduler: Loaded 0 events",
            "[Note] [MY-010931] [Server] /usr/sbin/mysqld: ready for connections. Version: '8.4.0'"};
    private static final String[] WATCHTOWER_LOG = {
            "time=\"2026-09-25T03:00:00Z\" level=info msg=\"Checking for updates\"",
            "time=\"2026-09-25T03:00:04Z\" level=info msg=\"Found new image for discord-bot\"",
            "time=\"2026-09-25T03:00:09Z\" level=error msg=\"Could not pull image: toomanyrequests\"",
            "time=\"2026-09-25T03:00:09Z\" level=info msg=\"Session done\""};
    private static final String[] APP_LOG = {
            "INFO  gateway connected shard=0",
            "INFO  heartbeat ok · 14 users online",
            "INFO  command /rank handled in 38ms",
            "WARN  rate limited by discord, retry in 1.2s",
            "INFO  heartbeat ok · 16 users online",
            "INFO  command /help handled in 12ms",
            "ERROR failed to send message: 503 Service Unavailable",
            "INFO  retry succeeded after 1 attempt",
            "INFO  heartbeat ok · 15 users online"};

    /**
     * {@code docker logs --timestamps}와 같은 모양의 로그. 실행 중인 컨테이너는 지금까지 몇 초 간격으로
     * 쌓인 것처럼, 멈춘 컨테이너는 3일 전에 끝난 것처럼 시각을 붙인다.
     */
    static String logs(String name, boolean running) {
        String[] lines = switch (name) {
            case "nginx" -> NGINX_LOG;
            case "redis" -> REDIS_LOG;
            case "mysql" -> MYSQL_LOG;
            case "watchtower" -> WATCHTOWER_LOG;
            default -> APP_LOG;
        };
        Instant end = (running ? Instant.now() : Instant.now().minus(Duration.ofDays(3))).truncatedTo(ChronoUnit.SECONDS);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            Instant at = end.minusSeconds(4L * (lines.length - 1 - i));
            out.append(at).append(' ').append(lines[i]).append('\n');
        }
        return out.toString();
    }

    /** 컨테이너 이름으로 정해지는 가짜 네트워크 양("받은 / 보낸"). 같은 이름은 항상 같은 값이라 화면이 흔들리지 않는다. */
    private static String netIo(String name) {
        double received = 0.2 + Math.floorMod(name.hashCode(), 900) / 10.0;
        double sent = 0.1 + Math.floorMod(name.hashCode() * 31, 600) / 10.0;
        return String.format(java.util.Locale.ROOT, "%.1fMB / %.1fMB", received, sent);
    }

    /** {@link DockerCommands} 명령의 출력. 실제 셸이 출력하는 것과 같은 형태. */
    static String run(String host, String command) {
        Map<String, Box> boxes = HOSTS.computeIfAbsent(host, DemoDocker::seed);
        if (command.contains(DockerCommands.PS)) {
            StringBuilder ps = new StringBuilder(DockerCommands.PS + "\n");
            StringBuilder stats = new StringBuilder(DockerCommands.STATS + "\n");
            int i = 0;
            for (Box b : boxes.values()) {
                ps.append("{\"ID\":\"").append(b.id).append("\",\"Names\":\"").append(b.name)
                        .append("\",\"Image\":\"").append(b.image).append("\",\"State\":\"")
                        .append(b.running ? "running" : "exited").append("\",\"Status\":\"")
                        .append(b.running ? "Up " + b.since : "Exited (0) " + b.since + " ago")
                        .append("\",\"Ports\":\"").append(b.ports).append("\"}\n");
                if (b.running) {
                    double cpu = Math.floorMod((host + b.name).hashCode(), 400) / 10.0;
                    int mem = 40 + Math.floorMod(b.name.hashCode(), 400);
                    stats.append("{\"ID\":\"").append(b.id).append("\",\"Name\":\"").append(b.name)
                            .append("\",\"CPUPerc\":\"").append(cpu).append("%\",\"MemUsage\":\"").append(mem)
                            .append("MiB / 23.4GiB\",\"MemPerc\":\"").append(String.format(java.util.Locale.ROOT, "%.2f", mem / 239.6))
                            .append("%\",\"NetIO\":\"").append(netIo(b.name)).append("\"}\n");
                }
                i++;
            }
            return ps.toString() + stats;
        }
        Matcher logs = LOGS.matcher(command);
        if (logs.find()) {
            Box b = boxes.get(logs.group(2));
            return logs(b == null ? "container" : b.name, b != null && b.running);
        }
        Matcher action = ACTION.matcher(command);
        if (action.find()) {
            Box b = boxes.get(action.group(2));
            if (b == null) {
                return "Error response from daemon: No such container: " + action.group(2) + "\n";
            }
            boolean run = !action.group(1).equals("stop");
            boxes.put(b.id, new Box(b.id, b.name, b.image, run ? b.ports : "", run, run ? "Less than a second" : "1 second"));
            return b.id + "\n";
        }
        return "unknown docker command\n";
    }

    private static Map<String, Box> seed(String host) {
        Map<String, Box> m = new LinkedHashMap<>();
        String app = host.replace("demo:", "");
        add(m, app, app + ":latest", "0.0.0.0:8080->8080/tcp", true, "14 days");
        add(m, "redis", "redis:7-alpine", "6379/tcp", true, "14 days");
        add(m, "mysql", "mysql:8.4", "3306/tcp, 33060/tcp", true, "14 days");
        add(m, "nginx", "nginx:1.27", "0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp", true, "9 days");
        add(m, "watchtower", "containrrr/watchtower", "", false, "3 days");
        return m;
    }

    private static void add(Map<String, Box> m, String name, String image, String ports, boolean running, String since) {
        String id = String.format("%064x", new java.math.BigInteger(1, name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .substring(0, 64);
        m.put(id, new Box(id, name, image, running ? ports : "", running, since));
    }
}
