package com.infradesk.ssh;

import com.infradesk.demo.DemoLocalFiles;
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

    @Test
    void mkdirRenameAndDeleteInsideARestrictedRoot() throws Exception {
        LocalFiles files = new LocalFiles(dir, dir);
        String made = files.join(dir.toString(), "work");

        files.mkdir(made);
        assertTrue(Files.isDirectory(dir.resolve("work")));
        assertThrows(IOException.class, () -> files.mkdir(made), "이미 있는 폴더");

        files.rename(made, files.join(dir.toString(), "project"));
        assertTrue(Files.isDirectory(dir.resolve("project")));
        Files.writeString(dir.resolve("project/a.txt"), "x");
        Files.createDirectories(dir.resolve("project/sub"));
        Files.writeString(dir.resolve("project/sub/b.txt"), "y");

        boolean trashed = files.delete(dir.resolve("project").toString());

        assertFalse(trashed, "제한된 루트에서는 휴지통을 쓰지 않는다");
        assertFalse(Files.exists(dir.resolve("project")));
    }

    @Test
    void deleteDoesNotFollowSymlinks() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "must survive");
        Path root = Files.createDirectories(dir.resolve("root"));
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경");
        }
        LocalFiles files = new LocalFiles(root, root);
        assertTrue(files.list(root.toString()).getFirst().symlink());

        files.delete(root.resolve("link").toString());

        assertFalse(Files.exists(root.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outside.resolve("keep.txt")), "링크가 가리키던 곳의 파일은 남아야 한다");
    }

    @Test
    void cannotChangeAnythingOutsideTheRestrictedRoot() {
        Path root = dir.resolve("root");
        LocalFiles files = new LocalFiles(root, root);
        assertThrows(IOException.class, () -> files.mkdir(dir.resolve("outside").toString()));
        assertThrows(IOException.class, () -> files.delete(dir.toString()));
        assertThrows(IOException.class, () -> files.rename(dir.resolve("x").toString(), root.resolve("y").toString()));
    }

    @Test
    void createFileMakesAnEmptyFileInsideTheRoot() throws Exception {
        LocalFiles files = new LocalFiles(dir, dir);
        String path = files.join(dir.toString(), "new.txt");

        files.createFile(path);

        assertTrue(Files.isRegularFile(dir.resolve("new.txt")));
        assertEquals(0, Files.size(dir.resolve("new.txt")));
        Files.writeString(dir.resolve("keep.txt"), "keep me");
        assertThrows(IOException.class, () -> files.createFile(files.join(dir.toString(), "keep.txt")), "덮어쓰지 않는다");
        assertEquals("keep me", Files.readString(dir.resolve("keep.txt")));
        assertThrows(IOException.class, () -> new LocalFiles(dir.resolve("root"), dir.resolve("root"))
                .createFile(dir.resolve("outside.txt").toString()), "제한된 루트 밖에는 만들 수 없다");
    }

    @Test
    void downloadsGoToTheDownloadsFolderWhenItExistsAndToHomeOtherwise() throws Exception {
        assertEquals(dir, LocalFiles.downloadsFolder(dir), "no Downloads folder: use home");

        Files.writeString(dir.resolve("Downloads"), "a file, not a folder");
        assertEquals(dir, LocalFiles.downloadsFolder(dir), "a file named Downloads does not count");
        Files.delete(dir.resolve("Downloads"));

        Files.createDirectories(dir.resolve("Downloads"));
        assertEquals(dir.resolve("Downloads"), LocalFiles.downloadsFolder(dir));
    }

    @Test
    void startsInHomeEvenWhenADownloadsFolderExists() throws Exception {
        Files.createDirectories(dir.resolve("Downloads"));

        LocalFiles files = LocalFiles.forHome(dir);

        // macOS는 Downloads 같은 폴더를 앱에 따로 허용해야 열어 주므로, 시작은 홈이고 받는 곳만 Downloads다.
        assertEquals(dir.toAbsolutePath().normalize().toString(), files.home());
        assertEquals(dir.resolve("Downloads").toAbsolutePath().normalize().toString(), files.downloadFolder());
    }

    @Test
    void aRestrictedRootSavesDownloadsInHome() throws Exception {
        LocalFiles demo = new LocalFiles(dir, dir);

        assertEquals(demo.home(), demo.downloadFolder());
    }
}
