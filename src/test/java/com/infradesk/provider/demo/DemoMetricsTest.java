package com.infradesk.provider.demo;

import com.infradesk.core.Metrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoMetricsTest {

    @Test
    void sixtyMinutesOfBoundedDeterministicData() {
        Instant now = Instant.parse("2026-09-28T12:34:56Z");
        Metrics a = DemoMetrics.forServer("srv", now);
        Metrics b = DemoMetrics.forServer("srv", now.plusSeconds(3));
        assertEquals(60, a.cpuPercent().size());
        assertEquals(a, b, "same minute gives the same history");
        assertTrue(a.cpuPercent().stream().allMatch(s -> s.value() >= 0 && s.value() <= 100));
        assertTrue(a.memoryPercent().stream().allMatch(s -> s.value() >= 0 && s.value() <= 100));
        assertTrue(a.networkInBps().stream().allMatch(s -> s.value() >= 0));
        assertEquals(Instant.parse("2026-09-28T12:34:00Z"), a.cpuPercent().getLast().time());
    }
}
