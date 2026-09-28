package com.infradesk.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shell commands for Docker over SSH and parsing of their output. Docker's TCP API is never used.
 *
 * <p>Every command starts with {@link #PRELUDE}, which picks {@code docker} or, when the login
 * user isn't in the docker group, {@code sudo -n docker} (passwordless sudo, the OCI default).
 * Container ids are validated as hex before they go into a command, so names or other text from
 * the server never reach the shell.
 */
public final class DockerCommands {

    /** Markers in the combined output. */
    static final String NO_DOCKER = "@@nodocker";
    static final String NO_ACCESS = "@@noaccess";
    static final String PS = "@@ps";
    static final String STATS = "@@stats";

    public static final String PRELUDE = "command -v docker >/dev/null 2>&1 || { echo " + NO_DOCKER + "; exit 0; }; "
            + "D=docker; docker info >/dev/null 2>&1 || { sudo -n docker info >/dev/null 2>&1 && D='sudo -n docker'; } "
            + "|| { echo " + NO_ACCESS + "; exit 0; }; ";

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{12,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DockerCommands() {
    }

    /** Containers (all states) plus a one-shot stats sample for the running ones. */
    public static String list() {
        return PRELUDE + "echo " + PS + "; $D ps -a --no-trunc --format '{{json .}}'; "
                + "echo " + STATS + "; $D stats --no-stream --no-trunc --format '{{json .}}' 2>/dev/null; true";
    }

    public enum Action { START, STOP, RESTART }

    public static String action(Action action, String containerId) {
        return PRELUDE + "$D " + action.name().toLowerCase(java.util.Locale.ROOT) + " " + checkedId(containerId);
    }

    public static String logs(String containerId, int tail) {
        if (tail < 1 || tail > 5000) {
            throw new IllegalArgumentException("tail out of range: " + tail);
        }
        return PRELUDE + "$D logs --tail " + tail + " --timestamps " + checkedId(containerId) + " 2>&1";
    }

    /** Rejects anything that isn't a Docker id, so nothing else reaches the shell. */
    static String checkedId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("not a container id: " + id);
        }
        return id;
    }

    /** Result of {@link #list()}. */
    public record Listing(Status status, List<Container> containers, String problem) {
        public enum Status { OK, NO_DOCKER, NO_ACCESS, ERROR }
    }

    public static Listing parseList(String output) {
        if (output.contains(NO_DOCKER)) {
            return new Listing(Listing.Status.NO_DOCKER, List.of(), "이 서버에 Docker가 설치되어 있지 않아요");
        }
        if (output.contains(NO_ACCESS)) {
            return new Listing(Listing.Status.NO_ACCESS, List.of(),
                    "Docker에 접근할 권한이 없어요. 사용자를 docker 그룹에 넣거나 비밀번호 없는 sudo를 허용하세요");
        }
        int ps = output.indexOf(PS);
        if (ps < 0) {
            return new Listing(Listing.Status.ERROR, List.of(), "Docker 정보를 읽지 못했어요: " + firstLine(output));
        }
        int stats = output.indexOf(STATS, ps);
        String psPart = output.substring(ps + PS.length(), stats < 0 ? output.length() : stats);
        String statsPart = stats < 0 ? "" : output.substring(stats + STATS.length());

        Map<String, JsonNode> statsById = new HashMap<>();
        for (JsonNode n : jsonLines(statsPart)) {
            String id = n.path("ID").asText(n.path("Container").asText(""));
            if (!id.isEmpty()) {
                statsById.put(id, n);
            }
        }
        List<Container> containers = new ArrayList<>();
        for (JsonNode n : jsonLines(psPart)) {
            String id = n.path("ID").asText("");
            JsonNode st = statsById.get(id);
            if (st == null) {
                for (var e : statsById.entrySet()) {
                    if (id.startsWith(e.getKey()) || e.getKey().startsWith(id)) {
                        st = e.getValue();
                    }
                }
            }
            String state = n.path("State").asText("").toLowerCase(java.util.Locale.ROOT);
            boolean running = "running".equals(state);
            containers.add(new Container(id, n.path("Names").asText(""), n.path("Image").asText(""), state,
                    n.path("Status").asText(""), n.path("Ports").asText(""),
                    running && st != null ? percent(st.path("CPUPerc").asText("")) : -1,
                    running && st != null ? st.path("MemUsage").asText("") : "",
                    running && st != null ? percent(st.path("MemPerc").asText("")) : -1));
        }
        containers.sort(java.util.Comparator.comparing((Container c) -> !c.isRunning()).thenComparing(Container::name));
        return new Listing(Listing.Status.OK, containers, null);
    }

    private static List<JsonNode> jsonLines(String text) {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("{")) {
                try {
                    nodes.add(JSON.readTree(t));
                } catch (IOException ignored) {
                    // Skip a malformed line.
                }
            }
        }
        return nodes;
    }

    static double percent(String text) {
        try {
            return Double.parseDouble(text.replace("%", "").strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }
}
