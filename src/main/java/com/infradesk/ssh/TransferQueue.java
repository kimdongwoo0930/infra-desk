package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 파일·폴더 전송 대기열. 작업(파일 하나 또는 폴더 통째)을 하나씩 차례로 실행한다. 탐색에 쓰는
 * SFTP 연결과 따로 연결을 맺어서(첫 작업 때) 전송 중에도 폴더를 둘러볼 수 있다.
 *
 * <p>안전하게 이어받으려고 파일은 {@value #PART_SUFFIX}가 붙은 임시 이름으로 쓰고, 다 끝나면
 * 제 이름으로 바꾼다. 그래서 끊긴 파일이 완성본처럼 보이지 않는다. 취소하거나 실패해도 임시 파일은
 * 남기고, {@link #retry}하면 원본의 크기·수정 시각이 처음과 같을 때만 이어서 전송한다. 심볼릭 링크는
 * 따라가지 않고 건너뛴다.
 *
 * <p>리스너는 작업 스레드에서 불리므로 화면은 EDT로 넘겨서 갱신해야 한다.
 */
public final class TransferQueue implements AutoCloseable {

    public static final String PART_SUFFIX = ".infradesk-part";

    public enum Direction {
        UPLOAD, DOWNLOAD
    }

    public enum State {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED;

        public boolean finished() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    public interface Listener {
        void changed(Job job);
    }

    /** 작업 하나. 필드는 작업 스레드가 쓰고 화면이 읽는다. */
    public static final class Job {
        private static final AtomicLong IDS = new AtomicLong();

        private final long id = IDS.incrementAndGet();
        private final Direction direction;
        private final String name;
        /** 올리기는 로컬 경로, 받기는 원격 경로. */
        private final String source;
        /** 올리기는 서버 폴더, 받기는 로컬 폴더. */
        private final String targetDir;
        private final RemoteFile remote;
        private final boolean overwrite;

        private volatile State state = State.QUEUED;
        private volatile long doneBytes;
        private volatile long totalBytes = -1;
        private volatile String message = "";
        private volatile boolean cancelRequested;
        private volatile boolean resumable;
        private volatile int skipped;

        /** 지난 시도에서 끝난 파일(루트 기준 상대 경로)과 그 크기. */
        private final Map<String, Long> completed = new HashMap<>();
        /** 파일을 시작할 때의 원본 크기·수정 시각. 이어받아도 되는지 판단한다. */
        private final Map<String, String> fingerprints = new HashMap<>();
        private final Set<Path> localPartials = new HashSet<>();
        private final Set<String> remotePartials = new HashSet<>();

        private Job(Direction direction, String name, String source, String targetDir, RemoteFile remote, boolean overwrite) {
            this.direction = direction;
            this.name = name;
            this.source = source;
            this.targetDir = targetDir;
            this.remote = remote;
            this.overwrite = overwrite;
        }

        public long id() {
            return id;
        }

        public Direction direction() {
            return direction;
        }

        public String name() {
            return name;
        }

        public String source() {
            return source;
        }

        public String targetDir() {
            return targetDir;
        }

        public State state() {
            return state;
        }

        public long doneBytes() {
            return doneBytes;
        }

        /** 전체 바이트. 아직 계산 중이면 -1. */
        public long totalBytes() {
            return totalBytes;
        }

        /** 사용자에게 보여줄 한 줄. 오류나 건너뛴 항목 같은 것. */
        public String message() {
            return message;
        }

        public int percent() {
            long total = totalBytes;
            if (total <= 0) {
                return state == State.DONE ? 100 : 0;
            }
            return (int) Math.min(100, doneBytes * 100 / total);
        }
    }

    private final Callable<RemoteFiles> opener;
    private final Listener listener;
    private final List<Job> jobs = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sftp-transfer");
        t.setDaemon(true);
        return t;
    });

    private RemoteFiles remote;
    private volatile boolean closed;
    private long lastNotify;

    /** @param opener 전송 전용 SFTP 연결을 여는 방법. 첫 작업이 실행될 때 한 번 부른다 */
    public TransferQueue(Callable<RemoteFiles> opener, Listener listener) {
        this.opener = opener;
        this.listener = listener;
    }

    public List<Job> jobs() {
        return List.copyOf(jobs);
    }

    /**
     * 이 컴퓨터의 파일이나 폴더를 서버 폴더로 올린다.
     *
     * @param overwrite 같은 이름의 파일이 있으면 덮어쓸지(false면 건너뛴다)
     */
    public Job upload(Path local, String remoteDir, boolean overwrite) {
        Job job = new Job(Direction.UPLOAD, local.getFileName().toString(), local.toString(), remoteDir, null, overwrite);
        return submit(job);
    }

    /** 서버의 파일이나 폴더를 이 컴퓨터 폴더로 받는다. */
    public Job download(RemoteFile remote, Path localDir, boolean overwrite) {
        Job job = new Job(Direction.DOWNLOAD, remote.name(), remote.path(), localDir.toString(), remote, overwrite);
        return submit(job);
    }

    /** 끝나지 않은 작업을 멈춘다. 대기 중이면 바로 취소되고, 전송 중이면 다음 조각에서 멈춘다. */
    public void cancel(Job job) {
        job.cancelRequested = true;
        if (job.state == State.QUEUED) {
            finish(job, State.CANCELLED, "취소했어요");
        }
    }

    /** 실패하거나 취소한 작업을 다시 시작한다. 끝난 파일은 건너뛰고, 중단된 파일은 이어서 받는다. */
    public void retry(Job job) {
        if (job.state != State.FAILED && job.state != State.CANCELLED) {
            return;
        }
        job.cancelRequested = false;
        job.resumable = true;
        job.message = "";
        job.state = State.QUEUED;
        changed(job, true);
        enqueue(job);
    }

    /**
     * 끝난 작업을 목록에서 지운다. 완료되지 않은 작업이면 남은 임시 파일도 지운다.
     */
    public void remove(Job job) {
        if (!job.state.finished()) {
            return;
        }
        jobs.remove(job);
        if (job.state != State.DONE) {
            discardPartials(job);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Job j : jobs) {
            j.cancelRequested = true;
        }
        worker.shutdownNow();
        RemoteFiles r = remote;
        remote = null;
        if (r != null) {
            r.close();
        }
    }

    private Job submit(Job job) {
        jobs.add(job);
        changed(job, true);
        enqueue(job);
        return job;
    }

    private void enqueue(Job job) {
        if (closed) {
            return;
        }
        worker.submit(() -> run(job));
    }

    private void run(Job job) {
        if (closed || job.state != State.QUEUED || job.cancelRequested) {
            if (job.state == State.QUEUED) {
                finish(job, State.CANCELLED, "취소했어요");
            }
            return;
        }
        job.state = State.RUNNING;
        job.doneBytes = job.completed.values().stream().mapToLong(Long::longValue).sum();
        changed(job, true);
        try {
            RemoteFiles rf = remote();
            if (job.direction == Direction.UPLOAD) {
                runUpload(job, rf);
            } else {
                runDownload(job, rf);
            }
            String note = job.skipped > 0 ? "완료 · " + job.skipped + "개는 이미 있거나 링크라서 건너뛰었어요" : "완료";
            finish(job, State.DONE, note);
        } catch (CancellationException e) {
            finish(job, State.CANCELLED, "취소했어요. 다시 시도하면 이어서 전송해요");
        } catch (Exception e) {
            finish(job, State.FAILED, describe(e) + " · 다시 시도하면 이어서 전송해요");
        }
    }

    private synchronized RemoteFiles remote() throws Exception {
        if (remote == null) {
            remote = opener.call();
        }
        return remote;
    }

    // ---- 올리기 ----

    private void runUpload(Job job, RemoteFiles rf) throws IOException {
        Path src = Path.of(job.source);
        String rootRemote = RemoteFiles.join(job.targetDir, job.name);
        if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            job.totalBytes = Files.size(src);
            uploadFile(job, rf, src, rootRemote, job.name);
            return;
        }
        List<LocalEntry> entries = walkLocal(job, src);
        job.totalBytes = entries.stream().filter(e -> !e.directory).mapToLong(e -> e.size).sum();
        changed(job, true);
        ensureRemoteDir(rf, rootRemote);
        for (LocalEntry e : entries) {
            checkCancel(job);
            String remotePath = RemoteFiles.join(rootRemote, e.relative);
            if (e.directory) {
                ensureRemoteDir(rf, remotePath);
            } else {
                uploadFile(job, rf, e.path, remotePath, job.name + "/" + e.relative);
            }
        }
    }

    private record LocalEntry(Path path, String relative, boolean directory, long size) {
    }

    private List<LocalEntry> walkLocal(Job job, Path root) throws IOException {
        List<LocalEntry> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                checkCancel(job);
                if (!dir.equals(root)) {
                    result.add(new LocalEntry(dir, relative(root, dir), true, 0));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    result.add(new LocalEntry(file, relative(root, file), false, attrs.size()));
                } else {
                    job.skipped++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                job.skipped++;
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private void uploadFile(Job job, RemoteFiles rf, Path local, String remotePath, String key) throws IOException {
        long size = Files.size(local);
        if (job.completed.containsKey(key)) {
            return;
        }
        checkCancel(job);
        boolean exists = rf.exists(remotePath);
        if (exists && !job.overwrite) {
            job.skipped++;
            job.doneBytes += size;
            job.completed.put(key, size);
            changed(job, false);
            return;
        }
        String part = remotePath + PART_SUFFIX;
        String fingerprint = size + ":" + Files.getLastModifiedTime(local).toMillis();
        long offset = 0;
        if (job.resumable && fingerprint.equals(job.fingerprints.get(key)) && rf.exists(part)) {
            long partial = rf.stat(part).size();
            if (partial > 0 && partial < size) {
                offset = partial;
            }
        }
        job.fingerprints.put(key, fingerprint);
        job.remotePartials.add(part);
        long before = job.doneBytes;
        rf.upload(local, part, offset, done -> {
            checkCancel(job);
            job.doneBytes = before + done;
            changed(job, false);
        });
        if (rf.stat(part).size() != size) {
            throw new IOException(job.name + ": 올린 크기가 원본과 달라요");
        }
        if (exists) {
            rf.delete(new RemoteFile(local.getFileName().toString(), remotePath, false, 0, null));
        }
        rf.rename(part, remotePath);
        job.remotePartials.remove(part);
        job.doneBytes = before + size;
        job.completed.put(key, size);
        changed(job, false);
    }

    private static void ensureRemoteDir(RemoteFiles rf, String path) throws IOException {
        if (!rf.exists(path)) {
            rf.mkdir(path);
        }
    }

    // ---- 받기 ----

    private void runDownload(Job job, RemoteFiles rf) throws IOException {
        RemoteFile src = job.remote;
        Path localRoot = Path.of(job.targetDir).resolve(RemoteFiles.safeLocalName(src.name()));
        if (!src.directory()) {
            job.totalBytes = src.size();
            downloadFile(job, rf, src, localRoot, src.name());
            return;
        }
        List<RemoteEntry> entries = new ArrayList<>();
        walkRemote(job, rf, src, "", entries);
        job.totalBytes = entries.stream().filter(e -> !e.file.directory()).mapToLong(e -> e.file.size()).sum();
        changed(job, true);
        Files.createDirectories(localRoot);
        for (RemoteEntry e : entries) {
            checkCancel(job);
            Path target = localRoot;
            for (String segment : e.relative.split("/")) {
                target = target.resolve(RemoteFiles.safeLocalName(segment));
            }
            if (e.file.directory()) {
                Files.createDirectories(target);
            } else {
                downloadFile(job, rf, e.file, target, src.name() + "/" + e.relative);
            }
        }
    }

    private record RemoteEntry(RemoteFile file, String relative) {
    }

    private void walkRemote(Job job, RemoteFiles rf, RemoteFile dir, String prefix, List<RemoteEntry> out) throws IOException {
        for (RemoteFile child : rf.list(dir.path())) {
            checkCancel(job);
            if (child.symlink()) {
                job.skipped++;
                continue;
            }
            String relative = prefix.isEmpty() ? child.name() : prefix + "/" + child.name();
            out.add(new RemoteEntry(child, relative));
            if (child.directory()) {
                walkRemote(job, rf, child, relative, out);
            }
        }
    }

    private void downloadFile(Job job, RemoteFiles rf, RemoteFile listed, Path target, String key) throws IOException {
        if (job.completed.containsKey(key)) {
            return;
        }
        checkCancel(job);
        // 다시 시도할 때는 서버에서 지금 상태를 다시 읽는다. 처음 목록을 읽은 뒤 파일이 바뀌었을 수 있다.
        RemoteFile file = job.resumable ? rf.stat(listed.path()) : listed;
        long size = file.size();
        if (Files.exists(target) && !job.overwrite) {
            job.skipped++;
            job.doneBytes += size;
            job.completed.put(key, size);
            changed(job, false);
            return;
        }
        Path part = target.resolveSibling(target.getFileName() + PART_SUFFIX);
        String fingerprint = size + ":" + (file.modified() == null ? 0 : file.modified().toEpochMilli());
        long offset = 0;
        if (job.resumable && fingerprint.equals(job.fingerprints.get(key)) && Files.exists(part)) {
            long partial = Files.size(part);
            if (partial > 0 && partial < size) {
                offset = partial;
            }
        }
        job.fingerprints.put(key, fingerprint);
        job.localPartials.add(part);
        long before = job.doneBytes;
        rf.download(file.path(), part, offset, done -> {
            checkCancel(job);
            job.doneBytes = before + done;
            changed(job, false);
        });
        if (Files.size(part) != size) {
            throw new IOException(file.name() + ": 받은 크기가 서버와 달라요");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        job.localPartials.remove(part);
        job.doneBytes = before + size;
        job.completed.put(key, size);
        changed(job, false);
    }

    // ---- 공통 ----

    private static void checkCancel(Job job) {
        if (job.cancelRequested) {
            throw new CancellationException();
        }
    }

    private void finish(Job job, State state, String message) {
        job.message = message;
        job.state = state;
        changed(job, true);
    }

    private void discardPartials(Job job) {
        for (Path p : job.localPartials) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 임시 파일이라 못 지워도 괜찮다.
            }
        }
        job.localPartials.clear();
        if (job.remotePartials.isEmpty()) {
            return;
        }
        Set<String> parts = new HashSet<>(job.remotePartials);
        job.remotePartials.clear();
        worker.submit(() -> {
            try {
                RemoteFiles rf = remote();
                for (String part : parts) {
                    if (rf.exists(part)) {
                        rf.delete(new RemoteFile(part, part, false, 0, null));
                    }
                }
            } catch (Exception ignored) {
                // 연결이 없으면 서버의 임시 파일은 남는다.
            }
        });
    }

    /** 진행률은 0.1초에 한 번만 알려서 화면이 밀리지 않게 한다. */
    private synchronized void changed(Job job, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastNotify < 100_000_000L) {
            return;
        }
        lastNotify = now;
        listener.changed(job);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
