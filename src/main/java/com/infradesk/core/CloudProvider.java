package com.infradesk.core;

import java.util.List;

/**
 * The only entry point UI and service code use to talk to a cloud. One instance serves one
 * {@link Account}. All methods block on network I/O and must never be called on the EDT.
 */
public interface CloudProvider {

    /** Lists servers in the account. */
    List<Server> listServers();

    void start(String serverId);

    void stop(String serverId);

    void reboot(String serverId);

    /** Returns recent utilization, roughly the last hour. */
    Metrics getMetrics(String serverId);
}
