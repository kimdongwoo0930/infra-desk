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
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 업로드가 권한을 확인하고 지키는지. 덮어쓰면 기존 파일의 권한을, 새 파일이면 내 컴퓨터 파일의 권한을 따르고,
 * 비밀 파일이 잠깐이라도 넓은 권한으로 서버에 놓이는 순간이 없어야 한다. 서버가 권한을 안 알려주거나 권한을
 * 못 바꾸는 상황은 {@link RemoteFiles}를 감싸서 흉내 낸다.
 */
class UploadPermissionTest {

    @TempDir
    Path dir;

    private SshServer server;
    private MinaShellConnector connector;
    private SshTarget target;
    private Path remoteRoot;
    private Path localDir;
    private RemoteFiles browsing;
    private final List<TransferQueue> queues = new ArrayList<>();

    /** 진짜 서버로 호출을 넘긴다. */
    private interface Proceed {
        Object call() throws Throwable;
    }

    /** 호출을 가로채는 방법. {@code proceed}를 부르면 진짜 서버로 간다. */
    private interface Interceptor {
        Object handle(Method method, Object[] args, Proceed proceed) throws Throwable;
    }

    @BeforeEach
    void start() throws Exception {
        remoteRoot = Files.createDirectories(dir.resolve("remote"));
        localDir = Files.createDirectories(dir.resolve("local"));
        assumeTrue(Files.getFileStore(remoteRoot).supportsFileAttributeView("posix"), "POSIX 권한이 있는 파일 시스템만");

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
        target = new SshTarget("127.0.0.1", server.getPort(), "ubuntu", pem.toString(StandardCharsets.UTF_8), null);
        browsing = connector.sftp(target, (h, p, t, f) -> true);
    }

    @AfterEach
    void stop() throws Exception {
        for (TransferQueue q : queues) {
            q.close();
        }
        if (browsing != null) {
            browsing.close();
        }
        if (connector != null) {
            connector.close();
        }
        if (server != null) {
            server.stop(true);
        }
    }

    private TransferQueue queue(Interceptor interceptor) {
        TransferQueue q = new TransferQueue(() -> wrap(connector.sftp(target, (h, p, t, f) -> true), interceptor), job -> { });
        queues.add(q);
        return q;
    }

    private TransferQueue queue() {
        return queue((m, a, proceed) -> proceed.call());
    }

    private RemoteFiles wrap(RemoteFiles real, Interceptor interceptor) {
        return (RemoteFiles) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {RemoteFiles.class},
                (proxy, method, args) -> interceptor.handle(method, args, () -> {
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    private static RemoteFile withUid(Object result, int uid) {
        RemoteFile real = (RemoteFile) result;
        return new RemoteFile(real.name(), real.path(), real.directory(), real.size(), real.modified(), real.permissions(),
                real.symlink(), uid);
    }

    /** 우리 계정의 uid는 501, 기존 파일의 소유자가 다르면 1000으로 보이게 하는 가짜 서버. 이 테스트 서버는 uid를 아예 알려주지 않아서 직접 정해 준다. */
    private Interceptor owners(int existingFileOwner) {
        return (method, args, proceed) -> {
            Object result = proceed.call();
            if (method.getName().equals("stat")) {
                String path = (String) args[0];
                return withUid(result, path.endsWith(TransferQueue.PART_SUFFIX) ? 501 : existingFileOwner);
            }
            return result;
        };
    }

    private TransferQueue.Job await(TransferQueue.Job job) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!job.state().finished() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertTrue(job.state().finished(), "시간 안에 끝나지 않았다: " + job.state() + " " + job.message());
        return job;
    }

    private String home() throws IOException {
        return browsing.home();
    }

    private static void setMode(Path file, String rwx) throws IOException {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(rwx));
    }

    private static String modeOf(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    // ---- 덮어쓰기: 기존 권한 유지 ----

    @Test
    void overwritingKeepsTheExistingFilesPermissions() throws Exception {
        Path secret = Files.writeString(remoteRoot.resolve("secret.env"), "old");
        setMode(secret, "rw-------");
        Path script = Files.writeString(remoteRoot.resolve("run.sh"), "old");
        setMode(script, "rwxr-xr-x");
        Path odd = Files.writeString(remoteRoot.resolve("shared.txt"), "old");
        setMode(odd, "rw-rw----");
        TransferQueue q = queue();

        // 내 컴퓨터 파일의 권한은 일부러 서버와 다르게 둔다. 덮어쓰기에서는 서버의 기존 권한이 이겨야 한다.
        for (String name : new String[] {"secret.env", "run.sh", "shared.txt"}) {
            Path local = Files.writeString(localDir.resolve(name), "new " + name);
            setMode(local, "rw-r--r--");
            assertEquals(TransferQueue.State.DONE, await(q.upload(local, home(), true)).state());
        }

        assertEquals("rw-------", modeOf(secret), "600인 비밀 파일이 넓어지면 안 된다");
        assertEquals("rwxr-xr-x", modeOf(script), "실행 권한이 사라지면 안 된다");
        assertEquals("rw-rw----", modeOf(odd));
        assertEquals("new secret.env", Files.readString(secret), "내용은 바뀌었다");
    }

    // ---- 새 파일: 내 컴퓨터 파일의 권한을 따름 ----

    @Test
    void aNewFileFollowsTheLocalFilesPermissions() throws Exception {
        TransferQueue q = queue();
        Path key = Files.writeString(localDir.resolve("id_key"), "k");
        setMode(key, "rw-------");
        Path script = Files.writeString(localDir.resolve("deploy.sh"), "s");
        setMode(script, "rwxr-xr-x");
        Path doc = Files.writeString(localDir.resolve("notes.txt"), "n");
        setMode(doc, "rw-r--r--");

        for (Path local : new Path[] {key, script, doc}) {
            assertEquals(TransferQueue.State.DONE, await(q.upload(local, home(), false)).state());
        }

        assertEquals("rw-------", modeOf(remoteRoot.resolve("id_key")), "내 컴퓨터의 600 키는 서버에서도 600");
        assertEquals("rwxr-xr-x", modeOf(remoteRoot.resolve("deploy.sh")));
        assertEquals("rw-r--r--", modeOf(remoteRoot.resolve("notes.txt")));
    }

    @Test
    void theTemporaryFileHasItsFinalPermissionsBeforeAnyContentIsWritten() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        TransferQueue q = queue((method, args, proceed) -> {
            if (method.getName().equals("upload") && args.length == 4) {
                Path part = remoteRoot.resolve(((String) args[1]).substring(1));
                if (Files.exists(part)) {
                    seen.add(modeOf(part) + " size=" + Files.size(part));
                }
            }
            return proceed.call();
        });
        Path key = Files.writeString(localDir.resolve("secret.pem"), "TOP-SECRET");
        setMode(key, "rw-------");

        assertEquals(TransferQueue.State.DONE, await(q.upload(key, home(), false)).state());

        assertEquals(List.of("rw------- size=0"), seen,
                "내용을 올리기 시작하는 순간 임시 파일은 이미 600이고 비어 있어야 한다(비밀 내용이 넓은 권한으로 놓이는 순간이 없다)");
    }

    // ---- 폴더 ----

    @Test
    void newFoldersGetTheLocalFoldersPermissionsAfterTheirContentsAreUploaded() throws Exception {
        Path tree = Files.createDirectories(localDir.resolve("project/private"));
        Files.writeString(tree.resolve("token.txt"), "t");
        Files.writeString(localDir.resolve("project/readme.txt"), "r");
        Path readOnly = Files.createDirectories(localDir.resolve("project/readonly"));
        Files.writeString(readOnly.resolve("a.txt"), "a");
        setMode(tree, "rwx------");
        setMode(readOnly, "r-xr-xr-x");
        setMode(localDir.resolve("project"), "rwxr-x---");

        try {
            TransferQueue.Job job = await(queue().upload(localDir.resolve("project"), home(), true));
            assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        } finally {
            // 임시 폴더를 지울 수 있게 되돌린다.
            setMode(readOnly, "rwxr-xr-x");
            setMode(remoteRoot.resolve("project/readonly"), "rwxr-xr-x");
        }

        assertEquals("rwxr-x---", modeOf(remoteRoot.resolve("project")));
        assertEquals("rwx------", modeOf(remoteRoot.resolve("project/private")), "비공개 폴더가 서버에서 755가 되면 안 된다");
        assertTrue(Files.exists(remoteRoot.resolve("project/readonly/a.txt")),
                "읽기 전용 폴더도 그 안에 파일을 다 올린 뒤에 권한을 주므로 올라간다");
    }

    // ---- 소유자 ----

    @Test
    void warnsAboutOwnerChangeWhenTheOverwrittenFileBelongedToSomeoneElse() throws Exception {
        Path file = Files.writeString(remoteRoot.resolve("app.conf"), "old");
        Path local = Files.writeString(localDir.resolve("app.conf"), "new");

        TransferQueue.Job job = await(queue(owners(1000)).upload(local, home(), true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertEquals(1, job.ownerChangedCount());
        assertEquals(List.of("app.conf"), job.ownerChangedNames());
        assertTrue(job.message().contains("소유자가 바뀐"), job.message());
        assertEquals("new", Files.readString(file));
    }

    @Test
    void doesNotWarnWhenTheOwnerStaysTheSame() throws Exception {
        Files.writeString(remoteRoot.resolve("mine.txt"), "old");
        Path local = Files.writeString(localDir.resolve("mine.txt"), "new");

        TransferQueue.Job job = await(queue(owners(501)).upload(local, home(), true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertEquals(0, job.ownerChangedCount(), "소유자가 같으면 경고하지 않는다");
        assertFalse(job.message().contains("소유자"), job.message());
    }

    @Test
    void doesNotWarnWhenTheServerDoesNotReportOwners() throws Exception {
        Files.writeString(remoteRoot.resolve("unknown.txt"), "old");
        Path local = Files.writeString(localDir.resolve("unknown.txt"), "new");

        TransferQueue.Job job = await(queue().upload(local, home(), true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertEquals(0, job.ownerChangedCount(), "소유자를 모르면 근거 없이 경고하지 않는다(이 테스트 서버는 uid를 알려주지 않는다)");
    }

    @Test
    void aFolderUploadCollectsOwnerChangesByPathAndReportsThemInOneJob() throws Exception {
        Files.createDirectories(remoteRoot.resolve("site/css"));
        Files.writeString(remoteRoot.resolve("site/index.html"), "old");
        Files.writeString(remoteRoot.resolve("site/css/index.html"), "old");
        Files.createDirectories(localDir.resolve("site/css"));
        Files.writeString(localDir.resolve("site/index.html"), "new");
        Files.writeString(localDir.resolve("site/css/index.html"), "new");

        TransferQueue.Job job = await(queue(owners(1000)).upload(localDir.resolve("site"), home(), true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertEquals(2, job.ownerChangedCount(), "한 작업(폴더 하나) 안의 변경은 모아서 한 번에 알린다");
        assertEquals(List.of("site/index.html", "site/css/index.html"), job.ownerChangedNames(),
                "이름이 같은 파일도 폴더 기준 경로로 구분한다");
    }

    // ---- 서버가 이상하게 동작할 때 ----

    @Test
    void neverChangesPermissionsWhenTheServerDoesNotReportThem() throws Exception {
        Path file = Files.writeString(remoteRoot.resolve("x.txt"), "old");
        setMode(file, "rw-------");
        List<String> chmods = Collections.synchronizedList(new ArrayList<>());
        TransferQueue q = queue((method, args, proceed) -> {
            if (method.getName().equals("chmod")) {
                chmods.add(args[0] + " " + args[1]);
            }
            Object result = proceed.call();
            if (method.getName().equals("stat") && args[0].equals(RemoteFiles.join(home(), "x.txt"))) {
                RemoteFile real = (RemoteFile) result;
                return new RemoteFile(real.name(), real.path(), real.directory(), real.size(), real.modified(),
                        RemoteFile.UNKNOWN_PERMISSIONS, real.symlink(), real.uid());
            }
            return result;
        });
        Path local = Files.writeString(localDir.resolve("x.txt"), "new");
        setMode(local, "rw-r--r--");

        TransferQueue.Job job = await(q.upload(local, home(), true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertTrue(chmods.isEmpty(), "권한을 모르는데 chmod하면 000처럼 잘못 바꿀 수 있다: " + chmods);
        assertTrue(job.message().contains("권한을 알려주지 않아"), job.message());
    }

    @Test
    void failsInsteadOfUploadingWhenThePermissionCannotBeSet() throws Exception {
        Path secret = Files.writeString(remoteRoot.resolve("secret.env"), "old-secret");
        setMode(secret, "rw-------");
        TransferQueue q = queue((method, args, proceed) -> {
            if (method.getName().equals("chmod")) {
                throw new IOException("chmod 거부됨(테스트)");
            }
            return proceed.call();
        });
        Path local = Files.writeString(localDir.resolve("secret.env"), "NEW-SECRET");

        TransferQueue.Job job = await(q.upload(local, home(), true));

        assertEquals(TransferQueue.State.FAILED, job.state());
        assertTrue(job.message().contains("권한"), job.message());
        assertEquals("old-secret", Files.readString(secret), "권한을 맞추지 못하면 원본은 그대로여야 한다");
        assertEquals("rw-------", modeOf(secret));
        assertFalse(Files.exists(remoteRoot.resolve("secret.env" + TransferQueue.PART_SUFFIX)),
                "내용이 없는 임시 파일은 남기지 않는다");
    }

    @Test
    void reportsAFailureWhenTheFinalPermissionCannotBeConfirmed() throws Exception {
        Path secret = Files.writeString(remoteRoot.resolve("secret.env"), "old");
        setMode(secret, "rw-------");
        boolean[] renamed = {false};
        TransferQueue q = queue((method, args, proceed) -> {
            Object result = proceed.call();
            if (method.getName().equals("rename")) {
                renamed[0] = true;
            }
            // 이름을 바꾼 뒤에 서버가 계속 644라고 답하는 것처럼 보이게 한다(바로잡아도 소용없다).
            if (renamed[0] && method.getName().equals("stat") && args[0].equals(RemoteFiles.join(home(), "secret.env"))) {
                RemoteFile real = (RemoteFile) result;
                return new RemoteFile(real.name(), real.path(), real.directory(), real.size(), real.modified(),
                        0644, real.symlink(), real.uid());
            }
            return result;
        });
        Path local = Files.writeString(localDir.resolve("secret.env"), "new");

        TransferQueue.Job job = await(q.upload(local, home(), true));

        assertEquals(TransferQueue.State.FAILED, job.state(), "권한이 확인되지 않았는데 성공으로 알리면 안 된다");
        assertTrue(job.message().contains("권한이 600이어야"), job.message());
    }
}
