package com.infradesk.core;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 서버 하나의 최근 사용률 시계열. 시계열은 오래된 것부터 정렬되며, provider에 아직 데이터가
 * 없으면 비어 있을 수 있다.
 *
 * @param cpuPercent    CPU 사용률, 0–100
 * @param memoryPercent 메모리 사용률, 0–100
 * @param networkInBps  초당 수신 바이트
 * @param networkOutBps 초당 송신 바이트
 */
public record Metrics(
        List<Sample> cpuPercent,
        List<Sample> memoryPercent,
        List<Sample> networkInBps,
        List<Sample> networkOutBps) {

    /** 데이터 점 하나. */
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
