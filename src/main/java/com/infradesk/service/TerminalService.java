package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.ssh.HostKeyPrompt;
import com.infradesk.ssh.ProcStats;
import com.infradesk.ssh.ShellConnector;
import com.infradesk.ssh.ShellSession;
import com.infradesk.ssh.SshException;
import com.infradesk.ssh.SshSettings;
import com.infradesk.ssh.SshTarget;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ssh.SavedCommand;
import com.infradesk.storage.SavedCommandStore;
import com.infradesk.storage.SecretStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final SavedCommandStore commandStore;
    private final boolean demo;

    public TerminalService(SshSettingsStore settingsStore, SecretStore secretStore, ShellConnector connector,
                           SavedCommandStore commandStore, boolean demo) {
        this.settingsStore = settingsStore;
        this.secretStore = secretStore;
        this.connector = connector;
        this.commandStore = commandStore;
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
        return connector.open(target(server), prompt, columns, rows);
    }

    /** Output kept per server for batch runs; the rest is dropped. */
    public static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /**
     * Runs a command without a PTY and waits for it. Never throws: connection and timeout
     * failures come back as {@link ExecResult#failure}.
     */
    public ExecResult run(Server server, String command, HostKeyPrompt prompt, Duration timeout) {
        ShellSession session;
        try {
            session = connector.exec(target(server), prompt, command);
        } catch (RuntimeException e) {
            return ExecResult.failure(e.getMessage());
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(timeout);
                timedOut.set(true);
                session.close();
            } catch (InterruptedException ignored) {
                // Finished in time.
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream in = session.output()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                int room = MAX_OUTPUT_BYTES - out.size();
                if (room > 0) {
                    out.write(buf, 0, Math.min(n, room));
                }
                truncated |= n > room;
            }
            int exit = session.waitFor();
            String text = out.toString(StandardCharsets.UTF_8);
            if (timedOut.get()) {
                return new ExecResult(-1, text, timeout.toSeconds() + "초 안에 끝나지 않아 중단했어요", truncated);
            }
            return new ExecResult(exit, text, null, truncated);
        } catch (IOException e) {
            return new ExecResult(-1, out.toString(StandardCharsets.UTF_8),
                    timedOut.get() ? timeout.toSeconds() + "초 안에 끝나지 않아 중단했어요" : "출력을 읽지 못했어요: " + e.getMessage(),
                    truncated);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.failure("중단됐어요");
        } finally {
            watchdog.interrupt();
            session.close();
        }
    }

    public List<SavedCommand> savedCommands() {
        return commandStore.load();
    }

    public void saveCommands(List<SavedCommand> commands) {
        commandStore.save(commands);
    }

    /** Starts streaming /proc snapshots from the server (see {@link ProcStats#COMMAND}). */
    public ShellSession openStats(Server server, HostKeyPrompt prompt) {
        return connector.exec(target(server), prompt, ProcStats.COMMAND);
    }

    private SshTarget target(Server server) {
        if (demo) {
            SshSettings s = settingsStore.get(server.id()).orElse(new SshSettings(server.id(), "ubuntu", 22));
            return new SshTarget("demo:" + server.name(), s.port(), s.username(), "demo", null);
        }
        String host = server.publicIpAddress().orElseThrow(() -> new SshException(SshException.Kind.CONNECT,
                server.name() + "에 공인 IP가 없어요. 공인 IP를 붙이거나 사설망(VPN)으로 접속하세요."));
        SshSettings s = settingsStore.get(server.id()).orElseThrow(() -> new SshException(SshException.Kind.AUTH,
                server.name() + "의 SSH 설정이 없어요. SSH 키를 먼저 등록하세요."));
        String key = secretStore.get(serverKey(server.id(), SSH_KEY)).orElseThrow(() -> new SshException(SshException.Kind.AUTH,
                server.name() + "의 SSH 키가 없어요. SSH 키를 먼저 등록하세요."));
        String passphrase = secretStore.get(serverKey(server.id(), SSH_PASSPHRASE)).orElse(null);
        return new SshTarget(host, s.port(), s.username(), key, passphrase);
    }

    private static String serverKey(String serverId, String name) {
        return "server." + serverId + "." + name;
    }
}
