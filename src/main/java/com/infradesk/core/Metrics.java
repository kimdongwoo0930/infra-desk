package com.infradesk.core;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Recent utilization series for one server. Series are ordered oldest first and may be empty when
 * the provider has no data yet.
 *
 * @param cpuPercent    CPU utilization, 0–100
 * @param memoryPercent memory utilization, 0–100
 * @param networkInBps  inbound bytes per second
 * @param networkOutBps outbound bytes per second
 */
public record Metrics(
        List<Sample> cpuPercent,
        List<Sample> memoryPercent,
        List<Sample> networkInBps,
        List<Sample> networkOutBps) {

    /** One data point. */
    public record Sample(Instant time, double value) {
    }

    public static final Metrics EMPTY = new Metrics(List.of(), List.of(), List.of(), List.of());

    public Metrics {
        cpuPercent = List.copyOf(cpuPercent);
        memoryPercent = List.copyOf(memoryPercent);
        networkInBps = List.copyOf(networkInBps);
        networkOutBps = List.copyOf(networkOutBps);
    }

    public OptionalDouble latestCpu() {
        return latest(cpuPercent);
    }

    public OptionalDouble latestMemory() {
        return latest(memoryPercent);
    }

    private static OptionalDouble latest(List<Sample> series) {
        return series.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(series.getLast().value());
    }
}
