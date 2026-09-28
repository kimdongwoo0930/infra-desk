package com.infradesk.provider.ssh;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.Metrics;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.core.SshHostProperties;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshHostProviderTest {

    private static Account account(String host, int port) {
        return new Account("home", "맥미니", ProviderType.SSH, SshHostProperties.REGION, SshHostProperties.of(host, port));
    }

    @Test
    void oneServerWhoseStatusIsReachability() {
        SshHostProvider up = new SshHostProvider(account("mac-mini", 2222), (h, p) -> h.equals("mac-mini") && p == 2222);
        Server s = up.listServers().getFirst();
        assertEquals(SshHostProperties.serverId("home"), s.id());
        assertEquals("맥미니", s.name());
        assertEquals(ServerStatus.RUNNING, s.status());
        assertEquals("mac-mini", s.publicIp(), "SSH connects to this address");
        assertEquals("mac-mini:2222", s.shape());

        SshHostProvider down = new SshHostProvider(account("mac-mini", 22), (h, p) -> false);
        assertEquals(ServerStatus.UNREACHABLE, down.listServers().getFirst().status());
    }

    @Test
    void noPowerControlOrCloudMetrics() {
        SshHostProvider p = new SshHostProvider(account("h", 22), (h, port) -> true);
        assertThrows(CloudProviderException.class, () -> p.stop("x"));
        assertThrows(CloudProviderException.class, () -> p.start("x"));
        assertThrows(CloudProviderException.class, () -> p.reboot("x"));
        assertEquals(Metrics.EMPTY, p.getMetrics("x"));
        assertTrue(p.currentCpu(List.of()).isEmpty());
        assertFalse(ProviderType.SSH.hasPowerControl());
        assertFalse(ProviderType.SSH.hasCloudMetrics());
        assertTrue(ProviderType.ORACLE.hasPowerControl());
    }

    @Test
    void tcpReachabilityAgainstALocalPort() throws Exception {
        try (ServerSocket open = new ServerSocket(0)) {
            assertTrue(SshHostProviderFactory.tcpConnect("127.0.0.1", open.getLocalPort()));
        }
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertFalse(SshHostProviderFactory.tcpConnect("127.0.0.1", closed));
        assertFalse(SshHostProviderFactory.tcpConnect("no-such-host.invalid", 22));
    }

    @Test
    void propertiesValidateTheAddress() {
        assertTrue(SshHostProperties.isValidHost("mac-mini"));
        assertTrue(SshHostProperties.isValidHost("192.168.0.20"));
        assertTrue(SshHostProperties.isValidHost("mac-mini.tail1234.ts.net"));
        assertTrue(SshHostProperties.isValidHost("fd7a:115c:a1e0::1"));
        assertFalse(SshHostProperties.isValidHost("-oProxyCommand=x"));
        assertFalse(SshHostProperties.isValidHost("host name"));
        assertFalse(SshHostProperties.isValidHost("a;b"));
        assertEquals(22, SshHostProperties.port(new Account("x", "x", ProviderType.SSH, "ssh", java.util.Map.of())));
    }
}
