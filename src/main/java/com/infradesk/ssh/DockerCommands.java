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
 * SSH로 실행하는 Docker 셸 명령과 그 출력의 파싱. Docker의 TCP API는 절대 쓰지 않는다.
 *
 * <p>모든 명령은 {@link #PRELUDE}로 시작한다. 이는 {@code docker}를 고르거나, 로그인 사용자가
 * docker 그룹에 없으면 {@code sudo -n docker}(비밀번호 없는 sudo, OCI 기본값)를 고른다.
 * 컨테이너 id는 명령에 넣기 전에 16진수인지 검증하므로, 서버에서 온 이름이나 다른 텍스트가
 * 셸에 닿지 않는다.
 */
public final class DockerCommands {

    /** 합친 출력 안의 구분자. */
    static final String NO_DOCKER = "@@nodocker";
    static final String NO_ACCESS = "@@noaccess";
    static final String PS = "@@ps";
    static final String STATS = "@@stats";

    // macOS의 비대화형 SSH는 PATH가 최소한이다. Docker Desktop과 Homebrew는 다른 곳에 설치된다.
    public static final String PRELUDE = "PATH=\"$PATH:/usr/local/bin:/opt/homebrew/bin\"; command -v docker >/dev/null 2>&1 || { echo " + NO_DOCKER + "; exit 0; }; "
            + "D=docker; docker info >/dev/null 2>&1 || { sudo -n docker info >/dev/null 2>&1 && D='sudo -n docker'; } "
            + "|| { echo " + NO_ACCESS + "; exit 0; }; ";

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{12,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DockerCommands() {
    }

    /** 모든 상태의 컨테이너와, 실행 중인 컨테이너의 1회성 stats 샘플. */
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

    /** 컨테이너 안의 대화형 셸: 이미지에 bash가 있으면 bash, 없으면 sh. PTY가 필요하다. */
    public static String shell(String containerId) {
        return exec(containerId, "if command -v bash >/dev/null 2>&1; then exec bash; else exec sh; fi");
    }

    /** 잘 알려진 이미지의 데이터베이스 클라이언트를 컨테이너 안에서 실행한다. PTY가 필요하다. */
    public static String console(String containerId, Console console) {
        return exec(containerId, console.script);
    }

    private static String exec(String containerId, String script) {
        // 스크립트는 이 클래스의 고정 상수다. 작은따옴표로 감싸서 $VARS를 컨테이너의 sh가 해석하게 한다.
        return PRELUDE + "exec $D exec -it " + checkedId(containerId) + " sh -c '" + script + "'";
    }

    /**
     * 이미지 이름으로 고르는 데이터베이스 콘솔. 비밀번호는 터미널에서 직접 입력하며
     * (클라이언트가 묻는다) 아무것도 저장하지 않는다.
     */
    public enum Console {
        MYSQL("MySQL", "if command -v mariadb >/dev/null 2>&1; then exec mariadb -u root -p; else exec mysql -u root -p; fi",
                "mysql", "mariadb", "percona"),
        POSTGRES("PostgreSQL", "exec psql -U \"${POSTGRES_USER:-postgres}\"", "postgres", "postgis", "timescale"),
        REDIS("Redis", "exec redis-cli", "redis", "valkey", "keydb"),
        MONGO("MongoDB", "if command -v mongosh >/dev/null 2>&1; then exec mongosh; else exec mongo; fi", "mongo");

        /** 이름은 데이터베이스지만 데이터베이스가 아닌 이미지: exporter, 관리 UI, 프록시. */
        private static final String[] SIDECARS = {"exporter", "express", "commander", "insight", "admin", "operator",
                "proxy", "backup", "router"};

        public final String label;
        final String script;
        private final String[] imageNames;

        Console(String label, String script, String... imageNames) {
            this.label = label;
            this.script = script;
            this.imageNames = imageNames;
        }

        /** "mysql:8", "bitnami/postgresql:16", "redis/redis-stack" 같은 이미지의 콘솔. */
        public static java.util.Optional<Console> forImage(String image) {
            if (image == null) {
                return java.util.Optional.empty();
            }
            String name = image.toLowerCase(java.util.Locale.ROOT);
            int digest = name.indexOf('@');
            if (digest >= 0) {
                name = name.substring(0, digest);
            }
            name = name.substring(name.lastIndexOf('/') + 1);
            int tag = name.indexOf(':');
            if (tag >= 0) {
                name = name.substring(0, tag);
            }
            for (String tool : SIDECARS) {
                if (name.contains(tool)) {
                    return java.util.Optional.empty();
                }
            }
            for (Console c : values()) {
                for (String n : c.imageNames) {
                    if (name.startsWith(n)) {
                        return java.util.Optional.of(c);
                    }
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** Docker id가 아닌 것은 거부해서 다른 것이 셸에 닿지 않게 한다. */
    static String checkedId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("not a container id: " + id);
        }
        return id;
    }

    /** {@link #list()}의 결과. */
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
                    // 형식이 잘못된 줄은 건너뛴다.
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
