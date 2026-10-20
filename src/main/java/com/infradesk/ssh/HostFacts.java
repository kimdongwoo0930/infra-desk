package com.infradesk.ssh;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 서버 자신만 아는 정보를 SSH로 한 번 읽은 것: 업타임, OS 이름, 루트 디스크 사용량,
 * 외부에서 접근 가능한(loopback에 바인딩되지 않은) 수신 대기 TCP 포트.
 *
 * @param uptime    알 수 없으면 null
 * @param osName    예: "Ubuntu 22.04.4 LTS". 알 수 없으면 null
 * @param diskUsed  /의 사용 바이트. 알 수 없으면 -1
 * @param diskTotal /의 전체 바이트. 알 수 없으면 -1
 * @param cpuCount  논리 CPU 수. 알 수 없으면 0
 * @param memoryKb  전체 메모리(KiB). 알 수 없으면 0
 */
public record HostFacts(Duration uptime, String osName, long diskUsed, long diskTotal, List<Integer> ports,
                        int cpuCount, long memoryKb) {

    public HostFacts(Duration uptime, String osName, long diskUsed, long diskTotal, List<Integer> ports) {
        this(uptime, osName, diskUsed, diskTotal, ports, 0, 0);
    }

    /** POSIX sh. 각 섹션은 "@name" 표식 줄로 시작한다. busybox에서도 동작한다. */
    public static final String COMMAND = "echo @uptime; if [ -r /proc/uptime ]; then cat /proc/uptime; else "
            // macOS: kern.boottime 이후 경과한 초
            + "b=$(sysctl -n kern.boottime 2>/dev/null | sed 's/^{ sec = \\([0-9]*\\).*/\\1/'); "
            + "[ -n \"$b\" ] && echo $(( $(date +%s) - b )); fi; "
            + "echo @os; (. /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\") "
            + "|| { command -v sw_vers >/dev/null 2>&1 && echo \"macOS $(sw_vers -productVersion)\"; } || uname -sr; "
            // macOS는 사용자 데이터를 Data 볼륨에 두고, "/"는 작은 봉인된 시스템 볼륨이다.
            + "echo @disk; (df -Pk /System/Volumes/Data 2>/dev/null || df -Pk / 2>/dev/null) | tail -n 1; "
            + "echo @cpus; (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null); "
            + "echo @memkb; (awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null "
            + "|| echo $(( $(sysctl -n hw.memsize 2>/dev/null) / 1024 ))); "
            + "echo @ports; (ss -tln 2>/dev/null || netstat -an -p tcp 2>/dev/null | grep LISTEN || netstat -tln 2>/dev/null)";

    public HostFacts {
        ports = List.copyOf(ports);
    }

    public static HostFacts parse(String output) {
        Duration uptime = null;
        String os = null;
        long used = -1;
        long total = -1;
        int cpus = 0;
        long memKb = 0;
        TreeSet<Integer> ports = new TreeSet<>();
        String section = "";
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("@")) {
                section = line;
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            switch (section) {
                case "@uptime" -> {
                    try {
                        double seconds = Double.parseDouble(line.split("\\s+")[0]);
                        uptime = Duration.ofSeconds((long) seconds);
                    } catch (NumberFormatException ignored) {
                        // 알 수 없는 상태로 둔다.
                    }
                }
                case "@os" -> os = os == null ? line.replace("\"", "") : os;
                case "@disk" -> {
                    // Filesystem 1024-blocks Used Available Capacity Mounted-on
                    String[] f = line.split("\\s+");
                    if (f.length >= 4) {
                        try {
                            total = Long.parseLong(f[1]) * 1024;
                            used = Long.parseLong(f[2]) * 1024;
                        } catch (NumberFormatException ignored) {
                            // 헤더이거나 형식이 이상한 줄.
                        }
                    }
                }
                case "@ports" -> listeningPort(line).ifPresent(ports::add);
                case "@cpus" -> cpus = cpus > 0 ? cpus : parseInt(line);
                case "@memkb" -> memKb = memKb > 0 ? memKb : parseInt(line);
                default -> { }
            }
        }
        return new HostFacts(uptime, os, used, total, new ArrayList<>(ports), cpus, memKb);
    }

    private static int parseInt(String s) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Long.parseLong(s.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** `ss -tln` 또는 `netstat -tln` 줄에서 뽑은 포트. loopback이 아닌 주소에서 수신 대기할 때만. */
    static java.util.Optional<Integer> listeningPort(String line) {
        String[] f = line.split("\\s+");
        String local = null;
        if (f.length >= 5 && f[0].equals("LISTEN")) {
            local = f[3]; // ss: State Recv-Q Send-Q Local:Port Peer:Port
        } else if (f.length >= 6 && f[0].startsWith("tcp") && f[5].equals("LISTEN")) {
            local = f[3]; // netstat: Proto Recv-Q Send-Q Local Foreign State
        }
        if (local == null) {
            return java.util.Optional.empty();
        }
        int colon = local.lastIndexOf(':');
        if (colon < 0 && f[0].startsWith("tcp")) {
            colon = local.lastIndexOf('.'); // BSD/macOS netstat: "*.22", "127.0.0.1.631"
        }
        if (colon < 0) {
            return java.util.Optional.empty();
        }
        String host = local.substring(0, colon).replace("[", "").replace("]", "");
        if (host.startsWith("127.") || host.equals("::1") || host.equals("localhost") || host.contains("%lo")) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Integer.parseInt(local.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
