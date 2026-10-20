package com.infradesk.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 임시 디렉터리를 루트로 하는 프로세스 내 MINA 서버에 대한 SFTP. */
class SftpTest {

    @TempDir
    Path dir;

    private SshServer server;
    private MinaShellConnector connector;
    private RemoteFiles files;
    private Path remoteRoot;

    @BeforeEach
    void start() throws Exception {
        remoteRoot = Files.createDirectories(dir.resolve("remote"));
        Files.createDirectories(remoteRoot.resolve("app"));
        Files.writeString(remoteRoot.resolve("README.md"), "hello");

        KeyPair key = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host.ser")));
        server.setPublickeyAuthenticator((u, k, s) -> KeyUtils.compareKeys(k, key.getPublic()));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.start();

        ByteArrayOutputStream pem = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "t", null, pem);
        connector = new MinaShellConnector(dir.resolve("known_hosts"));
        files = connector.sftp(new SshTarget("127.0.0.1", server.getPort(), "ubuntu",
                pem.toString(StandardCharsets.UTF_8), null), (h, p, t, f) -> true);
    }

    @AfterEach
    void stop() throws Exception {
        files.close();
        connector.close();
        server.stop(true);
    }

    @Test
    void listsDirectoriesFirst() throws Exception {
        List<RemoteFile> list = files.list(files.home());
        assertEquals(List.of("app", "README.md"), list.stream().map(RemoteFile::name).toList());
        assertTrue(list.getFirst().directory());
        assertEquals(5, list.get(1).size());
    }

    @Test
    void uploadDownloadDeleteRoundTrip() throws Exception {
        Path local = Files.writeString(dir.resolve("local.txt"), "x".repeat(200_000));
        String remote = RemoteFiles.join(files.home(), "app/uploaded.txt");
        AtomicLong progress = new AtomicLong();

        files.upload(local, remote, progress::set);
        assertEquals(200_000, progress.get());
        assertTrue(files.exists(remote));
        assertEquals(200_000, Files.size(remoteRoot.resolve("app/uploaded.txt")));

        Path back = dir.resolve("back.txt");
        files.download(remote, back, p -> { });
        assertEquals(Files.readString(local), Files.readString(back));

        files.delete(new RemoteFile("uploaded.txt", remote, false, 0, null));
        assertFalse(files.exists(remote));
    }

    @Test
    void mkdirRenameAndChmod() throws Exception {
        String base = files.home();
        files.mkdir(RemoteFiles.join(base, "newdir"));
        assertTrue(Files.isDirectory(remoteRoot.resolve("newdir")));

        files.rename(RemoteFiles.join(base, "newdir"), RemoteFiles.join(base, "renamed"));
        assertFalse(files.exists(RemoteFiles.join(base, "newdir")));
        assertTrue(files.exists(RemoteFiles.join(base, "renamed")));

        assertThrows(java.io.IOException.class, () -> files.mkdir(RemoteFiles.join(base, "renamed")), "이미 있는 폴더");

        assumeTrue(Files.getFileStore(remoteRoot).supportsFileAttributeView("posix"), "POSIX 권한이 있는 파일 시스템만");
        files.chmod(RemoteFiles.join(base, "README.md"), 0600);
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(remoteRoot.resolve("README.md"))));
        RemoteFile readme = files.list(base).stream().filter(f -> f.name().equals("README.md")).findFirst().orElseThrow();
        assertEquals(0600, readme.permissions());
        assertEquals("rw-------", readme.permissionText());
    }

    @Test
    void deleteRecursivelyRemovesNonEmptyFolders() throws Exception {
        Files.createDirectories(remoteRoot.resolve("tree/a/b"));
        Files.writeString(remoteRoot.resolve("tree/a/b/deep.txt"), "x");
        Files.writeString(remoteRoot.resolve("tree/top.txt"), "y");
        RemoteFile tree = files.list(files.home()).stream().filter(f -> f.name().equals("tree")).findFirst().orElseThrow();

        assertThrows(java.io.IOException.class, () -> files.delete(tree), "비어 있지 않으면 delete는 실패");
        files.deleteRecursively(tree);

        assertFalse(Files.exists(remoteRoot.resolve("tree")));
    }

    @Test
    void deleteRecursivelyDoesNotFollowSymlinks() throws Exception {
        Files.writeString(remoteRoot.resolve("app/keep.txt"), "must survive");
        try {
            Files.createSymbolicLink(remoteRoot.resolve("applink"), Path.of("app"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경");
        }
        RemoteFile link = files.list(files.home()).stream().filter(f -> f.name().equals("applink")).findFirst().orElseThrow();
        assertTrue(link.symlink(), "링크로 보고돼야 한다");

        files.deleteRecursively(link);

        assertFalse(Files.exists(remoteRoot.resolve("applink"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(remoteRoot.resolve("app/keep.txt")), "링크가 가리키던 폴더의 내용은 남아야 한다");
    }

    @Test
    void resumeDownloadAppendsOnlyTheMissingPart() throws Exception {
        String content = "0123456789".repeat(20_000);
        Files.writeString(remoteRoot.resolve("big.bin"), content);
        Path partial = Files.writeString(dir.resolve("big.part"), content.substring(0, 70_000));
        AtomicLong last = new AtomicLong();

        files.download(RemoteFiles.join(files.home(), "big.bin"), partial, 70_000, last::set);

        assertEquals(content, Files.readString(partial));
        assertEquals(content.length(), last.get(), "진행률은 이어받은 바이트를 포함한 누적값");
    }

    @Test
    void resumeUploadWritesOnlyTheMissingPart() throws Exception {
        String content = "abcdefghij".repeat(20_000);
        Path local = Files.writeString(dir.resolve("up.bin"), content);
        Files.writeString(remoteRoot.resolve("up.bin"), content.substring(0, 50_000));
        AtomicLong last = new AtomicLong();

        files.upload(local, RemoteFiles.join(files.home(), "up.bin"), 50_000, last::set);

        assertEquals(content, Files.readString(remoteRoot.resolve("up.bin")));
        assertEquals(content.length(), last.get());
    }

    @Test
    void progressCallbackCanCancelATransfer() throws Exception {
        Files.writeString(remoteRoot.resolve("big2.bin"), "z".repeat(500_000));
        Path target = dir.resolve("big2.bin");

        assertThrows(IllegalStateException.class, () -> files.download(
                RemoteFiles.join(files.home(), "big2.bin"), target, done -> {
                    throw new IllegalStateException("취소");
                }));
        assertTrue(Files.size(target) < 500_000, "끝까지 받지 않고 멈춘다");
    }

    @Test
    void createFileMakesAnEmptyFileAndRefusesToOverwrite() throws Exception {
        String path = RemoteFiles.join(files.home(), "empty.txt");

        files.createFile(path);

        assertTrue(Files.isRegularFile(remoteRoot.resolve("empty.txt")));
        assertEquals(0, Files.size(remoteRoot.resolve("empty.txt")));
        Files.writeString(remoteRoot.resolve("README.md"), "keep me");
        assertThrows(java.io.IOException.class, () -> files.createFile(RemoteFiles.join(files.home(), "README.md")),
                "이미 있는 파일은 덮어쓰지 않는다");
        assertEquals("keep me", Files.readString(remoteRoot.resolve("README.md")));
        assertThrows(java.io.IOException.class, () -> files.createFile(RemoteFiles.join(files.home(), "nodir/x.txt")),
                "상위 폴더가 없으면 실패");
    }

    @Test
    void pathHelpers() {
        assertEquals("/home/u/a", RemoteFiles.join("/home/u", "a"));
        assertEquals("/a", RemoteFiles.join("/", "a"));
        assertEquals("/home", RemoteFiles.parent("/home/u"));
        assertEquals("/", RemoteFiles.parent("/home"));
        assertEquals("/", RemoteFiles.parent("/"));
        assertEquals("_etc_passwd", RemoteFiles.safeLocalName("/etc/passwd"));
        assertEquals("download", RemoteFiles.safeLocalName(".."));
    }
}
