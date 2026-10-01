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
 * SSH로 직접 닿는 기기(집 서버, Tailscale 위의 Mac mini). 클라우드 API가 없다.
 * 서버 하나의 상태는 SSH 포트가 연결을 받는지 여부이고, 전원 제어와 클라우드 메트릭은
 * 존재하지 않는다(대신 실시간 모드가 SSH로 읽는다).
 */
public class SshHostProvider implements CloudProvider {

    /** host:port가 TCP 연결을 받는지 알려준다. 블로킹. */
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
