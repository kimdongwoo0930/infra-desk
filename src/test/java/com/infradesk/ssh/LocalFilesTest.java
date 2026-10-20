package com.infradesk.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFilesTest {

    @TempDir
    Path dir;

    @Test
    void listsFoldersFirstThenByNameIgnoringCase() throws Exception {
        Files.createDirectories(dir.resolve("zeta"));
        Files.createDirectories(dir.resolve("Alpha"));
        Files.writeString(dir.resolve("b.txt"), "12345");
        Files.writeString(dir.resolve("A.txt"), "");
        LocalFiles files = new LocalFiles(dir, null);

        List<RemoteFile> list = files.list(files.home());

        assertEquals(List.of("Alpha", "zeta", "A.txt", "b.txt"), list.stream().map(RemoteFile::name).toList());
        RemoteFile b = list.get(3);
        assertFalse(b.directory());
        assertEquals(5, b.size());
        assertEquals(dir.resolve("b.txt").toString(), b.path());
        assertTrue(list.get(0).directory());
    }

    @Test
    void rejectsNonFolders() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        LocalFiles files = new LocalFiles(dir, null);

        assertThrows(IOException.class, () -> files.list(file.toString()));
        assertThrows(IOException.class, () -> files.list(dir.resolve("missing").toString()));
    }

    @Test
    void joinAndParent() {
        LocalFiles files = new LocalFiles(dir, null);
        Path sub = dir.resolve("a").resolve("b");

        assertEquals(sub.toString(), files.join(dir.resolve("a").toString(), "b"));
        assertEquals(dir.resolve("a").toString(), files.parent(sub.toString()));
    }

    @Test
    void rootStopsGoingUp() throws Exception {
        Path home = Files.createDirectories(dir.resolve("root").resolve("home"));
        LocalFiles files = new LocalFiles(home, dir.resolve("root"));

        assertFalse(files.isRoot(home.toString()));
        assertEquals(dir.resolve("root").toString(), files.parent(home.toString()));
        assertTrue(files.isRoot(dir.resolve("root").toString()));
        assertEquals(dir.resolve("root").toString(), files.parent(dir.resolve("root").toString()), "제한된 루트 위로는 올라가지 않는다");
    }

    @Test
    void restrictedRootCannotBeLeft() {
        Path root = dir.resolve("root");
        Path outside = dir;
        assertThrows(IOException.class, () -> {
            Files.createDirectories(root);
            new LocalFiles(root, root).list(outside.toString());
        });
    }

    @Test
    void demoFolderIsSeededAndConfined() throws Exception {
        LocalFiles demo = DemoLocalFiles.get();

        var names = demo.list(demo.home()).stream().map(RemoteFile::name).toList();
        assertTrue(names.containsAll(List.of("Documents", "Downloads", "notes.txt")), names.toString());
        assertThrows(IOException.class, () -> demo.list(System.getProperty("user.home")), "데모는 실제 홈 폴더를 볼 수 없다");
        assertEquals(demo, DemoLocalFiles.get(), "같은 폴더를 재사용한다");
    }
}
