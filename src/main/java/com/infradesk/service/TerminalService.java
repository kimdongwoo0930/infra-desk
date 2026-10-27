package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.demo.DemoLocalFiles;
import com.infradesk.ssh.HostFacts;
import com.infradesk.ssh.LocalFiles;
import com.infradesk.ssh.HostKeyPrompt;
import com.infradesk.ssh.ProcStats;
import com.infradesk.ssh.Purpose;
import com.infradesk.ssh.SshConfig;
import com.infradesk.ssh.RemoteFiles;
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
 * 서버별 SSH 설정과 셸 열기. {@link com.infradesk.core.CloudProvider}와는 무관하다.
 * 서버의 주소만 있으면 된다. 블로킹이므로 EDT 밖에서 호출한다.
 */
public class TerminalService {

    public static final String SSH_KEY = "sshKey";
    public static final String SSH_PASSPHRASE = "sshPassphrase";

    private final SshSettingsStore settingsStore;
    private final SecretStore secretStore;
    private final ShellConnector connector;
    private final SavedCommandStore commandStore;
    private final boolean demo;
    private java.util.function.Supplier<SshConfig> sshConfig = SshConfig::userDefault;

    public TerminalService(SshSettingsStore settingsStore, SecretStore secretStore, ShellConnector connector,
                           SavedCommandStore commandStore, boolean demo) {
        this.settingsStore = settingsStore;
        this.secretStore = secretStore;
        this.connector = connector;
        this.commandStore = commandStore;
        this.demo = demo;
    }

    /** 테스트용: 다른 설정을 읽는다. */
    void setSshConfigSource(java.util.function.Supplier<SshConfig> source) {
        this.sshConfig = source;
    }

    public Optional<SshSettings> settings(String serverId) {
        return settingsStore.get(serverId);
    }

    /** 설정을 먼저 묻지 않고도 서버에 연결할 수 있는지. */
    public boolean isConfigured(String serverId) {
        return demo || (settingsStore.get(serverId).isPresent() && secretStore.get(serverKey(serverId, SSH_KEY)).isPresent());
    }

    /**
     * 서버의 공인 IP(또는 Host 별칭으로서의 서버 이름)에 대해 {@code ~/.ssh/config}가 추천하는 설정.
     * 실제 설정을 읽지 않는 데모 모드에서는 항상 비어 있다.
     */
    public Optional<SshConfig.Suggestion> suggestFromSshConfig(Server server) {
        if (demo || server.publicIp() == null) {
            return Optional.empty();
        }
        return sshConfig.get().suggest(server.publicIp(), server.name());
    }

    public boolean hasKey(String serverId) {
        return secretStore.get(serverKey(serverId, SSH_KEY)).isPresent();
    }

    /**
     * 설정을 저장한다. {@code privateKeyPem}이 null이면 저장된 키를 유지하고, 빈 암호는 암호를 삭제한다.
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
        // 사용자 이름·키·포트가 바뀌었을 수 있다. 옛 설정으로 맺어 둔 연결을 바로 끊는다.
        connector.invalidate(settings.serverId());
    }

    public void forget(String serverId) {
        settingsStore.delete(serverId);
        secretStore.delete(serverKey(serverId, SSH_KEY));
        secretStore.delete(serverKey(serverId, SSH_PASSPHRASE));
        connector.invalidate(serverId);
    }

    /**
     * 앱 창이 메뉴 막대로만 숨었는지 알린다. 숨은 동안은 조회 연결만 남기고 나머지는 쓰는 대로 닫는다.
     */
    public void setBackground(boolean background) {
        connector.setBackground(background);
    }

    /** 저장된 설정으로 서버의 공인 IP에 셸을 연다. */
    public ShellSession open(Server server, HostKeyPrompt prompt, int columns, int rows) {
        return connector.open(target(server), prompt, columns, rows);
    }

    /** 서버에서 {@code command}를 PTY와 함께 실행한다(예: 컨테이너 안의 셸). */
    public ShellSession open(Server server, String command, HostKeyPrompt prompt, int columns, int rows) {
        return connector.open(target(server), prompt, columns, rows, command);
    }

    /** 명령 실행 결과로 보관하는 출력의 최대 크기. 나머지는 버린다. */
    public static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /**
     * PTY 없이 명령을 실행하고 끝날 때까지 기다린다. 예외를 던지지 않는다. 연결 실패와 시간 초과는
     * {@link ExecResult#failure}로 돌려준다.
     */
    public ExecResult run(Server server, String command, HostKeyPrompt prompt, Duration timeout) {
        return run(Purpose.COMMAND, server, command, prompt, timeout);
    }

    /**
     * 목적을 밝히고 실행한다. 목적을 밝히지 않은 명령은 서버를 바꿀 수 있다고 보고 {@link Purpose#COMMAND}로
     * 실행한다(실패해도 자동으로 다시 시도하지 않는다). 읽기만 하는 조회는 {@link Purpose#QUERY}로 부른다.
     */
    public ExecResult run(Purpose purpose, Server server, String command, HostKeyPrompt prompt, Duration timeout) {
        ShellSession session;
        try {
            session = connector.exec(purpose, target(server), prompt, command);
        } catch (RuntimeException e) {
            return ExecResult.failure(e.getMessage(), e instanceof SshException ssh ? ssh.kind() : null);
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(timeout);
                timedOut.set(true);
                session.close();
            } catch (InterruptedException ignored) {
                // 제한 시간 안에 끝났다.
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

    /** SSH로 업타임, OS, 디스크, 수신 대기 포트를 읽는다(짧은 exec 한 번). */
    public HostFacts facts(Server server, HostKeyPrompt prompt) {
        ExecResult r = run(Purpose.QUERY, server, HostFacts.COMMAND, prompt, Duration.ofSeconds(20));
        if (r.error() != null) {
            throw new SshException(SshException.Kind.CHANNEL, r.error());
        }
        return HostFacts.parse(r.output());
    }

    /** 저장된 SSH 설정으로 서버에 SFTP를 연다. */
    public RemoteFiles openFiles(Server server, HostKeyPrompt prompt) {
        return connector.sftp(Purpose.FILES, target(server), prompt);
    }

    /** SFTP 전송 대기열용. 큰 전송이 폴더 탐색을 막지 않게 탐색용({@link #openFiles})과 연결을 따로 쓴다. */
    public RemoteFiles openTransferFiles(Server server, HostKeyPrompt prompt) {
        return connector.sftp(Purpose.TRANSFER, target(server), prompt);
    }

    /** SFTP 창 왼쪽에 보여줄 이 컴퓨터의 폴더. 데모 모드는 실제 폴더 대신 가짜 폴더를 쓴다. */
    public LocalFiles localFiles() {
        return demo ? DemoLocalFiles.get() : LocalFiles.userHome();
    }

    public List<SavedCommand> savedCommands() {
        return commandStore.load();
    }

    public void saveCommands(List<SavedCommand> commands) {
        commandStore.save(commands);
    }

    /** 서버에서 /proc 스냅샷 스트리밍을 시작한다({@link ProcStats#COMMAND} 참고). */
    public ShellSession openStats(Server server, HostKeyPrompt prompt) {
        return connector.exec(Purpose.QUERY, target(server), prompt, ProcStats.COMMAND);
    }

    private SshTarget target(Server server) {
        if (demo) {
            SshSettings s = settingsStore.get(server.id()).orElse(new SshSettings(server.id(), "ubuntu", 22));
            return new SshTarget("demo:" + server.name(), s.port(), s.username(), "demo", null, server.id());
        }
        String host = server.publicIpAddress().orElseThrow(() -> new SshException(SshException.Kind.CONNECT,
                server.name() + "에 공인 IP가 없어요. 공인 IP를 붙이거나 사설망(VPN)으로 접속하세요."));
        SshSettings s = settingsStore.get(server.id()).orElseThrow(() -> new SshException(SshException.Kind.AUTH,
                server.name() + "의 SSH 설정이 없어요. SSH 키를 먼저 등록하세요."));
        String key = secretStore.get(serverKey(server.id(), SSH_KEY)).orElseThrow(() -> new SshException(SshException.Kind.AUTH,
                server.name() + "의 SSH 키가 없어요. SSH 키를 먼저 등록하세요."));
        String passphrase = secretStore.get(serverKey(server.id(), SSH_PASSPHRASE)).orElse(null);
        return new SshTarget(host, s.port(), s.username(), key, passphrase, server.id());
    }

    private static String serverKey(String serverId, String name) {
        return "server." + serverId + "." + name;
    }
}
