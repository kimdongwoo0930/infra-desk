package com.infradesk.ssh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectingFilesTest {

    /** 연결 하나를 흉내 낸다: 열려 있는지, 다음 호출을 실패시킬지, 닫혔는지를 테스트가 바꾼다. */
    private static final class FakeFiles implements RemoteFiles {
        final int number;
        boolean open = true;
        boolean closed;
        /** true면 다음 호출을 IOException으로 실패시키고, {@code dieOnFailure}면 연결도 끊는다. */
        boolean failNext;
        boolean dieOnFailure;
        Runnable duringDownload = () -> { };

        FakeFiles(int number) {
            this.number = number;
        }

        private void maybeFail() throws IOException {
            if (failNext) {
                failNext = false;
                if (dieOnFailure) {
                    open = false;
                }
                throw new IOException("연결 오류 " + number);
            }
        }

        @Override
        public String home() throws IOException {
            maybeFail();
            return "/home/u" + number;
        }

        @Override
        public List<RemoteFile> list(String directory) throws IOException {
            maybeFail();
            return List.of(new RemoteFile("f" + number, directory + "/f" + number, false, 1, Instant.EPOCH));
        }

        @Override
        public boolean exists(String path) throws IOException {
            maybeFail();
            return true;
        }

        @Override
        public RemoteFile stat(String path) throws IOException {
            maybeFail();
            return new RemoteFile("s", path, false, 1, Instant.EPOCH);
        }

        @Override
        public void download(String remotePath, Path localFile, long offset, LongConsumer progress) throws IOException {
            maybeFail();
            duringDownload.run();
        }

        @Override
        public void upload(Path localFile, String remotePath, long offset, LongConsumer progress) throws IOException {
            maybeFail();
        }

        @Override
        public void delete(RemoteFile file) throws IOException {
            maybeFail();
        }

        @Override
        public void mkdir(String path) throws IOException {
            maybeFail();
        }

        @Override
        public void createFile(String path) throws IOException {
            maybeFail();
        }

        @Override
        public void rename(String from, String to) throws IOException {
            maybeFail();
        }

        @Override
        public void chmod(String path, int permissions) throws IOException {
            maybeFail();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final List<FakeFiles> opened = new ArrayList<>();
    private final AtomicLong now = new AtomicLong();
    private final ReconnectingFiles files = new ReconnectingFiles(() -> {
        FakeFiles next = new FakeFiles(opened.size() + 1);
        opened.add(next);
        return next;
    }, now::get);

    @Test
    void connectsOnFirstUseAndReusesTheConnection() throws Exception {
        assertTrue(opened.isEmpty(), "쓰기 전에는 연결하지 않는다");
        assertFalse(files.connected());

        assertEquals("/home/u1", files.home());
        files.list("/");
        files.stat("/x");

        assertEquals(1, opened.size());
        assertTrue(files.connected());
    }

    @Test
    void reconnectsWhenTheConnectionWasDroppedBetweenCalls() throws Exception {
        files.home();
        opened.get(0).open = false;

        assertEquals("/home/u2", files.home());

        assertEquals(2, opened.size());
        assertTrue(opened.get(0).closed, "끊어진 연결은 정리한다");
    }

    @Test
    void aReadThatFailsBecauseTheConnectionDiedIsRetriedOnceOnANewConnection() throws Exception {
        files.home();
        opened.get(0).failNext = true;
        opened.get(0).dieOnFailure = true;

        List<RemoteFile> list = files.list("/d");

        assertEquals("f2", list.get(0).name(), "새 연결의 결과");
        assertEquals(2, opened.size());
    }

    @Test
    void aReadThatFailsOnAHealthyConnectionIsNotRetried() throws Exception {
        files.home();
        opened.get(0).failNext = true;

        assertThrows(IOException.class, () -> files.stat("/없는파일"));

        assertEquals(1, opened.size(), "파일이 없는 것 같은 오류로 다시 접속하지 않는다");
    }

    @Test
    void aWriteIsNeverRetriedButTheNextCallReconnects() throws Exception {
        files.home();
        opened.get(0).failNext = true;
        opened.get(0).dieOnFailure = true;

        assertThrows(IOException.class, () -> files.mkdir("/new"));
        assertEquals(1, opened.size(), "서버에서 이미 실행됐을 수 있어서 다시 하지 않는다");

        files.mkdir("/new");
        assertEquals(2, opened.size(), "다음 호출은 새로 연결한다");
    }

    @Test
    void releasesAConnectionThatWasIdleLongEnoughAndReconnectsOnNextUse() throws Exception {
        files.home();
        long idle = Duration.ofMinutes(3).toNanos();

        now.set(idle - 1);
        assertFalse(files.releaseIfIdle(idle), "아직 덜 지났다");
        assertTrue(files.connected());

        now.set(idle);
        assertTrue(files.releaseIfIdle(idle));
        assertFalse(files.connected());
        assertTrue(opened.get(0).closed);

        assertEquals("/home/u2", files.home());
    }

    @Test
    void doesNotReleaseWhileACallIsRunning() throws Exception {
        files.home();
        now.set(Duration.ofHours(1).toNanos());
        boolean[] released = new boolean[1];
        opened.get(0).duringDownload = () -> released[0] = files.releaseIfIdle(0);

        files.download("/a", Path.of("a"), 0, done -> { });

        assertFalse(released[0], "전송 도중에 연결을 돌려주면 안 된다");
        assertFalse(opened.get(0).closed);
    }

    @Test
    void usingAClosedConnectionFailsInsteadOfReconnecting() throws Exception {
        files.home();
        files.close();

        assertTrue(opened.get(0).closed);
        assertThrows(IOException.class, files::home);
        assertEquals(1, opened.size());
        assertFalse(files.isOpen());
    }
}
