package com.infradesk.ssh;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcStatsTest {

    private static List<String> block(long user, long system, long idle, long iowait, long memAvailKb, long rx, long tx) {
        return List.of(
                "cpu  " + user + " 0 " + system + " " + idle + " " + iowait + " 0 0 0 0 0",
                "MemTotal:       16000000 kB",
                "MemAvailable:   " + memAvailKb + " kB",
                "    lo: 999999 10 0 0 0 0 0 0 999999 10 0 0 0 0 0 0",
                "  ens3: " + rx + " 100 0 0 0 0 0 0 " + tx + " 80 0 0 0 0 0 0");
    }

    @Test
    void computesUtilizationBetweenSnapshots() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        var a = ProcStats.parse(block(1000, 500, 8000, 500, 8_000_000, 1_000_000, 500_000), t0).orElseThrow();
        var b = ProcStats.parse(block(1150, 550, 8280, 520, 4_000_000, 1_204_800, 551_200), t0.plusSeconds(2)).orElseThrow();

        ProcStats.Sample s = ProcStats.between(a, b);
        // 전체 +500 jiffies 중 busy +200 (idle과 iowait는 idle로 센다)
        assertEquals(40.0, s.cpuPercent(), 1e-9);
        assertEquals(75.0, s.memoryPercent(), 1e-9);
        // loopback 제외: 204800 바이트 / 2초
        assertEquals(102_400, s.rxBytesPerSec(), 1e-9);
        assertEquals(25_600, s.txBytesPerSec(), 1e-9);
    }

    @Test
    void remoteLoopIsBoundedToJustOverTenMinutes() {
        assertTrue(ProcStats.COMMAND.contains("-lt " + ProcStats.MAX_SNAPSHOTS));
        long seconds = (long) ProcStats.MAX_SNAPSHOTS * ProcStats.INTERVAL_SECONDS;
        assertTrue(seconds > 600 && seconds <= 660, "remote loop runs " + seconds + "s");
    }

    @Test
    void incompleteBlockIsRejected() {
        assertTrue(ProcStats.parse(List.of("MemTotal: 1 kB"), Instant.EPOCH).isEmpty());
    }

    @Test
    void counterResetDoesNotProduceNegativeRates() {
        Instant t0 = Instant.EPOCH;
        var a = ProcStats.parse(block(100, 0, 100, 0, 1, 5_000, 5_000), t0).orElseThrow();
        var b = ProcStats.parse(block(200, 0, 200, 0, 1, 100, 100), t0.plusSeconds(2)).orElseThrow();
        ProcStats.Sample s = ProcStats.between(a, b);
        assertEquals(0, s.rxBytesPerSec());
        assertEquals(0, s.txBytesPerSec());
    }
}
