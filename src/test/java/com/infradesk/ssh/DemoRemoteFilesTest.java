package com.infradesk.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoRemoteFilesTest {

    @TempDir
    Path dir;

    @Test
    void seededTreeAndUpload() throws Exception {
        DemoRemoteFiles files = new DemoRemoteFiles("ubuntu", "demo-test-" + System.nanoTime());
        var names = files.list(files.home()).stream().map(RemoteFile::name).toList();
        assertTrue(names.containsAll(java.util.List.of("app", "backups", "README.md")), names.toString());

        Path local = Files.writeString(dir.resolve("a.txt"), "hi");
        files.upload(local, files.home() + "/a.txt", p -> { });
        assertTrue(files.exists(files.home() + "/a.txt"));
        RemoteFile app = files.list(files.home()).getFirst();
        assertThrows(java.io.IOException.class, () -> files.delete(app), "non-empty folder");
    }
}
