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

    /** 접속 시도를 세고 정해 둔 실패로 답하는 가짜 접속기. 서버에 몇 번 접속했는지 확인하려는 것이다. */
    private static final class FailingConnector implements com.infradesk.ssh.ShellConnector {
        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        volatile com.infradesk.ssh.SshException.Kind kind;

        @Override
        public com.infradesk.ssh.ShellSession open(com.infradesk.ssh.SshTarget target,
                                                   com.infradesk.ssh.HostKeyPrompt prompt, int columns, int rows) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.infradesk.ssh.ShellSession exec(com.infradesk.ssh.SshTarget target,
                                                   com.infradesk.ssh.HostKeyPrompt prompt, String command) {
            attempts.incrementAndGet();
            throw new com.infradesk.ssh.SshException(kind, "접속 실패 " + kind);
        }

        @Override
        public com.infradesk.ssh.RemoteFiles sftp(com.infradesk.ssh.SshTarget target,
                                                  com.infradesk.ssh.HostKeyPrompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    private static TerminalService realTerminal(FailingConnector connector) {
        TerminalService terminal = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                connector, new InMemorySavedCommandStore(List.of()), false);
        terminal.save(new com.infradesk.ssh.SshSettings(MAC.id(), "me", 22), "FAKE-KEY-FOR-TEST", null);
        return terminal;
    }

    @Test
    void stopsConnectingAfterRepeatedAuthenticationFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        FailingConnector connector = new FailingConnector();
        connector.kind = com.infradesk.ssh.SshException.Kind.AUTH;
        SshMetricsService svc = new SshMetricsService(realTerminal(connector), clock);

        assertTrue(svc.sample(MAC).isEmpty());
        assertEquals(1, connector.attempts.get());
        assertTrue(svc.blockedReason(MAC.id()).isEmpty(), "한 번으로는 멈추지 않는다");

        clock.now = clock.now.plus(Duration.ofMinutes(3));
        svc.sample(MAC);
        assertEquals(2, connector.attempts.get());
        assertTrue(svc.blockedReason(MAC.id()).orElseThrow().contains("SSH 키"), "멈춘 이유를 알려준다");

        for (int i = 0; i < 100; i++) {
            clock.now = clock.now.plus(Duration.ofMinutes(1));
            assertTrue(svc.sample(MAC).isEmpty());
        }
        assertEquals(2, connector.attempts.get(), "100분 동안 서버에 한 번도 접속하지 않았다");

        svc.reset(MAC.id());
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        svc.sample(MAC);
        assertEquals(3, connector.attempts.get(), "사용자가 고치면(reset) 다시 시도한다");
    }

    @Test
    void anUntrustedHostKeyStopsAtOnceAndNeverPrompts() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        FailingConnector connector = new FailingConnector();
        connector.kind = com.infradesk.ssh.SshException.Kind.HOST_KEY_REJECTED;
        SshMetricsService svc = new SshMetricsService(realTerminal(connector), clock);

        svc.sample(MAC);
        assertEquals(1, connector.attempts.get());
        assertTrue(svc.blockedReason(MAC.id()).orElseThrow().contains("호스트 키"));

        clock.now = clock.now.plus(Duration.ofHours(3));
        svc.sample(MAC);
        assertEquals(1, connector.attempts.get(), "믿지 않은 호스트 키는 몇 번을 시도해도 같다");
    }

    @Test
    void connectionProblemsBackOffInsteadOfRetryingEveryMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        FailingConnector connector = new FailingConnector();
        connector.kind = com.infradesk.ssh.SshException.Kind.CONNECT;
        SshMetricsService svc = new SshMetricsService(realTerminal(connector), clock);

        // 1분마다 불러도 서버에 실제로 접속하는 횟수는 훨씬 적다.
        for (int minute = 0; minute < 60; minute++) {
            svc.sample(MAC);
            clock.now = clock.now.plus(Duration.ofMinutes(1));
        }

        assertTrue(connector.attempts.get() <= 6, "60번 요청했는데 접속은 " + connector.attempts.get() + "번");
        assertTrue(svc.blockedReason(MAC.id()).isEmpty(), "연결 문제는 멈추지 않고 간격만 늘린다");
    }

    @Test
    void hiddenWindowSamplesOnlyEveryFiveMinutes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        SshMetricsService svc = new SshMetricsService(demoTerminal(), clock);

        int samples = 0;
        for (int minute = 0; minute < 30; minute++) {
            if (svc.sample(MAC, false).isPresent()) {
                samples++;
            }
            clock.now = clock.now.plus(Duration.ofMinutes(1));
        }

        assertEquals(6, samples, "30분 동안 창이 숨어 있으면 6번(5분마다)");
    }

    @Test
    void visibleWindowKeepsTheOncePerMinuteRhythm() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        SshMetricsService svc = new SshMetricsService(demoTerminal(), clock);

        int samples = 0;
        for (int minute = 0; minute < 10; minute++) {
            if (svc.sample(MAC, true).isPresent()) {
                samples++;
            }
            clock.now = clock.now.plus(Duration.ofMinutes(1));
        }

        assertEquals(10, samples);
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
