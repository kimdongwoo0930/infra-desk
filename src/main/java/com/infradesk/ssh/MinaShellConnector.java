package com.infradesk.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

/**
 * SSH shells over Apache MINA SSHD. Host keys are checked against {@code known_hosts} in the
 * app's config directory: unknown keys go to the {@link HostKeyPrompt}, changed keys are refused.
 */
public class MinaShellConnector implements ShellConnector, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

    /** Per-connect state. Host key checks run on MINA's I/O threads, so it travels with the connection. */
    private static final class Attempt {
        final HostKeyPrompt prompt;
        volatile boolean hostKeyChanged;
        volatile boolean hostKeyRejected;

        Attempt(HostKeyPrompt prompt) {
            this.prompt = prompt;
        }
    }

    private static final AttributeRepository.AttributeKey<Attempt> ATTEMPT = new AttributeRepository.AttributeKey<>();

    private final SshClient client;
    private final Path knownHosts;

    public MinaShellConnector(Path knownHosts) {
        this.knownHosts = knownHosts;
        this.client = SshClient.setUpDefaultClient();
        KnownHostsServerKeyVerifier verifier = new KnownHostsServerKeyVerifier(this::askUnknownHost, knownHosts);
        verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> {
            Attempt attempt = attemptOf(session);
            if (attempt != null) {
                attempt.hostKeyChanged = true;
            }
            return false;
        });
        client.setServerKeyVerifier(verifier);
        client.start();
    }

    private static Attempt attemptOf(ClientSession session) {
        AttributeRepository context = session.getConnectionContext();
        return context == null ? null : context.getAttribute(ATTEMPT);
    }

    private boolean askUnknownHost(ClientSession session, SocketAddress address, java.security.PublicKey key) {
        Attempt attempt = attemptOf(session);
        String host = address instanceof InetSocketAddress in ? in.getHostString() : String.valueOf(address);
        int port = address instanceof InetSocketAddress in ? in.getPort() : 22;
        boolean trusted = attempt != null
                && attempt.prompt.trustNewHost(host, port, KeyUtils.getKeyType(key), KeyUtils.getFingerPrint(key));
        if (attempt != null) {
            attempt.hostKeyRejected = !trusted;
        }
        return trusted;
    }

    @Override
    public ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows) {
        return withSession(target, prompt, session -> {
            ChannelShell channel = session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(columns);
            channel.setPtyLines(rows);
            channel.setEnv("LANG", "en_US.UTF-8");
            channel.setRedirectErrorStream(true);
            channel.open().verify(CONNECT_TIMEOUT);
            return new MinaShellSession(session, channel, target.address());
        });
    }

    @Override
    public ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command) {
        return withSession(target, prompt, session -> {
            ChannelExec channel = session.createExecChannel(command);
            channel.setRedirectErrorStream(true);
            channel.open().verify(CONNECT_TIMEOUT);
            return new MinaShellSession(session, channel, target.address());
        });
    }

    private interface SessionTask {
        ShellSession run(ClientSession session) throws IOException;
    }

    /** Connects and authenticates, then hands the session to {@code task}; cleans up on failure. */
    private ShellSession withSession(SshTarget target, HostKeyPrompt prompt, SessionTask task) {
        KeyPair identity = loadKey(target);
        Attempt attempt = new Attempt(prompt);
        ClientSession session = null;
        try {
            session = client.connect(target.username(), target.host(), target.port(),
                            AttributeRepository.ofKeyValuePair(ATTEMPT, attempt))
                    .verify(CONNECT_TIMEOUT).getSession();
            session.addPublicKeyIdentity(identity);
            session.auth().verify(AUTH_TIMEOUT);
            return task.run(session);
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.close(true);
            }
            throw translate(target, attempt, e);
        }
    }

    private SshException translate(SshTarget target, Attempt attempt, Exception e) {
        if (attempt.hostKeyChanged) {
            return new SshException(SshException.Kind.HOST_KEY_CHANGED,
                    target.host() + "의 호스트 키가 저장된 것과 달라요. 서버를 다시 만들었거나 중간자 공격일 수 있어요. "
                            + "확실하면 known_hosts에서 이 서버 항목을 지운 뒤 다시 연결하세요: " + knownHosts, e);
        }
        if (attempt.hostKeyRejected) {
            return new SshException(SshException.Kind.HOST_KEY_REJECTED, "호스트 키를 신뢰하지 않아 연결을 취소했어요.", e);
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (msg.contains("no more authentication methods") || msg.contains("auth")) {
            return new SshException(SshException.Kind.AUTH,
                    "SSH 인증에 실패했어요. 사용자 이름(" + target.username() + ")과 SSH 키가 이 서버에 등록된 것인지 확인하세요.", e);
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return new SshException(SshException.Kind.CONNECT,
                    target.host() + ":" + target.port() + "에 연결하지 못했어요 (시간 초과). 서버가 켜져 있는지, 보안 목록에서 22번 포트가 열려 있는지 확인하세요.", e);
        }
        if (msg.contains("refused")) {
            return new SshException(SshException.Kind.CONNECT,
                    target.host() + ":" + target.port() + "에서 연결을 거부했어요. SSH 서버가 실행 중인지 확인하세요.", e);
        }
        return new SshException(SshException.Kind.CONNECT, "SSH 연결 실패: " + e.getMessage(), e);
    }

    private static KeyPair loadKey(SshTarget target) {
        FilePasswordProvider password = target.passphrase() == null || target.passphrase().isEmpty()
                ? FilePasswordProvider.EMPTY
                : FilePasswordProvider.of(target.passphrase());
        try (InputStream in = new ByteArrayInputStream(target.privateKeyPem().getBytes(StandardCharsets.UTF_8))) {
            Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("ssh-key"), in, password);
            Iterator<KeyPair> it = pairs == null ? null : pairs.iterator();
            if (it == null || !it.hasNext()) {
                throw new SshException(SshException.Kind.KEY_FORMAT, "SSH 키를 읽지 못했어요. 개인키 파일이 맞는지 확인하세요.");
            }
            return it.next();
        } catch (IOException | GeneralSecurityException e) {
            throw new SshException(SshException.Kind.KEY_FORMAT,
                    "SSH 키를 읽지 못했어요. 암호가 걸린 키라면 암호를 입력하세요.", e);
        }
    }

    @Override
    public void close() {
        client.stop();
    }

    /** Shell over an open MINA channel. */
    private static final class MinaShellSession implements ShellSession {

        private final ClientSession session;
        private final ClientChannel channel;
        private final String address;

        MinaShellSession(ClientSession session, ClientChannel channel, String address) {
            this.session = session;
            this.channel = channel;
            this.address = address;
        }

        @Override
        public InputStream output() {
            return channel.getInvertedOut();
        }

        @Override
        public OutputStream input() {
            return channel.getInvertedIn();
        }

        @Override
        public void resize(int columns, int rows) {
            if (!(channel instanceof PtyCapableChannelSession pty)) {
                return;
            }
            try {
                pty.sendWindowChange(columns, rows);
            } catch (IOException e) {
                // Channel closing; nothing to resize.
            }
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen() && !channel.isClosing();
        }

        @Override
        public int waitFor() {
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            Integer status = channel.getExitStatus();
            return events.contains(ClientChannelEvent.CLOSED) && status != null ? status : -1;
        }

        @Override
        public String address() {
            return address;
        }

        @Override
        public void close() {
            channel.close(false);
            session.close(false);
        }
    }
}
