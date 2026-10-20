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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
