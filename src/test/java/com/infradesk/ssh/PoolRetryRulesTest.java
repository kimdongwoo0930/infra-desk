package com.infradesk.ssh;

import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 재사용하던 연결에서 채널을 열지 못할 때의 재시도 규칙. 서버로는 이 상황을 만들 수 없어서(서버가 명령을
 * 거부해도 클라이언트는 오류 없이 기다린다) 채널을 못 여는 가짜 연결을 끼워 넣어 확인한다.
 *
 * <p>규칙: 조회(QUERY)만 새로 맺어 <b>정확히 한 번</b> 다시 시도한다. 서버를 바꾸는 명령(COMMAND)과
 * SFTP는 이미 실행됐을 수 있거나 사용자가 다시 시도하면 되므로 다시 시도하지 않는다.
 */
class PoolRetryRulesTest {

    @TempDir
    Path dir;

    private final AtomicInteger connects = new AtomicInteger();
    private final AtomicReference<IOException> channelFailure = new AtomicReference<>();
    private MinaShellConnector connector;

    private static final SshTarget TARGET = new SshTarget("host", 22, "me", "KEY", null, "server-1");
    private static final HostKeyPrompt NO = (host, port, type, fingerprint) -> false;

    /** 열려 있는 것처럼 보이지만 채널을 만들려 하면 {@link #channelFailure}를 던지는 연결. */
    private ClientSession deadLookingSession() {
        return (ClientSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ClientSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "isClosing" -> false;
                    case "close" -> null;
                    case "createExecChannel", "createShellChannel", "createSubsystemChannel" -> {
                        throw channelFailure.get();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @BeforeEach
    void setUp() {
        connector = new MinaShellConnector(dir.resolve("known_hosts"), Clock.systemUTC(), (target, prompt) -> {
            connects.incrementAndGet();
            return deadLookingSession();
        });
    }

    @AfterEach
    void tearDown() {
        connector.close();
    }

    @Test
    void queryFirstUsesTheNewConnectionAndOnlyRetriesAfterReusingOne() {
        channelFailure.set(new IOException("채널을 열 수 없다"));

        // 처음 맺은 연결에서 난 실패는 다시 시도하지 않는다(접속 실패가 두 배로 쌓이지 않게).
        assertThrows(SshException.class, () -> connector.exec(Purpose.QUERY, TARGET, NO, "x"));
        assertEquals(1, connects.get(), "새로 맺은 연결에서 난 실패는 다시 시도하지 않는다");
    }

    @Test
    void queryRetriesExactlyOnceWhenAReusedConnectionFails() {
        channelFailure.set(new IOException("채널을 열 수 없다"));
        assertThrows(SshException.class, () -> connector.exec(Purpose.QUERY, TARGET, NO, "x"));
        assertEquals(1, connects.get());

        // 이제 풀에 연결이 남아 있다. 재사용하다 실패하면 그 연결을 버리고 새로 맺어 한 번만 다시 시도한다.
        assertThrows(SshException.class, () -> connector.exec(Purpose.QUERY, TARGET, NO, "x"));

        assertEquals(2, connects.get(), "재사용 실패 → 새로 맺어 재시도는 정확히 1번(접속은 처음 1번 + 재시도 1번). 무한히 반복하지 않는다");
    }

    @Test
    void commandsAreNeverRetried() {
        channelFailure.set(new IOException("채널을 열 수 없다"));
        assertThrows(SshException.class, () -> connector.exec(Purpose.COMMAND, TARGET, NO, "restart"));
        int afterFirst = connects.get();

        // 재사용하던 연결에서 실패해도 다시 시도하지 않는다: 명령이 이미 실행됐을 수 있다.
        assertThrows(SshException.class, () -> connector.exec(Purpose.COMMAND, TARGET, NO, "restart"));

        assertEquals(afterFirst, connects.get(), "COMMAND는 재사용 연결에서 실패해도 새로 맺어 다시 시도하지 않는다");
    }

    @Test
    void untaggedExecIsTreatedAsACommand() {
        channelFailure.set(new IOException("채널을 열 수 없다"));
        assertThrows(SshException.class, () -> connector.exec(TARGET, NO, "restart"));
        int afterFirst = connects.get();

        assertThrows(SshException.class, () -> connector.exec(TARGET, NO, "restart"));

        assertEquals(afterFirst, connects.get(), "목적을 밝히지 않은 exec도 재시도하지 않는다");
    }
}
