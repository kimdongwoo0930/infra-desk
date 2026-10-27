package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        /** 덮어써서 소유자가 바뀐 서버 파일의 이름. 전송이 끝난 뒤 한 번만 알리려고 모아 둔다. */
        private final List<String> ownerChanged = new CopyOnWriteArrayList<>();
        /** 서버가 권한을 알려주지 않아 덮어쓴 파일의 권한을 유지하지 못한 수. */
        private volatile int permissionUnknown;
        /** 새로 만든 서버 폴더와 줄 권한. 안에 파일을 다 올린 뒤에 적용한다(읽기 전용 폴더면 파일을 못 올리므로). */
        private final Map<String, Integer> pendingDirModes = new LinkedHashMap<>();

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

        /** 덮어써서 소유자가 바뀐 서버 파일 수. 일반 계정은 소유자를 되돌릴 수 없어서 알려야 한다. */
        public int ownerChangedCount() {
            return ownerChanged.size();
        }

        public List<String> ownerChangedNames() {
            return List.copyOf(ownerChanged);
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

    /** 작업 하나를 실행하고, 이어서 할 작업이 없으면 연결을 풀에 돌려준다. */
    private void run(Job job) {
        try {
            runJob(job);
        } finally {
            releaseIfIdle();
        }
    }

    private void runJob(Job job) {
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
            List<String> notes = new ArrayList<>();
            if (job.skipped > 0) {
                notes.add(job.skipped + "개는 이미 있거나 링크라서 건너뛰었어요");
            }
            if (!job.ownerChanged.isEmpty()) {
                notes.add("소유자가 바뀐 파일 " + job.ownerChanged.size() + "개");
            }
            if (job.permissionUnknown > 0) {
                notes.add("서버가 권한을 알려주지 않아 유지하지 못한 파일 " + job.permissionUnknown + "개");
            }
            finish(job, State.DONE, notes.isEmpty() ? "완료" : "완료 · " + String.join(" · ", notes));
        } catch (CancellationException e) {
            finish(job, State.CANCELLED, "취소했어요. 다시 시도하면 이어서 전송해요");
        } catch (Exception e) {
            // 연결이 끊겨서 실패했을 수 있다. 같은 연결로는 다시 시도해도 계속 실패하므로 버리고, 다시 시도할 때 새로 맺는다.
            closeRemote();
            finish(job, State.FAILED, describe(e) + " · 다시 시도하면 이어서 전송해요");
        }
    }

    private synchronized RemoteFiles remote() throws Exception {
        if (remote == null) {
            remote = opener.call();
        }
        return remote;
    }

    /**
     * 끝나지 않은 작업이 없으면 전송 연결을 풀에 돌려준다. 목록과 화면은 그대로이고, 다음 전송이 시작될 때
     * 다시 연결한다. 연결을 계속 쥐고 있으면 풀이 유휴 연결을 정리하지 못하고, 끊어진 연결을 계속 붙들게 된다.
     */
    private synchronized void releaseIfIdle() {
        if (remote != null && jobs.stream().allMatch(j -> j.state.finished())) {
            closeRemote();
        }
    }

    private synchronized void closeRemote() {
        RemoteFiles r = remote;
        remote = null;
        if (r != null) {
            r.close();
        }
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
        ensureRemoteDir(job, rf, rootRemote, localMode(src));
        for (LocalEntry e : entries) {
            checkCancel(job);
            String remotePath = RemoteFiles.join(rootRemote, e.relative);
            if (e.directory) {
                ensureRemoteDir(job, rf, remotePath, localMode(e.path));
            } else {
                uploadFile(job, rf, e.path, remotePath, job.name + "/" + e.relative);
            }
        }
        applyDirModes(job, rf);
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

    /**
     * 파일 하나를 올린다. <b>권한은 확인한 뒤에 정한다.</b>
     * <ul>
     *   <li>서버에 같은 파일이 있어 덮어쓰면 <b>기존 파일의 권한을 먼저 읽어서 그대로 유지</b>한다(600인 비밀 파일이 644가 되거나
     *       실행 권한이 사라지지 않게).</li>
     *   <li>새 파일이면 <b>내 컴퓨터 파일의 권한</b>을 따른다. Windows처럼 권한 정보가 없으면 서버 기본값이다.</li>
     *   <li>임시 파일을 만들 때 <b>내용을 올리기 전에</b> 그 권한부터 맞춘다. 비밀 파일이 잠깐이라도 넓은 권한으로 올라가지 않는다.
     *       권한을 맞추지 못하면 내용을 올리지 않고 실패한다.</li>
     *   <li>이름을 바꾼 뒤에 다시 읽어서 <b>실제로 그 권한인지 확인</b>한다.</li>
     *   <li>덮어써서 소유자가 바뀌면(일반 계정은 소유자를 되돌릴 수 없다) 기록해 두었다가 전송 하나가 끝나면 한 번만 알린다.</li>
     * </ul>
     */
    private void uploadFile(Job job, RemoteFiles rf, Path local, String remotePath, String key) throws IOException {
        long size = Files.size(local);
        if (job.completed.containsKey(key)) {
            return;
        }
        checkCancel(job);
        String name = local.getFileName().toString();
        RemoteFile existing = statIfExists(rf, remotePath, name);
        boolean exists = existing != null;
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
        Integer mode = exists ? (existing.permissions() >= 0 ? Integer.valueOf(existing.permissions()) : null) : localMode(local);
        if (exists && mode == null) {
            job.permissionUnknown++;
        }
        job.fingerprints.put(key, fingerprint);
        job.remotePartials.add(part);
        if (mode != null) {
            preparePart(rf, part, mode, name);
        }
        long before = job.doneBytes;
        rf.upload(local, part, offset, done -> {
            checkCancel(job);
            job.doneBytes = before + done;
            changed(job, false);
        });
        RemoteFile uploaded = rf.stat(part);
        if (uploaded.size() != size) {
            throw new IOException(job.name + ": 올린 크기가 원본과 달라요");
        }
        if (exists) {
            if (existing.uid() >= 0 && uploaded.uid() >= 0 && existing.uid() != uploaded.uid()
                    && !job.ownerChanged.contains(key)) {
                // 폴더 안의 같은 이름 파일을 구분하고, 다시 시도했을 때 중복으로 세지 않게 폴더 기준 경로로 기록한다.
                job.ownerChanged.add(key);
            }
            rf.delete(new RemoteFile(name, remotePath, false, 0, null));
        }
        rf.rename(part, remotePath);
        job.remotePartials.remove(part);
        if (mode != null) {
            verifyMode(rf, remotePath, mode, name);
        }
        job.doneBytes = before + size;
        job.completed.put(key, size);
        changed(job, false);
    }

    /** 서버에 이미 있는 항목의 상태. 없으면 null. 끊어진 링크처럼 상태를 못 읽으면 권한·소유자를 모르는 항목으로 돌려준다. */
    private static RemoteFile statIfExists(RemoteFiles rf, String path, String name) throws IOException {
        if (!rf.exists(path)) {
            return null;
        }
        try {
            return rf.stat(path);
        } catch (IOException e) {
            return new RemoteFile(name, path, false, 0, null);
        }
    }

    /**
     * 임시 파일을 내용이 없는 상태로 먼저 만들고 권한을 맞춘다. 내용은 그다음에 올라가므로, 비밀 파일이 잠깐이라도 넓은
     * 권한으로 서버에 놓이는 순간이 없다. 맞추지 못하면 내용을 올리지 않는다(넓은 권한으로 올라가는 것보다 실패가 안전하다).
     */
    private static void preparePart(RemoteFiles rf, String part, int mode, String name) throws IOException {
        try {
            if (!rf.exists(part)) {
                rf.createFile(part);
            }
            rf.chmod(part, mode);
        } catch (IOException e) {
            try {
                if (rf.exists(part) && rf.stat(part).size() == 0) {
                    rf.delete(new RemoteFile(part, part, false, 0, null));
                }
            } catch (IOException ignored) {
                // 비어 있는 임시 파일이라 못 지워도 큰일은 아니다.
            }
            throw new IOException(name + ": 서버 파일의 권한(" + octal(mode) + ")을 설정하지 못해 올리지 않았어요", e);
        }
    }

    /** 이름을 바꾼 뒤 서버에서 다시 읽어서 원하는 권한인지 확인한다. 다르면 한 번 바로잡아 보고, 그래도 다르면 실패로 알린다. */
    private static void verifyMode(RemoteFiles rf, String path, int mode, String name) throws IOException {
        int actual = rf.stat(path).permissions();
        if (actual < 0 || actual == mode) {
            return;
        }
        try {
            rf.chmod(path, mode);
            actual = rf.stat(path).permissions();
        } catch (IOException ignored) {
            // 바로잡지 못했다. 아래에서 실패로 알린다.
        }
        if (actual >= 0 && actual != mode) {
            throw new IOException(name + ": 올린 뒤 권한이 " + octal(mode) + "이어야 하는데 " + octal(actual) + "(으)로 확인돼요");
        }
    }

    private static String octal(int mode) {
        return String.format("%03o", mode);
    }

    /** 내 컴퓨터 파일·폴더의 권한(0777 범위). Windows처럼 POSIX 권한이 없으면 null. */
    static Integer localMode(Path path) {
        try {
            int mode = 0;
            for (PosixFilePermission permission : Files.getPosixFilePermissions(path)) {
                mode |= 1 << (8 - permission.ordinal());
            }
            return mode;
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return null;
        }
    }

    /** 서버에 폴더가 없으면 만든다. 새로 만든 폴더의 권한은 안에 파일을 다 올린 뒤에 적용한다. */
    private static void ensureRemoteDir(Job job, RemoteFiles rf, String path, Integer mode) throws IOException {
        if (!rf.exists(path)) {
            rf.mkdir(path);
            if (mode != null) {
                job.pendingDirModes.put(path, mode);
            }
        }
    }

    /** 새로 만든 폴더에 내 컴퓨터 폴더의 권한을 준다. 안쪽 폴더부터(바깥 폴더 권한이 좁으면 안쪽에 못 들어가므로). */
    private void applyDirModes(Job job, RemoteFiles rf) throws IOException {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(job.pendingDirModes.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<String, Integer> e = entries.get(i);
            try {
                rf.chmod(e.getKey(), e.getValue());
            } catch (IOException ex) {
                throw new IOException(e.getKey() + ": 폴더의 권한(" + octal(e.getValue()) + ")을 설정하지 못했어요", ex);
            }
            job.pendingDirModes.remove(e.getKey());
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
            releaseIfIdle();
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

    /** 서버·파일 시스템이 주는 영어 오류를 알아보기 쉬운 한국어로 바꾼다. */
    static String describe(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage());
            if (c instanceof java.nio.file.AccessDeniedException || m.contains("PERMISSION_DENIED")) {
                return "권한이 없어요. 이 계정으로는 할 수 없는 작업이에요";
            }
            if (c instanceof java.nio.file.NoSuchFileException || m.contains("NO_SUCH_FILE")) {
                return "파일이나 폴더를 찾을 수 없어요";
            }
            if (m.contains("NO_SPACE") || m.toLowerCase(java.util.Locale.ROOT).contains("no space left")) {
                return "저장 공간이 부족해요";
            }
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
