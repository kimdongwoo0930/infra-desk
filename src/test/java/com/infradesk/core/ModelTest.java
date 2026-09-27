package com.infradesk.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    void serverDefaultsNullStatusToUnknown() {
        Server s = new Server("id-1", "acc-1", "test", null, "region-1", "shape", 1, 1, null, null, null);
        assertEquals(ServerStatus.UNKNOWN, s.status());
        assertTrue(s.publicIpAddress().isEmpty());
        assertEquals(ServerStatus.RUNNING, s.withStatus(ServerStatus.RUNNING).status());
    }

    @Test
    void accountRequiresFields() {
        assertThrows(NullPointerException.class, () -> new Account("a", "A", null, "region-1", null));
    }

    @Test
    void metricsLatestAndDefensiveCopy() {
        List<Metrics.Sample> cpu = new ArrayList<>(List.of(
                new Metrics.Sample(Instant.EPOCH, 10), new Metrics.Sample(Instant.EPOCH.plusSeconds(60), 23)));
        Metrics m = new Metrics(cpu, List.of(), List.of(), List.of());
        cpu.clear();
        assertEquals(23, m.latestCpu().orElseThrow());
        assertTrue(m.latestMemory().isEmpty());
        assertTrue(Metrics.EMPTY.latestCpu().isEmpty());
    }
}
