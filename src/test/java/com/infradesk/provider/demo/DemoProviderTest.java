package com.infradesk.provider.demo;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoProviderTest {

    /** Clock the test advances by hand. */
    private static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

    @Test
    void stopGoesThroughStoppingThenSettles() {
        ManualClock clock = new ManualClock();
        Account a = DemoData.accounts().getFirst();
        DemoProvider p = new DemoProvider(a, clock, Duration.ZERO);
        Server s = p.listServers().getFirst();
        assertEquals(ServerStatus.RUNNING, s.status());

        p.stop(s.id());
        assertEquals(ServerStatus.STOPPING, p.listServers().getFirst().status());

        clock.now = clock.now.plus(DemoProvider.TRANSITION);
        assertEquals(ServerStatus.STOPPED, p.listServers().getFirst().status());
    }

    @Test
    void rejectsInvalidTransition() {
        Account c = DemoData.accounts().get(2); // test-box, stopped
        DemoProvider p = new DemoProvider(c, new ManualClock(), Duration.ZERO);
        String id = p.listServers().getFirst().id();
        assertThrows(CloudProviderException.class, () -> p.stop(id));
    }
}
