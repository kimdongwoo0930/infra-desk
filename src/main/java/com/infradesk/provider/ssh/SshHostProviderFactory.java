package com.infradesk.provider.ssh;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderFactory;
import com.infradesk.core.SshHostProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/** 단순 TCP 연결로 도달 가능 여부를 확인하는 {@link SshHostProvider}를 만든다. */
public class SshHostProviderFactory implements CloudProviderFactory {

    private static final int TIMEOUT_MS = 3000;

    private final SshHostProvider.Reachability reachability;

    public SshHostProviderFactory(SshHostProvider.Reachability reachability) {
        this.reachability = reachability;
    }

    public SshHostProviderFactory() {
        this(SshHostProviderFactory::tcpConnect);
    }

    /** TCP 연결을 열고 닫는다. 아무것도 보내지 않는다. */
    static boolean tcpConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public CloudProvider create(Account account, Map<String, String> secrets) {
        return new SshHostProvider(account, reachability);
    }

    @Override
    public List<String> regions() {
        return List.of(SshHostProperties.REGION);
    }
}
