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
    private final Map<String, Deque<ProcStats.Sample>> history = new ConcurrentHashMap<>();

    public SshMetricsService(TerminalService terminal, Clock clock) {
        this.terminal = terminal;
        this.clock = clock;
    }

    /** 서버에 샘플링에 필요한 것(SSH 사용자와 키)이 있는지. */
    public boolean canSample(Server server) {
        return terminal.isConfigured(server.id());
    }

    /**
     * 샘플을 한 번 찍어 기록에 추가한다. SSH가 설정되지 않았거나, 호스트 키를 아직 신뢰하지 않았거나
     * (백그라운드에서 실행되므로 절대 묻지 않는다) 명령이 실패하면 빈 값.
     */
    public Optional<ProcStats.Sample> sample(Server server) {
        if (!canSample(server)) {
            return Optional.empty();
        }
        ExecResult r = terminal.run(server, ProcStats.ONE_SHOT, (host, port, type, fingerprint) -> false, TIMEOUT);
        Optional<ProcStats.Sample> sample = ProcStats.parseOneShot(r.output(), clock.instant());
        if (sample.isEmpty()) {
            LOG.log(Level.FINE, "SSH sample failed for " + server.name() + ": " + firstLine(r.output()));
            return Optional.empty();
        }
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
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
