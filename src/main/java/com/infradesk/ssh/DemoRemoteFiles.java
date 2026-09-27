package com.infradesk.ssh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * In-memory file tree for demo mode. Uploads live until the app exits. Shared per host so the
 * tree survives closing and reopening the SFTP window.
 */
final class DemoRemoteFiles implements RemoteFiles {

    private static final Map<String, Map<String, byte[]>> TREES = new ConcurrentHashMap<>();
    private static final Instant BASE = Instant.parse("2026-09-20T09:00:00Z");

    private final String home;
    /** Absolute path → content; directories end with "/" and have null content. */
    private final Map<String, byte[]> tree;

    DemoRemoteFiles(String username, String hostname) {
        this.home = "/home/" + username;
        this.tree = TREES.computeIfAbsent(hostname, h -> seed(home, h));
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
            result.add(new RemoteFile(name, prefix + name, isDir, isDir ? 4096 : content.length,
                    BASE.plusSeconds(3600L * (key.hashCode() & 0xFF)).plusSeconds(i++)));
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
    public void download(String remotePath, Path localFile, LongConsumer progress) throws IOException {
        byte[] content = tree.get(remotePath);
        if (content == null) {
            throw new NoSuchFileException(remotePath);
        }
        try (InputStream in = new ByteArrayInputStream(content); OutputStream out = Files.newOutputStream(localFile)) {
            Transfers.copy(in, out, progress);
        }
    }

    @Override
    public void upload(Path localFile, String remotePath, LongConsumer progress) throws IOException {
        byte[] content = Files.readAllBytes(localFile);
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
    }

    @Override
    public void close() {
    }
}
