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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 임시 디렉터리를 루트로 하는 프로세스 내 MINA 서버에 대한 전송 대기열. */
class TransferQueueTest {

    @TempDir
    Path dir;

    private SshServer server;
    private MinaShellConnector connector;
    private SshTarget target;
    private Path remoteRoot;
    private Path localDir;
    private TransferQueue queue;
    private RemoteFiles browsing;

    /** 전송 연결이 받은 오프셋과, 진행률이 이 값에 이르면 실행할 일. */
    private final List<Long> offsets = Collections.synchronizedList(new ArrayList<>());
    private volatile long triggerAt = Long.MAX_VALUE;
    private volatile Runnable trigger = () -> { };
    private final List<TransferQueue.State> states = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        remoteRoot = Files.createDirectories(dir.resolve("remote"));
        localDir = Files.createDirectories(dir.resolve("local"));

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
        queue = new TransferQueue(() -> instrument(connector.sftp(target, (h, p, t, f) -> true)),
                job -> states.add(job.state()));
    }

    @AfterEach
    void stop() throws Exception {
        queue.close();
        browsing.close();
        connector.close();
        server.stop(true);
    }

    /** 오프셋을 기록하고, 진행률이 {@link #triggerAt}에 이르면 {@link #trigger}를 실행하는 감시 프록시. */
    private RemoteFiles instrument(RemoteFiles real) {
        return (RemoteFiles) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {RemoteFiles.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("download") && args.length == 4) {
                        offsets.add((Long) args[2]);
                        args[3] = watch((LongConsumer) args[3]);
                    } else if (method.getName().equals("upload") && args.length == 4) {
                        offsets.add((Long) args[2]);
                        args[3] = watch((LongConsumer) args[3]);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private LongConsumer watch(LongConsumer inner) {
        return done -> {
            if (done >= triggerAt) {
                triggerAt = Long.MAX_VALUE;
                trigger.run();
            }
            inner.accept(done);
        };
    }

    private TransferQueue.Job await(TransferQueue.Job job) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!job.state().finished() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertTrue(job.state().finished(), "시간 안에 끝나지 않았다: " + job.state() + " " + job.message());
        return job;
    }

    private RemoteFile remoteEntry(String name) throws Exception {
        return browsing.list(browsing.home()).stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void uploadsAFolderTreeAndSkipsSymlinks() throws Exception {
        Path tree = Files.createDirectories(localDir.resolve("proj/src/deep"));
        Files.writeString(tree.resolve("a.txt"), "alpha");
        Files.writeString(localDir.resolve("proj/src/b.txt"), "bravo!");
        Files.createDirectories(localDir.resolve("proj/empty"));
        boolean linked = true;
        try {
            Files.createSymbolicLink(localDir.resolve("proj/link"), localDir.resolve("proj/src"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            linked = false;
        }

        TransferQueue.Job job = await(queue.upload(localDir.resolve("proj"), browsing.home(), true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertEquals("alpha", Files.readString(remoteRoot.resolve("proj/src/deep/a.txt")));
        assertEquals("bravo!", Files.readString(remoteRoot.resolve("proj/src/b.txt")));
        assertTrue(Files.isDirectory(remoteRoot.resolve("proj/empty")), "빈 폴더도 만든다");
        assertEquals(11, job.totalBytes());
        assertEquals(11, job.doneBytes());
        assertEquals(100, job.percent());
        if (linked) {
            assertFalse(Files.exists(remoteRoot.resolve("proj/link")), "링크는 따라가지 않고 건너뛴다");
            assertTrue(job.message().contains("건너뛰"), job.message());
        }
        try (var leftovers = Files.walk(remoteRoot)) {
            assertTrue(leftovers.noneMatch(p -> p.toString().endsWith(TransferQueue.PART_SUFFIX)), "임시 파일이 남지 않는다");
        }
    }

    @Test
    void downloadsAFolderTree() throws Exception {
        Files.createDirectories(remoteRoot.resolve("site/css"));
        Files.writeString(remoteRoot.resolve("site/index.html"), "<html/>");
        Files.writeString(remoteRoot.resolve("site/css/main.css"), "body{}");

        TransferQueue.Job job = await(queue.download(remoteEntry("site"), localDir, true));

        assertEquals(TransferQueue.State.DONE, job.state(), job.message());
        assertEquals("<html/>", Files.readString(localDir.resolve("site/index.html")));
        assertEquals("body{}", Files.readString(localDir.resolve("site/css/main.css")));
        assertEquals(13, job.totalBytes());
    }

    @Test
    void existingFilesAreSkippedUnlessOverwriting() throws Exception {
        Files.writeString(remoteRoot.resolve("note.txt"), "old");
        Path local = Files.writeString(localDir.resolve("note.txt"), "new content");

        TransferQueue.Job skipped = await(queue.upload(local, browsing.home(), false));
        assertEquals(TransferQueue.State.DONE, skipped.state());
        assertEquals("old", Files.readString(remoteRoot.resolve("note.txt")), "덮어쓰지 않는다");
        assertTrue(skipped.message().contains("건너뛰"), skipped.message());

        TransferQueue.Job replaced = await(queue.upload(local, browsing.home(), true));
        assertEquals(TransferQueue.State.DONE, replaced.state(), replaced.message());
        assertEquals("new content", Files.readString(remoteRoot.resolve("note.txt")));
    }

    @Test
    void cancelKeepsAPartialFileAndRetryResumesIt() throws Exception {
        String content = "0123456789".repeat(100_000);
        Files.writeString(remoteRoot.resolve("big.bin"), content);
        RemoteFile big = remoteEntry("big.bin");
        TransferQueue.Job[] holder = new TransferQueue.Job[1];
        triggerAt = 300_000;
        trigger = () -> queue.cancel(holder[0]);

        holder[0] = queue.download(big, localDir, true);
        await(holder[0]);

        assertEquals(TransferQueue.State.CANCELLED, holder[0].state());
        Path part = localDir.resolve("big.bin" + TransferQueue.PART_SUFFIX);
        assertTrue(Files.exists(part), "끊긴 임시 파일은 남는다");
        assertFalse(Files.exists(localDir.resolve("big.bin")), "완성본처럼 보이면 안 된다");
        long partial = Files.size(part);
        assertTrue(partial > 0 && partial < content.length());
        offsets.clear();

        queue.retry(holder[0]);
        await(holder[0]);

        assertEquals(TransferQueue.State.DONE, holder[0].state(), holder[0].message());
        assertEquals(List.of(partial), offsets, "남은 부분부터 이어받는다");
        assertEquals(content, Files.readString(localDir.resolve("big.bin")));
        assertFalse(Files.exists(part));
    }

    @Test
    void retryDownloadRestartsFromZeroWhenTheServerFileChanged() throws Exception {
        Files.writeString(remoteRoot.resolve("data.bin"), "a".repeat(800_000));
        TransferQueue.Job[] holder = new TransferQueue.Job[1];
        triggerAt = 200_000;
        trigger = () -> queue.cancel(holder[0]);
        holder[0] = queue.download(remoteEntry("data.bin"), localDir, true);
        await(holder[0]);
        assertEquals(TransferQueue.State.CANCELLED, holder[0].state());
        assertTrue(Files.exists(localDir.resolve("data.bin" + TransferQueue.PART_SUFFIX)));
        offsets.clear();

        String changed = "b".repeat(900_000);
        Files.writeString(remoteRoot.resolve("data.bin"), changed);
        queue.retry(holder[0]);
        await(holder[0]);

        assertEquals(TransferQueue.State.DONE, holder[0].state(), holder[0].message());
        assertEquals(List.of(0L), offsets, "서버 파일이 바뀌었으면 처음부터 받는다");
        assertEquals(changed, Files.readString(localDir.resolve("data.bin")), "낡은 조각이 섞이면 안 된다");
    }

    @Test
    void cancelledUploadResumesFromTheRemotePartialFile() throws Exception {
        String content = "0123456789".repeat(100_000);
        Path local = Files.writeString(localDir.resolve("up2.bin"), content);
        TransferQueue.Job[] holder = new TransferQueue.Job[1];
        triggerAt = 300_000;
        trigger = () -> queue.cancel(holder[0]);
        holder[0] = queue.upload(local, browsing.home(), true);
        await(holder[0]);
        assertEquals(TransferQueue.State.CANCELLED, holder[0].state());
        Path part = remoteRoot.resolve("up2.bin" + TransferQueue.PART_SUFFIX);
        long partial = Files.size(part);
        assertTrue(partial > 0 && partial < content.length());
        assertFalse(Files.exists(remoteRoot.resolve("up2.bin")), "완성본처럼 보이면 안 된다");
        offsets.clear();

        queue.retry(holder[0]);
        await(holder[0]);

        assertEquals(TransferQueue.State.DONE, holder[0].state(), holder[0].message());
        assertEquals(List.of(partial), offsets, "서버에 있는 만큼은 건너뛰고 이어서 올린다");
        assertEquals(content, Files.readString(remoteRoot.resolve("up2.bin")));
        assertFalse(Files.exists(part));
    }

    @Test
    void retryWithAChangedFileFingerprintDoesNotReusePartialData() throws Exception {
        Path local = Files.writeString(localDir.resolve("up.bin"), "x".repeat(800_000));
        TransferQueue.Job[] holder = new TransferQueue.Job[1];
        triggerAt = 200_000;
        trigger = () -> queue.cancel(holder[0]);
        holder[0] = queue.upload(local, browsing.home(), true);
        await(holder[0]);
        assertEquals(TransferQueue.State.CANCELLED, holder[0].state());
        assertTrue(Files.exists(remoteRoot.resolve("up.bin" + TransferQueue.PART_SUFFIX)));
        offsets.clear();

        Files.writeString(local, "y".repeat(850_000));
        queue.retry(holder[0]);
        await(holder[0]);

        assertEquals(TransferQueue.State.DONE, holder[0].state(), holder[0].message());
        assertEquals(List.of(0L), offsets, "원본이 바뀌었으면 처음부터 올린다");
        assertEquals("y".repeat(850_000), Files.readString(remoteRoot.resolve("up.bin")));
    }

    @Test
    void removingACancelledJobDeletesItsPartialFile() throws Exception {
        Files.writeString(remoteRoot.resolve("tmp.bin"), "q".repeat(800_000));
        TransferQueue.Job[] holder = new TransferQueue.Job[1];
        triggerAt = 200_000;
        trigger = () -> queue.cancel(holder[0]);
        holder[0] = queue.download(remoteEntry("tmp.bin"), localDir, true);
        await(holder[0]);
        Path part = localDir.resolve("tmp.bin" + TransferQueue.PART_SUFFIX);
        assertTrue(Files.exists(part));

        queue.remove(holder[0]);

        assertFalse(Files.exists(part), "지운 작업의 임시 파일은 정리한다");
        assertTrue(queue.jobs().isEmpty());
    }

    @Test
    void cancellingAQueuedJobNeverRunsIt() throws Exception {
        Files.writeString(remoteRoot.resolve("first.bin"), "1".repeat(1_500_000));
        Files.writeString(remoteRoot.resolve("second.bin"), "2");
        CountDownLatch inFirst = new CountDownLatch(1);
        triggerAt = 100_000;
        trigger = inFirst::countDown;

        TransferQueue.Job first = queue.download(remoteEntry("first.bin"), localDir, true);
        TransferQueue.Job second = queue.download(remoteEntry("second.bin"), localDir, true);
        assertTrue(inFirst.await(20, TimeUnit.SECONDS));
        queue.cancel(second);
        await(first);
        await(second);

        assertEquals(TransferQueue.State.DONE, first.state(), first.message());
        assertEquals(TransferQueue.State.CANCELLED, second.state());
        assertFalse(Files.exists(localDir.resolve("second.bin")));
    }

    @Test
    void translatesCommonErrorsToKorean() {
        assertTrue(TransferQueue.describe(new java.io.IOException("SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied"))
                .contains("권한이 없어요"));
        assertTrue(TransferQueue.describe(new java.io.IOException("wrapper", new java.nio.file.AccessDeniedException("/etc/x")))
                .contains("권한이 없어요"), "원인 체인도 본다");
        assertTrue(TransferQueue.describe(new java.nio.file.NoSuchFileException("/x")).contains("찾을 수 없어요"));
        assertEquals("뭔가 잘못됐어요", TransferQueue.describe(new java.io.IOException("뭔가 잘못됐어요")));
    }

    @Test
    void returnsTheConnectionWhenTheQueueIsIdleAndReconnectsForTheNextTransfer() throws Exception {
        int[] opens = {0};
        int[] closes = {0};
        TransferQueue counted = new TransferQueue(() -> {
            opens[0]++;
            RemoteFiles real = connector.sftp(target, (h, p, t, f) -> true);
            return (RemoteFiles) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {RemoteFiles.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            closes[0]++;
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }, job -> { });
        try {
            Path first = Files.writeString(localDir.resolve("one.txt"), "1");
            Path second = Files.writeString(localDir.resolve("two.txt"), "2");

            await(counted.upload(first, "/", false));
            waitUntil(() -> closes[0] == 1);
            assertEquals(1, opens[0], "첫 전송 때 한 번 연결했다");

            await(counted.upload(second, "/", false));
            waitUntil(() -> closes[0] == 2);
            assertEquals(2, opens[0], "큐가 비어 연결을 돌려줬으니 다음 전송은 새로 연결한다");
        } finally {
            counted.close();
        }
    }

    @Test
    void keepsTheConnectionWhileMoreTransfersAreWaiting() throws Exception {
        int[] opens = {0};
        TransferQueue counted = new TransferQueue(() -> {
            opens[0]++;
            return connector.sftp(target, (h, p, t, f) -> true);
        }, job -> { });
        try {
            List<TransferQueue.Job> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                jobs.add(counted.upload(Files.writeString(localDir.resolve("batch" + i + ".txt"), "x" + i), "/", false));
            }
            for (TransferQueue.Job job : jobs) {
                await(job);
            }

            assertEquals(1, opens[0], "이어서 할 작업이 있는 동안에는 같은 연결을 쓴다");
        } finally {
            counted.close();
        }
    }

    @Test
    void dropsTheConnectionAfterAFailureSoARetryConnectsAgain() throws Exception {
        int[] opens = {0};
        TransferQueue counted = new TransferQueue(() -> {
            opens[0]++;
            return connector.sftp(target, (h, p, t, f) -> true);
        }, job -> { });
        try {
            TransferQueue.Job missing = counted.upload(localDir.resolve("없는파일.txt"), "/", false);
            await(missing);
            assertEquals(TransferQueue.State.FAILED, missing.state());

            Files.writeString(localDir.resolve("없는파일.txt"), "이제 있어요");
            counted.retry(missing);
            await(missing);

            assertEquals(TransferQueue.State.DONE, missing.state(), missing.message());
            assertEquals(2, opens[0], "실패한 뒤에는 연결을 버리고, 다시 시도할 때 새로 맺는다");
        } finally {
            counted.close();
        }
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "시간 안에 조건이 되지 않았다");
    }
}
