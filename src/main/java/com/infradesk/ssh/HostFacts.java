package com.infradesk.ssh;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Facts about a server that only the server itself knows, read once over SSH: uptime, OS name,
 * root disk usage and listening TCP ports reachable from outside (not bound to loopback).
 *
 * @param uptime    null when unknown
 * @param osName    e.g. "Ubuntu 22.04.4 LTS", null when unknown
 * @param diskUsed  bytes used on /, -1 when unknown
 * @param diskTotal bytes on /, -1 when unknown
 * @param cpuCount  logical CPUs, 0 when unknown
 * @param memoryKb  total memory in KiB, 0 when unknown
 */
public record HostFacts(Duration uptime, String osName, long diskUsed, long diskTotal, List<Integer> ports,
                        int cpuCount, long memoryKb) {

    public HostFacts(Duration uptime, String osName, long diskUsed, long diskTotal, List<Integer> ports) {
        this(uptime, osName, diskUsed, diskTotal, ports, 0, 0);
    }

    /** POSIX sh; each section starts with a "@name" marker line. Works with busybox too. */
    public static final String COMMAND = "echo @uptime; if [ -r /proc/uptime ]; then cat /proc/uptime; else "
            // macOS: seconds since kern.boottime
            + "b=$(sysctl -n kern.boottime 2>/dev/null | sed 's/^{ sec = \\([0-9]*\\).*/\\1/'); "
            + "[ -n \"$b\" ] && echo $(( $(date +%s) - b )); fi; "
            + "echo @os; (. /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\") "
            + "|| { command -v sw_vers >/dev/null 2>&1 && echo \"macOS $(sw_vers -productVersion)\"; } || uname -sr; "
            // macOS keeps user data on the Data volume; "/" is the small sealed system volume.
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
                        // Leave unknown.
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
                            // Header or odd format.
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

    /** Port from an `ss -tln` or `netstat -tln` line, if it listens on a non-loopback address. */
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
