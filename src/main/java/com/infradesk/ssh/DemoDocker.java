package com.infradesk.ssh;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fake Docker for demo mode: per-host containers whose state changes with start/stop/restart. */
final class DemoDocker {

    private record Box(String id, String name, String image, String ports, boolean running, String since) {
    }

    private static final Map<String, Map<String, Box>> HOSTS = new ConcurrentHashMap<>();
    private static final Pattern ACTION = Pattern.compile("\\$D (start|stop|restart) ([0-9a-f]{12,64})$");
    private static final Pattern LOGS = Pattern.compile("\\$D logs --tail (\\d+) --timestamps ([0-9a-f]{12,64})");

    private DemoDocker() {
    }

    /** Output for a {@link DockerCommands} command, as the real shell would print it. */
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
                            .append("%\"}\n");
                }
                i++;
            }
            return ps.toString() + stats;
        }
        Matcher logs = LOGS.matcher(command);
        if (logs.find()) {
            Box b = boxes.get(logs.group(2));
            String name = b == null ? "container" : b.name;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                out.append("2026-09-28T06:").append(String.format("%02d", 10 + i)).append(":00.000000000Z ")
                        .append(i % 5 == 3 ? "WARN  " : "INFO  ").append(name).append(": ")
                        .append(i % 5 == 3 ? "slow response 1.2s" : "heartbeat ok · " + (12 + i) + " users online").append('\n');
            }
            return out.toString();
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
