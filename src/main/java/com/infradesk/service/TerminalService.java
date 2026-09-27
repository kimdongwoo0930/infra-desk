package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.ssh.HostKeyPrompt;
import com.infradesk.ssh.ShellConnector;
import com.infradesk.ssh.ShellSession;
import com.infradesk.ssh.SshException;
import com.infradesk.ssh.SshSettings;
import com.infradesk.ssh.SshTarget;
import com.infradesk.storage.SecretStore;
import com.infradesk.storage.SshSettingsStore;

import java.util.Optional;

/**
 * SSH settings per server and opening shells. Independent of {@link com.infradesk.core.CloudProvider}:
 * it only needs a server's address. Blocking; call off the EDT.
 */
public class TerminalService {

    public static final String SSH_KEY = "sshKey";
    public static final String SSH_PASSPHRASE = "sshPassphrase";

    private final SshSettingsStore settingsStore;
    private final SecretStore secretStore;
    private final ShellConnector connector;
    private final boolean demo;

    public TerminalService(SshSettingsStore settingsStore, SecretStore secretStore, ShellConnector connector, boolean demo) {
        this.settingsStore = settingsStore;
        this.secretStore = secretStore;
        this.connector = connector;
        this.demo = demo;
    }

    public Optional<SshSettings> settings(String serverId) {
        return settingsStore.get(serverId);
    }

    /** Whether the server can be connected to without asking for settings first. */
    public boolean isConfigured(String serverId) {
        return demo || (settingsStore.get(serverId).isPresent() && secretStore.get(serverKey(serverId, SSH_KEY)).isPresent());
    }

    public boolean hasKey(String serverId) {
        return secretStore.get(serverKey(serverId, SSH_KEY)).isPresent();
    }

    /**
     * Saves settings. {@code privateKeyPem} null keeps the stored key; an empty passphrase removes it.
     */
    public void save(SshSettings settings, String privateKeyPem, String passphrase) {
        if (privateKeyPem != null) {
            secretStore.put(serverKey(settings.serverId(), SSH_KEY), privateKeyPem);
        }
        if (passphrase != null) {
            if (passphrase.isEmpty()) {
                secretStore.delete(serverKey(settings.serverId(), SSH_PASSPHRASE));
            } else {
                secretStore.put(serverKey(settings.serverId(), SSH_PASSPHRASE), passphrase);
            }
        }
        settingsStore.put(settings);
    }

    public void forget(String serverId) {
        settingsStore.delete(serverId);
        secretStore.delete(serverKey(serverId, SSH_KEY));
        secretStore.delete(serverKey(serverId, SSH_PASSPHRASE));
    }

    /** Opens a shell to the server's public IP with its saved settings. */
    public ShellSession open(Server server, HostKeyPrompt prompt, int columns, int rows) {
        if (demo) {
            SshSettings s = settingsStore.get(server.id()).orElse(new SshSettings(server.id(), "ubuntu", 22));
            return connector.open(new SshTarget("demo:" + server.name(), s.port(), s.username(), "demo", null),
                    prompt, columns, rows);
        }
        String host = server.publicIpAddress().orElseThrow(() -> new SshException(SshException.Kind.CONNECT,
                server.name() + "에 공인 IP가 없어요. 공인 IP를 붙이거나 사설망(VPN)으로 접속하세요."));
        SshSettings s = settingsStore.get(server.id()).orElseThrow(() -> new SshException(SshException.Kind.AUTH,
                server.name() + "의 SSH 설정이 없어요. SSH 키를 먼저 등록하세요."));
        String key = secretStore.get(serverKey(server.id(), SSH_KEY)).orElseThrow(() -> new SshException(SshException.Kind.AUTH,
                server.name() + "의 SSH 키가 없어요. SSH 키를 먼저 등록하세요."));
        String passphrase = secretStore.get(serverKey(server.id(), SSH_PASSPHRASE)).orElse(null);
        return connector.open(new SshTarget(host, s.port(), s.username(), key, passphrase), prompt, columns, rows);
    }

    private static String serverKey(String serverId, String name) {
        return "server." + serverId + "." + name;
    }
}
