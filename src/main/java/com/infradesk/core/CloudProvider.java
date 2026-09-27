package com.infradesk.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only entry point UI and service code use to talk to a cloud. One instance serves one
 * {@link Account}. All methods block on network I/O and must never be called on the EDT.
 */
public interface CloudProvider extends AutoCloseable {

    /** Lists servers in the account. */
    List<Server> listServers();

    void start(String serverId);

    void stop(String serverId);

    void reboot(String serverId);

    /** Returns recent utilization at one-minute resolution, roughly the last hour. */
    Metrics getMetrics(String serverId);

    /**
     * Latest CPU utilization (0–100) per server id, for the sidebar. Servers without data are
     * absent. The default asks {@link #getMetrics} per server; providers that can fetch all
     * servers in one call should override it.
     */
    default Map<String, Double> currentCpu(List<Server> servers) {
        Map<String, Double> result = new HashMap<>();
        for (Server s : servers) {
            if (s.status() == ServerStatus.RUNNING) {
                getMetrics(s.id()).latestCpu().ifPresent(v -> result.put(s.id(), v));
            }
        }
        return result;
    }

    /** Releases SDK clients. */
    @Override
    default void close() {
    }
}
