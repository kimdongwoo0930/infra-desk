package com.infradesk.ssh;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 연속된 {@code /proc} 스냅샷을 사용률로 바꾼다. 스냅샷 블록은 {@link #COMMAND}의 출력 중
 * {@code ---} 줄 두 개 사이 부분으로, /proc/stat의 {@code cpu} 줄, /proc/meminfo의 MemTotal과
 * MemAvailable, /proc/net/dev의 인터페이스 줄로 이루어진다.
 */
public final class ProcStats {

    /** 서버에서 스냅샷을 찍는 간격(초). */
    public static final int INTERVAL_SECONDS = 2;

    /**
     * 세션당 스냅샷 수의 상한. 클라이언트는 10분 뒤 실시간 모드를 끄지만, 서버가 연결 끊김을
     * 알아채지 못한 경우(예: 노트북이 잠자기에 들어감)에도 이 상한 덕분에 원격 루프가 곧 스스로 끝난다.
     */
    public static final int MAX_SNAPSHOTS = 10 * 60 / INTERVAL_SECONDS + 10;

    /** {@link #INTERVAL_SECONDS}마다 스냅샷 블록 하나를 출력하는 원격 루프. POSIX sh(zsh에서도 동작). */
    public static final String COMMAND = command(MAX_SNAPSHOTS, INTERVAL_SECONDS);

    /**
     * 1초 간격으로 스냅샷 두 개를 찍고 종료한다. 직접 연결 서버의 분당 수집에서 사용률 샘플 하나를
     * 얻기 위한 것이다. {@link #parseOneShot}으로 파싱한다.
     */
    public static final String ONE_SHOT = command(2, 1);

    /**
     * Linux는 실제 /proc 파일을 읽는다. macOS에는 /proc이 없으므로 같은 블록을 자체 도구로 만든다.
     * CPU 사용률은 iostat로 1초 동안 측정하고("@cpu" 줄), 메모리는 hw.memsize와 vm_stat에서
     * (free + inactive + speculative + purgeable 페이지를 가용으로 센다), 인터페이스별 바이트
     * 카운터는 netstat -ib에서 /proc/net/dev 형식으로 만든다. iostat 자체가 1초 걸리므로
     * macOS 루프는 1초 덜 잔다.
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

    /** {@link #ONE_SHOT} 출력에서 얻은 샘플에 {@code now}를 찍는다. 출력이 불완전하면 빈 값. */
    public static Optional<Sample> parseOneShot(String output, Instant now) {
        List<Snapshot> snapshots = new java.util.ArrayList<>();
        List<String> block = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.strip().equals("---")) {
                // 서버에서 1초 간격. 정확한 간격은 네트워크 속도 계산에만 영향을 준다.
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

    /** 파싱한 스냅샷 하나. 카운터는 부팅 이후 누적값이다. */
    public record Snapshot(Instant time, long cpuBusy, long cpuTotal, long memTotalKb, long memAvailableKb,
                           long rxBytes, long txBytes, double cpuPercent) {

        /** 카운터 기반 스냅샷(Linux). CPU %는 이전 스냅샷과의 차이로 계산한다. */
        public Snapshot(Instant time, long cpuBusy, long cpuTotal, long memTotalKb, long memAvailableKb,
                        long rxBytes, long txBytes) {
            this(time, cpuBusy, cpuTotal, memTotalKb, memAvailableKb, rxBytes, txBytes, -1);
        }
    }

    /** 두 스냅샷 사이의 사용률. */
    public record Sample(Instant time, double cpuPercent, double memoryPercent, double rxBytesPerSec,
                         double txBytesPerSec) {
    }

    private ProcStats() {
    }

    /** 블록 하나(구분자 사이의 줄들)를 파싱한다. 블록이 불완전하면 빈 값. */
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
                    // CPU는 알 수 없는 상태로 둔다. 이 블록은 아래에서 버려진다.
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

    /** {@code prev}에서 {@code next}까지의 사용률. */
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
