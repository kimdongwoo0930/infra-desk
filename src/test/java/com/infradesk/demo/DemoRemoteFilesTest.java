package com.infradesk.demo;

import com.infradesk.ssh.RemoteFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void mkdirRenameChmodAndRecursiveDelete() throws Exception {
        DemoRemoteFiles files = new DemoRemoteFiles("ubuntu", "demo-ops-" + System.nanoTime());
        String home = files.home();

        files.mkdir(home + "/work");
        files.upload(Files.writeString(dir.resolve("n.txt"), "note"), home + "/work/n.txt", p -> { });
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> files.mkdir(home + "/work"));

        files.rename(home + "/work", home + "/project");
        assertTrue(files.exists(home + "/project/n.txt"), "폴더 안의 파일도 같이 옮겨진다");
        assertFalse(files.exists(home + "/work"));

        files.chmod(home + "/project/n.txt", 0600);
        RemoteFile n = files.list(home + "/project").getFirst();
        assertEquals(0600, n.permissions());

        RemoteFile project = files.list(home).stream().filter(f -> f.name().equals("project")).findFirst().orElseThrow();
        files.deleteRecursively(project);
        assertFalse(files.exists(home + "/project"));
    }

    @Test
    void resumeKeepsTheAlreadyTransferredPart() throws Exception {
        DemoRemoteFiles files = new DemoRemoteFiles("ubuntu", "demo-resume-" + System.nanoTime());
        String remote = files.home() + "/app/bot.log";
        byte[] full = new byte[0];
        Path whole = dir.resolve("whole.log");
        files.download(remote, whole, p -> { });
        full = Files.readAllBytes(whole);
        Path partial = dir.resolve("partial.log");
        Files.write(partial, java.util.Arrays.copyOf(full, 1000));

        files.download(remote, partial, 1000, p -> { });

        org.junit.jupiter.api.Assertions.assertArrayEquals(full, Files.readAllBytes(partial));
    }

    @Test
    void createFileMakesAnEmptyFileAndRefusesToOverwrite() throws Exception {
        DemoRemoteFiles files = new DemoRemoteFiles("ubuntu", "demo-create-" + System.nanoTime());
        String home = files.home();

        files.createFile(home + "/new.txt");

        assertTrue(files.exists(home + "/new.txt"));
        assertEquals(0, files.stat(home + "/new.txt").size());
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> files.createFile(home + "/README.md"));
        assertThrows(java.nio.file.NoSuchFileException.class, () -> files.createFile(home + "/nodir/x.txt"));
    }
}
