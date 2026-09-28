package com.infradesk.provider.ssh;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.Metrics;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.core.SshHostProperties;

import java.util.List;
import java.util.Map;

/**
 * A machine you reach directly over SSH (a home server, a Mac mini on Tailscale). There is no cloud
 * API: the one server's status is whether its SSH port accepts a connection, and power control and
 * cloud metrics don't exist (live mode reads them over SSH instead).
 */
public class SshHostProvider implements CloudProvider {

    /** Tells whether host:port accepts a TCP connection. Blocking. */
    public interface Reachability {
        boolean reachable(String host, int port);
    }

    private final Account account;
    private final Reachability reachability;

    public SshHostProvider(Account account, Reachability reachability) {
        this.account = account;
        this.reachability = reachability;
    }

    @Override
    public List<Server> listServers() {
        String host = SshHostProperties.host(account);
        int port = SshHostProperties.port(account);
        ServerStatus status = reachability.reachable(host, port) ? ServerStatus.RUNNING : ServerStatus.UNREACHABLE;
        return List.of(new Server(SshHostProperties.serverId(account.id()), account.id(), account.displayName(), status,
                "SSH", host + ":" + port, 0, 0, host, null, null));
    }

    @Override
    public void start(String serverId) {
        throw unsupported();
    }

    @Override
    public void stop(String serverId) {
        throw unsupported();
    }

    @Override
    public void reboot(String serverId) {
        throw unsupported();
    }

    @Override
    public Metrics getMetrics(String serverId) {
        return Metrics.EMPTY;
    }

    @Override
    public Map<String, Double> currentCpu(List<Server> servers) {
        return Map.of();
    }

    private CloudProviderException unsupported() {
        return new CloudProviderException("직접 연결한 서버는 앱에서 켜고 끌 수 없어요.");
    }
}
