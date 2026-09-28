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

/** Creates {@link SshHostProvider}s that check reachability with a plain TCP connect. */
public class SshHostProviderFactory implements CloudProviderFactory {

    private static final int TIMEOUT_MS = 3000;

    private final SshHostProvider.Reachability reachability;

    public SshHostProviderFactory(SshHostProvider.Reachability reachability) {
        this.reachability = reachability;
    }

    public SshHostProviderFactory() {
        this(SshHostProviderFactory::tcpConnect);
    }

    /** Opens and closes a TCP connection; nothing is sent. */
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
