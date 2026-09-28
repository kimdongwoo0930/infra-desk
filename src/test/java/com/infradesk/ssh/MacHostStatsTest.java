package com.infradesk.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** macOS hosts (a Mac mini) have no /proc: the stats and facts commands switch to macOS tools. */
class MacHostStatsTest {

    @Test
    void cpuPercentLineIsUsedAsIs() {
        List<String> block = List.of("@cpu 12.5", "MemTotal: 1000 kB", "MemAvailable: 250 kB",
                "en0: 1000 0 0 0 0 0 0 0 500", "utun4: 10 0 0 0 0 0 0 0 20");
        ProcStats.Snapshot a = ProcStats.parse(block, Instant.ofEpochSecond(0)).orElseThrow();
        ProcStats.Snapshot b = ProcStats.parse(List.of("@cpu 40", "MemTotal: 1000 kB", "MemAvailable: 500 kB",
                "en0: 3000 0 0 0 0 0 0 0 1500", "utun4: 10 0 0 0 0 0 0 0 20"), Instant.ofEpochSecond(2)).orElseThrow();
        ProcStats.Sample s = ProcStats.between(a, b);
        assertEquals(40, s.cpuPercent(), 0.001);
        assertEquals(50, s.memoryPercent(), 0.001);
        assertEquals(1000, s.rxBytesPerSec(), 0.001);
        assertEquals(500, s.txBytesPerSec(), 0.001);
    }

    @Test
    void bsdNetstatListenLines() {
        assertEquals(Optional.of(22), HostFacts.listeningPort("tcp4       0      0  *.22                   *.*                    LISTEN"));
        assertEquals(Optional.of(5000), HostFacts.listeningPort("tcp6       0      0  *.5000                 *.*                    LISTEN"));
        assertEquals(Optional.of(8080), HostFacts.listeningPort("tcp4       0      0  192.168.0.20.8080      *.*                    LISTEN"));
        assertEquals(Optional.empty(), HostFacts.listeningPort("tcp4       0      0  127.0.0.1.631          *.*                    LISTEN"));
    }

    /** Runs the real commands in zsh (the macOS login shell) on this Mac and parses them. */
    @Test
    @EnabledOnOs(OS.MAC)
    void realMacCommandsParse() throws Exception {
        Process facts = new ProcessBuilder("/bin/zsh", "-c", HostFacts.COMMAND).redirectErrorStream(true).start();
        HostFacts f = HostFacts.parse(new String(facts.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(f.uptime() != null && f.uptime().toSeconds() > 0 && f.uptime().toDays() < 3650, "uptime " + f.uptime());
        assertTrue(f.osName().startsWith("macOS "), f.osName());
        assertTrue(f.diskTotal() > 0 && f.diskUsed() > 0, "disk");

        Process stats = new ProcessBuilder("/bin/zsh", "-c", ProcStats.COMMAND).redirectErrorStream(true).start();
        List<ProcStats.Snapshot> snapshots = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stats.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> block = new ArrayList<>();
            String line;
            while (snapshots.size() < 2 && (line = r.readLine()) != null) {
                if (line.equals("---")) {
                    ProcStats.parse(block, Instant.now()).ifPresent(snapshots::add);
                    block.clear();
                } else {
                    block.add(line);
                }
            }
        } finally {
            stats.destroyForcibly();
        }
        assertEquals(2, snapshots.size());
        ProcStats.Sample s = ProcStats.between(snapshots.get(0), snapshots.get(1));
        assertTrue(s.cpuPercent() >= 0 && s.cpuPercent() <= 100, "cpu " + s.cpuPercent());
        assertTrue(s.memoryPercent() > 0 && s.memoryPercent() < 100, "mem " + s.memoryPercent());
        assertTrue(snapshots.get(1).cpuPercent() >= 0, "iostat line present");
    }
}
