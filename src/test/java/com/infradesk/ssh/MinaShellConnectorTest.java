package com.infradesk.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 프로세스 안의 MINA SSH 서버에 연결한다. 네트워크도 실제 키도 쓰지 않는다. */
class MinaShellConnectorTest {

    @TempDir
    Path dir;

    private SshServer server;
    private MinaShellConnector connector;

    /** 받은 바이트를 모두 그대로 되돌려 준다. 로컬 에코가 있는 셸처럼. */
    private static final class EchoShell implements Command, Runnable {
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread.ofVirtual().start(this);
        }

        @Override
        public void run() {
            try {
                int b;
                while ((b = in.read()) != -1) {
                    out.write(b);
                    out.flush();
                    if (b == 'q') {
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
            exit.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }

    private int startServer(KeyPair clientKey, Path hostKeyFile) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));
        server.setPublickeyAuthenticator((user, key, session) ->
                user.equals("ubuntu") && KeyUtils.compareKeys(key, clientKey.getPublic()));
        server.setShellFactory(channel -> new EchoShell());
        server.setCommandFactory((channel, command) -> new ReportCommand(command));
        server.start();
        return server.getPort();
    }

    @AfterEach
    void stop() throws IOException {
        if (connector != null) {
            connector.close();
        }
        if (server != null) {
            server.stop(true);
        }
    }

    private static String pem(KeyPair kp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, "test", null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String roundTrip(ShellSession shell, String text) throws IOException {
        shell.input().write(text.getBytes(StandardCharsets.UTF_8));
        shell.input().flush();
        byte[] buf = shell.output().readNBytes(text.length());
        return new String(buf, StandardCharsets.UTF_8);
    }

    @Test
    void connectsWithRsaKeyAndRemembersHostKey() throws Exception {
        KeyPair key = rsa();
        int port = startServer(key, dir.resolve("host.ser"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        AtomicInteger prompts = new AtomicInteger();
        HostKeyPrompt trust = (host, p, type, fp) -> {
            prompts.incrementAndGet();
            assertTrue(fp.startsWith("SHA256:"));
            return true;
        };
        SshTarget target = new SshTarget("127.0.0.1", port, "ubuntu", pem(key), null);

        try (ShellSession shell = connector.open(target, trust, 80, 24)) {
            assertEquals("hello", roundTrip(shell, "hello"));
        }
        try (ShellSession shell = connector.open(target, trust, 80, 24)) {
            assertEquals("again", roundTrip(shell, "again"));
        }
        assertEquals(1, prompts.get(), "second connect uses known_hosts without asking");
        assertTrue(Files.readString(dir.resolve("known_hosts")).contains("127.0.0.1"));
    }

    @Test
    void connectsWithEd25519Key() throws Exception {
        KeyPair key = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        int port = startServer(key, dir.resolve("host.ser"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        try (ShellSession shell = connector.open(new SshTarget("127.0.0.1", port, "ubuntu", pem(key), null),
                (h, p, t, f) -> true, 80, 24)) {
            assertEquals("ed", roundTrip(shell, "ed"));
        }
    }

    @Test
    void rejectedHostKeyAborts() throws Exception {
        KeyPair key = rsa();
        int port = startServer(key, dir.resolve("host.ser"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        SshException e = assertThrows(SshException.class, () -> connector.open(
                new SshTarget("127.0.0.1", port, "ubuntu", pem(key), null), (h, p, t, f) -> false, 80, 24));
        assertEquals(SshException.Kind.HOST_KEY_REJECTED, e.kind());
    }

    @Test
    void changedHostKeyIsRefused() throws Exception {
        KeyPair key = rsa();
        int port = startServer(key, dir.resolve("host1.ser"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        SshTarget target = new SshTarget("127.0.0.1", port, "ubuntu", pem(key), null);
        connector.open(target, (h, p, t, f) -> true, 80, 24).close();

        // 같은 주소, 다른 호스트 키.
        server.stop(true);
        server = null;
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host2.ser")));
        server.setPublickeyAuthenticator((user, k, session) -> true);
        server.setShellFactory(channel -> new EchoShell());
        server.start();

        SshException e = assertThrows(SshException.class, () -> connector.open(target, (h, p, t, f) -> true, 80, 24));
        assertEquals(SshException.Kind.HOST_KEY_CHANGED, e.kind());
    }

    @Test
    void wrongKeyFailsAuthentication() throws Exception {
        int port = startServer(rsa(), dir.resolve("host.ser"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        SshException e = assertThrows(SshException.class, () -> connector.open(
                new SshTarget("127.0.0.1", port, "ubuntu", pem(rsa()), null), (h, p, t, f) -> true, 80, 24));
        assertEquals(SshException.Kind.AUTH, e.kind());
    }

    @Test
    void garbageKeyIsReportedAsKeyFormat() {
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        SshException e = assertThrows(SshException.class, () -> connector.open(
                new SshTarget("127.0.0.1", 22, "ubuntu", "not a key", null), (h, p, t, f) -> true, 80, 24));
        assertEquals(SshException.Kind.KEY_FORMAT, e.kind());
    }

    /** 실행하라고 한 명령과 받은 터미널 종류를 출력하고 종료하는 exec 명령. */
    private static final class ReportCommand implements org.apache.sshd.server.command.Command {
        private final String command;
        private java.io.OutputStream out;
        private org.apache.sshd.server.ExitCallback exit;

        ReportCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(java.io.InputStream in) {
        }

        @Override
        public void setOutputStream(java.io.OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(java.io.OutputStream err) {
        }

        @Override
        public void setExitCallback(org.apache.sshd.server.ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, org.apache.sshd.server.Environment env) throws IOException {
            String term = env.getEnv().getOrDefault("TERM", "none");
            out.write(("ran:" + command + " term:" + term + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            exit.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }

    @Test
    void commandWithPtyGetsATerminal() throws Exception {
        KeyPair key = rsa();
        int port = startServer(key, dir.resolve("host.ser"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        SshTarget target = new SshTarget("127.0.0.1", port, "ubuntu", pem(key), null);
        try (ShellSession s = connector.open(target, (h, p, t, f) -> true, 100, 30, "docker exec -it abc sh")) {
            String out = new String(s.output().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(out.contains("ran:docker exec -it abc sh"), out);
            assertTrue(out.contains("term:xterm-256color"), out);
            assertEquals(0, s.waitFor());
        }
        try (ShellSession s = connector.exec(target, (h, p, t, f) -> true, "plain")) {
            String out = new String(s.output().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(out.contains("term:none"), "no PTY for batch commands: " + out);
        }
    }
}
