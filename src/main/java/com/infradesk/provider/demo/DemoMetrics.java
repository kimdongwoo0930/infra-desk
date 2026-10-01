package com.infradesk.provider.demo;

import com.infradesk.core.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 결정적인 가짜 메트릭: 부드러운 파형에 해시 잡음을 더하고 서버 id로 시드를 정한다. 그래서
 * 새로고침할 때마다 같은 기록이 현재 분만큼 밀려서 보인다.
 */
final class DemoMetrics {

    private static final Duration HOUR = Duration.ofHours(1);

    private DemoMetrics() {
    }

    static Metrics forServer(String serverId, Instant now) {
        return forServer(serverId, now, HOUR);
    }

    /** 실제 provider처럼 긴 기간일수록 거친 간격을 쓴다: 6시간까지 1분, 24시간까지 5분, 그 이상은 15분. */
    static Metrics forServer(String serverId, Instant now, Duration range) {
        long stepMinutes = range.compareTo(Duration.ofHours(6)) <= 0 ? 1 : range.compareTo(Duration.ofHours(24)) <= 0 ? 5 : 15;
        int points = (int) Math.max(2, range.toMinutes() / stepMinutes);
        int seed = serverId.hashCode();
        double cpuBase = 12 + Math.floorMod(seed, 30);
        double memBase = 35 + Math.floorMod(seed >> 3, 30);
        double netBase = 20_000 + Math.floorMod(seed >> 5, 80_000);
        Instant end = now.truncatedTo(ChronoUnit.MINUTES);

        List<Metrics.Sample> cpu = new ArrayList<>();
        List<Metrics.Sample> mem = new ArrayList<>();
        List<Metrics.Sample> in = new ArrayList<>();
        List<Metrics.Sample> out = new ArrayList<>();
        for (int i = points - 1; i >= 0; i--) {
            Instant t = end.minus(Duration.ofMinutes(i * stepMinutes));
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

    /** 서버와 분(分)에 대해 안정적인 [-1, 1] 범위의 의사 난수. */
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
