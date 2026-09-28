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
 * Monitoring for directly connected servers, which have no cloud metrics: one short SSH command a
 * minute (two /proc or macOS snapshots a second apart) while the app runs, kept in memory for the
 * last hour so the detail charts and the sidebar CPU work like they do for cloud servers.
 * Blocking; call off the EDT.
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

    /** Whether the server has what sampling needs (SSH user and key). */
    public boolean canSample(Server server) {
        return terminal.isConfigured(server.id());
    }

    /**
     * Takes one sample and adds it to the history. Empty when SSH isn't set up, the host key isn't
     * trusted yet (never asks: this runs in the background) or the command failed.
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

    /** The collected hour as chart series (empty until the first sample). */
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

    /** Latest CPU % per server that has any sample. */
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
