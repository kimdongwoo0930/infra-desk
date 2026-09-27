package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.ShellConnector;
import com.infradesk.ssh.SshException;
import com.infradesk.ssh.SshSettings;
import com.infradesk.ssh.SshTarget;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.InMemorySshSettingsStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalServiceTest {

    private static final Server WITH_IP = new Server("srv-1", "acc", "bot", ServerStatus.RUNNING, "r", "s", 1, 1,
            "203.0.113.9", "10.0.0.9", null);
    private static final Server NO_IP = new Server("srv-2", "acc", "internal", ServerStatus.RUNNING, "r", "s", 1, 1,
            null, "10.0.0.10", null);
    private static final String KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----";

    private final AtomicReference<SshTarget> lastTarget = new AtomicReference<>();
    private final ShellConnector capturing = new ShellConnector() {
        @Override
        public com.infradesk.ssh.ShellSession open(SshTarget target, com.infradesk.ssh.HostKeyPrompt prompt, int cols, int rows) {
            lastTarget.set(target);
            return null;
        }

        @Override
        public com.infradesk.ssh.ShellSession exec(SshTarget target, com.infradesk.ssh.HostKeyPrompt prompt, String command) {
            lastTarget.set(target);
            return null;
        }
    };

    @Test
    void notConfiguredUntilSettingsAndKeyAreSaved() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), capturing, new com.infradesk.storage.InMemorySavedCommandStore(java.util.List.of()), false);
        assertFalse(svc.isConfigured("srv-1"));
        svc.save(new SshSettings("srv-1", "ubuntu", 22), KEY, "");
        assertTrue(svc.isConfigured("srv-1"));
    }

    @Test
    void opensWithPublicIpSavedUserPortKeyAndPassphrase() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), capturing, new com.infradesk.storage.InMemorySavedCommandStore(java.util.List.of()), false);
        svc.save(new SshSettings("srv-1", "opc", 2222), KEY, "secret");
        svc.open(WITH_IP, (h, p, t, f) -> true, 80, 24);
        SshTarget t = lastTarget.get();
        assertEquals("203.0.113.9", t.host());
        assertEquals(2222, t.port());
        assertEquals("opc", t.username());
        assertEquals(KEY, t.privateKeyPem());
        assertEquals("secret", t.passphrase());
    }

    @Test
    void savingWithoutNewKeyKeepsOldKeyAndEmptyPassphraseClearsIt() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), capturing, new com.infradesk.storage.InMemorySavedCommandStore(java.util.List.of()), false);
        svc.save(new SshSettings("srv-1", "ubuntu", 22), KEY, "secret");
        svc.save(new SshSettings("srv-1", "ubuntu", 22), null, "");
        svc.open(WITH_IP, (h, p, t, f) -> true, 80, 24);
        assertEquals(KEY, lastTarget.get().privateKeyPem());
        assertNull(lastTarget.get().passphrase());
    }

    @Test
    void serverWithoutPublicIpFailsClearly() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), capturing, new com.infradesk.storage.InMemorySavedCommandStore(java.util.List.of()), false);
        svc.save(new SshSettings("srv-2", "ubuntu", 22), KEY, "");
        SshException e = assertThrows(SshException.class, () -> svc.open(NO_IP, (h, p, t, f) -> true, 80, 24));
        assertTrue(e.getMessage().contains("공인 IP"));
    }

    @Test
    void forgetRemovesSettingsAndKey() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), capturing, new com.infradesk.storage.InMemorySavedCommandStore(java.util.List.of()), false);
        svc.save(new SshSettings("srv-1", "ubuntu", 22), KEY, "");
        svc.forget("srv-1");
        assertFalse(svc.isConfigured("srv-1"));
        assertFalse(svc.hasKey("srv-1"));
    }

    @Test
    void demoModeNeedsNoSettings() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), capturing, new com.infradesk.storage.InMemorySavedCommandStore(java.util.List.of()), true);
        assertTrue(svc.isConfigured("anything"));
        svc.open(NO_IP, (h, p, t, f) -> true, 80, 24);
        assertEquals("ubuntu", lastTarget.get().username());
    }
}
