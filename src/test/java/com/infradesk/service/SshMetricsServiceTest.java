package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.DemoShellConnector;
import com.infradesk.ssh.ProcStats;
import com.infradesk.storage.InMemorySavedCommandStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.InMemorySshSettingsStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshMetricsServiceTest {

    private static final Server MAC = new Server("ssh-m", "m", "맥미니", ServerStatus.RUNNING, "SSH", "mac-mini:22",
            0, 0, "mac-mini", null, null);

    private static TerminalService demoTerminal() {
        return new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                new DemoShellConnector(Duration.ZERO, Duration.ofMillis(50)), new InMemorySavedCommandStore(List.of()), true);
    }

    @Test
    void samplesBuildAnHourOfHistory() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        SshMetricsService svc = new SshMetricsService(demoTerminal(), clock);
        assertTrue(svc.metrics(MAC.id()).cpuPercent().isEmpty());

        assertTrue(svc.sample(MAC).isPresent());
        clock.now = clock.now.plusSeconds(60);
        assertTrue(svc.sample(MAC).isPresent());
        var m = svc.metrics(MAC.id());
        assertEquals(2, m.cpuPercent().size());
        assertEquals(2, m.memoryPercent().size());
        double cpu = m.cpuPercent().getLast().value();
        assertTrue(cpu > 0 && cpu < 100, "cpu " + cpu);
        assertEquals(cpu, svc.latestCpu().get(MAC.id()), 0.0001);

        clock.now = clock.now.plus(Duration.ofMinutes(61));
        svc.sample(MAC);
        assertEquals(1, svc.metrics(MAC.id()).cpuPercent().size(), "older than an hour is dropped");

        svc.forget(MAC.id());
        assertTrue(svc.latestCpu().isEmpty());
    }

    @Test
    void notConfiguredMeansNoSample() {
        TerminalService real = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                new DemoShellConnector(Duration.ZERO, Duration.ofMillis(50)), new InMemorySavedCommandStore(List.of()), false);
        SshMetricsService svc = new SshMetricsService(real, Clock.systemUTC());
        assertFalse(svc.canSample(MAC));
        assertTrue(svc.sample(MAC).isEmpty());
    }

    @Test
    void oneShotParsesLinuxAndMacOutput() {
        String linux = "cpu  100 0 100 800 0 0 0 0\nMemTotal: 1000 kB\nMemAvailable: 600 kB\n  eth0: 100 0 0 0 0 0 0 0 50\n---\n"
                + "cpu  150 0 150 900 0 0 0 0\nMemTotal: 1000 kB\nMemAvailable: 500 kB\n  eth0: 1100 0 0 0 0 0 0 0 550\n---\n";
        var s = ProcStats.parseOneShot(linux, Instant.EPOCH.plusSeconds(10)).orElseThrow();
        assertEquals(50, s.cpuPercent(), 0.01);
        assertEquals(50, s.memoryPercent(), 0.01);
        assertEquals(1000, s.rxBytesPerSec(), 0.01);

        String mac = "@cpu 7\nMemTotal: 2000 kB\nMemAvailable: 1500 kB\nen0: 10 0 0 0 0 0 0 0 10\n---\n"
                + "@cpu 9\nMemTotal: 2000 kB\nMemAvailable: 1000 kB\nen0: 20 0 0 0 0 0 0 0 30\n---\n";
        var m = ProcStats.parseOneShot(mac, Instant.EPOCH.plusSeconds(10)).orElseThrow();
        assertEquals(9, m.cpuPercent(), 0.01);
        assertEquals(50, m.memoryPercent(), 0.01);
        assertTrue(ProcStats.parseOneShot("ssh: connect failed", Instant.EPOCH).isEmpty());
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
