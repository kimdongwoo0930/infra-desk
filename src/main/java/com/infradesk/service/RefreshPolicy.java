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
 * 다음에 언제 조회할지와 어느 계정을 조회할지 결정한다. 일반 폴링은 모든 계정을 다시 불러오고,
 * 빠른 폴링은 전이 중인 서버가 있거나 최근에 동작을 보낸 계정만 다시 불러온다.
 *
 * <p>동작을 보낸 뒤에는 OCI가 몇 초 동안 이전 상태를 계속 알려줄 수 있으므로, 아직 전이처럼
 * 보이지 않아도 {@link #ACTION_GRACE} 동안은 그 계정을 빠른 모드로 유지한다.
 */
public class RefreshPolicy {

    public static final Duration NORMAL_INTERVAL = Duration.ofSeconds(30);
    public static final Duration FAST_INTERVAL = Duration.ofSeconds(5);
    public static final Duration ACTION_GRACE = Duration.ofSeconds(20);
    /** 서버가 전이 상태에서 멈춰 있더라도 빠른 폴링은 결국 멈춘다. */
    public static final Duration FAST_LIMIT = Duration.ofMinutes(10);

    /** 다음에 할 일. {@code accountIds}가 비어 있으면 "모든 계정"을 뜻한다. */
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

    /** 주어진 계정의 서버에 동작을 보냈음을 기록한다. */
    public synchronized void actionSent(String accountId) {
        Instant now = clock.instant();
        actionAt.put(accountId, now);
        fastSince.putIfAbsent(accountId, now);
    }

    /** 최근에 불러온 데이터로 다음 조회를 계획한다. */
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
