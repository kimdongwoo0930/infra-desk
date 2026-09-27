package com.infradesk.ssh;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns successive {@code /proc} snapshots into utilization. A snapshot block is the output of
 * {@link #COMMAND} between two {@code ---} lines: the {@code cpu} line of /proc/stat, MemTotal and
 * MemAvailable from /proc/meminfo, and the interface lines of /proc/net/dev.
 */
public final class ProcStats {

    /** Seconds between snapshots on the server. */
    public static final int INTERVAL_SECONDS = 2;

    /** Remote loop printing one snapshot block every {@link #INTERVAL_SECONDS}. POSIX sh. */
    public static final String COMMAND = "while :; do head -n 1 /proc/stat; "
            + "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; tail -n +3 /proc/net/dev; "
            + "echo ---; sleep " + INTERVAL_SECONDS + "; done";

    /** One parsed snapshot. Counters are cumulative since boot. */
    public record Snapshot(Instant time, long cpuBusy, long cpuTotal, long memTotalKb, long memAvailableKb,
                           long rxBytes, long txBytes) {
    }

    /** Utilization between two snapshots. */
    public record Sample(Instant time, double cpuPercent, double memoryPercent, double rxBytesPerSec,
                         double txBytesPerSec) {
    }

    private ProcStats() {
    }

    /** Parses one block (lines between separators). Empty if the block is incomplete. */
    public static Optional<Snapshot> parse(List<String> lines, Instant time) {
        long busy = -1;
        long total = -1;
        long memTotal = -1;
        long memAvail = -1;
        long rx = 0;
        long tx = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("cpu ")) {
                String[] f = line.split("\\s+");
                // user nice system idle iowait irq softirq steal
                long idle = 0;
                total = 0;
                for (int i = 1; i < f.length && i <= 8; i++) {
                    long v = Long.parseLong(f[i]);
                    total += v;
                    if (i == 4 || i == 5) {
                        idle += v;
                    }
                }
                busy = total - idle;
            } else if (line.startsWith("MemTotal:")) {
                memTotal = kb(line);
            } else if (line.startsWith("MemAvailable:")) {
                memAvail = kb(line);
            } else if (line.contains(":")) {
                String iface = line.substring(0, line.indexOf(':')).strip();
                if (iface.equals("lo")) {
                    continue;
                }
                String[] f = line.substring(line.indexOf(':') + 1).strip().split("\\s+");
                if (f.length >= 9) {
                    rx += Long.parseLong(f[0]);
                    tx += Long.parseLong(f[8]);
                }
            }
        }
        if (busy < 0 || memTotal <= 0 || memAvail < 0) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(time, busy, total, memTotal, memAvail, rx, tx));
    }

    /** Utilization from {@code prev} to {@code next}. */
    public static Sample between(Snapshot prev, Snapshot next) {
        double dTotal = next.cpuTotal() - prev.cpuTotal();
        double cpu = dTotal <= 0 ? 0 : 100.0 * (next.cpuBusy() - prev.cpuBusy()) / dTotal;
        double mem = 100.0 * (next.memTotalKb() - next.memAvailableKb()) / next.memTotalKb();
        double seconds = Math.max(0.001, (next.time().toEpochMilli() - prev.time().toEpochMilli()) / 1000.0);
        double rx = Math.max(0, next.rxBytes() - prev.rxBytes()) / seconds;
        double tx = Math.max(0, next.txBytes() - prev.txBytes()) / seconds;
        return new Sample(next.time(), clamp(cpu), clamp(mem), rx, tx);
    }

    private static long kb(String line) {
        String[] f = line.split("\\s+");
        return Long.parseLong(f[1]);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
