package com.infradesk.ssh;

import org.apache.sshd.client.session.ClientSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 서버와 목적({@link Purpose})마다 SSH 연결 하나를 유지하고 재사용한다. 접속(TCP+인증)은 서버의 인증 로그에
 * 남고 시간도 걸리므로, 명령마다 새로 접속하지 않고 한 연결 위에서 채널만 연다.
 *
 * <p>연결을 오래 두는 만큼 아래 규칙을 지킨다.
 * <ul>
 *   <li><b>수명 12시간.</b> 한 키로 인증된 연결을 너무 오래 두지 않는다. 12시간이 지나면 <b>쓰는 곳이 없는 빈틈에</b>
 *       닫고 다음에 새로 맺는다(그때 키를 보관소에서 다시 읽는다). 조회(QUERY) 연결은 빈틈이 {@value #FORCE_MINUTES}분
 *       동안 안 생기면 강제로 닫는다. 전송 등 다른 목적은 쓰는 중이면 끝날 때까지 기다린다.</li>
 *   <li><b>유휴 종료.</b> 조회는 15분, 나머지는 5분 동안 쓰지 않으면 닫는다.</li>
 *   <li><b>설정이 바뀌면 즉시 끊는다.</b> 사용자 이름·키·주소가 달라지면 다음 사용 때 옛 연결을 버리고, 서버 설정을
 *       저장하거나 서버를 지우면({@link #invalidate}) 그 서버의 연결을 바로 닫는다.</li>
 *   <li><b>창이 메뉴 막대로 숨으면</b>({@link #setBackground}) 조회 연결만 남긴다. 쓰고 있지 않은 다른 연결은 바로
 *       닫고, 쓰는 중인 연결은 끝나는 대로 닫는다(전송이 중간에 끊기지 않게).</li>
 * </ul>
 *
 * <p>연결을 맺는 동안 같은 키의 다른 요청은 기다린다(같은 서버에 연결이 둘 생기지 않게). 개인키는 풀에 남기지 않고
 * 바뀌었는지 알아볼 해시만 보관한다.
 */
final class SessionPool implements AutoCloseable {

    /** 연결을 맺고 인증까지 끝낸다. 실패하면 {@link SshException}. */
    interface Connector {
        ClientSession connect(SshTarget target, HostKeyPrompt prompt);
    }

    /** 풀에서 빌린 연결. 다 쓰면 {@link #close}로 돌려준다. */
    interface SessionLease extends AutoCloseable {
        ClientSession session();

        /** 이미 열려 있던 연결을 재사용했는지(새로 맺었으면 false). 재사용한 연결이 죽어 있었는지 가르는 데 쓴다. */
        boolean reused();

        /** 연결을 돌려준다. 풀 밖에서 맺은 연결({@link #owning})이면 연결을 닫는다. */
        @Override
        void close();

        /** 이 연결이 죽었다. 풀에서 빼고 닫는다. */
        void discard();
    }

    /** 풀 밖에서 맺은 연결을 감싼다. 돌려주면 연결을 닫는다(터미널 탭처럼 사용자가 열고 닫는 연결). */
    static SessionLease owning(ClientSession session) {
        return new SessionLease() {
            private boolean closed;

            @Override
            public ClientSession session() {
                return session;
            }

            @Override
            public boolean reused() {
                return false;
            }

            @Override
            public synchronized void close() {
                if (!closed) {
                    closed = true;
                    session.close(false);
                }
            }

            @Override
            public void discard() {
                close();
            }
        };
    }

    static final int MAX_AGE_HOURS = 12;
    static final int FORCE_MINUTES = 10;
    static final Duration MAX_AGE = Duration.ofHours(MAX_AGE_HOURS);
    static final Duration FORCE_AFTER = Duration.ofMinutes(FORCE_MINUTES);
    private static final long MAINTAIN_SECONDS = 30;

    private record Key(String id, Purpose purpose) {
    }

    private static final class Entry {
        final Key key;
        final ReentrantLock lock = new ReentrantLock();
        ClientSession session;
        String credentialHash;
        Instant createdAt;
        Instant lastUsed;
        /** 지금 빌려 간 곳의 수. */
        int active;
        /** 세션이 바뀔 때마다 올려서, 닫힌 옛 세션을 쥔 곳이 돌려줄 때 새 세션의 수를 건드리지 않게 한다. */
        int generation;
        volatile boolean closeWhenIdle;
        volatile boolean stale;

        Entry(Key key) {
            this.key = key;
        }
    }

    private final Connector connector;
    private final Clock clock;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintainer;
    private volatile boolean background;

    /** @param autoMaintain true면 30초마다 스스로 정리한다(테스트는 false로 두고 {@link #maintain}을 직접 부른다) */
    SessionPool(Connector connector, Clock clock, boolean autoMaintain) {
        this.connector = connector;
        this.clock = clock;
        if (autoMaintain) {
            this.maintainer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ssh-pool-maintenance");
                t.setDaemon(true);
                return t;
            });
            maintainer.scheduleWithFixedDelay(this::maintain, MAINTAIN_SECONDS, MAINTAIN_SECONDS, TimeUnit.SECONDS);
        } else {
            this.maintainer = null;
        }
    }

    /**
     * 연결을 빌린다. 쓸 수 있는 연결이 있으면 그대로, 없으면 새로 맺는다. 새로 맺다가 실패하면
     * {@link SshException}이 올라가고 풀에는 아무것도 남지 않는다.
     */
    SessionLease acquire(Purpose purpose, SshTarget target, HostKeyPrompt prompt) {
        Key key = new Key(target.id() != null ? target.id() : target.address(), purpose);
        Entry e = entries.computeIfAbsent(key, Entry::new);
        String hash = credentialHash(target);
        e.lock.lock();
        try {
            Instant now = clock.instant();
            boolean reused = false;
            if (e.session != null && shouldDiscard(e, hash, now)) {
                closeSession(e);
            }
            if (e.session == null) {
                e.session = connector.connect(target, prompt);
                e.credentialHash = hash;
                e.createdAt = now;
                e.stale = false;
                e.closeWhenIdle = background && purpose != Purpose.QUERY;
            } else {
                reused = true;
            }
            e.active++;
            e.lastUsed = now;
            return new PoolLease(e, e.session, e.generation, reused);
        } finally {
            e.lock.unlock();
        }
    }

    /** 이 연결을 계속 써도 되는지. 죽었거나, 설정이 바뀌었거나, 수명이 다했으면 버린다. */
    private boolean shouldDiscard(Entry e, String hash, Instant now) {
        if (!e.session.isOpen() || e.session.isClosing() || e.stale || !hash.equals(e.credentialHash)) {
            return true;
        }
        return expired(e, now) && (e.active == 0 || forced(e, now));
    }

    private static boolean expired(Entry e, Instant now) {
        return !now.isBefore(e.createdAt.plus(MAX_AGE));
    }

    /** 수명이 다한 뒤에도 빈틈이 안 생겨서 강제로 바꿔야 하는지. 조회 연결만 해당한다. */
    private static boolean forced(Entry e, Instant now) {
        return e.key.purpose() == Purpose.QUERY && !now.isBefore(e.createdAt.plus(MAX_AGE).plus(FORCE_AFTER));
    }

    private void release(Entry e, int generation) {
        e.lock.lock();
        try {
            if (generation != e.generation) {
                return;
            }
            e.active = Math.max(0, e.active - 1);
            e.lastUsed = clock.instant();
            if (e.active == 0 && (e.closeWhenIdle || e.stale) && e.session != null) {
                closeSession(e);
            }
        } finally {
            e.lock.unlock();
        }
    }

    /** 락을 쥔 채로 부른다. */
    private void closeSession(Entry e) {
        ClientSession s = e.session;
        e.session = null;
        e.active = 0;
        e.generation++;
        e.credentialHash = null;
        e.closeWhenIdle = false;
        if (s != null) {
            s.close(false);
        }
    }

    /**
     * 창이 메뉴 막대로만 숨었는지. 숨으면 조회 연결만 남기고, 쓰고 있지 않은 다른 연결은 바로 닫는다. 쓰는 중인
     * 연결은 끝나는 대로 닫는다. 다시 나타나면 닫을 예정이던 표시를 지운다.
     */
    void setBackground(boolean background) {
        this.background = background;
        for (Entry e : entries.values()) {
            if (e.key.purpose() == Purpose.QUERY) {
                continue;
            }
            e.closeWhenIdle = background;
            if (background && e.lock.tryLock()) {
                try {
                    if (e.session != null && e.active == 0) {
                        closeSession(e);
                    }
                } finally {
                    e.lock.unlock();
                }
            }
        }
    }

    /** 이 서버의 설정이 바뀌었거나 서버가 지워졌다. 연결 중이 아니면 바로 닫고, 연결 중이면 표시만 해 둔다. */
    void invalidate(String serverId) {
        for (Entry e : entries.values()) {
            if (!e.key.id().equals(serverId)) {
                continue;
            }
            e.stale = true;
            if (e.lock.tryLock()) {
                try {
                    if (e.session != null) {
                        closeSession(e);
                    }
                } finally {
                    e.lock.unlock();
                }
            }
        }
    }

    /**
     * 사용자가 설정 화면에서 연결 하나를 끊었다. 놀고 있는 연결은 바로 닫는다. 쓰는 중인 연결(전송, 실행 중인 명령,
     * 파일 탭이 쥐고 있는 것)은 {@code force}일 때만 닫는다: 그 연결 위의 채널은 끊기고, 쥐고 있던 쪽은 다음에 쓸 때
     * 알아서 다시 맺거나(파일 탭) 실패로 끝난다(전송, 명령). 닫은 뒤 그 서버를 다시 쓰면 필요할 때 새로 맺는다.
     *
     * @return 끊었으면 true. 열려 있지 않거나, 쓰는 중인데 {@code force}가 아니거나, 접속·반납 중이라 잠글 수 없으면 false
     */
    boolean disconnect(String serverId, Purpose purpose, boolean force) {
        Entry e = entries.get(new Key(serverId, purpose));
        return e != null && close(e, force);
    }

    /** 연결을 모두 끊는다. {@code force}가 아니면 쓰는 중인 연결은 둔다. @return 끊은 연결 수 */
    int disconnectAll(boolean force) {
        int closed = 0;
        for (Entry e : entries.values()) {
            if (close(e, force)) {
                closed++;
            }
        }
        return closed;
    }

    private boolean close(Entry e, boolean force) {
        try {
            // 강제로 끊을 때는 접속하는 중인 연결이 끝나기를 잠깐(화면이 멈추지 않을 만큼만) 기다린다
            if (!(force ? e.lock.tryLock(300, java.util.concurrent.TimeUnit.MILLISECONDS) : e.lock.tryLock())) {
                return false;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            if (e.session == null || (!force && e.active > 0)) {
                return false;
            }
            ClientSession s = e.session;
            closeSession(e);
            if (force) {
                s.close(true);   // 쓰는 중인 채널을 기다리지 않고 바로 끊는다
            }
            return true;
        } finally {
            e.lock.unlock();
        }
    }

    /** 주기적으로 부른다: 죽은 연결, 유휴 연결, 수명이 다한 연결을 닫는다. 쓰는 중인 연결은 건드리지 않는다. */
    void maintain() {
        Instant now = clock.instant();
        for (Entry e : entries.values()) {
            if (!e.lock.tryLock()) {
                continue;
            }
            try {
                if (e.session == null) {
                    continue;
                }
                boolean dead = !e.session.isOpen() || e.session.isClosing();
                boolean idle = e.active == 0 && !now.isBefore(e.lastUsed.plus(e.key.purpose().idleTimeout()));
                boolean retiring = expired(e, now) && (e.active == 0 || forced(e, now));
                boolean pending = e.active == 0 && (e.closeWhenIdle || e.stale);
                if (dead || idle || retiring || pending) {
                    closeSession(e);
                }
            } finally {
                e.lock.unlock();
            }
        }
    }

    /**
     * 지금 열려 있는 연결들의 상태(읽기 전용, 서버 이름순 → 용도순). 연결을 만들거나 닫지 않고 화면에 보여 주기만
     * 한다. 연결하는 중이거나 닫는 중이라 잠겨 있는 것은 이번에는 건너뛴다(곧 다음에 나온다).
     */
    List<OpenConnection> snapshot() {
        List<OpenConnection> result = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.lock.tryLock()) {
                continue;
            }
            try {
                if (e.session != null && e.session.isOpen()) {
                    result.add(new OpenConnection(e.key.id(), e.key.purpose(), e.createdAt, e.lastUsed, e.active));
                }
            } finally {
                e.lock.unlock();
            }
        }
        result.sort(java.util.Comparator.comparing(OpenConnection::serverId)
                .thenComparing(c -> c.purpose().ordinal()));
        return result;
    }

    /** 지금 열려 있는 연결 수(테스트·점검용). */
    int openCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.session != null && e.session.isOpen()) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        if (maintainer != null) {
            maintainer.shutdownNow();
        }
        for (Entry e : entries.values()) {
            e.lock.lock();
            try {
                if (e.session != null) {
                    closeSession(e);
                }
            } finally {
                e.lock.unlock();
            }
        }
    }

    /** 사용자 이름·키·주소가 바뀌었는지 알아보는 해시. 개인키 자체는 풀에 남기지 않는다. */
    private static String credentialHash(SshTarget t) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {t.host(), String.valueOf(t.port()), t.username(), t.privateKeyPem(),
                    t.passphrase() == null ? "" : t.passphrase()}) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private final class PoolLease implements SessionLease {
        private final Entry entry;
        private final ClientSession session;
        private final int generation;
        private final boolean reused;
        private boolean released;

        PoolLease(Entry entry, ClientSession session, int generation, boolean reused) {
            this.entry = entry;
            this.session = session;
            this.generation = generation;
            this.reused = reused;
        }

        @Override
        public ClientSession session() {
            return session;
        }

        @Override
        public boolean reused() {
            return reused;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            release(entry, generation);
        }

        @Override
        public void discard() {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            entry.lock.lock();
            try {
                if (generation == entry.generation && entry.session != null) {
                    closeSession(entry);
                }
            } finally {
                entry.lock.unlock();
            }
        }
    }
}
