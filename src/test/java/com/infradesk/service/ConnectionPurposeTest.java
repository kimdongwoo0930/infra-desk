package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ssh.HostKeyPrompt;
import com.infradesk.ssh.Purpose;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ssh.ShellConnector;
import com.infradesk.ssh.ShellSession;
import com.infradesk.ssh.SshException;
import com.infradesk.ssh.SshSettings;
import com.infradesk.ssh.SshTarget;
import com.infradesk.storage.InMemorySavedCommandStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.InMemorySshSettingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 각 기능이 접속 풀에서 올바른 목적({@link Purpose})으로 연결을 빌리는지, 설정이 바뀌면 연결을 끊는지 확인한다.
 * 목적이 틀리면(예: 서버를 바꾸는 명령을 조회로 보내면) 자동 재시도 규칙이 어긋나서 명령이 두 번 실행될 수 있다.
 */
class ConnectionPurposeTest {

    private static final Server MAC = new Server("ssh-m", "m", "서버", ServerStatus.RUNNING, "SSH", "host:22",
            0, 0, "host", null, null);
    private static final HostKeyPrompt NO = (host, port, type, fingerprint) -> false;

    /** 어떤 목적으로 어떤 호출이 왔는지 기록하고 실패로 답하는 가짜 접속기. */
    private static final class Recording implements ShellConnector {
        final List<String> calls = new ArrayList<>();
        final List<String> invalidated = new ArrayList<>();
        final List<Boolean> background = new ArrayList<>();

        @Override
        public ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows) {
            calls.add("TERMINAL");
            throw new SshException(SshException.Kind.CONNECT, "테스트");
        }

        @Override
        public ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command) {
            calls.add("exec-without-purpose");
            throw new SshException(SshException.Kind.CONNECT, "테스트");
        }

        @Override
        public ShellSession exec(Purpose purpose, SshTarget target, HostKeyPrompt prompt, String command) {
            calls.add(purpose.name());
            throw new SshException(SshException.Kind.CONNECT, "테스트");
        }

        @Override
        public RemoteFiles sftp(SshTarget target, HostKeyPrompt prompt) {
            calls.add("sftp-without-purpose");
            throw new SshException(SshException.Kind.CONNECT, "테스트");
        }

        @Override
        public RemoteFiles sftp(Purpose purpose, SshTarget target, HostKeyPrompt prompt) {
            calls.add(purpose.name());
            throw new SshException(SshException.Kind.CONNECT, "테스트");
        }

        @Override
        public void setBackground(boolean b) {
            background.add(b);
        }

        @Override
        public void invalidate(String serverId) {
            invalidated.add(serverId);
        }
    }

    private Recording connector;
    private TerminalService terminal;

    @BeforeEach
    void setUp() {
        connector = new Recording();
        terminal = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), connector,
                new InMemorySavedCommandStore(List.of()), false);
        terminal.save(new SshSettings(MAC.id(), "me", 22), "FAKE-KEY", null);
        connector.invalidated.clear();
    }

    private String only() {
        assertEquals(1, connector.calls.size(), connector.calls.toString());
        return connector.calls.getFirst();
    }

    @Test
    void readOnlyLookupsUseQuery() {
        assertThrows(SshException.class, () -> terminal.facts(MAC, NO));
        assertEquals("QUERY", only(), "서버 정보");
        connector.calls.clear();

        assertThrows(IllegalStateException.class, () -> new ContainerService(terminal).list(MAC, NO));
        assertEquals("QUERY", only(), "컨테이너 목록");
        connector.calls.clear();

        assertThrows(IllegalStateException.class, () -> new ContainerService(terminal).logs(MAC, container(), 50, NO));
        assertEquals("QUERY", only(), "컨테이너 로그");
        connector.calls.clear();

        new SshMetricsService(terminal, Clock.systemUTC()).sample(MAC);
        assertEquals("QUERY", only(), "분당 측정");
        connector.calls.clear();

        assertThrows(SshException.class, () -> terminal.openStats(MAC, NO));
        assertEquals("QUERY", only(), "실시간 모드");
    }

    @Test
    void commandsThatChangeTheServerUseCommand() {
        assertThrows(IllegalStateException.class,
                () -> new ContainerService(terminal).act(MAC, container(), DockerCommands.Action.RESTART, NO));
        assertEquals("COMMAND", only(), "컨테이너 시작·정지·재시작");
        connector.calls.clear();

        terminal.run(MAC, "echo hi", NO, Duration.ofSeconds(5));
        assertEquals("COMMAND", only(), "일괄 실행(목적을 밝히지 않은 명령도 재시도하지 않는 쪽)");
    }

    @Test
    void sftpUsesFilesForBrowsingAndTransferForTheQueue() {
        assertThrows(SshException.class, () -> terminal.openFiles(MAC, NO));
        assertEquals("FILES", only());
        connector.calls.clear();

        assertThrows(SshException.class, () -> terminal.openTransferFiles(MAC, NO));
        assertEquals("TRANSFER", only());
    }

    @Test
    void terminalsDoNotGoThroughThePool() {
        assertThrows(SshException.class, () -> terminal.open(MAC, NO, 80, 24));

        assertEquals("TERMINAL", only());
    }

    @Test
    void savingOrForgettingSshSettingsDropsThatServersConnections() {
        terminal.save(new SshSettings(MAC.id(), "other", 2222), "FAKE-KEY-2", null);
        assertEquals(List.of(MAC.id()), connector.invalidated, "설정을 저장하면 옛 설정으로 맺은 연결을 끊는다");

        terminal.forget(MAC.id());
        assertEquals(List.of(MAC.id(), MAC.id()), connector.invalidated, "서버를 지우면 연결을 끊는다");
    }

    @Test
    void hidingTheWindowIsForwardedToTheConnector() {
        terminal.setBackground(true);
        terminal.setBackground(false);

        assertEquals(List.of(true, false), connector.background);
    }

    private static Container container() {
        return new Container("c".repeat(64), "web", "nginx:1", "running", "Up 1 day", "80/tcp", 0.1, "10MiB / 1GiB", 0.5, "");
    }
}
