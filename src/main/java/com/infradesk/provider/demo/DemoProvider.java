package com.infradesk.provider.demo;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.Metrics;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 데모 모드용 가짜 provider. 네트워크 지연과 생명주기 전이를 흉내 낸다. 시작/정지/재부팅은
 * 서버를 전이 상태로 만들고, {@link #TRANSITION}이 지나면 안정된다.
 */
public class DemoProvider implements CloudProvider {

    static final Duration TRANSITION = Duration.ofSeconds(8);

    private record State(Server server, ServerStatus target, Instant settlesAt) {
    }

    private final Clock clock;
    private final Duration latency;
    private final Map<String, State> states = new LinkedHashMap<>();

    public DemoProvider(Account account, Clock clock, Duration latency) {
        this.clock = clock;
        this.latency = latency;
        for (Server s : DemoData.serversFor(account)) {
            states.put(s.id(), new State(s, null, null));
        }
    }

    @Override
    public synchronized List<Server> listServers() {
        simulateLatency();
        List<Server> result = new ArrayList<>();
        for (String id : states.keySet()) {
            result.add(current(id));
        }
        return result;
    }

    @Override
    public synchronized void start(String serverId) {
        transition(serverId, ServerStatus.STOPPED, ServerStatus.STARTING, ServerStatus.RUNNING);
    }

    @Override
    public synchronized void stop(String serverId) {
        transition(serverId, ServerStatus.RUNNING, ServerStatus.STOPPING, ServerStatus.STOPPED);
    }

    @Override
    public synchronized void reboot(String serverId) {
        transition(serverId, ServerStatus.RUNNING, ServerStatus.REBOOTING, ServerStatus.RUNNING);
    }

    @Override
    public Metrics getMetrics(String serverId) {
        simulateLatency();
        synchronized (this) {
            if (current(serverId).status() != ServerStatus.RUNNING) {
                return Metrics.EMPTY;
            }
        }
        return DemoMetrics.forServer(serverId, clock.instant());
    }

    @Override
    public Metrics getMetrics(String serverId, java.time.Duration range) {
        simulateLatency();
        synchronized (this) {
            if (current(serverId).status() != ServerStatus.RUNNING) {
                return Metrics.EMPTY;
            }
        }
        return DemoMetrics.forServer(serverId, clock.instant(), range);
    }

    @Override
    public java.util.Map<String, Double> currentCpu(List<Server> servers) {
        simulateLatency();
        java.util.Map<String, Double> result = new java.util.HashMap<>();
        for (Server s : servers) {
            synchronized (this) {
                if (!states.containsKey(s.id()) || current(s.id()).status() != ServerStatus.RUNNING) {
                    continue;
                }
            }
            DemoMetrics.forServer(s.id(), clock.instant()).latestCpu().ifPresent(v -> result.put(s.id(), v));
        }
        return result;
    }

    private void transition(String serverId, ServerStatus required, ServerStatus via, ServerStatus target) {
        simulateLatency();
        Server server = current(serverId);
        if (server.status() != required) {
            throw new CloudProviderException(server.name() + "은(는) 지금 " + server.status().label() + " 상태라 요청을 처리할 수 없어요");
        }
        states.put(serverId, new State(server.withStatus(via), target, clock.instant().plus(TRANSITION)));
    }

    /** 현재 서버. 끝난 전이가 있으면 반영한다. */
    private Server current(String serverId) {
        State state = states.get(serverId);
        if (state == null) {
            throw new CloudProviderException("서버를 찾을 수 없어요: " + serverId);
        }
        if (state.target() != null && !clock.instant().isBefore(state.settlesAt())) {
            state = new State(state.server().withStatus(state.target()), null, null);
            states.put(serverId, state);
        }
        return state.server();
    }

    private void simulateLatency() {
        if (latency.isZero()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
