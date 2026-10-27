package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 오래 열어 두는 화면(파일 탭)을 위한 {@link RemoteFiles}: 쓸 때 연결하고, 한동안 안 쓰면 풀에 돌려주고,
 * 끊어졌으면 다음에 쓸 때 새로 맺는다.
 *
 * <p>{@code RemoteFiles}를 계속 열어 두면 풀에서 빌린 연결을 계속 쥐게 된다. 그러면 풀이 유휴 연결을 정리하지
 * 못하고(docs/architecture/ssh.md "접속 풀"의 수명·유휴 규칙), 노트북이 잠들었다 깨는 것처럼 연결이 죽었을 때
 * 그 죽은 연결을 계속 쓰게 된다. 이 클래스가 그 둘을 맡아서, 화면은 연결 상태를 신경 쓰지 않고
 * {@code RemoteFiles}처럼 쓰면 된다.
 *
 * <ul>
 *   <li>첫 호출 때 연결한다({@code opener}).</li>
 *   <li>연결이 끊어져 있으면({@link RemoteFiles#isOpen()}) 다음 호출에서 새로 맺는다.</li>
 *   <li>읽기(목록·정보 확인)가 끊어진 연결 때문에 실패하면 새로 맺어서 <b>한 번</b> 다시 한다. 서버를 바꾸는 작업
 *       (업로드·삭제·이름 바꾸기 등)은 응답만 못 받았을 뿐 이미 실행됐을 수 있으므로 다시 하지 않는다(풀의
 *       {@code COMMAND}·{@code FILES} 규칙과 같다). 다음 호출이 새로 맺는다.</li>
 *   <li>{@link #releaseIfIdle(long)}를 부르면 오래 안 쓴 연결을 돌려준다. 작업 중이면 돌려주지 않는다.</li>
 * </ul>
 */
public final class ReconnectingFiles implements RemoteFiles {

    @FunctionalInterface
    private interface Operation<T> {
        T run(RemoteFiles files) throws IOException;
    }

    private final Callable<RemoteFiles> opener;
    private final LongSupplier clock;
    private RemoteFiles current;
    /** 지금 실행 중인 호출 수. 0이 아니면 연결을 돌려주지 않는다. */
    private int active;
    private long lastUse;
    private boolean closed;

    public ReconnectingFiles(Callable<RemoteFiles> opener) {
        this(opener, System::nanoTime);
    }

    ReconnectingFiles(Callable<RemoteFiles> opener, LongSupplier clock) {
        this.opener = opener;
        this.clock = clock;
    }

    // ── 연결 관리 ───────────────────────────────────────────────

    /** 쓸 연결을 돌려준다(없거나 끊겼으면 새로 맺는다). 부른 뒤에는 반드시 {@link #finish()}를 부른다. */
    private synchronized RemoteFiles begin() throws IOException {
        if (closed) {
            throw new IOException("닫힌 연결이에요");
        }
        if (current != null && !current.isOpen()) {
            closeQuietly(current);
            current = null;
        }
        if (current == null) {
            current = open();
        }
        active++;
        lastUse = clock.getAsLong();
        return current;
    }

    private synchronized void finish() {
        active--;
        lastUse = clock.getAsLong();
    }

    private RemoteFiles open() throws IOException {
        try {
            return opener.call();
        } catch (RuntimeException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 연결을 돌려줄 때가 됐는지 본다.
     *
     * @param idleNanos 이 시간 이상 안 썼으면 돌려준다
     * @return 돌려줬으면 true
     */
    public synchronized boolean releaseIfIdle(long idleNanos) {
        if (current == null || active > 0 || clock.getAsLong() - lastUse < idleNanos) {
            return false;
        }
        RemoteFiles released = current;
        current = null;
        closeQuietly(released);
        return true;
    }

    /** 지금 연결을 쥐고 있는지(테스트와 화면 표시용). */
    public synchronized boolean connected() {
        return current != null;
    }

    private static void closeQuietly(RemoteFiles files) {
        try {
            files.close();
        } catch (RuntimeException ignored) {
            // 이미 끊어진 연결이다.
        }
    }

    // ── 호출 ────────────────────────────────────────────────────

    /** 서버를 바꾸지 않는 호출. 끊어진 연결 때문에 실패했으면 새로 맺어 한 번 더 한다. */
    private <T> T read(Operation<T> operation) throws IOException {
        RemoteFiles files = begin();
        try {
            return operation.run(files);
        } catch (IOException e) {
            if (files.isOpen()) {
                throw e;   // 연결 문제가 아니다(없는 파일, 권한 없음 등)
            }
        } finally {
            finish();
        }
        RemoteFiles again = begin();
        try {
            return operation.run(again);
        } finally {
            finish();
        }
    }

    /** 서버를 바꾸는 호출. 다시 하지 않는다. */
    private <T> T write(Operation<T> operation) throws IOException {
        RemoteFiles files = begin();
        try {
            return operation.run(files);
        } finally {
            finish();
        }
    }

    @Override
    public String home() throws IOException {
        return read(RemoteFiles::home);
    }

    @Override
    public List<RemoteFile> list(String directory) throws IOException {
        return read(files -> files.list(directory));
    }

    @Override
    public boolean exists(String path) throws IOException {
        return read(files -> files.exists(path));
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        return read(files -> files.stat(path));
    }

    @Override
    public void download(String remotePath, Path localFile, long offset, LongConsumer progress) throws IOException {
        write(files -> {
            files.download(remotePath, localFile, offset, progress);
            return null;
        });
    }

    @Override
    public void upload(Path localFile, String remotePath, long offset, LongConsumer progress) throws IOException {
        write(files -> {
            files.upload(localFile, remotePath, offset, progress);
            return null;
        });
    }

    @Override
    public void delete(RemoteFile file) throws IOException {
        write(files -> {
            files.delete(file);
            return null;
        });
    }

    @Override
    public void mkdir(String path) throws IOException {
        write(files -> {
            files.mkdir(path);
            return null;
        });
    }

    @Override
    public void createFile(String path) throws IOException {
        write(files -> {
            files.createFile(path);
            return null;
        });
    }

    @Override
    public void rename(String from, String to) throws IOException {
        write(files -> {
            files.rename(from, to);
            return null;
        });
    }

    @Override
    public void chmod(String path, int permissions) throws IOException {
        write(files -> {
            files.chmod(path, permissions);
            return null;
        });
    }

    @Override
    public synchronized boolean isOpen() {
        return !closed;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (current != null) {
            closeQuietly(current);
            current = null;
        }
    }
}
