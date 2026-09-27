package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.DemoShellConnector;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ssh.HostKeyPrompt;
import com.infradesk.ssh.ShellConnector;
import com.infradesk.ssh.ShellSession;
import com.infradesk.ssh.SshException;
import com.infradesk.ssh.SshTarget;
import com.infradesk.storage.InMemorySavedCommandStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.InMemorySshSettingsStore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalServiceRunTest {

    private static final Server BOT = new Server("srv-1", "acc", "bot", ServerStatus.RUNNING, "r", "s", 1, 1,
            "203.0.113.9", null, null);
    private static final HostKeyPrompt TRUST = (h, p, t, f) -> true;

    private static TerminalService demo() {
        return new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                new DemoShellConnector(Duration.ZERO, Duration.ofMillis(50)), new InMemorySavedCommandStore(List.of()), true);
    }

    @Test
    void runsCommandAndReportsOutputAndExitCode() {
        ExecResult r = demo().run(BOT, "whoami", TRUST, Duration.ofSeconds(5));
        assertTrue(r.succeeded());
        assertEquals("ubuntu\n", r.output());
        assertNull(r.error());
    }

    @Test
    void unknownCommandFails() {
        ExecResult r = demo().run(BOT, "nope", TRUST, Duration.ofSeconds(5));
        assertFalse(r.succeeded());
        assertEquals(127, r.exitCode());
    }

    @Test
    void connectionFailureBecomesResultNotException() {
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                new ShellConnector() {
                    @Override
                    public ShellSession open(SshTarget t, HostKeyPrompt p, int c, int r) {
                        throw new SshException(SshException.Kind.CONNECT, "연결 거부");
                    }

                    @Override
                    public ShellSession exec(SshTarget t, HostKeyPrompt p, String cmd) {
                        throw new SshException(SshException.Kind.CONNECT, "연결 거부");
                    }

                    @Override
                    public com.infradesk.ssh.RemoteFiles sftp(SshTarget t, HostKeyPrompt p) {
                        throw new SshException(SshException.Kind.CONNECT, "연결 거부");
                    }
                }, new InMemorySavedCommandStore(List.of()), true);
        ExecResult r = svc.run(BOT, "uptime", TRUST, Duration.ofSeconds(5));
        assertEquals("연결 거부", r.error());
    }

    @Test
    void hangingCommandIsStoppedAtTimeout() {
        CountDownLatch closed = new CountDownLatch(1);
        ShellSession hanging = new ShellSession() {
            private final java.io.PipedInputStream in = new java.io.PipedInputStream();
            private final java.io.PipedOutputStream feed;

            {
                try {
                    feed = new java.io.PipedOutputStream(in);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public InputStream output() {
                return in;
            }

            @Override
            public OutputStream input() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public void resize(int c, int r) {
            }

            @Override
            public boolean isOpen() {
                return closed.getCount() > 0;
            }

            @Override
            public int waitFor() {
                return -1;
            }

            @Override
            public String address() {
                return "x";
            }

            @Override
            public void close() {
                closed.countDown();
                try {
                    feed.close();
                } catch (java.io.IOException ignored) {
                }
            }
        };
        TerminalService svc = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                new ShellConnector() {
                    @Override
                    public ShellSession open(SshTarget t, HostKeyPrompt p, int c, int r) {
                        return hanging;
                    }

                    @Override
                    public ShellSession exec(SshTarget t, HostKeyPrompt p, String cmd) {
                        return hanging;
                    }

                    @Override
                    public com.infradesk.ssh.RemoteFiles sftp(SshTarget t, HostKeyPrompt p) {
                        throw new UnsupportedOperationException();
                    }
                }, new InMemorySavedCommandStore(List.of()), true);
        ExecResult r = svc.run(BOT, "sleep 999", TRUST, Duration.ofMillis(200));
        assertTrue(r.error().contains("끝나지 않아"), r.error());
        assertEquals(0, closed.getCount());
    }

    @Test
    void savedCommandsRoundTrip() {
        TerminalService svc = demo();
        var cmd = new com.infradesk.ssh.SavedCommand("1", "디스크", "df -h");
        svc.saveCommands(List.of(cmd));
        assertEquals(List.of(cmd), svc.savedCommands());
    }
}
