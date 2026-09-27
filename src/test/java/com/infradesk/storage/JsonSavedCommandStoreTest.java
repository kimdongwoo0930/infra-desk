package com.infradesk.storage;

import com.infradesk.ssh.SavedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSavedCommandStoreTest {

    @TempDir
    Path dir;

    @Test
    void keepsOrderAcrossReloads() {
        var a = new SavedCommand("a", "봇 재시작", "docker restart bot-app");
        var b = new SavedCommand("b", "디스크 확인", "df -h && du -sh /var/lib/docker");
        new JsonSavedCommandStore(dir).save(List.of(b, a));
        assertEquals(List.of(b, a), new JsonSavedCommandStore(dir).load());
    }

    @Test
    void missingFileIsEmpty() {
        assertTrue(new JsonSavedCommandStore(dir).load().isEmpty());
    }
}
