package com.infradesk.alert;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMonitorTest {

    private static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Account ACC = new Account("a", "계정 A", ProviderType.ORACLE, "ap-seoul-1", Map.of());

    private final ManualClock clock = new ManualClock();
    private final AtomicReference<AlertSettings> settings = new AtomicReference<>(
            new AlertSettings(true, true, true, true, 90, 3));
    private final AlertMonitor monitor = new AlertMonitor(clock, settings::get);

    private static List<AccountInventory> inv(ServerStatus status) {
        return List.of(new AccountInventory(ACC, List.of(new Server("s1", "a", "bot", status, "ap-seoul-1", "shape",
                1, 1, null, null, null)), null));
    }

    @Test
    void startupStateNeverAlerts() {
        assertTrue(monitor.onInventory(inv(ServerStatus.STOPPED)).isEmpty());
    }

    @Test
    void unexpectedStopAlertsOnceThenRecovers() {
        monitor.onInventory(inv(ServerStatus.RUNNING));
        List<Alert> down = monitor.onInventory(inv(ServerStatus.STOPPING));
        assertEquals(1, down.size());
        assertEquals(Alert.Level.PROBLEM, down.getFirst().level());
        assertTrue(down.getFirst().title().contains("bot"));
        assertTrue(monitor.onInventory(inv(ServerStatus.STOPPED)).isEmpty(), "no repeat while down");

        monitor.onInventory(inv(ServerStatus.STARTING));
        List<Alert> up = monitor.onInventory(inv(ServerStatus.RUNNING));
        assertEquals(Alert.Level.RECOVERED, up.getFirst().level());
    }

    @Test
    void stopFromTheAppIsExpected() {
        monitor.onInventory(inv(ServerStatus.RUNNING));
        monitor.expectChange("s1");
        assertTrue(monitor.onInventory(inv(ServerStatus.STOPPING)).isEmpty());
        assertTrue(monitor.onInventory(inv(ServerStatus.RUNNING)).isEmpty(), "no recovery for an expected stop");
    }

    @Test
    void expectationExpires() {
        monitor.onInventory(inv(ServerStatus.RUNNING));
        monitor.expectChange("s1");
        clock.now = clock.now.plus(AlertMonitor.EXPECTED_WINDOW).plus(Duration.ofSeconds(1));
        assertEquals(1, monitor.onInventory(inv(ServerStatus.STOPPED)).size());
    }

    @Test
    void accountErrorAlertsOnceAndRecovers() {
        var failing = List.of(new AccountInventory(ACC, List.of(), "인증 실패"));
        assertEquals(1, monitor.onInventory(failing).size());
        assertTrue(monitor.onInventory(failing).isEmpty());
        assertEquals(Alert.Level.RECOVERED, monitor.onInventory(inv(ServerStatus.RUNNING)).getFirst().level());
    }

    @Test
    void cpuMustStayHighForTheConfiguredMinutes() {
        monitor.onInventory(inv(ServerStatus.RUNNING));
        assertTrue(monitor.onCpu(Map.of("s1", 95.0)).isEmpty());
        assertTrue(monitor.onCpu(Map.of("s1", 97.0)).isEmpty());
        List<Alert> high = monitor.onCpu(Map.of("s1", 96.0));
        assertEquals(1, high.size());
        assertTrue(high.getFirst().title().contains("bot"));
        assertTrue(monitor.onCpu(Map.of("s1", 99.0)).isEmpty(), "no repeat");
        assertEquals(Alert.Level.RECOVERED, monitor.onCpu(Map.of("s1", 40.0)).getFirst().level());
    }

    @Test
    void dipResetsTheStreak() {
        monitor.onCpu(Map.of("s1", 95.0));
        monitor.onCpu(Map.of("s1", 95.0));
        monitor.onCpu(Map.of("s1", 50.0));
        assertTrue(monitor.onCpu(Map.of("s1", 95.0)).isEmpty());
    }

    @Test
    void disabledSendsNothingButKeepsTracking() {
        settings.set(new AlertSettings(false, true, true, true, 90, 3));
        monitor.onInventory(inv(ServerStatus.RUNNING));
        assertTrue(monitor.onInventory(inv(ServerStatus.STOPPED)).isEmpty());
    }
}
