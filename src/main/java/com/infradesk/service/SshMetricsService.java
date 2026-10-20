package com.infradesk.service;

import com.infradesk.core.Metrics;
import com.infradesk.core.Server;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ssh.ProcStats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 클라우드 메트릭이 없는 직접 연결 서버의 모니터링. 앱이 실행되는 동안 분당 짧은 SSH 명령 한 번
 * (1초 간격의 /proc 또는 macOS 스냅샷 두 개)을 실행하고 최근 1시간을 메모리에 보관해서,
 * 상세 차트와 사이드바 CPU가 클라우드 서버처럼 동작하게 한다.
 * 블로킹이므로 EDT 밖에서 호출한다.
 */
public class SshMetricsService {

    private static final Logger LOG = Logger.getLogger(SshMetricsService.class.getName());
    static final Duration KEEP = Duration.ofHours(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final TerminalService terminal;
    private final Clock clock;
    private final SamplingGate gate;
    private final Map<String, Deque<ProcStats.Sample>> history = new ConcurrentHashMap<>();

    public SshMetricsService(TerminalService terminal, Clock clock) {
        this.terminal = terminal;
        this.clock = clock;
        this.gate = new SamplingGate(clock);
    }

    /** 서버에 샘플링에 필요한 것(SSH 사용자와 키)이 있는지. */
    public boolean canSample(Server server) {
        return terminal.isConfigured(server.id());
    }

    public Optional<ProcStats.Sample> sample(Server server) {
        return sample(server, true);
    }

    /**
     * 샘플을 한 번 찍어 기록에 추가한다. SSH가 설정되지 않았거나, 호스트 키를 아직 신뢰하지 않았거나
     * (백그라운드에서 실행되므로 절대 묻지 않는다) 명령이 실패하면 빈 값.
     *
     * <p>서버에 접속하는 횟수를 {@link SamplingGate}가 제한한다. 지금 접속하면 안 되는 때(최근에 접속했거나,
     * 실패 뒤 기다리는 중이거나, 설정 문제로 멈춘 상태)는 접속하지 않고 빈 값을 돌려준다.
     *
     * @param windowVisible 앱 창이 보이는 상태인지. 숨어 있으면 접속 간격이 길어진다
     */
    public Optional<ProcStats.Sample> sample(Server server, boolean windowVisible) {
        if (!canSample(server) || !gate.tryAcquire(server.id(), windowVisible)) {
            return Optional.empty();
        }
        ExecResult r = terminal.run(server, ProcStats.ONE_SHOT, (host, port, type, fingerprint) -> false, TIMEOUT);
        Optional<ProcStats.Sample> sample = ProcStats.parseOneShot(r.output(), clock.instant());
        if (sample.isEmpty()) {
            boolean stopped = gate.failure(server.id(), r.failureKind());
            if (stopped) {
                // 서버 로그에 접속 시도가 쌓이지 않도록 자동 측정을 멈췄다. 한 번만 남긴다.
                LOG.log(Level.WARNING, "Stopped automatic SSH sampling for " + server.name() + " (" + r.failureKind()
                        + "). It resumes after the SSH settings are saved or the list is refreshed.");
            }
            LOG.log(Level.FINE, "SSH sample failed for " + server.name() + ": " + firstLine(r.output()));
            return Optional.empty();
        }
        gate.success(server.id());
        Deque<ProcStats.Sample> series = history.computeIfAbsent(server.id(), id -> new ArrayDeque<>());
        synchronized (series) {
            series.addLast(sample.get());
            Instant cutoff = clock.instant().minus(KEEP);
            while (!series.isEmpty() && series.peekFirst().time().isBefore(cutoff)) {
                series.removeFirst();
            }
        }
        return sample;
    }

    /** 수집한 1시간을 차트 시리즈로 돌려준다(첫 샘플 전까지는 비어 있다). */
    public Metrics metrics(String serverId) {
        Deque<ProcStats.Sample> series = history.get(serverId);
        if (series == null) {
            return Metrics.EMPTY;
        }
        List<ProcStats.Sample> copy;
        synchronized (series) {
            copy = List.copyOf(series);
        }
        return new Metrics(
                copy.stream().map(s -> new Metrics.Sample(s.time(), s.cpuPercent())).toList(),
                copy.stream().map(s -> new Metrics.Sample(s.time(), s.memoryPercent())).toList(),
                copy.stream().map(s -> new Metrics.Sample(s.time(), s.rxBytesPerSec())).toList(),
                copy.stream().map(s -> new Metrics.Sample(s.time(), s.txBytesPerSec())).toList());
    }

    /** 샘플이 하나라도 있는 서버별 최신 CPU %. */
    public Map<String, Double> latestCpu() {
        Map<String, Double> result = new HashMap<>();
        history.forEach((id, series) -> {
            synchronized (series) {
                if (!series.isEmpty()) {
                    result.put(id, series.peekLast().cpuPercent());
                }
            }
        });
        return result;
    }

    public void forget(String serverId) {
        history.remove(serverId);
        gate.forget(serverId);
    }

    /**
     * 자동 측정을 멈춘 이유를 사용자에게 보여줄 문구로. 멈추지 않았으면 비어 있다.
     * 멈춘 서버는 {@link #reset} 전까지 서버에 접속하지 않는다.
     */
    public Optional<String> blockedReason(String serverId) {
        return gate.blocked(serverId).map(kind -> switch (kind) {
            case AUTH -> "SSH 키나 사용자 이름이 맞지 않아요";
            case HOST_KEY_REJECTED -> "서버의 호스트 키를 아직 믿지 않았어요 (터미널로 한 번 접속해서 확인하세요)";
            case HOST_KEY_CHANGED -> "서버의 호스트 키가 저장된 것과 달라졌어요";
            case KEY_FORMAT -> "SSH 키 파일을 읽을 수 없어요";
            default -> "SSH 접속에 실패했어요";
        });
    }

    /** 사용자가 SSH 설정을 고쳤거나 서버에 접속이 된다는 걸 확인했다. 이 서버의 멈춤과 기다림을 푼다. */
    public void reset(String serverId) {
        gate.reset(serverId);
    }

    /** 사용자가 직접 새로고침했다. 모든 서버의 멈춤과 기다림을 푼다. */
    public void resetAll() {
        gate.resetAll();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
