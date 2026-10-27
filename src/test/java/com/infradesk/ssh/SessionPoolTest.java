package com.infradesk.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 접속 풀 규칙을 프로세스 안의 실제 MINA 서버에 대고 확인한다. 서버가 본 접속 수를 세므로, "명령 50번에 접속 1번"
 * 같은 약속이 서버 로그에 정말 그렇게 남는지를 검증하는 셈이다.
 */
class SessionPoolTest {

    @TempDir
    Path dir;

    private SshServer server;
    private KeyPair key;
    private KeyPair otherKey;
    private String pem;
    private String otherPem;
    private MinaShellConnector connector;
    private MutableClock clock;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger authAttempts = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        key = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        otherKey = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        pem = pemOf(key);
        otherPem = pemOf(otherKey);
        Path root = Files.createDirectories(dir.resolve("remote"));

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host.ser")));
        server.setPublickeyAuthenticator((u, k, s) -> {
            authAttempts.incrementAndGet();
            return KeyUtils.compareKeys(k, key.getPublic()) || KeyUtils.compareKeys(k, otherKey.getPublic());
        });
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.setCommandFactory((channel, command) -> new OkCommand());
        server.setShellFactory(channel -> new OkCommand());
        server.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(Session session) {
                created.incrementAndGet();
            }
        });
        server.start();

        clock = new MutableClock(Instant.parse("2026-10-22T09:00:00Z"));
        connector = new MinaShellConnector(dir.resolve("known_hosts"), clock, Duration.ofSeconds(30), false);
    }

    @AfterEach
    void stop() throws Exception {
        connector.close();
        server.stop(true);
    }

    private static String pemOf(KeyPair pair) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "t", null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private SshTarget target(String pemKey) {
        return new SshTarget("127.0.0.1", server.getPort(), "ubuntu", pemKey, null, "server-1");
    }

    private static final HostKeyPrompt TRUST = (host, port, type, fingerprint) -> true;

    private String query(SshTarget t) throws Exception {
        return run(connector.exec(Purpose.QUERY, t, TRUST, "anything"));
    }

    private static String run(ShellSession session) throws Exception {
        try (InputStream in = session.output()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            session.waitFor();
            return text;
        } finally {
            session.close();
        }
    }

    private int serverSessions() {
        return server.getActiveSessions().size();
    }

    private static void eventually(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(message);
    }

    // ---- 재사용 ----

    @Test
    void thirtyCommandsUseOneConnection() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertEquals("ok\n", query(target(pem)));
        }

        assertEquals(1, created.get(), "명령 30번이어도 서버가 본 접속은 1번");
        int authForOneConnection = authAttempts.get();
        assertTrue(authForOneConnection >= 1, "공개키 인증은 한 번의 접속 안에서 시도가 여러 번 불릴 수 있다");
        query(target(pem));
        assertEquals(authForOneConnection, authAttempts.get(), "재사용하면 인증이 더 일어나지 않는다");
        assertEquals(1, connector.pooledConnections());
    }

    @Test
    void eachPurposeHasItsOwnConnectionAndClosingAChannelKeepsTheConnection() throws Exception {
        SshTarget t = target(pem);
        query(t);
        run(connector.exec(Purpose.COMMAND, t, TRUST, "x"));
        connector.sftp(Purpose.FILES, t, TRUST).close();
        connector.sftp(Purpose.TRANSFER, t, TRUST).close();

        assertEquals(4, created.get(), "QUERY, COMMAND, FILES, TRANSFER 각각 1번");
        assertEquals(4, serverSessions(), "채널을 닫아도 연결은 남아 있다");

        query(t);
        connector.sftp(Purpose.FILES, t, TRUST).close();
        assertEquals(4, created.get(), "다시 써도 새로 접속하지 않는다");
    }

    @Test
    void twoChannelsShareOneConnection() throws Exception {
        SshTarget t = target(pem);
        try (RemoteFiles a = connector.sftp(Purpose.FILES, t, TRUST); RemoteFiles b = connector.sftp(Purpose.FILES, t, TRUST)) {
            assertTrue(a.exists("/"));
            assertTrue(b.exists("/"));
        }

        assertEquals(1, created.get(), "같은 목적의 SFTP 두 개는 연결 하나 위의 채널 둘");
    }

    @Test
    void terminalsAreNotPooled() throws Exception {
        SshTarget t = target(pem);
        ShellSession shell = connector.open(t, TRUST, 80, 24);
        assertEquals(1, created.get());
        assertEquals(0, connector.pooledConnections(), "터미널은 풀 밖의 연결이다");

        shell.close();

        eventually(() -> serverSessions() == 0, "터미널을 닫으면 연결도 닫혀야 한다");
        query(t);
        assertEquals(2, created.get(), "조회는 터미널과 별개로 새 연결을 맺는다");
    }

    // ---- 열린 연결 목록(읽기 전용) ----

    @Test
    void listsTheOpenPooledConnectionsWithoutOpeningAnyNewOne() throws Exception {
        assertEquals(List.of(), connector.openConnections(), "아무것도 쓰기 전에는 비어 있다");

        SshTarget t = target(pem);
        query(t);
        connector.sftp(Purpose.FILES, t, TRUST).close();
        int sessionsBefore = created.get();

        List<OpenConnection> open = connector.openConnections();

        assertEquals(2, open.size());
        assertEquals(List.of(Purpose.QUERY, Purpose.FILES), open.stream().map(OpenConnection::purpose).toList());
        assertEquals("server-1", open.getFirst().serverId());
        assertTrue(open.stream().allMatch(c -> c.active() == 0), "채널을 닫았으니 놀고 있다");
        assertEquals(sessionsBefore, created.get(), "목록을 읽는다고 접속하지 않는다");
    }

    @Test
    void aClosedConnectionDisappearsFromTheList() throws Exception {
        SshTarget t = target(pem);
        query(t);
        assertEquals(1, connector.openConnections().size());

        connector.invalidate("server-1");

        eventually(() -> connector.openConnections().isEmpty(), "끊긴 연결은 목록에서 빠져야 한다");
    }

    @Test
    void terminalsAreNotInTheList() throws Exception {
        ShellSession shell = connector.open(target(pem), TRUST, 80, 24);
        try {
            assertEquals(List.of(), connector.openConnections(), "터미널은 풀 밖의 연결이다");
        } finally {
            shell.close();
        }
    }

    // ---- 실패 ----

    @Test
    void queryReconnectsOnceWhenTheConnectionWasDropped() throws Exception {
        SshTarget t = target(pem);
        query(t);
        assertEquals(1, created.get());

        server.getActiveSessions().forEach(s -> s.close(true));

        assertEquals("ok\n", query(t), "끊긴 연결이어도 조회는 다시 맺어서 성공한다");
        assertEquals(2, created.get(), "다시 맺은 것은 한 번");
    }

    @Test
    void authenticationFailureIsNotRetried() throws Exception {
        SshTarget wrong = new SshTarget("127.0.0.1", server.getPort(), "ubuntu", pemOf(KeyUtils.generateKeyPair("ssh-ed25519", 256)),
                null, "server-1");

        SshException e = assertThrows(SshException.class, () -> query(wrong));

        assertEquals(SshException.Kind.AUTH, e.kind());
        assertEquals(1, created.get(), "인증 실패는 다시 접속하지 않는다(두 번 시도하면 fail2ban에 두 배로 쌓인다)");
        assertEquals(0, connector.pooledConnections(), "실패한 연결은 풀에 남지 않는다");
    }

    // ---- 설정이 바뀜 ----

    @Test
    void aChangedKeyOpensANewConnectionAndClosesTheOldOne() throws Exception {
        query(target(pem));
        assertEquals(1, serverSessions());

        query(target(otherPem));

        assertEquals(2, created.get());
        eventually(() -> serverSessions() == 1, "키가 바뀌면 옛 키로 맺은 연결은 닫혀야 한다");
    }

    @Test
    void invalidateClosesTheServersConnectionsAtOnce() throws Exception {
        SshTarget t = target(pem);
        query(t);
        connector.sftp(Purpose.FILES, t, TRUST).close();
        assertEquals(2, serverSessions());

        connector.invalidate("server-1");

        eventually(() -> serverSessions() == 0, "설정이 바뀌거나 서버가 지워지면 연결을 바로 끊는다");
        query(t);
        assertEquals(3, created.get());
    }

    @Test
    void invalidatingAnotherServerLeavesThisOneAlone() throws Exception {
        query(target(pem));

        connector.invalidate("server-2");

        Thread.sleep(200);
        assertEquals(1, serverSessions());
    }

    // ---- 창이 메뉴 막대로 숨음 ----

    @Test
    void whenHiddenOnlyTheQueryConnectionStaysAndBusyOnesCloseWhenDone() throws Exception {
        SshTarget t = target(pem);
        query(t);
        connector.sftp(Purpose.FILES, t, TRUST).close();
        RemoteFiles transfer = connector.sftp(Purpose.TRANSFER, t, TRUST);
        run(connector.exec(Purpose.COMMAND, t, TRUST, "x"));
        assertEquals(4, serverSessions());

        connector.setBackground(true);

        eventually(() -> serverSessions() == 2, "쓰지 않는 FILES와 COMMAND는 바로 닫히고, 쓰는 TRANSFER와 QUERY는 남는다");
        assertTrue(transfer.exists("/"), "전송 중인 연결은 끊기지 않는다");

        transfer.close();

        eventually(() -> serverSessions() == 1, "전송이 끝나면 TRANSFER도 닫히고 조회만 남는다");
        assertEquals(1, connector.pooledConnections());
        query(t);
        assertEquals(4, created.get(), "조회 연결은 계속 재사용한다");
    }

    @Test
    void showingTheWindowAgainStopsClosingConnections() throws Exception {
        SshTarget t = target(pem);
        connector.setBackground(true);
        connector.setBackground(false);

        connector.sftp(Purpose.FILES, t, TRUST).close();

        Thread.sleep(200);
        assertEquals(1, serverSessions(), "창이 다시 보이면 쓴 뒤에 닫지 않는다");
    }

    // ---- 수명 12시간 ----

    @Test
    void aConnectionOlderThanTwelveHoursIsReplacedInTheNextGap() throws Exception {
        SshTarget t = target(pem);
        query(t);
        assertEquals(1, created.get());

        clock.now = clock.now.plus(Duration.ofHours(11)).plus(Duration.ofMinutes(59));
        query(t);
        assertEquals(1, created.get(), "12시간 전에는 그대로 쓴다");

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        query(t);

        assertEquals(2, created.get(), "12시간이 지나면 쓰는 곳이 없는 빈틈에 새로 맺는다");
        eventually(() -> serverSessions() == 1, "옛 연결은 닫힌다");
    }

    @Test
    void maintenanceClosesAnExpiredIdleConnection() throws Exception {
        query(target(pem));

        clock.now = clock.now.plus(Duration.ofHours(12)).plusSeconds(1);
        connector.maintainPool();

        eventually(() -> serverSessions() == 0, "수명이 다한 빈 연결은 정리 때 닫힌다");
    }

    @Test
    void anExpiredQueryConnectionInUseIsForcedOnlyAfterTheGracePeriod() throws Exception {
        SshTarget t = target(pem);
        ShellSession busy = connector.exec(Purpose.QUERY, t, TRUST, "x");

        clock.now = clock.now.plus(Duration.ofHours(12)).plusSeconds(1);
        connector.maintainPool();
        Thread.sleep(200);
        assertEquals(1, serverSessions(), "쓰는 중이면 빈틈을 기다린다");

        clock.now = clock.now.plus(Duration.ofMinutes(10));
        connector.maintainPool();

        eventually(() -> serverSessions() == 0, "빈틈이 10분 동안 안 생기면 조회 연결은 강제로 닫는다");
        busy.close();
    }

    @Test
    void anExpiredTransferConnectionInUseIsNeverForced() throws Exception {
        RemoteFiles transfer = connector.sftp(Purpose.TRANSFER, target(pem), TRUST);

        clock.now = clock.now.plus(Duration.ofHours(30));
        connector.maintainPool();

        Thread.sleep(200);
        assertEquals(1, serverSessions(), "전송은 몇 시간이 지나도 끝날 때까지 끊지 않는다");
        assertTrue(transfer.exists("/"));
        transfer.close();
        connector.maintainPool();
        eventually(() -> serverSessions() == 0, "전송이 끝나면 정리 때 닫힌다");
    }

    // ---- 유휴 ----

    @Test
    void idleConnectionsCloseAfterTheirTimeout() throws Exception {
        SshTarget t = target(pem);
        query(t);
        connector.sftp(Purpose.FILES, t, TRUST).close();

        clock.now = clock.now.plus(Duration.ofMinutes(6));
        connector.maintainPool();
        eventually(() -> serverSessions() == 1, "FILES는 5분 유휴 뒤 닫히고 QUERY는 남는다");

        clock.now = clock.now.plus(Duration.ofMinutes(8));
        connector.maintainPool();
        Thread.sleep(200);
        assertEquals(1, serverSessions(), "QUERY는 마지막 사용 뒤 14분이라 아직 남는다");

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        connector.maintainPool();
        eventually(() -> serverSessions() == 0, "QUERY도 15분이 지나면 닫힌다");
    }

    @Test
    void aConnectionThatIsBeingUsedIsNotClosedForIdleness() throws Exception {
        RemoteFiles files = connector.sftp(Purpose.FILES, target(pem), TRUST);

        clock.now = clock.now.plus(Duration.ofMinutes(30));
        connector.maintainPool();

        Thread.sleep(200);
        assertEquals(1, serverSessions(), "SFTP 창이 열려 있는 동안은 유휴가 아니다");
        assertTrue(files.exists("/"));
        files.close();
    }

    // ---- 죽은 연결 감지 ----

    @Test
    void heartbeatNoticesAConnectionThatSilentlyDied() throws Exception {
        try (BlackHoleProxy proxy = new BlackHoleProxy(server.getPort());
             MinaShellConnector fast = new MinaShellConnector(dir.resolve("known_hosts2"), Clock.systemUTC(),
                     Duration.ofMillis(300), false)) {
            SshTarget viaProxy = new SshTarget("127.0.0.1", proxy.port(), "ubuntu", pem, null, "server-1");
            assertEquals("ok\n", run(fast.exec(Purpose.QUERY, viaProxy, TRUST, "x")));
            assertEquals(1, fast.pooledConnections());

            proxy.blackHole();

            eventually(() -> fast.pooledConnections() == 0,
                    "응답이 없으면 heartbeat가 연결이 죽은 것을 알아채서 닫아야 한다");
        }
    }

    // ---- 도구 ----

    /** 명령을 받으면 "ok"를 쓰고 끝나는 서버 쪽 명령. */
    private static final class OkCommand implements Command {
        private OutputStream out;
        private ExitCallback callback;

        @Override
        public void setInputStream(InputStream in) {
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
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            out.write("ok\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            callback.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }

    /** 서버로 바이트를 그대로 전달하다가, 신호를 받으면 양쪽 데이터를 버리는 중계기(네트워크가 조용히 끊긴 상황). */
    private static final class BlackHoleProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final int targetPort;
        private volatile boolean blackHole;

        BlackHoleProxy(int targetPort) throws IOException {
            this.listener = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
            this.targetPort = targetPort;
            Thread accept = new Thread(this::acceptLoop, "proxy-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        void blackHole() {
            blackHole = true;
        }

        private void acceptLoop() {
            try {
                while (!listener.isClosed()) {
                    Socket client = listener.accept();
                    Socket upstream = new Socket(java.net.InetAddress.getLoopbackAddress(), targetPort);
                    pump(client, upstream);
                    pump(upstream, client);
                }
            } catch (IOException ignored) {
                // 닫는 중이다.
            }
        }

        private void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (!blackHole) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {
                    // 연결이 끝났다.
                }
            }, "proxy-pump");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
