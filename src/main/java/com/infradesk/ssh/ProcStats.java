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

    /**
     * Upper bound on snapshots per session. The client turns live mode off after ten minutes; this
     * cap makes the remote loop end on its own shortly after, even if the connection died without
     * the server noticing (e.g. the laptop went to sleep).
     */
    public static final int MAX_SNAPSHOTS = 10 * 60 / INTERVAL_SECONDS + 10;

    /** Remote loop printing one snapshot block every {@link #INTERVAL_SECONDS}. POSIX sh (also zsh). */
    public static final String COMMAND = command(MAX_SNAPSHOTS, INTERVAL_SECONDS);

    /**
     * Two snapshots one second apart, then exit: one utilization sample for the once-a-minute
     * collection on directly connected servers. Parse with {@link #parseOneShot}.
     */
    public static final String ONE_SHOT = command(2, 1);

    /**
     * Linux reads the real /proc files. macOS has no /proc, so the same block is written from its
     * own tools: CPU busy % over one second from iostat (as an "@cpu" line), memory from hw.memsize
     * and vm_stat (free + inactive + speculative + purgeable pages count as available), and
     * per-interface byte counters from netstat -ib in /proc/net/dev layout. iostat itself takes a
     * second, so the macOS loop sleeps one second less.
     */
    private static String command(int snapshots, int intervalSeconds) {
        String linux = "i=0; while [ $i -lt " + snapshots + " ]; do head -n 1 /proc/stat; "
                + "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; tail -n +3 /proc/net/dev; "
                + "echo ---; i=$((i+1)); [ $i -lt " + snapshots + " ] && sleep " + intervalSeconds + "; done; true";
        String mac = "t=$(sysctl -n hw.memsize); ps=$(sysctl -n hw.pagesize); i=0; "
                + "while [ $i -lt " + snapshots + " ]; do "
                + "iostat -n0 -c 2 -w 1 | tail -n 1 | awk '{print \"@cpu \" $1+$2}'; "
                + "echo \"MemTotal: $((t/1024)) kB\"; "
                + "vm_stat | awk -v ps=$ps '/Pages free/{f=$3+0} /Pages inactive/{n=$3+0} /Pages speculative/{s=$3+0} "
                + "/Pages purgeable/{p=$3+0} END{print \"MemAvailable: \" int((f+n+s+p)*ps/1024) \" kB\"}'; "
                + "netstat -ibn | awk '$3 ~ /^<Link/ && $1 !~ /^lo/ {if (NF>=11) print $1\": \"$7\" 0 0 0 0 0 0 0 \"$10; "
                + "else print $1\": \"$6\" 0 0 0 0 0 0 0 \"$9}'; "
                + "echo ---; i=$((i+1)); sleep " + (intervalSeconds - 1) + "; done";
        return "if [ \"$(uname)\" = Darwin ]; then " + mac + "; else " + linux + "; fi";
    }

    /** The sample from {@link #ONE_SHOT} output, stamped {@code now}; empty if the output is incomplete. */
    public static Optional<Sample> parseOneShot(String output, Instant now) {
        List<Snapshot> snapshots = new java.util.ArrayList<>();
        List<String> block = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.strip().equals("---")) {
                // One second apart on the server; the exact gap only matters for network rates.
                parse(block, now.minusSeconds(snapshots.isEmpty() ? 1 : 0)).ifPresent(snapshots::add);
                block.clear();
            } else {
                block.add(line);
            }
        }
        if (snapshots.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(between(snapshots.get(snapshots.size() - 2), snapshots.getLast()));
    }

    /** One parsed snapshot. Counters are cumulative since boot. */
    public record Snapshot(Instant time, long cpuBusy, long cpuTotal, long memTotalKb, long memAvailableKb,
                           long rxBytes, long txBytes, double cpuPercent) {

        /** Counter-based snapshot (Linux); CPU % comes from the difference to the previous one. */
        public Snapshot(Instant time, long cpuBusy, long cpuTotal, long memTotalKb, long memAvailableKb,
                        long rxBytes, long txBytes) {
            this(time, cpuBusy, cpuTotal, memTotalKb, memAvailableKb, rxBytes, txBytes, -1);
        }
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
        double cpuPercent = -1;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("@cpu ")) {
                try {
                    cpuPercent = Double.parseDouble(line.substring(5).strip());
                    busy = 0;
                    total = 0;
                } catch (NumberFormatException ignored) {
                    // Leave CPU unknown; the block is dropped below.
                }
            } else if (line.startsWith("cpu ")) {
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
        return Optional.of(new Snapshot(time, busy, total, memTotal, memAvail, rx, tx, cpuPercent));
    }

    /** Utilization from {@code prev} to {@code next}. */
    public static Sample between(Snapshot prev, Snapshot next) {
        double dTotal = next.cpuTotal() - prev.cpuTotal();
        double cpu = next.cpuPercent() >= 0 ? next.cpuPercent()
                : dTotal <= 0 ? 0 : 100.0 * (next.cpuBusy() - prev.cpuBusy()) / dTotal;
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
