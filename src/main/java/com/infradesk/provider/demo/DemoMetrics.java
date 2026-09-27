package com.infradesk.provider.demo;

import com.infradesk.core.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic fake metrics: smooth waves plus hash noise, seeded by server id, so every
 * refresh shows the same history shifted by the current minute.
 */
final class DemoMetrics {

    private static final int POINTS = 60;

    private DemoMetrics() {
    }

    static Metrics forServer(String serverId, Instant now) {
        int seed = serverId.hashCode();
        double cpuBase = 12 + Math.floorMod(seed, 30);
        double memBase = 35 + Math.floorMod(seed >> 3, 30);
        double netBase = 20_000 + Math.floorMod(seed >> 5, 80_000);
        Instant end = now.truncatedTo(ChronoUnit.MINUTES);

        List<Metrics.Sample> cpu = new ArrayList<>();
        List<Metrics.Sample> mem = new ArrayList<>();
        List<Metrics.Sample> in = new ArrayList<>();
        List<Metrics.Sample> out = new ArrayList<>();
        for (int i = POINTS - 1; i >= 0; i--) {
            Instant t = end.minus(Duration.ofMinutes(i));
            long minute = t.getEpochSecond() / 60;
            double n = noise(seed, minute);
            cpu.add(new Metrics.Sample(t, clamp(cpuBase + 9 * Math.sin(minute / 7.0 + seed) + 6 * n)));
            mem.add(new Metrics.Sample(t, clamp(memBase + 3 * Math.sin(minute / 23.0 + seed) + n)));
            double wave = 1 + 0.6 * Math.sin(minute / 5.0 + seed) + 0.3 * n;
            in.add(new Metrics.Sample(t, Math.max(0, netBase * wave)));
            out.add(new Metrics.Sample(t, Math.max(0, netBase * 0.35 * wave)));
        }
        return new Metrics(cpu, mem, in, out);
    }

    /** Stable pseudo-random value in [-1, 1] for a server and minute. */
    private static double noise(int seed, long minute) {
        long x = seed * 0x9E3779B97F4A7C15L + minute * 0xBF58476D1CE4E5B9L;
        x ^= x >>> 31;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 29;
        return (x & 0xFFFF) / 32767.5 - 1;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
