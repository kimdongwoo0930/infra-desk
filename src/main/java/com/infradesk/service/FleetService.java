package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ssh.HostFacts;
import com.infradesk.ssh.HostKeyPrompt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 개요 화면이 서버 여러 대의 사용량(메모리·디스크·업타임)과 컨테이너 수를 한꺼번에 읽는다.
 * 서버마다 조회용(QUERY) 연결을 빌려 {@link HostFacts}와 가벼운 컨테이너 목록을 읽는다.
 *
 * <ul>
 *   <li>한 번에 {@value #PARALLEL}대씩만 읽는다. 서버가 많아도 접속이 몰리지 않는다.</li>
 *   <li>읽은 값은 서버별로 {@link #TTL} 동안 다시 쓴다. 개요를 여러 번 열어도 다시 읽지 않고,
 *       새로고침({@code force})일 때만 다시 읽는다.</li>
 *   <li>실행 중이 아니거나 SSH 설정이 없는 서버는 읽지 않는다.</li>
 *   <li>읽기에 실패한 서버는 새로고침하기 전에는 시간이 지나도 다시 읽지 않는다(서버의 인증 로그와 보안 도구를
 *       자극하지 않으려는 것이다. 호스트 키를 믿지 않은 서버에도 묻지 않는다).</li>
 * </ul>
 * 블로킹 작업은 이 클래스의 작업 스레드에서 하므로 호출한 쪽(화면)은 기다리지 않는다.
 */
public class FleetService {

    /** 한 번에 읽는 서버 수. */
    public static final int PARALLEL = 2;
    /** 읽은 값을 다시 쓰는 시간. */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** 한 서버를 읽은 결과. {@code problem}이 있으면 읽지 못한 것이고 나머지는 null이다. */
    public record Reading(String serverId, Instant at, HostFacts facts, DockerCommands.Listing containers,
                          String problem) {
        public boolean failed() {
            return problem != null;
        }
    }

    /** 서버 하나를 읽는 방법. 실제로는 SSH, 시험에서는 가짜. */
    interface Reader {
        boolean readable(Server server);

        HostFacts facts(Server server);

        DockerCommands.Listing containers(Server server);
    }

    /** 백그라운드 읽기는 처음 보는 호스트 키를 묻지 않고 거절한다(사용자가 터미널로 한 번 접속해서 믿어야 한다). */
    private static final HostKeyPrompt NEVER_TRUST = (host, port, keyType, fingerprint) -> false;

    private final Reader reader;
    private final Clock clock;
    private final Map<String, Reading> readings = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    /** 읽기를 새로 시작하거나 {@link #cancel}할 때마다 올라간다. 오래된 요청의 남은 작업은 건너뛴다. */
    private final AtomicInteger generation = new AtomicInteger();

    public FleetService(TerminalService terminal, ContainerService containers) {
        this(new Reader() {
            @Override
            public boolean readable(Server server) {
                return server.status() == ServerStatus.RUNNING && terminal.isConfigured(server.id());
            }

            @Override
            public HostFacts facts(Server server) {
                return terminal.facts(server, NEVER_TRUST);
            }

            @Override
            public DockerCommands.Listing containers(Server server) {
                return containers.listPlain(server, NEVER_TRUST);
            }
        }, Clock.systemUTC());
    }

    FleetService(Reader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(PARALLEL, r -> {
            Thread t = new Thread(r, "fleet-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** 가장 최근에 읽은 값(오래됐어도). 없으면 비어 있다. */
    public Optional<Reading> latest(String serverId) {
        return Optional.ofNullable(readings.get(serverId));
    }

    /** 읽은 값이 아직 쓸 만한가. */
    public boolean fresh(String serverId) {
        Reading r = readings.get(serverId);
        return r != null && r.at().plus(TTL).isAfter(clock.instant());
    }

    /** 아직 못 읽었거나 값이 오래됐다. 읽기에 실패한 서버는 새로고침({@code force})하기 전에는 다시 읽지 않는다. */
    private boolean needsReading(String serverId) {
        Reading r = readings.get(serverId);
        return r == null || !r.failed() && !fresh(serverId);
    }

    /**
     * 읽을 수 있는 서버 중 값이 없거나 오래된 서버(또는 {@code force}면 전부)를 읽는다. 한 서버를 읽을 때마다
     * {@code onEach}를 작업 스레드에서 부른다(화면은 EDT로 넘겨야 한다). 이전 요청이 남아 있으면 그 남은 서버는
     * 읽지 않는다.
     *
     * @return 이번에 읽기로 한 서버 수
     */
    public int read(List<Server> servers, boolean force, Consumer<Reading> onEach) {
        int mine = generation.incrementAndGet();
        List<Server> todo = servers.stream()
                .filter(reader::readable)
                .filter(s -> force || needsReading(s.id()))
                .toList();
        for (Server server : todo) {
            workers.execute(() -> {
                if (generation.get() != mine) {
                    return;   // 그 사이 새 요청이 왔거나 취소됐다
                }
                Reading reading = readOne(server);
                readings.put(server.id(), reading);
                onEach.accept(reading);
            });
        }
        return todo.size();
    }

    /** 아직 시작하지 않은 서버 읽기를 그만둔다(개요를 떠날 때). 이미 읽는 중인 것은 끝까지 간다. */
    public void cancel() {
        generation.incrementAndGet();
    }

    private Reading readOne(Server server) {
        try {
            HostFacts facts = reader.facts(server);
            DockerCommands.Listing containers = null;
            try {
                containers = reader.containers(server);
            } catch (RuntimeException e) {
                // 컨테이너만 못 읽은 것: 서버 정보는 그대로 보여 준다(Docker 없음·권한 없음은 Listing 상태로 온다)
            }
            return new Reading(server.id(), clock.instant(), facts, containers, null);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return new Reading(server.id(), clock.instant(), null, null,
                    message == null || message.isBlank() ? "SSH로 읽지 못했어요" : message);
        }
    }
}
