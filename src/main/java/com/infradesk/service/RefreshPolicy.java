package com.infradesk.service;

import com.infradesk.core.Server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides when to poll next and which accounts to poll. Normal polling reloads every account;
 * fast polling reloads only accounts with a server in transition or a recent action.
 *
 * <p>After an action, OCI may keep reporting the old state for a few seconds, so the account stays
 * in fast mode for {@link #ACTION_GRACE} even if nothing looks transitional yet.
 */
public class RefreshPolicy {

    public static final Duration NORMAL_INTERVAL = Duration.ofSeconds(45);
    public static final Duration FAST_INTERVAL = Duration.ofSeconds(5);
    public static final Duration ACTION_GRACE = Duration.ofSeconds(20);
    /** Stop fast polling eventually even if a server is stuck in a transitional state. */
    public static final Duration FAST_LIMIT = Duration.ofMinutes(10);

    /** What to do next. {@code accountIds} empty means "all accounts". */
    public record Plan(Duration delay, Set<String> accountIds) {

        public boolean isFull() {
            return accountIds.isEmpty();
        }
    }

    private final Clock clock;
    private final Map<String, Instant> actionAt = new HashMap<>();
    private final Map<String, Instant> fastSince = new HashMap<>();

    public RefreshPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Records that an action was sent for a server in the given account. */
    public synchronized void actionSent(String accountId) {
        Instant now = clock.instant();
        actionAt.put(accountId, now);
        fastSince.putIfAbsent(accountId, now);
    }

    /** Plans the next poll from the latest loaded data. */
    public synchronized Plan next(List<AccountInventory> inventory) {
        Instant now = clock.instant();
        Set<String> transitional = inventory.stream()
                .filter(inv -> inv.servers().stream().map(Server::status).anyMatch(s -> s.isTransitional()))
                .map(inv -> inv.account().id())
                .collect(Collectors.toSet());

        actionAt.values().removeIf(t -> t.plus(ACTION_GRACE).isBefore(now));
        Set<String> fast = new java.util.HashSet<>(transitional);
        fast.addAll(actionAt.keySet());

        for (String id : fast) {
            fastSince.putIfAbsent(id, now);
        }
        fastSince.keySet().retainAll(fast);
        fast.removeIf(id -> fastSince.get(id).plus(FAST_LIMIT).isBefore(now));

        if (fast.isEmpty()) {
            return new Plan(NORMAL_INTERVAL, Set.of());
        }
        return new Plan(FAST_INTERVAL, Set.copyOf(fast));
    }
}
