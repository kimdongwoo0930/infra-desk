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
 * Fake provider for demo mode. Simulates network latency and lifecycle transitions: start/stop/
 * reboot move the server into a transitional state that settles after {@link #TRANSITION}.
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
        return Metrics.EMPTY;
    }

    private void transition(String serverId, ServerStatus required, ServerStatus via, ServerStatus target) {
        simulateLatency();
        Server server = current(serverId);
        if (server.status() != required) {
            throw new CloudProviderException(server.name() + "은(는) 지금 " + server.status().label() + " 상태라 요청을 처리할 수 없어요");
        }
        states.put(serverId, new State(server.withStatus(via), target, clock.instant().plus(TRANSITION)));
    }

    /** Current server, settling any finished transition. */
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
