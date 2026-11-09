package com.infradesk.ui.files;

import com.infradesk.ssh.RemoteFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileClipboardTest {

    private static RemoteFile file(String name) {
        return new RemoteFile(name, "/tmp/" + name, false, 1, Instant.EPOCH);
    }

    @AfterEach
    void cleanUp() {
        FileClipboard.clear();
    }

    @Test
    void 처음에는_비어_있다() {
        assertTrue(FileClipboard.isEmpty());
    }

    @Test
    void 복사한_항목을_기억한다() {
        FileClipboard.set(new FileClipboard.Entry(null, false, "/tmp", List.of(file("a"), file("b"))));

        assertFalse(FileClipboard.isEmpty());
        assertEquals(2, FileClipboard.get().items().size());
        assertEquals("/tmp", FileClipboard.get().dir());
    }

    @Test
    void 복사한_뒤_원래_목록이_바뀌어도_영향이_없다() {
        List<RemoteFile> items = new ArrayList<>(List.of(file("a")));
        FileClipboard.set(new FileClipboard.Entry(null, false, "/tmp", items));

        items.add(file("b"));

        assertEquals(1, FileClipboard.get().items().size());
    }

    @Test
    void 항목이_없는_복사는_빈_클립보드로_본다() {
        FileClipboard.set(new FileClipboard.Entry(null, true, "/tmp", List.of()));

        assertTrue(FileClipboard.isEmpty());
    }
}
