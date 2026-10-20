package com.infradesk.ssh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * 데모 모드용 메모리 파일 트리. 업로드한 파일은 앱이 끝날 때까지 남는다. 호스트별로 공유해서
 * SFTP 창을 닫았다 다시 열어도 트리가 유지된다.
 */
final class DemoRemoteFiles implements RemoteFiles {

    private static final Map<String, Map<String, byte[]>> TREES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Integer>> PERMISSIONS = new ConcurrentHashMap<>();
    private static final Instant BASE = Instant.parse("2026-09-20T09:00:00Z");

    private final String home;
    /** 절대 경로 → 내용. 디렉터리는 "/"로 끝나고 내용이 null이다. */
    private final Map<String, byte[]> tree;
    /** 절대 경로 → 권한. 바꾸지 않은 항목은 없다(폴더 0755, 파일 0644로 보인다). */
    private final Map<String, Integer> permissions;

    DemoRemoteFiles(String username, String hostname) {
        this.home = "/home/" + username;
        this.tree = TREES.computeIfAbsent(hostname, h -> seed(home, h));
        this.permissions = PERMISSIONS.computeIfAbsent(hostname, h -> new ConcurrentHashMap<>());
    }

    private static Map<String, byte[]> seed(String home, String host) {
        Map<String, byte[]> t = new TreeMap<>();
        dir(t, "/");
        dir(t, "/home/");
        dir(t, home + "/");
        dir(t, home + "/app/");
        dir(t, home + "/backups/");
        file(t, home + "/README.md", "# " + host + "\n\n데모 서버예요. 이 파일은 메모리에만 있어요.\n");
        file(t, home + "/docker-compose.yml", "services:\n  " + host + ":\n    image: " + host + ":latest\n    restart: always\n");
        file(t, home + "/app/config.json", "{\n  \"logLevel\": \"info\"\n}\n");
        file(t, home + "/app/bot.log", "[20:52:10] INFO  voice tracker tick\n".repeat(200));
        file(t, home + "/backups/db-2026-09-26.sql.gz", "x".repeat(48_000));
        file(t, home + "/backups/db-2026-09-27.sql.gz", "x".repeat(51_200));
        return new ConcurrentHashMap<>(t);
    }

    private static void dir(Map<String, byte[]> t, String path) {
        t.put(path, new byte[0]);
    }

    private static void file(Map<String, byte[]> t, String path, String content) {
        t.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String home() {
        return home;
    }

    @Override
    public List<RemoteFile> list(String directory) throws IOException {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        if (!tree.containsKey(prefix)) {
            throw new NoSuchFileException(directory);
        }
        List<RemoteFile> result = new ArrayList<>();
        int i = 0;
        for (String key : tree.keySet()) {
            if (!key.startsWith(prefix) || key.equals(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            boolean isDir = rest.endsWith("/");
            String name = isDir ? rest.substring(0, rest.length() - 1) : rest;
            if (name.isEmpty() || name.contains("/")) {
                continue;
            }
            byte[] content = tree.get(key);
            String path = prefix + name;
            result.add(new RemoteFile(name, path, isDir, isDir ? 4096 : content.length,
                    BASE.plusSeconds(3600L * (key.hashCode() & 0xFF)).plusSeconds(i++),
                    permissions.getOrDefault(path, isDir ? 0755 : 0644), false));
        }
        result.sort(Comparator.comparing((RemoteFile f) -> !f.directory())
                .thenComparing(RemoteFile::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public boolean exists(String path) {
        return tree.containsKey(path) || tree.containsKey(path + "/");
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        String parent = RemoteFiles.parent(path);
        return list(parent).stream().filter(f -> f.path().equals(path)).findFirst()
                .orElseThrow(() -> new NoSuchFileException(path));
    }

    @Override
    public void download(String remotePath, Path localFile, long offset, LongConsumer progress) throws IOException {
        byte[] content = tree.get(remotePath);
        if (content == null) {
            throw new NoSuchFileException(remotePath);
        }
        if (offset <= 0) {
            try (InputStream in = new ByteArrayInputStream(content); OutputStream out = Files.newOutputStream(localFile)) {
                Transfers.copy(in, out, progress);
            }
            return;
        }
        byte[] head = Files.readAllBytes(localFile);
        byte[] kept = java.util.Arrays.copyOf(head, (int) Math.min(offset, head.length));
        try (InputStream in = new ByteArrayInputStream(content, kept.length, content.length - kept.length);
             OutputStream out = Files.newOutputStream(localFile)) {
            out.write(kept);
            Transfers.copy(in, out, kept.length, progress);
        }
    }

    @Override
    public void upload(Path localFile, String remotePath, long offset, LongConsumer progress) throws IOException {
        byte[] content = Files.readAllBytes(localFile);
        byte[] existing = tree.get(remotePath);
        if (offset > 0 && existing != null) {
            byte[] merged = java.util.Arrays.copyOf(existing, (int) Math.min(offset, existing.length));
            byte[] result = new byte[merged.length + content.length - merged.length];
            System.arraycopy(merged, 0, result, 0, merged.length);
            System.arraycopy(content, merged.length, result, merged.length, content.length - merged.length);
            content = result;
        }
        progress.accept(content.length);
        tree.put(remotePath, content);
    }

    @Override
    public void delete(RemoteFile file) throws IOException {
        if (file.directory()) {
            String prefix = file.path() + "/";
            boolean hasChildren = tree.keySet().stream().anyMatch(k -> k.startsWith(prefix) && !k.equals(prefix));
            if (hasChildren) {
                throw new IOException("폴더가 비어 있지 않아요");
            }
            tree.remove(prefix);
        } else {
            tree.remove(file.path());
        }
        permissions.remove(file.path());
    }

    @Override
    public void mkdir(String path) throws IOException {
        if (exists(path)) {
            throw new FileAlreadyExistsException(path);
        }
        if (!tree.containsKey(RemoteFiles.parent(path) + "/") && !RemoteFiles.parent(path).equals("/")) {
            throw new NoSuchFileException(RemoteFiles.parent(path));
        }
        tree.put(path + "/", new byte[0]);
    }

    @Override
    public void rename(String from, String to) throws IOException {
        if (!exists(from)) {
            throw new NoSuchFileException(from);
        }
        if (exists(to)) {
            throw new FileAlreadyExistsException(to);
        }
        boolean isDir = tree.containsKey(from + "/");
        if (!isDir) {
            tree.put(to, tree.remove(from));
            moveOwnPermission(from, to);
            return;
        }
        String oldPrefix = from + "/";
        String newPrefix = to + "/";
        for (String key : new ArrayList<>(tree.keySet())) {
            if (key.startsWith(oldPrefix)) {
                tree.put(newPrefix + key.substring(oldPrefix.length()), tree.remove(key));
            }
        }
        for (String key : new ArrayList<>(permissions.keySet())) {
            if (key.equals(from) || key.startsWith(oldPrefix)) {
                permissions.put(to + key.substring(from.length()), permissions.remove(key));
            }
        }
    }

    private void moveOwnPermission(String from, String to) {
        Integer perm = permissions.remove(from);
        if (perm != null) {
            permissions.put(to, perm);
        }
    }

    @Override
    public void chmod(String path, int mode) throws IOException {
        if (!exists(path)) {
            throw new NoSuchFileException(path);
        }
        permissions.put(path, mode & 0777);
    }

    @Override
    public void close() {
    }
}
