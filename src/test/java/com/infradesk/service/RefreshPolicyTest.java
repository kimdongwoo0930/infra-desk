package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshPolicyTest {

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

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static AccountInventory inv(String accountId, ServerStatus status) {
        Account a = new Account(accountId, accountId, ProviderType.ORACLE, "r", Map.of());
        return new AccountInventory(a, List.of(new Server("s-" + accountId, accountId, "srv", status,
                "r", "shape", 1, 1, null, null, null)), null);
    }

    @Test
    void stableInventoryPollsEverythingSlowly() {
        RefreshPolicy p = new RefreshPolicy(new ManualClock());
        RefreshPolicy.Plan plan = p.next(List.of(inv("a", ServerStatus.RUNNING), inv("b", ServerStatus.STOPPED)));
        assertEquals(RefreshPolicy.NORMAL_INTERVAL, plan.delay());
        assertTrue(plan.isFull());
    }

    @Test
    void transitionalAccountPollsFastAlone() {
        RefreshPolicy p = new RefreshPolicy(new ManualClock());
        RefreshPolicy.Plan plan = p.next(List.of(inv("a", ServerStatus.STOPPING), inv("b", ServerStatus.RUNNING)));
        assertEquals(RefreshPolicy.FAST_INTERVAL, plan.delay());
        assertEquals(Set.of("a"), plan.accountIds());
    }

    @Test
    void actionKeepsFastModeForGracePeriodEvenIfStateLooksStable() {
        ManualClock clock = new ManualClock();
        RefreshPolicy p = new RefreshPolicy(clock);
        List<AccountInventory> stable = List.of(inv("a", ServerStatus.RUNNING));

        p.actionSent("a");
        assertEquals(Set.of("a"), p.next(stable).accountIds());

        clock.advance(RefreshPolicy.ACTION_GRACE.plusSeconds(1));
        assertTrue(p.next(stable).isFull());
    }

    @Test
    void stuckTransitionFallsBackToNormalAfterLimit() {
        ManualClock clock = new ManualClock();
        RefreshPolicy p = new RefreshPolicy(clock);
        List<AccountInventory> stuck = List.of(inv("a", ServerStatus.STOPPING));

        assertEquals(RefreshPolicy.FAST_INTERVAL, p.next(stuck).delay());
        clock.advance(RefreshPolicy.FAST_LIMIT.plusSeconds(1));
        assertEquals(RefreshPolicy.NORMAL_INTERVAL, p.next(stuck).delay());
    }
}
