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
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

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
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Apache MINA SSHD 기반 SSH 셸. 호스트 키는 앱 설정 디렉터리의 {@code known_hosts}와 대조한다.
 * 모르는 키는 {@link HostKeyPrompt}로 넘기고, 바뀐 키는 거부한다.
 *
 * <p>조회·명령·SFTP는 {@link SessionPool}에서 서버·목적별로 연결을 재사용한다. 터미널 탭({@code open})은 사용자가
 * 열고 닫는 연결이라 풀 밖에서 따로 맺는다.
 */
public class MinaShellConnector implements ShellConnector, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);
    /** 풀 연결이 살아 있는지 확인하는 주기. 끊어진 연결을 빨리 알아채려는 것이다. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);
    /** 응답을 요구하고, 응답이 이만큼 연속으로 없으면 연결이 죽은 것으로 보고 세션을 닫는다(약 1~1.5분 안에 감지). */
    private static final int HEARTBEAT_NO_REPLY_MAX = 2;

    /** 연결별 상태. 호스트 키 검사는 MINA의 I/O 스레드에서 실행되므로 연결과 함께 전달한다. */
    private static final class Attempt {
        final HostKeyPrompt prompt;
        volatile boolean hostKeyChanged;
        volatile boolean hostKeyRejected;

        Attempt(HostKeyPrompt prompt) {
            this.prompt = prompt;
        }
    }

    private static final AttributeRepository.AttributeKey<Attempt> ATTEMPT = new AttributeRepository.AttributeKey<>();

    /** 터미널 탭용. heartbeat가 없고 사용자가 열고 닫는 연결을 맺는다. */
    private final SshClient client;
    /**
     * 풀 연결용. 오래 열어 두므로 heartbeat를 켠다. MINA는 heartbeat 설정을 세션이 만들어질 때 읽어 가서 세션마다
     * 바꿀 수 없다. 그래서 설정이 다른 클라이언트를 따로 둔다.
     */
    private final SshClient pooledClient;
    private final Path knownHosts;
    private final SessionPool pool;

    public MinaShellConnector(Path knownHosts) {
        this(knownHosts, Clock.systemUTC(), HEARTBEAT, true);
    }

    /** 테스트용: 시계와 heartbeat 간격을 바꾸고, 풀의 자동 정리를 끌 수 있다. */
    MinaShellConnector(Path knownHosts, Clock clock, Duration heartbeat, boolean autoMaintain) {
        this(knownHosts, clock, heartbeat, autoMaintain, null);
    }

    /** 테스트용: 연결을 맺는 부분을 바꿔 끼운다(예: 채널을 못 여는 가짜 연결로 재시도 규칙을 확인). */
    MinaShellConnector(Path knownHosts, Clock clock, SessionPool.Connector connectOverride) {
        this(knownHosts, clock, HEARTBEAT, false, connectOverride);
    }

    private MinaShellConnector(Path knownHosts, Clock clock, Duration heartbeat, boolean autoMaintain,
                               SessionPool.Connector connectOverride) {
        this.knownHosts = knownHosts;
        this.client = newClient();
        this.pooledClient = newClient();
        CoreModuleProperties.HEARTBEAT_REQUEST.set(pooledClient, "keepalive@openssh.com");
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(pooledClient, HEARTBEAT_NO_REPLY_MAX);
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(pooledClient, heartbeat);
        client.start();
        pooledClient.start();
        this.pool = new SessionPool(connectOverride != null ? connectOverride : (target, prompt) -> connect(target, prompt, true),
                clock, autoMaintain);
    }

    /** 호스트 키 검사를 붙인 클라이언트. 아직 시작하지 않았다. */
    private SshClient newClient() {
        SshClient c = SshClient.setUpDefaultClient();
        KnownHostsServerKeyVerifier verifier = new KnownHostsServerKeyVerifier(this::askUnknownHost, knownHosts);
        verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> {
            Attempt attempt = attemptOf(session);
            if (attempt != null) {
                attempt.hostKeyChanged = true;
            }
            return false;
        });
        c.setServerKeyVerifier(verifier);
        return c;
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
        return withOwnedSession(target, prompt, session -> {
            ChannelShell channel = session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(columns);
            channel.setPtyLines(rows);
            channel.setEnv("LANG", "en_US.UTF-8");
            channel.setRedirectErrorStream(true);
            channel.open().verify(CONNECT_TIMEOUT);
            return new MinaShellSession(SessionPool.owning(session), channel, target.address());
        });
    }

    @Override
    public ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows, String command) {
        return withOwnedSession(target, prompt, session -> {
            ChannelExec channel = session.createExecChannel(command);
            channel.setUsePty(true);
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(columns);
            channel.setPtyLines(rows);
            channel.setEnv("LANG", "en_US.UTF-8");
            channel.setRedirectErrorStream(true);
            channel.open().verify(CONNECT_TIMEOUT);
            return new MinaShellSession(SessionPool.owning(session), channel, target.address());
        });
    }

    /** 목적을 밝히지 않은 명령은 서버를 바꿀 수 있다고 보고 {@link Purpose#COMMAND}로 실행한다(자동으로 다시 시도하지 않는다). */
    @Override
    public ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command) {
        return exec(Purpose.COMMAND, target, prompt, command);
    }

    @Override
    public ShellSession exec(Purpose purpose, SshTarget target, HostKeyPrompt prompt, String command) {
        return pooled(purpose, target, prompt, lease -> {
            ChannelExec channel = lease.session().createExecChannel(command);
            try {
                channel.setRedirectErrorStream(true);
                channel.open().verify(CONNECT_TIMEOUT);
            } catch (IOException | RuntimeException e) {
                channel.close(true);
                throw e;
            }
            return new MinaShellSession(lease, channel, target.address());
        });
    }

    @Override
    public RemoteFiles sftp(SshTarget target, HostKeyPrompt prompt) {
        return sftp(Purpose.FILES, target, prompt);
    }

    @Override
    public RemoteFiles sftp(Purpose purpose, SshTarget target, HostKeyPrompt prompt) {
        return pooled(purpose, target, prompt, lease -> {
            try {
                return new MinaRemoteFiles(lease, SftpClientFactory.instance().createSftpClient(lease.session()));
            } catch (IOException e) {
                if (!lease.session().isOpen()) {
                    throw new SshException(SshException.Kind.CONNECT, "서버와의 연결이 끊어져 있었어요. 다시 시도하세요.", e);
                }
                throw new SshException(SshException.Kind.CHANNEL,
                        "SFTP를 열지 못했어요. 서버에서 SFTP 서브시스템이 꺼져 있을 수 있어요.", e);
            }
        });
    }

    @Override
    public void setBackground(boolean background) {
        pool.setBackground(background);
    }

    /** 테스트용: 풀이 정리를 한 번 하게 한다(수명·유휴 시간이 지난 연결을 닫는다). */
    void maintainPool() {
        pool.maintain();
    }

    @Override
    public java.util.List<OpenConnection> openConnections() {
        return pool.snapshot();
    }

    /** 테스트용: 지금 열려 있는 풀 연결 수. */
    int pooledConnections() {
        return pool.openCount();
    }

    @Override
    public void invalidate(String serverId) {
        pool.invalidate(serverId);
    }

    private interface SessionTask<T> {
        T run(ClientSession session) throws IOException;
    }

    private interface LeaseTask<T> {
        T run(SessionPool.SessionLease lease) throws IOException;
    }

    /**
     * 풀 밖에서 연결을 맺고 작업을 한다(터미널 탭). 작업이 실패하면 연결을 닫는다. 성공하면 연결은
     * 결과(셸)가 쥐고 있다가 닫을 때 같이 닫는다.
     */
    private <T> T withOwnedSession(SshTarget target, HostKeyPrompt prompt, SessionTask<T> task) {
        ClientSession session = connect(target, prompt, false);
        try {
            return task.run(session);
        } catch (SshException e) {
            session.close(true);
            throw e;
        } catch (IOException | RuntimeException e) {
            session.close(true);
            throw translate(target, new Attempt(prompt), e);
        }
    }

    /**
     * 풀에서 연결을 빌려 작업을 한다. 재사용하던 연결이 죽어 있어서 채널을 못 열면, 조회(QUERY)만 그 연결을 버리고
     * 새로 맺어 <b>한 번만</b> 다시 시도한다. 새로 맺은 연결에서 난 실패는 다시 시도하지 않는다(접속 실패가
     * 두 배로 쌓이지 않게). 컨테이너 시작·정지처럼 서버를 바꾸는 명령은 이미 실행됐을 수 있어서 다시 시도하지 않는다.
     */
    private <T> T pooled(Purpose purpose, SshTarget target, HostKeyPrompt prompt, LeaseTask<T> task) {
        SessionPool.SessionLease lease = pool.acquire(purpose, target, prompt);
        try {
            return task.run(lease);
        } catch (SshException e) {
            lease.close();
            throw e;
        } catch (IOException | RuntimeException e) {
            if (!(purpose.retriesOnce() && lease.reused())) {
                lease.close();
                throw translate(target, new Attempt(prompt), e);
            }
            lease.discard();
        }
        SessionPool.SessionLease fresh = pool.acquire(purpose, target, prompt);
        try {
            return task.run(fresh);
        } catch (SshException e) {
            fresh.close();
            throw e;
        } catch (IOException | RuntimeException e) {
            fresh.close();
            throw translate(target, new Attempt(prompt), e);
        }
    }

    /** 연결하고 인증까지 끝낸 세션을 돌려준다. 실패하면 정리한다. */
    private ClientSession connect(SshTarget target, HostKeyPrompt prompt, boolean pooled) {
        KeyPair identity = loadKey(target);
        Attempt attempt = new Attempt(prompt);
        ClientSession session = null;
        try {
            session = (pooled ? pooledClient : client).connect(target.username(), target.host(), target.port(),
                            AttributeRepository.ofKeyValuePair(ATTEMPT, attempt))
                    .verify(CONNECT_TIMEOUT).getSession();
            session.addPublicKeyIdentity(identity);
            session.auth().verify(AUTH_TIMEOUT);
            return session;
        } catch (SshException e) {
            if (session != null) {
                session.close(true);
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.close(true);
            }
            throw translate(target, attempt, e);
        }
    }

    /**
     * 서버가 권한을 알려줬을 때만 그 값을 쓴다. 안 알려주면 -1(알 수 없음)이다. 값이 없는데 0으로 읽으면 "권한 000"으로
     * 착각해서 파일을 열 수 없게 만들 수 있다.
     */
    private static int permissionsOf(SftpClient.Attributes a) {
        return a.getFlags().contains(SftpClient.Attribute.Perms) ? a.getPermissions() & 0777 : RemoteFile.UNKNOWN_PERMISSIONS;
    }

    private static int uidOf(SftpClient.Attributes a) {
        return a.getFlags().contains(SftpClient.Attribute.UidGid) ? a.getUserId() : RemoteFile.UNKNOWN_UID;
    }

    /** 연결 위의 SFTP 채널. 닫으면 채널만 닫고 연결은 풀에 돌려준다. */
    private static final class MinaRemoteFiles implements RemoteFiles {

        private final SessionPool.SessionLease lease;
        private final SftpClient sftp;

        MinaRemoteFiles(SessionPool.SessionLease lease, SftpClient sftp) {
            this.lease = lease;
            this.sftp = sftp;
        }

        @Override
        public String home() throws IOException {
            return sftp.canonicalPath(".");
        }

        @Override
        public java.util.List<RemoteFile> list(String directory) throws IOException {
            java.util.List<RemoteFile> files = new java.util.ArrayList<>();
            for (SftpClient.DirEntry e : sftp.readDir(directory)) {
                String name = e.getFilename();
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                SftpClient.Attributes a = e.getAttributes();
                String path = RemoteFiles.join(directory, name);
                boolean dir = a.isDirectory();
                if (a.isSymbolicLink()) {
                    try {
                        dir = sftp.stat(path).isDirectory();
                    } catch (IOException broken) {
                        dir = false;
                    }
                }
                files.add(new RemoteFile(name, path, dir, a.getSize(),
                        a.getModifyTime() == null ? null : a.getModifyTime().toInstant(),
                        permissionsOf(a), a.isSymbolicLink(), uidOf(a)));
            }
            files.sort(java.util.Comparator.comparing((RemoteFile f) -> !f.directory())
                    .thenComparing(RemoteFile::name, String.CASE_INSENSITIVE_ORDER));
            return files;
        }

        @Override
        public boolean exists(String path) throws IOException {
            try {
                sftp.lstat(path);
                return true;
            } catch (org.apache.sshd.sftp.common.SftpException e) {
                if (e.getStatus() == org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE) {
                    return false;
                }
                throw e;
            }
        }

        @Override
        public RemoteFile stat(String path) throws IOException {
            SftpClient.Attributes a = sftp.stat(path);
            String name = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            name = name.substring(name.lastIndexOf('/') + 1);
            return new RemoteFile(name, path, a.isDirectory(), a.getSize(),
                    a.getModifyTime() == null ? null : a.getModifyTime().toInstant(), permissionsOf(a), false, uidOf(a));
        }

        @Override
        public void download(String remotePath, java.nio.file.Path localFile, long offset,
                             java.util.function.LongConsumer progress) throws IOException {
            if (offset <= 0) {
                try (InputStream in = sftp.read(remotePath);
                     OutputStream out = java.nio.file.Files.newOutputStream(localFile)) {
                    Transfers.copy(in, out, progress);
                }
                return;
            }
            try (SftpClient.CloseableHandle handle = sftp.open(remotePath, java.util.EnumSet.of(SftpClient.OpenMode.Read));
                 java.nio.channels.FileChannel out = java.nio.channels.FileChannel.open(localFile,
                         java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE)) {
                out.truncate(offset);
                out.position(offset);
                byte[] buf = new byte[32 * 1024];
                long pos = offset;
                int n;
                while ((n = sftp.read(handle, pos, buf, 0, buf.length)) > 0) {
                    out.write(java.nio.ByteBuffer.wrap(buf, 0, n));
                    pos += n;
                    progress.accept(pos);
                }
            }
        }

        @Override
        public void upload(java.nio.file.Path localFile, String remotePath, long offset,
                           java.util.function.LongConsumer progress) throws IOException {
            if (offset <= 0) {
                try (InputStream in = java.nio.file.Files.newInputStream(localFile);
                     OutputStream out = sftp.write(remotePath)) {
                    Transfers.copy(in, out, progress);
                }
                return;
            }
            try (InputStream in = java.nio.file.Files.newInputStream(localFile);
                 SftpClient.CloseableHandle handle = sftp.open(remotePath,
                         java.util.EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create))) {
                in.skipNBytes(offset);
                byte[] buf = new byte[32 * 1024];
                long pos = offset;
                int n;
                while ((n = in.read(buf)) > 0) {
                    sftp.write(handle, pos, buf, 0, n);
                    pos += n;
                    progress.accept(pos);
                }
            }
        }

        @Override
        public void delete(RemoteFile file) throws IOException {
            if (file.directory() && !file.symlink()) {
                sftp.rmdir(file.path());
            } else {
                sftp.remove(file.path());
            }
        }

        @Override
        public void mkdir(String path) throws IOException {
            sftp.mkdir(path);
        }

        @Override
        public void createFile(String path) throws IOException {
            if (exists(path)) {
                throw new java.nio.file.FileAlreadyExistsException(path);
            }
            // 열었다 닫으면 빈 파일이 만들어진다. Exclusive는 그 사이 누가 같은 이름을 만들었을 때를 막는다.
            sftp.open(path, java.util.EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Exclusive)).close();
        }

        @Override
        public void rename(String from, String to) throws IOException {
            sftp.rename(from, to);
        }

        @Override
        public void chmod(String path, int permissions) throws IOException {
            SftpClient.Attributes attrs = new SftpClient.Attributes();
            attrs.setPermissions(permissions & 07777);
            sftp.setStat(path, attrs);
        }

        @Override
        public boolean isOpen() {
            return sftp.isOpen() && lease.session().isOpen();
        }

        @Override
        public void close() {
            try {
                sftp.close();
            } catch (IOException ignored) {
            }
            lease.close();
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
        pool.close();
        pooledClient.stop();
        client.stop();
    }

    /** 열려 있는 MINA 채널 위의 셸. */
    private static final class MinaShellSession implements ShellSession {

        private final SessionPool.SessionLease lease;
        private final ClientChannel channel;
        private final String address;

        MinaShellSession(SessionPool.SessionLease lease, ClientChannel channel, String address) {
            this.lease = lease;
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
                // 채널이 닫히는 중이다. 크기를 조정할 것이 없다.
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
            lease.close();
        }
    }
}
