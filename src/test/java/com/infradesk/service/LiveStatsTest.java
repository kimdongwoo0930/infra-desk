package com.infradesk.service;

import com.infradesk.ssh.DemoShellConnector;
import com.infradesk.ssh.ProcStats;
import com.infradesk.ssh.SshTarget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStatsTest {

    @Test
    void demoStreamProducesSaneSamples() throws Exception {
        var connector = new DemoShellConnector(Duration.ZERO, Duration.ofMillis(40));
        var session = connector.exec(new SshTarget("demo:bot", 22, "ubuntu", "demo", null), (h, p, t, f) -> true,
                ProcStats.COMMAND);
        List<ProcStats.Sample> samples = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        LiveStats stats = LiveStats.start(session, Clock.systemUTC(), s -> {
            samples.add(s);
            three.countDown();
        }, reason -> { });
        try {
            assertTrue(three.await(5, TimeUnit.SECONDS), "three samples within 5s");
        } finally {
            stats.close();
        }
        for (ProcStats.Sample s : samples) {
            assertTrue(s.cpuPercent() >= 0 && s.cpuPercent() <= 100, "cpu " + s.cpuPercent());
            assertTrue(s.memoryPercent() > 0 && s.memoryPercent() < 100, "mem " + s.memoryPercent());
            assertTrue(s.rxBytesPerSec() > 0, "rx " + s.rxBytesPerSec());
        }
    }
}
