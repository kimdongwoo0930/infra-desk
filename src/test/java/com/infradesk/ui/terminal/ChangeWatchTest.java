package com.infradesk.ui.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeWatchTest {

    @TempDir
    Path dir;

    private static void touch(Path file, String content, long millis) throws Exception {
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
    }

    @Test
    void reportsAChangeOnlyOnceItHasSettled() throws Exception {
        Path file = dir.resolve("a.conf");
        touch(file, "one", 1_000_000);
        ChangeWatch watch = new ChangeWatch(file);

        assertFalse(watch.poll(), "바뀌지 않았다");
        touch(file, "two!", 2_000_000);
        assertFalse(watch.poll(), "방금 바뀌었으니 한 번 더 확인한다(저장 중일 수 있다)");
        assertTrue(watch.poll(), "그대로면 저장이 끝난 것");
        assertTrue(watch.poll(), "처리하기 전까지는 계속 알린다");
    }

    @Test
    void keepsWaitingWhileTheFileIsStillChanging() throws Exception {
        Path file = dir.resolve("b.conf");
        touch(file, "x", 1_000_000);
        ChangeWatch watch = new ChangeWatch(file);

        touch(file, "xx", 2_000_000);
        assertFalse(watch.poll());
        touch(file, "xxx", 3_000_000);
        assertFalse(watch.poll(), "또 바뀌었다. 아직 저장 중");
        assertTrue(watch.poll());
    }

    @Test
    void acknowledgeStartsOver() throws Exception {
        Path file = dir.resolve("c.conf");
        touch(file, "x", 1_000_000);
        ChangeWatch watch = new ChangeWatch(file);
        touch(file, "xx", 2_000_000);
        watch.poll();
        assertTrue(watch.poll());

        watch.acknowledge();
        assertFalse(watch.poll(), "처리한 상태는 다시 알리지 않는다");
        assertFalse(watch.dirty());

        touch(file, "xxxx", 4_000_000);
        assertTrue(watch.dirty(), "처리한 뒤에 또 바뀌었다");
        assertFalse(watch.poll());
        assertTrue(watch.poll(), "새 변경은 다시 감지한다");
    }

    @Test
    void ignoresTheMomentWhenAnEditorReplacesTheFile() throws Exception {
        Path file = dir.resolve("d.conf");
        touch(file, "x", 1_000_000);
        ChangeWatch watch = new ChangeWatch(file);

        Files.delete(file);
        assertFalse(watch.poll(), "잠깐 없어도 오류 없이 기다린다");
        assertFalse(watch.dirty());

        touch(file, "new", 2_000_000);
        assertFalse(watch.poll());
        assertTrue(watch.poll());
    }
}
